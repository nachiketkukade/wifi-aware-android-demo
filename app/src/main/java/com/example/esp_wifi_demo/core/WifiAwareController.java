package com.example.esp_wifi_demo.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.esp_wifi_demo.WifiAwareUdpClient;
import com.example.esp_wifi_demo.library.nan.NanServiceInfo;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiAwareController {
    private static final String TAG = "WifiAwareController";

    private static WifiAwareController instance;
    private final Context context;
    private final WifiAwareManager wifiAwareManager;
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler;

    private WifiAwareSession wifiAwareSession;
    private WifiAwareUdpClient.DiscoverUdpServer discoverUdpServer;
    private WifiAwareUdpClient.UdpConnect udpConnect;

    private final List<NanServiceInfo> discoveredPeers = new ArrayList<>();
    private final Set<android.net.wifi.aware.PeerHandle> seenPeerHandles = new HashSet<>();

    private final List<WifiAwareStateListener> listeners = new CopyOnWriteArrayList<>();

    private String currentPassphrase;
    private String currentProtocol = WifiAwareUdpClient.DiscoverUdpServer.DEFAULT_PROTOCOL;

    public interface WifiAwareStateListener {
        void onDiscoveryStarted();
        void onDiscoveryFailed(String reason);
        void onPeerDiscovered(NanServiceInfo service);
        void onDiscoveryComplete(List<NanServiceInfo> services);

        void onConnecting(String instanceName);
        void onConnected(String instanceName, Inet6Address peerIpv6, int peerPort);
        void onConnectionFailed(String error);
        void onDisconnected();

        void onUdpMessageReceived(String message);
        void onMessageSent(String status);
        void onMessageSendFailed(String error);
    }

    private WifiAwareController(Context context) {
        this.context = context.getApplicationContext();
        this.wifiAwareManager = (WifiAwareManager) this.context.getSystemService(Context.WIFI_AWARE_SERVICE);
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized WifiAwareController getInstance(Context context) {
        if (instance == null) {
            instance = new WifiAwareController(context);
        }
        return instance;
    }

    public void addListener(WifiAwareStateListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(WifiAwareStateListener listener) {
        listeners.remove(listener);
    }

    public List<NanServiceInfo> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers);
    }

    public String getCurrentProtocol() {
        return currentProtocol;
    }

    public void startDiscovery(String passphrase) {
        startDiscovery(passphrase, WifiAwareUdpClient.DiscoverUdpServer.DEFAULT_SERVICE_TYPE,
                WifiAwareUdpClient.DiscoverUdpServer.DEFAULT_PROTOCOL);
    }

    public void startDiscovery(String passphrase, String serviceType, String protocol) {
        this.currentPassphrase = passphrase;
        this.currentProtocol = protocol;
        discoveredPeers.clear();
        seenPeerHandles.clear();

        if (wifiAwareManager == null) {
            notifyDiscoveryFailed("WiFi Aware is not supported on this device");
            return;
        }

        try {
            wifiAwareManager.attach(new AttachCallback() {
                @Override
                public void onAttached(WifiAwareSession session) {
                    super.onAttached(session);
                    wifiAwareSession = session;
                    Log.d(TAG, "WiFi Aware attached");

                    mainHandler.post(() -> startUdpServiceDiscovery(serviceType, protocol));
                }

                @Override
                public void onAttachFailed() {
                    super.onAttachFailed();
                    notifyDiscoveryFailed("WiFi Aware attach failed");
                }
            }, null, mainHandler);
        } catch (SecurityException e) {
            notifyDiscoveryFailed("Permission denied");
        }
    }

    private void startUdpServiceDiscovery(String serviceType, String protocol) {
        discoverUdpServer = new WifiAwareUdpClient.DiscoverUdpServer(wifiAwareSession);

        discoverUdpServer.discover(serviceType, protocol, 120000,
                new WifiAwareUdpClient.DiscoverUdpServer.DiscoverCallback() {
            @Override
            public void onDiscoverStarted() {
                notifyDiscoveryStarted();
            }

            @Override
            public void onDiscoverFailed(String reason) {
                notifyDiscoveryFailed(reason);
            }

            @Override
            public void onServiceFound(NanServiceInfo service) {
                mainHandler.post(() -> {
                    if (!seenPeerHandles.contains(service.getPeerHandle())) {
                        seenPeerHandles.add(service.getPeerHandle());
                        discoveredPeers.add(service);
                        notifyPeerDiscovered(service);
                    }
                });
            }

            @Override
            public void onPeersDiscovered(List<NanServiceInfo> services) {
                notifyDiscoveryComplete(new ArrayList<>(discoveredPeers));
            }
        });
    }

    public void connectToPeer(String instanceName) {
        NanServiceInfo target = null;
        for (NanServiceInfo service : discoveredPeers) {
            if (instanceName.equals(service.getServiceName())) {
                target = service;
                break;
            }
        }

        if (target == null && !discoveredPeers.isEmpty()) {
            target = discoveredPeers.get(0);
        }

        if (target == null) {
            notifyConnectionFailed("No peers available to connect");
            return;
        }

        connectToPeer(target);
    }

    public void connectToPeer(NanServiceInfo service) {
        notifyConnecting(service.getServiceName());

        udpConnect = new WifiAwareUdpClient.UdpConnect(context);

        if (currentPassphrase != null) {
            udpConnect.getTransport().setPskPassphrase(currentPassphrase);
        }

        udpConnect.connect(discoverUdpServer, service,
                new WifiAwareUdpClient.UdpConnect.UdpCallback() {

            @Override
            public void onConnected(Inet6Address peerIpv6, int peerPort) {
                notifyConnected(service.getServiceName(), peerIpv6, peerPort);
            }

            @Override
            public void onMessageReceived(String message) {
                notifyUdpMessageReceived(message);
            }

            @Override
            public void onError(String error) {
                notifyConnectionFailed(error);
            }

            @Override
            public void onDisconnected() {
                notifyDisconnected();
            }
        });
    }

    public void runIperf(WifiAwareUdpClient.UdpConnect.IperfListener listener) {
        if (udpConnect == null) {
            listener.onLine("Not connected");
            listener.onComplete(false);
            return;
        }
        boolean tcp = currentProtocol != null && currentProtocol.equalsIgnoreCase("TCP");
        udpConnect.runIperf(tcp, listener);
    }

    public void sendTcpMessage(String message) {
        if (udpConnect != null) {
            udpConnect.sendTcpMessage(message, new WifiAwareUdpClient.UdpConnect.UdpCallback() {
                @Override
                public void onConnected(Inet6Address peerIpv6, int peerPort) { }
                @Override
                public void onMessageReceived(String msg) {
                    notifyMessageSent(msg);
                }
                @Override
                public void onError(String error) {
                    notifyMessageSendFailed(error);
                }
                @Override
                public void onDisconnected() { }
            });
        } else {
            notifyMessageSendFailed("Not connected");
        }
    }

    public void sendUdpMessage(String message) {
        if (udpConnect != null) {
            udpConnect.sendUdpMessage(message, new WifiAwareUdpClient.UdpConnect.UdpCallback() {
                @Override
                public void onConnected(Inet6Address peerIpv6, int peerPort) { }
                @Override
                public void onMessageReceived(String msg) { } // Handled by main receive loop
                @Override
                public void onError(String error) {
                    notifyMessageSendFailed(error);
                }
                @Override
                public void onDisconnected() { }
            });
        } else {
            notifyMessageSendFailed("Not connected");
        }
    }

    public void disconnect() {
        Log.d(TAG, "disconnect: Starting cleanup...");

        if (connectivityManager != null) {
            connectivityManager.bindProcessToNetwork(null);
        }

        if (udpConnect != null) {
            udpConnect.release();
            udpConnect = null;
        }

        if (discoverUdpServer != null) {
            discoverUdpServer.stop();
            discoverUdpServer = null;
        }

        if (wifiAwareSession != null) {
            wifiAwareSession.close();
            wifiAwareSession = null;
        }

        discoveredPeers.clear();
        seenPeerHandles.clear();

        notifyDisconnected();
    }

    // --- Notification Helpers (running on main thread) ---

    private void notifyDiscoveryStarted() {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onDiscoveryStarted();
        });
    }

    private void notifyDiscoveryFailed(String reason) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onDiscoveryFailed(reason);
        });
    }

    private void notifyPeerDiscovered(NanServiceInfo service) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onPeerDiscovered(service);
        });
    }

    private void notifyDiscoveryComplete(List<NanServiceInfo> services) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onDiscoveryComplete(services);
        });
    }

    private void notifyConnecting(String instanceName) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onConnecting(instanceName);
        });
    }

    private void notifyConnected(String instanceName, Inet6Address peerIpv6, int peerPort) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onConnected(instanceName, peerIpv6, peerPort);
        });
    }

    private void notifyConnectionFailed(String error) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onConnectionFailed(error);
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onDisconnected();
        });
    }

    private void notifyUdpMessageReceived(String message) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onUdpMessageReceived(message);
        });
    }

    private void notifyMessageSent(String status) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onMessageSent(status);
        });
    }

    private void notifyMessageSendFailed(String error) {
        mainHandler.post(() -> {
            for (WifiAwareStateListener listener : listeners) listener.onMessageSendFailed(error);
        });
    }
}
