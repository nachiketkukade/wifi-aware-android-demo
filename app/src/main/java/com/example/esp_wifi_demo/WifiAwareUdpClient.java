package com.example.esp_wifi_demo;

import android.content.Context;
import android.net.Network;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.esp_wifi_demo.library.nan.NanManager;
import com.example.esp_wifi_demo.library.nan.NanServiceInfo;
import com.example.esp_wifi_demo.library.nan.Transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiAwareUdpClient {

    public static class DiscoverUdpServer {
        private static final String TAG = "DiscoverUdpServer";
        public static final String DEFAULT_SERVICE_TYPE = "ESP-Demo";
        public static final String DEFAULT_PROTOCOL = "UDP";
        private final WifiAwareSession session;
        private NanManager nanManager;
        private NanManager.DiscoveryListener discoverListener;

        public interface DiscoverCallback {
            void onDiscoverStarted();
            void onDiscoverFailed(String reason);
            void onServiceFound(NanServiceInfo service);
            void onPeersDiscovered(List<NanServiceInfo> services);
        }

        public DiscoverUdpServer(WifiAwareSession session) {
            this.session = session;
        }

        public void discover(String serviceType, String protocol, int timeoutMs,
                             DiscoverCallback callback) {
            String normalizedServiceType = normalizeServicePart(serviceType, DEFAULT_SERVICE_TYPE);
            String normalizedProtocol = normalizeServicePart(protocol, DEFAULT_PROTOCOL)
                    .toLowerCase(java.util.Locale.US);

            String fullServiceType = normalizedServiceType + "." + normalizedProtocol;

            nanManager = new NanManager(session);

            final List<NanServiceInfo> discoveredServices = new java.util.ArrayList<>();

            discoverListener = new NanManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    Log.d(TAG, "UDP service discovery started");
                    callback.onDiscoverStarted();
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    Log.d(TAG, "UDP service discovery stopped");
                }

                @Override
                public void onServiceFound(NanServiceInfo serviceInfo) {
                    Log.d(TAG, "Found UDP service: " + serviceInfo.getServiceName());
                    discoveredServices.add(serviceInfo);
                    callback.onServiceFound(serviceInfo);
                }

                @Override
                public void onServiceLost(NanServiceInfo serviceInfo) {
                    Log.d(TAG, "Service lost: " + serviceInfo.getServiceName());
                    discoveredServices.remove(serviceInfo);
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(TAG, "Discovery failed: error " + errorCode);
                    callback.onDiscoverFailed("Error " + errorCode);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    if (messageHandler != null) {
                        messageHandler.onMessageReceived(peerHandle, message);
                    }
                }
            };

            nanManager.discoverServices(fullServiceType, NanManager.PROTOCOL_NAN, discoverListener);

            if (timeoutMs > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Discovery timeout. Found " + discoveredServices.size() + " services");
                    callback.onPeersDiscovered(discoveredServices);
                }, timeoutMs);
            }
        }

        private static String normalizeServicePart(String value, String defaultValue) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                normalized = defaultValue;
            }
            return normalized.startsWith("_") ? normalized : "_" + normalized;
        }

        public NanManager getNanManager() {
            return nanManager;
        }

        public NanManager.DiscoveryListener getDiscoverListener() {
            return discoverListener;
        }

        public interface MessageHandler {
            void onMessageReceived(PeerHandle peerHandle, byte[] message);
        }

        private MessageHandler messageHandler;

        public void setMessageHandler(MessageHandler handler) {
            this.messageHandler = handler;
        }

        public void stop() {
            if (nanManager != null && discoverListener != null) {
                nanManager.stopServiceDiscovery(discoverListener);
            }
        }
    }

    public static class UdpConnect {
        private static final String TAG = "UdpConnect";
        private final Context context;
        private Transport transport;
        private DatagramSocket udpSocket;
        private Thread receiveThread;
        private volatile boolean isRunning = false;

        public interface UdpCallback {
            void onConnected(Inet6Address peerIpv6, int peerPort);
            void onMessageReceived(String message);
            void onError(String error);
            void onDisconnected();
        }

        public UdpConnect(Context context) {
            this.context = context;
            this.transport = new Transport(context);
            // UDP needs the NAN data path but does not use Transport's HTTP/TCP socket.
            this.transport.setPersistentSocketEnabled(false);
        }

        public Transport getTransport() {
            return transport;
        }

        public void connect(DiscoverUdpServer discoverUdpServer,
                            NanServiceInfo service,
                            UdpCallback callback) {

            Log.d(TAG, "Starting connection to " + service.getServiceName());

            discoverUdpServer.setMessageHandler((peerHandle, message) -> {
                boolean handled = transport.onMessageReceived(message);
                if (handled) {
                    Log.d(TAG, "SRV Response processed by Transport");
                }
            });

            boolean sent = transport.sendSrvRequest(
                    discoverUdpServer.getNanManager().getSession(discoverUdpServer.getDiscoverListener()),
                    service.getPeerHandle(),
                    new Transport.TransportCallback() {
                        @Override
                        public void onConnected(Socket socket, Inet6Address peerIpv6) {
                            Log.d(TAG, "Transport connected to " + peerIpv6.getHostAddress() + ":" + transport.getPeerPort());
                            try {
                                setupUdpSocket(callback);
                                callback.onConnected(peerIpv6, transport.getPeerPort());
                            } catch (IOException e) {
                                callback.onError("Failed to setup UDP socket: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onDisconnected() {
                            Log.d(TAG, "Transport disconnected");
                            stopUdpSocket();
                            callback.onDisconnected();
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Transport error: " + error);
                            callback.onError("Connection failed: " + error);
                        }
                    });

            if (!sent) {
                callback.onError("Failed to send SRV request");
            }
        }

        private void setupUdpSocket(UdpCallback callback) throws IOException {
            Network network = transport.getNanNetwork();
            if (network == null) {
                throw new IOException("No NAN network available");
            }

            udpSocket = new DatagramSocket();
            network.bindSocket(udpSocket);
            isRunning = true;

            receiveThread = new Thread(() -> {
                byte[] buffer = new byte[1024];
                while (isRunning && !udpSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        udpSocket.receive(packet);
                        String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        Log.d(TAG, "Received UDP: " + msg);
                        callback.onMessageReceived(msg);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error receiving UDP packet", e);
                        }
                    }
                }
            });
            receiveThread.start();
        }

        public void sendUdpMessage(String message, UdpCallback callback) {
            if (udpSocket == null || udpSocket.isClosed() || transport.getPeerIpv6() == null) {
                callback.onError("UDP socket not ready");
                return;
            }

            new Thread(() -> {
                try {
                    byte[] data = message.getBytes("UTF-8");
                    InetAddress peerAddr = transport.getPeerIpv6();
                    int peerPort = transport.getPeerPort();
                    if (peerPort <= 0) peerPort = 3333; // Default port used by some examples

                    DatagramPacket packet = new DatagramPacket(data, data.length, peerAddr, peerPort);
                    udpSocket.send(packet);
                    Log.d(TAG, "Sent UDP message: " + message);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send UDP message", e);
                    callback.onError("Send failed: " + e.getMessage());
                }
            }).start();
        }

        public void stopUdpSocket() {
            isRunning = false;
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            if (receiveThread != null) {
                receiveThread.interrupt();
            }
        }

        public void release() {
            stopUdpSocket();
            if (transport != null) {
                transport.release();
            }
        }
    }
}
