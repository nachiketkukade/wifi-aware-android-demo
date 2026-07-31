package com.example.esp_wifi_demo.library.nan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Transport layer for WiFi Aware NAN Data Path connections
 *
 * Handles the complete connection flow:
 * 1. Send empty follow-up (SRV request)
 * 2. Wait for SRV response containing port info
 * 3. Establish NDP (NAN Data Path)
 * 4. Create socket and return to caller
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class Transport {
    private static final String TAG = "NAN_Transport";
    private static final int SRV_REQUEST_MESSAGE_ID = 100;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 10000;
    private static final int SOCKET_READ_TIMEOUT_MS = 30000;

    private final Context context;
    private final ConnectivityManager connectivityManager;
    private Network nanNetwork;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Inet6Address peerIpv6;
    private boolean isConnected = false;

    // Connection state
    private DiscoverySession currentSession;
    private PeerHandle currentPeerHandle;
    private int peerPort;
    private TransportCallback currentCallback;

    // Secure datapath
    private String pskPassphrase = null;
    private volatile boolean ndpRequested = false;
    private int ndpDelayMs = 3000; // Responder-readiness delay
    private boolean persistentSocketEnabled = true;

    // Persistent socket for HTTP keep-alive
    private Socket persistentSocket;
    private BufferedInputStream socketInputStream;
    private BufferedOutputStream socketOutputStream;
    private final Object socketLock = new Object();

    /**
     * Callback interface for transport events
     */
    public interface TransportCallback {
        void onConnected(Socket socket, Inet6Address peerIpv6);
        void onDisconnected();
        void onError(String error);
    }

    /**
     * Callback for SRV response from peer
     * This is registered with the discovery session to receive messages
     */
    public interface SrvResponseHandler {
        void onSrvReceived(PeerHandle peerHandle, NanUtilities.ParsedGspData srvData);
        void onMessageSendSucceeded(int messageId);
        void onMessageSendFailed(int messageId);
    }

    public Transport(Context context) {
        this.context = context;
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Set PSK passphrase for secure NAN datapath.
     * If set, the NDP will use WPA2 encryption with this passphrase.
     * Passphrase must be at least 8 characters.
     * Pass null to use open (unsecured) datapath.
     */
    public void setPskPassphrase(String passphrase) {
        this.pskPassphrase = passphrase;
    }

    /**
     * Set the delay before establishing NDP (milliseconds).
     * This gives the publisher time to register its responder.
     * Default is 3000ms.
     */
    public void setNdpDelayMs(int delayMs) {
        this.ndpDelayMs = delayMs;
    }

    /**
     * Controls whether a TCP socket is created automatically after the NAN data path is ready.
     *
     * <p>Enabled by default for HTTP/TCP clients. Disable it for protocols, such as UDP, which
     * need the NAN network but do not have a TCP listener on the advertised port.</p>
     */
    public void setPersistentSocketEnabled(boolean enabled) {
        this.persistentSocketEnabled = enabled;
    }

    /**
     * Connect to a peer over NAN Data Path and create a socket
     *
     * Flow:
     * 1. Send empty follow-up (SRV request) to peer
     * 2. Caller must call onSrvResponseReceived() when SRV response arrives
     * 3. Then NDP is established and socket is created
     *
     * @param discoverySession The active discovery session
     * @param peerHandle PeerHandle of the peer to connect to
     * @param callback Callback for connection events
     * @return true if SRV request was sent, false on error
     */
    public boolean sendSrvRequest(DiscoverySession discoverySession, PeerHandle peerHandle,
            TransportCallback callback) {

        this.currentSession = discoverySession;
        this.currentPeerHandle = peerHandle;
        this.currentCallback = callback;

        // Create empty GSP SSI (header only) for SRV request
        byte[] srvRequest = NanUtilities.createEmptyGspSsi();
        Log.d(TAG, "Sending SRV request, length: " + srvRequest.length +
              ", data: " + NanUtilities.toHexString(srvRequest));

        try {
            discoverySession.sendMessage(peerHandle, SRV_REQUEST_MESSAGE_ID, srvRequest);
            Log.d(TAG, "SRV request sent to peer");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SRV request", e);
            callback.onError("Failed to send SRV request: " + e.getMessage());
            return false;
        }
    }

    /**
     * Called when SRV response is received from peer
     * Parses port from GSP data and establishes NDP
     *
     * @param srvData Parsed GSP data from SRV response
     */
    public void onSrvResponseReceived(NanUtilities.ParsedGspData srvData) {
        Log.d(TAG, "### onSrvResponseReceived: ENTERED, srvData=" + (srvData != null ? srvData.toString() : "null"));
        if (srvData == null) {
            Log.e(TAG, "### onSrvResponseReceived: SRV response data is null - bailing");
            if (currentCallback != null) {
                currentCallback.onError("Invalid SRV response");
            }
            return;
        }

        // Extract port from SRV response
        peerPort = srvData.port;
        if (peerPort <= 0 || peerPort > 65535) {
            peerPort = 80; // Default to HTTP port
            Log.w(TAG, "### onSrvResponseReceived: Invalid port, defaulting to 80");
        }

        Log.d(TAG, "### onSrvResponseReceived: Port=" + peerPort + ", Hostname=" + srvData.hostname);
        Log.d(TAG, "### onSrvResponseReceived: pskPassphrase=" + (pskPassphrase != null && !pskPassphrase.isEmpty() ? "SET (len=" + pskPassphrase.length() + ")" : "null/empty")
                + ", ndpDelayMs=" + ndpDelayMs);

        // When using secure datapath, add a delay to give the publisher
        // time to register its NDP responder before we request the network
        if (pskPassphrase != null && !pskPassphrase.isEmpty() && ndpDelayMs > 0) {
            Log.d(TAG, "### onSrvResponseReceived: Secure mode - scheduling establishNdp() after " + ndpDelayMs + "ms");
            new Thread(() -> {
                try {
                    Log.d(TAG, "### onSrvResponseReceived: delay thread sleeping " + ndpDelayMs + "ms");
                    Thread.sleep(ndpDelayMs);
                    Log.d(TAG, "### onSrvResponseReceived: delay thread WOKE - calling establishNdp()");
                } catch (InterruptedException e) {
                    Log.w(TAG, "### onSrvResponseReceived: delay thread interrupted", e);
                    Thread.currentThread().interrupt();
                }
                establishNdp();
            }).start();
        } else {
            Log.d(TAG, "### onSrvResponseReceived: Open mode - calling establishNdp() immediately");
            establishNdp();
        }
    }

    /**
     * Called when a message is received from peer
     * If it's a valid SRV response, parses it and establishes NDP
     *
     * @param message Raw message bytes from peer
     * @return true if message was a valid SRV response and was processed
     */
    public boolean onMessageReceived(byte[] message) {
        Log.d(TAG, "### onMessageReceived: ENTERED, length=" + (message != null ? message.length : -1));
        if (message != null) {
            Log.d(TAG, "### onMessageReceived: bytes=" + NanUtilities.toHexString(message));
        }
        Log.d(TAG, "### onMessageReceived: state pskPassphrase=" + (pskPassphrase != null && !pskPassphrase.isEmpty() ? "SET" : "null/empty")
                + ", currentSession=" + (currentSession != null ? "SET" : "null")
                + ", currentPeerHandle=" + (currentPeerHandle != null ? "SET" : "null")
                + ", ndpRequested=" + ndpRequested);

        if (message == null || message.length < 4) {
            Log.w(TAG, "### onMessageReceived: REJECTED - message null or < 4 bytes");
            return false;
        }

        boolean validGsp = NanUtilities.isValidGspSsi(message);
        Log.d(TAG, "### onMessageReceived: isValidGspSsi=" + validGsp);
        if (validGsp) {
            NanUtilities.ParsedGspData srvData = NanUtilities.parseGspSsi(message);
            Log.d(TAG, "### onMessageReceived: parseGspSsi -> " + (srvData != null ? srvData.toString() : "null"));
            if (srvData != null) {
                Log.d(TAG, "### onMessageReceived: dispatching to onSrvResponseReceived");
                onSrvResponseReceived(srvData);
                return true;
            }
        }

        Log.w(TAG, "### onMessageReceived: NOT handled (not a valid GSP SRV response)");
        return false;
    }

    /**
     * Establish NAN Data Path (NDP) after SRV exchange
     */
    private void establishNdp() {
        Log.d(TAG, "### establishNdp: ENTERED, ndpRequested=" + ndpRequested
                + ", currentSession=" + (currentSession != null ? "SET" : "null")
                + ", currentPeerHandle=" + (currentPeerHandle != null ? currentPeerHandle.hashCode() : "null")
                + ", pskPassphrase=" + (pskPassphrase != null && !pskPassphrase.isEmpty() ? "SET" : "null/empty"));

        if (ndpRequested) {
            Log.w(TAG, "### establishNdp: NDP already requested - SKIPPING duplicate");
            return;
        }

        if (currentSession == null || currentPeerHandle == null) {
            Log.e(TAG, "### establishNdp: Cannot establish NDP - session or peer handle is null - BAILING");
            if (currentCallback != null) {
                currentCallback.onError("No active session");
            }
            return;
        }

        ndpRequested = true;
        Log.d(TAG, "### establishNdp: Building WifiAwareNetworkSpecifier...");

        // Build network specifier for NAN Data Path
        WifiAwareNetworkSpecifier.Builder specBuilder = new WifiAwareNetworkSpecifier.Builder(
                currentSession, currentPeerHandle);

        if (pskPassphrase != null && !pskPassphrase.isEmpty()) {
            specBuilder.setPskPassphrase(pskPassphrase);
            Log.d(TAG, "### establishNdp: Applied setPskPassphrase (len=" + pskPassphrase.length() + ") -> SECURE NDP");
        } else {
            Log.d(TAG, "### establishNdp: No passphrase -> OPEN NDP");
        }

        WifiAwareNetworkSpecifier specifier = specBuilder.build();
        Log.d(TAG, "### establishNdp: Specifier built");

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "NAN Network available");
                nanNetwork = network;
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                    @NonNull NetworkCapabilities networkCapabilities) {
                Log.d(TAG, "Network capabilities changed");

                // Get peer IPv6 address from WifiAwareNetworkInfo
                WifiAwareNetworkInfo awareInfo =
                        (WifiAwareNetworkInfo) networkCapabilities.getTransportInfo();

                if (awareInfo != null && !isConnected) {
                    peerIpv6 = awareInfo.getPeerIpv6Addr();
                    Log.d(TAG, "Peer IPv6: " + peerIpv6 + ", Port: " + peerPort);

                    if (peerIpv6 != null && nanNetwork != null) {
                        isConnected = true;

                        if (!persistentSocketEnabled) {
                            Log.d(TAG, "NDP ready; persistent TCP socket disabled");
                            currentCallback.onConnected(null, peerIpv6);
                            return;
                        }

                        // Create persistent socket in background thread
                        new Thread(() -> {
                            if (createPersistentSocket()) {
                                Log.d(TAG, "Persistent socket created, NDP ready for HTTP");
                                currentCallback.onConnected(persistentSocket, peerIpv6);
                            } else {
                                currentCallback.onError("Failed to create persistent socket");
                            }
                        }).start();
                    }
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "NAN Network lost");
                synchronized (socketLock) {
                    closePersistentSocketLocked();
                }
                isConnected = false;
                ndpRequested = false;
                nanNetwork = null;
                peerIpv6 = null;
                if (currentCallback != null) {
                    currentCallback.onDisconnected();
                }
            }

            @Override
            public void onUnavailable() {
                Log.e(TAG, "NAN Network unavailable");
                ndpRequested = false;
                if (currentCallback != null) {
                    currentCallback.onError("Network unavailable");
                }
            }
        };

        try {
            Log.d(TAG, "### establishNdp: Calling connectivityManager.requestNetwork()...");
            connectivityManager.requestNetwork(networkRequest, networkCallback);
            Log.d(TAG, "### establishNdp: requestNetwork() RETURNED - NDP request submitted to framework");
        } catch (SecurityException e) {
            Log.e(TAG, "### establishNdp: SecurityException - permission denied", e);
            if (currentCallback != null) {
                currentCallback.onError("Permission denied: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "### establishNdp: UNEXPECTED exception requesting network", e);
            if (currentCallback != null) {
                currentCallback.onError("requestNetwork failed: " + e.getMessage());
            }
        }
    }

    /**
     * Create a socket connected to the peer (for one-off connections)
     * @param port Port to connect to
     * @param timeoutMs Connection timeout in milliseconds
     * @return Connected socket, or null on error
     */
    public Socket createConnectedSocket(int port, int timeoutMs) {
        if (nanNetwork == null || peerIpv6 == null) {
            Log.e(TAG, "Cannot create socket: network not connected");
            return null;
        }

        try {
            Socket socket = nanNetwork.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(peerIpv6, port), timeoutMs);
            Log.d(TAG, "Socket connected to [" + peerIpv6.getHostAddress() + "]:" + port);
            return socket;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create socket", e);
            return null;
        }
    }

    /**
     * Create the persistent socket for HTTP keep-alive connections
     * @return true if socket was created successfully
     */
    private boolean createPersistentSocket() {
        synchronized (socketLock) {
            closePersistentSocketLocked();

            if (nanNetwork == null || peerIpv6 == null) {
                Log.e(TAG, "Cannot create persistent socket: network not connected");
                return false;
            }

            try {
                persistentSocket = nanNetwork.getSocketFactory().createSocket();
                persistentSocket.connect(new InetSocketAddress(peerIpv6, peerPort), SOCKET_CONNECT_TIMEOUT_MS);
                persistentSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                persistentSocket.setKeepAlive(true);
                persistentSocket.setTcpNoDelay(true);

                socketInputStream = new BufferedInputStream(persistentSocket.getInputStream());
                socketOutputStream = new BufferedOutputStream(persistentSocket.getOutputStream());

                Log.d(TAG, "Persistent socket created to [" + peerIpv6.getHostAddress() + "]:" + peerPort);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create persistent socket", e);
                closePersistentSocketLocked();
                return false;
            }
        }
    }

    /**
     * Get the persistent socket, reconnecting if necessary
     * @return The persistent socket, or null if reconnection failed
     */
    public Socket getSocket() {
        synchronized (socketLock) {
            if (isSocketValid()) {
                return persistentSocket;
            }

            Log.d(TAG, "Persistent socket invalid, reconnecting...");
            if (createPersistentSocket()) {
                return persistentSocket;
            }
            return null;
        }
    }

    /**
     * Get input stream from persistent socket
     * @return InputStream, or null if socket is not connected
     */
    public InputStream getInputStream() throws IOException {
        synchronized (socketLock) {
            if (!isSocketValid()) {
                if (!createPersistentSocket()) {
                    throw new IOException("Failed to create socket connection");
                }
            }
            return socketInputStream;
        }
    }

    /**
     * Get output stream from persistent socket
     * @return OutputStream, or null if socket is not connected
     */
    public OutputStream getOutputStream() throws IOException {
        synchronized (socketLock) {
            if (!isSocketValid()) {
                if (!createPersistentSocket()) {
                    throw new IOException("Failed to create socket connection");
                }
            }
            return socketOutputStream;
        }
    }

    /**
     * Check if persistent socket is valid and connected
     */
    private boolean isSocketValid() {
        return persistentSocket != null &&
               persistentSocket.isConnected() &&
               !persistentSocket.isClosed() &&
               !persistentSocket.isInputShutdown() &&
               !persistentSocket.isOutputShutdown();
    }

    /**
     * Close persistent socket (caller must hold socketLock)
     */
    private void closePersistentSocketLocked() {
        if (socketInputStream != null) {
            try {
                socketInputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket input stream: " + e.getMessage());
            }
            socketInputStream = null;
        }
        if (socketOutputStream != null) {
            try {
                socketOutputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket output stream: " + e.getMessage());
            }
            socketOutputStream = null;
        }
        if (persistentSocket != null) {
            try {
                persistentSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing persistent socket: " + e.getMessage());
            }
            persistentSocket = null;
        }
    }

    /**
     * Close and reconnect the persistent socket
     * Use this after an I/O error to reset the connection
     * @return true if reconnection succeeded
     */
    public boolean reconnect() {
        synchronized (socketLock) {
            Log.d(TAG, "Reconnecting persistent socket...");
            return createPersistentSocket();
        }
    }

    /**
     * Get the NAN network for use with OkHttp etc.
     */
    public Network getNetwork() {
        return nanNetwork;
    }

    /**
     * Get the NAN network (alias for getNetwork)
     */
    public Network getNanNetwork() {
        return nanNetwork;
    }

    /**
     * Get peer IPv6 address
     */
    public Inet6Address getPeerIpv6() {
        return peerIpv6;
    }

    /**
     * Get peer port from SRV response
     */
    public int getPeerPort() {
        return peerPort;
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Release the network connection and terminate NAN datapath
     */
    public void release() {
        Log.d(TAG, "release: Starting cleanup...");

        // Close persistent socket first
        try {
            synchronized (socketLock) {
                Log.d(TAG, "release: Closing persistent socket");
                closePersistentSocketLocked();
            }
        } catch (Exception e) {
            Log.w(TAG, "release: Error closing persistent socket: " + e.getMessage());
        }

        if (networkCallback != null) {
            try {
                Log.d(TAG, "release: Unregistering network callback (terminates NDP)");
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "release: Error unregistering callback: " + e.getMessage());
            }
            networkCallback = null;
        }

        isConnected = false;
        ndpRequested = false;
        nanNetwork = null;
        peerIpv6 = null;
        currentSession = null;
        currentPeerHandle = null;
        currentCallback = null;
        Log.d(TAG, "release: Transport cleanup complete, NDP terminated");
    }
}
