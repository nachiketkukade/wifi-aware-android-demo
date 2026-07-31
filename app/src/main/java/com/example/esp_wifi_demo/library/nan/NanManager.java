package com.example.esp_wifi_demo.library.nan;

import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service Discovery classes for WiFi Aware NAN service advertisement and discovery.
 * Designed to mimic android.net.nsd.NsdManager for easier porting.
 */
public class NanManager {
    private static final String TAG = "NanManager";

    public static final int PROTOCOL_NAN = 1;

    private final WifiAwareSession wifiAwareSession;
    private final Handler handler;

    // State mapping for active sessions
    private final Map<DiscoveryListener, SubscribeDiscoverySession> activeDiscoveries = new HashMap<>();
    private final Map<RegistrationListener, PublishDiscoverySession> activeRegistrations = new HashMap<>();
    private final Map<DiscoveryListener, Map<PeerHandle, NanServiceInfo>> discoveredServicesMap = new HashMap<>();

    /**
     * Interface for discovering services, mimicking NsdManager.DiscoveryListener.
     */
    public interface DiscoveryListener {
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onServiceFound(NanServiceInfo serviceInfo);
        void onServiceLost(NanServiceInfo serviceInfo);
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        
        // NAN Specific callback for receiving messages
        void onMessageReceived(PeerHandle peerHandle, byte[] message);
    }

    /**
     * Interface for registering services, mimicking NsdManager.RegistrationListener.
     */
    public interface RegistrationListener {
        void onRegistrationFailed(NanServiceInfo serviceInfo, int errorCode);
        void onServiceRegistered(NanServiceInfo serviceInfo);
        void onServiceUnregistered(NanServiceInfo serviceInfo);
        void onUnregistrationFailed(NanServiceInfo serviceInfo, int errorCode);
        
        // NAN Specific callbacks
        void onServiceDiscoveredByPeer(PeerHandle peerHandle, byte[] serviceSpecificInfo);
        void onMessageReceived(PeerHandle peerHandle, byte[] message);
    }

