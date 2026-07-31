package com.example.esp_wifi_demo.library.nan;

import android.net.wifi.aware.PeerHandle;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Peer Management for WiFi Aware NAN
 * 
 * Manages discovered peers and their associated information including
 * service details, connection state, and metadata.
 * 
 * This class is package-private and used internally by the library.
 */
class PeerManagement {

    private static final String TAG = "NAN_PeerManagement";

    private final Map<PeerHandle, DiscoveredPeer> peers;
    private PeerEventCallback callback;

    /**
     * Callback interface for peer events
     */
    public interface PeerEventCallback {
        void onPeerAdded(DiscoveredPeer peer);
        void onPeerUpdated(DiscoveredPeer peer);
        void onPeerRemoved(PeerHandle peerHandle);
    }

    /**
     * Constructor
     */
    public PeerManagement() {
        this.peers = new HashMap<>();
    }

    /**
     * Set callback for peer events
     */
    public void setCallback(PeerEventCallback callback) {
        this.callback = callback;
    }

    /**
     * Add or update a peer from onServiceDiscovered callback
     * 
     * @param peerHandle PeerHandle from onServiceDiscovered
     * @param serviceSpecificInfo Service specific info (GSP data)
     * @param matchFilter Match filter list
     * @return The discovered peer object
     */
    public DiscoveredPeer addOrUpdatePeer(@NonNull PeerHandle peerHandle,
                                          byte[] serviceSpecificInfo,
                                          List<byte[]> matchFilter) {
        DiscoveredPeer peer = peers.get(peerHandle);
        boolean isNewPeer = (peer == null);

        if (isNewPeer) {
            peer = new DiscoveredPeer(peerHandle);
            peers.put(peerHandle, peer);
            Log.d(TAG, "New peer added, total peers: " + peers.size());
        } else {
            Log.d(TAG, "Updating existing peer");
        }

        // Parse GSP data from service specific info using NanUtilities
        if (serviceSpecificInfo != null && serviceSpecificInfo.length > 0) {
            NanUtilities.ParsedGspData gspData = NanUtilities.parseGspSsi(serviceSpecificInfo);
            if (gspData != null) {
                peer.setServiceName(gspData.serviceName);
                peer.setInstanceName(gspData.instanceName);
                peer.setPort(gspData.port);
                peer.setHostname(gspData.hostname);
                
                // Parse and store TXT records from txtInfo (no need to store raw bytes)
                if (gspData.txtInfo != null && gspData.txtInfo.length > 0) {
                    peer.parseTxtRecords(gspData.txtInfo);
                }
                
                Log.d(TAG, "Parsed peer info: " + gspData.toString());
            } else {
                Log.w(TAG, "Failed to parse GSP data for peer");
            }
        }

        // Store match filter
        peer.setMatchFilter(matchFilter);

        // Update discovery timestamp
        peer.updateLastSeen();

        // Notify callback
        if (callback != null) {
            if (isNewPeer) {
                callback.onPeerAdded(peer);
            } else {
                callback.onPeerUpdated(peer);
            }
        }

        return peer;
    }

    /**
     * Remove a peer
     * 
     * @param peerHandle PeerHandle to remove
     * @return true if peer was removed, false if not found
     */
    public boolean removePeer(@NonNull PeerHandle peerHandle) {
        DiscoveredPeer removed = peers.remove(peerHandle);
        if (removed != null) {
            Log.d(TAG, "Peer removed: " + removed.getInstanceName() + ", remaining: " + peers.size());
            if (callback != null) {
                callback.onPeerRemoved(peerHandle);
            }
            return true;
        }
        return false;
    }

    /**
     * Get a peer by PeerHandle
     */
    public DiscoveredPeer getPeer(@NonNull PeerHandle peerHandle) {
        return peers.get(peerHandle);
    }

    /**
     * Get all discovered peers
     */
    public List<DiscoveredPeer> getAllPeers() {
        return new ArrayList<>(peers.values());
    }

    /**
     * Find peer by instance name
     */
    public DiscoveredPeer findPeerByInstanceName(String instanceName) {
        for (DiscoveredPeer peer : peers.values()) {
            if (instanceName.equals(peer.getInstanceName())) {
                return peer;
            }
        }
        return null;
    }

    /**
     * Get number of discovered peers
     */
    public int getPeerCount() {
        return peers.size();
    }

    /**
     * Clear all peers
     */
    public void clearAllPeers() {
        peers.clear();
        Log.d(TAG, "All peers cleared");
    }

    /**
     * Display info for all discovered peers
     * Prints to console and returns formatted string
     * 
     * @return Formatted string with all peer information
     */
    public String displayInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Discovered Peers (").append(peers.size()).append(") ===\n");
        
        for (DiscoveredPeer peer : peers.values()) {
            sb.append(peer.displayInfo()).append("\n");
        }
        
