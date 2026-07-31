package com.example.esp_wifi_demo.library.nan;

import android.net.wifi.aware.PeerHandle;
import java.util.HashMap;
import java.util.Map;

/**
 * A class representing service information for network service discovery.
 * Designed to mimic android.net.nsd.NsdServiceInfo for easier porting.
 */
public class NanServiceInfo {
    private String serviceName;
    private String serviceType;
    private PeerHandle peerHandle;
    private final Map<String, byte[]> attributes = new HashMap<>();

    public NanServiceInfo() {
    }

    /**
     * Get the service name (equivalent to the instance name in DNS-SD/WiFi Aware).
     * @return The service name (e.g. "ESP UDP Server")
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Set the service name.
     * @param serviceName The service name (e.g. "ESP UDP Server")
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Get the service type (equivalent to the service type + protocol in DNS-SD).
     * @return The service type (e.g. "_ESP-Demo._udp")
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Set the service type.
     * @param serviceType The service type (e.g. "_ESP-Demo._udp")
     */
    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    /**
     * Add a service attribute as a key/value pair.
     * @param key Attribute key
     * @param value Attribute value
     */
    public void setAttribute(String key, String value) {
        if (value != null) {
            attributes.put(key, value.getBytes());
        } else {
            attributes.put(key, null);
        }
    }

    /**
     * Retrieve attributes as a map of String keys to byte[] values.
     * @return Map of attributes
     */
    public Map<String, byte[]> getAttributes() {
        return attributes;
    }

    /**
     * (NAN Specific) Get the PeerHandle associated with this discovered service.
     * Required for establishing a NAN Data Path via Transport.
     */
    public PeerHandle getPeerHandle() {
        return peerHandle;
    }

    /**
     * (NAN Specific) Set the PeerHandle.
     */
    public void setPeerHandle(PeerHandle peerHandle) {
        this.peerHandle = peerHandle;
    }

    @Override
    public String toString() {
        return "NanServiceInfo{" +
                "serviceName='" + serviceName + '\'' +
                ", serviceType='" + serviceType + '\'' +
                ", peerHandle=" + peerHandle +
                '}';
    }
}