    public NanManager(WifiAwareSession session) {
        this.wifiAwareSession = session;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Discover services.
     * @param serviceType The service type being discovered (e.g., "_ESP-Demo._udp")
     * @param protocolType The protocol (e.g., PROTOCOL_NAN)
     * @param listener The discovery listener
     */
    @android.annotation.SuppressLint("MissingPermission")
    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        if (wifiAwareSession == null) {
            listener.onStartDiscoveryFailed(serviceType, -1);
            return;
        }

        SubscribeConfig config = new SubscribeConfig.Builder()
                .setServiceName(serviceType)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                .setTtlSec(0)
                .setMatchFilter(new ArrayList<>())
                .build();

        Map<PeerHandle, NanServiceInfo> discoveredServices = new HashMap<>();
        discoveredServicesMap.put(listener, discoveredServices);

        wifiAwareSession.subscribe(config, new DiscoverySessionCallback() {
            @Override
            public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
                super.onSubscribeStarted(session);
                activeDiscoveries.put(listener, session);
                listener.onDiscoveryStarted(serviceType);
            }

            @Override
            public void onServiceDiscovered(@NonNull PeerHandle peerHandle,
                                            byte[] serviceSpecificInfo,
                                            List<byte[]> matchFilter) {
                super.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter);

                NanServiceInfo serviceInfo = new NanServiceInfo();
                serviceInfo.setPeerHandle(peerHandle);

                if (serviceSpecificInfo != null && serviceSpecificInfo.length > 0) {
                    NanUtilities.ParsedGspData gspData = NanUtilities.parseGspSsi(serviceSpecificInfo);
                    if (gspData != null) {
                        gspData.populateServiceInfo(serviceInfo);
                    }
                }

                discoveredServices.put(peerHandle, serviceInfo);
                listener.onServiceFound(serviceInfo);
            }

            @Override
            public void onServiceLost(@NonNull PeerHandle peerHandle, int reason) {
                super.onServiceLost(peerHandle, reason);
                NanServiceInfo serviceInfo = discoveredServices.remove(peerHandle);
                if (serviceInfo != null) {
                    listener.onServiceLost(serviceInfo);
                }
            }

            @Override
            public void onMessageReceived(@NonNull PeerHandle peerHandle, byte[] message) {
                super.onMessageReceived(peerHandle, message);
                listener.onMessageReceived(peerHandle, message);
            }

            @Override
            public void onSessionTerminated() {
                super.onSessionTerminated();
                activeDiscoveries.remove(listener);
                discoveredServicesMap.remove(listener);
                listener.onDiscoveryStopped(serviceType);
            }
        }, null);
    }

    /**
     * Stop service discovery.
     * @param listener The discovery listener to stop
     */
    public void stopServiceDiscovery(DiscoveryListener listener) {
        SubscribeDiscoverySession session = activeDiscoveries.remove(listener);
        if (session != null) {
            session.close();
            // onSessionTerminated may not be called reliably after manual close
            discoveredServicesMap.remove(listener);
        } else {
            listener.onStopDiscoveryFailed("Unknown", -1);
        }
    }

    /**
     * Register a service.
     * @param serviceInfo Information about the service to register
     * @param protocolType The protocol (e.g., PROTOCOL_NAN)
     * @param listener The registration listener
     */
    @android.annotation.SuppressLint("MissingPermission")
    public void registerService(NanServiceInfo serviceInfo, int protocolType, RegistrationListener listener) {
        if (wifiAwareSession == null) {
            listener.onRegistrationFailed(serviceInfo, -1);
            return;
        }

        // Build TXT record bytes
        byte[] txtRecord = buildTxtRecord(serviceInfo.getAttributes());

        // Create PTR record SSI
        byte[] ssi = NanUtilities.createPtrRecordSsi(
                serviceInfo.getServiceType(),
                serviceInfo.getServiceName(),
                txtRecord
        );

        PublishConfig.Builder builder = new PublishConfig.Builder()
                .setServiceName(serviceInfo.getServiceType())
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED);

        if (ssi != null) {
            builder.setServiceSpecificInfo(ssi);
        }

        PublishConfig config = builder.build();

        wifiAwareSession.publish(config, new DiscoverySessionCallback() {
            @Override
            public void onPublishStarted(@NonNull PublishDiscoverySession session) {
                super.onPublishStarted(session);
                activeRegistrations.put(listener, session);
                listener.onServiceRegistered(serviceInfo);
            }

            @Override
            public void onServiceDiscovered(@NonNull PeerHandle peerHandle,
                                            byte[] serviceSpecificInfo,
                                            List<byte[]> matchFilter) {
                super.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter);
                listener.onServiceDiscoveredByPeer(peerHandle, serviceSpecificInfo);
            }

            @Override
            public void onMessageReceived(@NonNull PeerHandle peerHandle, byte[] message) {
                super.onMessageReceived(peerHandle, message);
                listener.onMessageReceived(peerHandle, message);
            }

            @Override
            public void onSessionTerminated() {
                super.onSessionTerminated();
                activeRegistrations.remove(listener);
                listener.onServiceUnregistered(serviceInfo);
            }
        }, null);
    }

    /**
     * Unregister a service.
     * @param listener The registration listener
     */
    public void unregisterService(RegistrationListener listener) {
        PublishDiscoverySession session = activeRegistrations.remove(listener);
        if (session != null) {
            session.close();
            // Provide a dummy NanServiceInfo for the callback since we don't track it locally
            listener.onServiceUnregistered(new NanServiceInfo());
        } else {
            listener.onUnregistrationFailed(new NanServiceInfo(), -1);
        }
    }

    /**
     * Send a custom message to a peer (NAN specific).
     */
    public void sendMessage(DiscoveryListener listener, PeerHandle peerHandle, byte[] message) {
        SubscribeDiscoverySession session = activeDiscoveries.get(listener);
        if (session != null) {
            session.sendMessage(peerHandle, 0, message);
        }
    }
    
    public void sendMessage(RegistrationListener listener, PeerHandle peerHandle, byte[] message) {
        PublishDiscoverySession session = activeRegistrations.get(listener);
        if (session != null) {
            session.sendMessage(peerHandle, 0, message);
        }
    }
    
    /**
     * Get the active DiscoverySession for a DiscoveryListener (needed for NetworkSpecifier).
     */
    public android.net.wifi.aware.DiscoverySession getSession(DiscoveryListener listener) {
        return activeDiscoveries.get(listener);
    }

    /**
     * Get the active DiscoverySession for a RegistrationListener (needed for NetworkSpecifier).
     */
    public android.net.wifi.aware.DiscoverySession getSession(RegistrationListener listener) {
        return activeRegistrations.get(listener);
    }

    /**
     * Build TXT record bytes in RFC 6763 format
     * Format: [length][key=value][length][key=value]...
     */
    private byte[] buildTxtRecord(Map<String, byte[]> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        int totalLen = 0;
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                totalLen += 1 + entry.getKey().length() + 1 + entry.getValue().length;
            }
        }

        if (totalLen == 0) return null;

        byte[] txtBuf = new byte[totalLen];
        int offset = 0;

        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                String k = entry.getKey();
                String v = new String(entry.getValue()); // assuming string values for GSP
                String kv = k + "=" + v;
                txtBuf[offset++] = (byte) kv.length();
                byte[] kvBytes = kv.getBytes();
                System.arraycopy(kvBytes, 0, txtBuf, offset, kvBytes.length);
                offset += kvBytes.length;
            }
        }
        return txtBuf;
    }
}