        String info = sb.toString();
        Log.d(TAG, info);
        return info;
    }

    /**
     * DiscoveredPeer class - represents a discovered WiFi Aware peer
     */
    public static class DiscoveredPeer {
        private final PeerHandle peerHandle;
        private String serviceName;
        private String instanceName;
        private int port;
        private String hostname;
        private Map<String, String> txtRecords;
        private List<byte[]> matchFilter;
        private long firstSeenTimestamp;
        private long lastSeenTimestamp;
        private ConnectionState connectionState;
        private String customNote;

        /**
         * Connection state enum
         */
        public enum ConnectionState {
            DISCOVERED,      // Just discovered
            CONNECTING,      // Connection in progress
            CONNECTED,       // Successfully connected
            DISCONNECTED,    // Disconnected
            FAILED          // Connection failed
        }

        /**
         * Constructor
         */
        public DiscoveredPeer(@NonNull PeerHandle peerHandle) {
            this.peerHandle = peerHandle;
            this.txtRecords = new HashMap<>();
            this.firstSeenTimestamp = System.currentTimeMillis();
            this.lastSeenTimestamp = this.firstSeenTimestamp;
            this.connectionState = ConnectionState.DISCOVERED;
        }

        // Getters and Setters

        public PeerHandle getPeerHandle() {
            return peerHandle;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public void setInstanceName(String instanceName) {
            this.instanceName = instanceName;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public Map<String, String> getTxtRecords() {
            return new HashMap<>(txtRecords);
        }

        public String getTxtValue(String key) {
            return txtRecords.get(key);
        }

        public List<byte[]> getMatchFilter() {
            return matchFilter;
        }

        public void setMatchFilter(List<byte[]> matchFilter) {
            this.matchFilter = matchFilter;
        }

        public long getFirstSeenTimestamp() {
            return firstSeenTimestamp;
        }

        public long getLastSeenTimestamp() {
            return lastSeenTimestamp;
        }

        public void updateLastSeen() {
            this.lastSeenTimestamp = System.currentTimeMillis();
        }

        public ConnectionState getConnectionState() {
            return connectionState;
        }

        public void setConnectionState(ConnectionState state) {
            this.connectionState = state;
            Log.d(TAG, "Peer " + instanceName + " state changed to: " + state);
        }

        public String getCustomNote() {
            return customNote;
        }

        public void setCustomNote(String note) {
            this.customNote = note;
        }

        /**
         * Get time since first discovery in milliseconds
         */
        public long getTimeSinceDiscovery() {
            return System.currentTimeMillis() - firstSeenTimestamp;
        }

        /**
         * Get time since last seen in milliseconds
         */
        public long getTimeSinceLastSeen() {
            return System.currentTimeMillis() - lastSeenTimestamp;
        }

        /**
         * Parse TXT records from txtInfo byte array
         * Format: [length][key=value][length][key=value]...
         * 
         * @param txtInfo TXT info bytes from ParsedGspData
         */
        public void parseTxtRecords(byte[] txtInfo) {
            txtRecords.clear();

            if (txtInfo == null || txtInfo.length == 0) {
                return;
            }

            int offset = 0;
            while (offset < txtInfo.length) {
                // Read length byte
                int len = txtInfo[offset++] & 0xFF;
                
                if (len == 0 || offset + len > txtInfo.length) {
                    break;
                }

                // Extract key=value string
                String kvPair = new String(txtInfo, offset, len);
                offset += len;

                // Split on first '=' character
                int eqPos = kvPair.indexOf('=');
                if (eqPos > 0) {
                    String key = kvPair.substring(0, eqPos);
                    String value = kvPair.substring(eqPos + 1);
                    txtRecords.put(key, value);
                    Log.d(TAG, "Parsed TXT record: " + key + "=" + value);
                }
            }

            Log.d(TAG, "Parsed " + txtRecords.size() + " TXT records for peer: " + instanceName);
        }

        /**
         * Check if peer is stale (not seen for a while)
         * 
         * @param timeoutMs Timeout in milliseconds
         * @return true if peer hasn't been seen within timeout
         */
        public boolean isStale(long timeoutMs) {
            return getTimeSinceLastSeen() > timeoutMs;
        }

        /**
         * Display basic peer information (Service Name, Instance Name, TXT Info)
         * Prints to console and returns formatted string
         * 
         * @return Formatted string with peer information
         */
        public String displayInfo() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Peer Information ===\n");
            sb.append("Service Name: ").append(serviceName != null ? serviceName : "N/A").append("\n");
            sb.append("Instance Name: ").append(instanceName != null ? instanceName : "N/A").append("\n");
            
            if (!txtRecords.isEmpty()) {
                sb.append("TXT Info:\n");
                for (Map.Entry<String, String> entry : txtRecords.entrySet()) {
                    sb.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            } else {
                sb.append("TXT Info: None\n");
            }
            sb.append("========================\n");
            
            String info = sb.toString();
            Log.i(TAG, info);
            return info;
        }


        @Override
        public String toString() {
            return "DiscoveredPeer{" +
                    "instanceName='" + instanceName + '\'' +
                    ", serviceName='" + serviceName + '\'' +
                    ", state=" + connectionState +
                    ", txtRecords=" + txtRecords.size() +
                    ", timeSinceDiscovery=" + getTimeSinceDiscovery() + "ms" +
                    '}';
        }

        /**
         * Get a detailed string representation with all information
         */
        public String toDetailedString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DiscoveredPeer Details:\n");
            sb.append("  Instance Name: ").append(instanceName).append("\n");
            sb.append("  Service Name: ").append(serviceName).append("\n");
            if (hostname != null) {
                sb.append("  Hostname: ").append(hostname).append("\n");
            }
            if (port > 0) {
                sb.append("  Port: ").append(port).append("\n");
            }
            sb.append("  Connection State: ").append(connectionState).append("\n");
            sb.append("  First Seen: ").append(new java.util.Date(firstSeenTimestamp)).append("\n");
            sb.append("  Last Seen: ").append(new java.util.Date(lastSeenTimestamp)).append("\n");
            sb.append("  Time Since Discovery: ").append(getTimeSinceDiscovery()).append("ms\n");
            
            if (!txtRecords.isEmpty()) {
                sb.append("  TXT Records:\n");
                for (Map.Entry<String, String> entry : txtRecords.entrySet()) {
                    sb.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }
            
            if (customNote != null) {
                sb.append("  Note: ").append(customNote).append("\n");
            }
            
            return sb.toString();
        }
    }
}
