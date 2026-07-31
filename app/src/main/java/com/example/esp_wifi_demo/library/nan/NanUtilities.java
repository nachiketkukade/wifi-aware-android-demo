package com.example.esp_wifi_demo.library.nan;

import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_128;
import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128;

/**
 * WiFi Aware NAN Utilities
 *
 * This is the main utility class for WiFi Aware NAN operations, providing:
 * - Device capability querying and display
 * - GSP (Generic Service Protocol) parsing (package-private, for library internal use)
 *
 * API Level: 28+ (Android 9.0 Pie)
 */
public class NanUtilities {
    private static final String TAG = "NanUtilities";

    // === WiFi Aware Capabilities ===

    // Basic characteristics (API 26+)
    private int maxServiceNameLength;
    private int maxServiceSpecificInfoLength;
    private int maxMatchFilterLength;

    // Advanced characteristics (API 33+)
    private int supportedCipherSuites;
    private int numberOfSupportedDataInterfaces;
    private int numberOfSupportedDataPaths;
    private int numberOfSupportedPublishSessions;
    private int numberOfSupportedSubscribeSessions;

    // API 34+ characteristics
    private int supportedPairingCipherSuites;
    private boolean isAwarePairingSupported;
    private boolean isInstantCommunicationModeSupported;
    private boolean isSuspensionSupported;

    /**
     * Constructor - initializes all capability flags to default values
     */
    public NanUtilities() {
        // Initialize to zero/false
        maxServiceNameLength = 0;
        maxServiceSpecificInfoLength = 0;
        maxMatchFilterLength = 0;
        supportedCipherSuites = 0;
        numberOfSupportedDataInterfaces = 0;
        numberOfSupportedDataPaths = 0;
        numberOfSupportedPublishSessions = 0;
        numberOfSupportedSubscribeSessions = 0;
        supportedPairingCipherSuites = 0;
        isAwarePairingSupported = false;
        isInstantCommunicationModeSupported = false;
        isSuspensionSupported = false;
    }

    /**
     * Refreshes capabilities from the WiFi Aware manager
     *
     * @param wifiAwareManager The WifiAwareManager instance
     * @return true if characteristics were successfully retrieved, false otherwise
     */
    public boolean refreshCapabilities(WifiAwareManager wifiAwareManager) {
        if (wifiAwareManager == null) {
            Log.w(TAG, "Cannot refresh - WifiAwareManager is null");
            return false;
        }

        Characteristics characteristics = wifiAwareManager.getCharacteristics();
        if (characteristics != null) {
            // Basic characteristics (API 26+, always available on minSdk 29)
            maxServiceNameLength = characteristics.getMaxServiceNameLength();
            maxServiceSpecificInfoLength = characteristics.getMaxServiceSpecificInfoLength();
            maxMatchFilterLength = characteristics.getMaxMatchFilterLength();
            Log.d(TAG, "Basic characteristics retrieved - Max Service Name: " + maxServiceNameLength);

            // Advanced characteristics (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                supportedCipherSuites = characteristics.getSupportedCipherSuites();
                numberOfSupportedDataInterfaces = characteristics.getNumberOfSupportedDataInterfaces();
                numberOfSupportedDataPaths = characteristics.getNumberOfSupportedDataPaths();
                numberOfSupportedPublishSessions = characteristics.getNumberOfSupportedPublishSessions();
                numberOfSupportedSubscribeSessions = characteristics.getNumberOfSupportedSubscribeSessions();
                Log.d(TAG, "API 33+ characteristics retrieved - Cipher Suites: " + supportedCipherSuites);
            }

            // API 34+ characteristics
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                supportedPairingCipherSuites = characteristics.getSupportedPairingCipherSuites();
                isAwarePairingSupported = characteristics.isAwarePairingSupported();
                isInstantCommunicationModeSupported = characteristics.isInstantCommunicationModeSupported();
                isSuspensionSupported = characteristics.isSuspensionSupported();
                Log.d(TAG, "API 34+ characteristics retrieved - Pairing supported: " + isAwarePairingSupported);
            }

            return true;
        } else {
            Log.w(TAG, "getCharacteristics() returned null - WiFi Aware may not be available yet");
            return false;
        }
    }

    /**
     * Generates a formatted string displaying all capabilities in a checklist format
     *
     * @return Formatted string with all WiFi Aware capabilities
     */
    public String displayCapabilities() {
        StringBuilder sb = new StringBuilder();

        // Add Android API version
        sb.append("• Android API Level: ").append(Build.VERSION.SDK_INT).append("\n\n");

        // Add WiFi Aware characteristics if available
        if (maxServiceNameLength > 0) {
            // Basic characteristics (API 26+)
            sb.append("• Max Service Name Length: ").append(maxServiceNameLength).append("\n");
            sb.append("• Max Service Specific Info Length: ").append(maxServiceSpecificInfoLength).append("\n");
            sb.append("• Max Match Filter Length: ").append(maxMatchFilterLength).append("\n\n");

            // Advanced characteristics (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                String cipherSuites = decodeCipherSuites(supportedCipherSuites);
                sb.append("• Supported Cipher Suites: ").append(cipherSuites).append("\n");
                sb.append("• Supported Data Interfaces: ").append(numberOfSupportedDataInterfaces).append("\n");
                sb.append("• Supported Data Paths: ").append(numberOfSupportedDataPaths).append("\n");
                sb.append("• Supported Publish Sessions: ").append(numberOfSupportedPublishSessions).append("\n");
                sb.append("• Supported Subscribe Sessions: ").append(numberOfSupportedSubscribeSessions).append("\n\n");
            }

            // Feature support checklist (API 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                sb.append("Feature Support:\n");
                sb.append(isAwarePairingSupported ? "✓ " : "✗ ").append("Aware Pairing\n");
                sb.append(isInstantCommunicationModeSupported ? "✓ " : "✗ ").append("Instant Communication Mode\n");
                sb.append(isSuspensionSupported ? "✓ " : "✗ ").append("Suspension\n");

                // Show supported pairing cipher suites if pairing is supported
                if (isAwarePairingSupported && supportedPairingCipherSuites != 0) {
                    sb.append("• Pairing Cipher Suites: ").append(decodePairingCipherSuites(supportedPairingCipherSuites)).append("\n");
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Show that API 34+ features are unavailable on API 33
                sb.append("Feature Support (requires API 34+):\n");
                sb.append("✗ Aware Pairing\n");
                sb.append("✗ Instant Communication Mode\n");
                sb.append("✗ Suspension\n");
            }
        } else {
            sb.append("⚠ WiFi Aware characteristics not available\n\n");
            sb.append("This usually means:\n");
            sb.append("• WiFi Aware is not enabled\n");
            sb.append("• WiFi is turned off\n");
            sb.append("• Device does not support WiFi Aware\n");
        }

        return sb.toString();
    }

    /**
     * Decodes cipher suite flags into human-readable string
     *
     * @param cipherSuites Bitmask of supported cipher suites
     * @return Comma-separated list of cipher suite names
     */
    private String decodeCipherSuites(int cipherSuites) {
        if (cipherSuites == 0) {
            return "None";
        }

        StringBuilder sb = new StringBuilder();
        if ((cipherSuites & WIFI_AWARE_CIPHER_SUITE_NCS_SK_128) != 0) {
            sb.append("NCS_SK_128");
        }
        if ((cipherSuites & WIFI_AWARE_CIPHER_SUITE_NCS_PK_128) != 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("NCS_PK_128");
        }

        return sb.length() > 0 ? sb.toString() : "Unknown";
    }

    /**
     * Decodes pairing cipher suite flags into human-readable string (API 34+)
     *
     * @param pairingCipherSuites Bitmask of supported pairing cipher suites
     * @return Comma-separated list of pairing cipher suite names
     */
    private String decodePairingCipherSuites(int pairingCipherSuites) {
        if (pairingCipherSuites == 0) {
            return "None";
        }

        StringBuilder sb = new StringBuilder();
        // API 34 pairing cipher suite constants (PASN-based)
        if ((pairingCipherSuites & 4) != 0) { // WIFI_AWARE_CIPHER_SUITE_NCS_SK_PASN_128
            sb.append("NCS_SK_PASN_128");
        }
        if ((pairingCipherSuites & 8) != 0) { // WIFI_AWARE_CIPHER_SUITE_NCS_PK_PASN_128
            if (sb.length() > 0) sb.append(", ");
            sb.append("NCS_PK_PASN_128");
        }

        return sb.length() > 0 ? sb.toString() : "Unknown (0x" + Integer.toHexString(pairingCipherSuites) + ")";
    }

    // === GSP Protocol Support (Package-Private) ===

    // WiFi Alliance OUI
    static final byte[] WFA_OUI = {0x50, 0x6F, (byte) 0x9A};

    // Service Protocol Type
    static final byte WIFI_SVC_PROTO_GENERIC = 0x02;

    // GSP Sub-attribute IDs from Wi-Fi Aware Spec Table 145
    static final int GSP_SUBATTR_TRANSPORT_PORT = 0x00;
    static final int GSP_SUBATTR_TRANSPORT_PROTOCOL = 0x01;
    static final int GSP_SUBATTR_SERVICE_NAME = 0x02;
    static final int GSP_SUBATTR_INSTANCE_NAME = 0x03;
    static final int GSP_SUBATTR_TEXTINFO = 0x04;
    static final int GSP_SUBATTR_UUID = 0x05;
    static final int GSP_SUBATTR_BLOB = 0x06;
    static final int GSP_SUBATTR_VENDOR_SPECIFIC = 0xDD;

    // SSI Header size (OUI + Protocol)
    static final int SSI_HEADER_SIZE = 4;

    /**
     * Parsed GSP attribute data
     */
    static class ParsedGspData {
        public String serviceName;
        public String instanceName;
        public byte[] txtInfo;
        public int port;
        public String hostname;
        public Map<Integer, byte[]> rawAttributes = new HashMap<>();

        @Override
        public String toString() {
            return "ParsedGspData{" +
                    "serviceName='" + serviceName + '\'' +
                    ", instanceName='" + instanceName + '\'' +
                    ", port=" + port +
                    ", hostname='" + hostname + '\'' +
                    ", txtInfoLen=" + (txtInfo != null ? txtInfo.length : 0) +
                    '}';
        }

        /**
         * Populates a NanServiceInfo object with the parsed GSP PTR data.
         */
        public void populateServiceInfo(NanServiceInfo serviceInfo) {
            serviceInfo.setServiceType(this.serviceName);
            serviceInfo.setServiceName(this.instanceName);

            // Parse TXT records (RFC 6763 format: [length][key=value])
            if (this.txtInfo != null && this.txtInfo.length > 0) {
                int offset = 0;
                while (offset < this.txtInfo.length) {
                    int len = this.txtInfo[offset++] & 0xFF;
                    if (len == 0 || offset + len > this.txtInfo.length) break;

                    String kv = new String(this.txtInfo, offset, len);
                    int eqIdx = kv.indexOf('=');
                    if (eqIdx > 0) {
                        String key = kv.substring(0, eqIdx);
                        String value = kv.substring(eqIdx + 1);
                        serviceInfo.setAttribute(key, value);
                    }
                    offset += len;
                }
            }
        }
    }

    /**
     * Create SSI header with WFA OUI and GSP protocol type
     *
     * @return 4-byte header: [OUI: 3 bytes][Protocol: 1 byte]
     */
    static byte[] createSsiHeader() {
        byte[] header = new byte[SSI_HEADER_SIZE];
        header[0] = WFA_OUI[0];
        header[1] = WFA_OUI[1];
        header[2] = WFA_OUI[2];
        header[3] = WIFI_SVC_PROTO_GENERIC;
        return header;
    }

    /**
     * Create empty GSP SSI (header only, no sub-attributes)
     * Used for SRV record request
     *
     * @return 4-byte SSI with only header
     */
    static byte[] createEmptyGspSsi() {
        return createSsiHeader();
    }

    /**
     * Create a TLV (Type-Length-Value) sub-attribute
     *
     * @param subAttrId Sub-attribute ID (1 byte)
     * @param value Value bytes
     * @return TLV-encoded sub-attribute
     */
    static byte[] createTlvSubAttribute(int subAttrId, byte[] value) {
        if (value == null) {
            value = new byte[0];
        }

        // TLV format: 1 byte ID + 2 bytes Length (LE) + N bytes Value
        byte[] tlv = new byte[3 + value.length];
        tlv[0] = (byte) subAttrId;
        tlv[1] = (byte) (value.length & 0xFF);
        tlv[2] = (byte) ((value.length >> 8) & 0xFF);
        System.arraycopy(value, 0, tlv, 3, value.length);

        return tlv;
    }

    /**
     * Create a TLV sub-attribute with string value
     */
    static byte[] createTlvSubAttribute(int subAttrId, String value) {
        return createTlvSubAttribute(subAttrId, value != null ? value.getBytes() : null);
    }

    /**
     * Create a TLV sub-attribute with 16-bit port value (little-endian)
     */
    static byte[] createPortTlv(int port) {
        byte[] portBytes = new byte[2];
        portBytes[0] = (byte) (port & 0xFF);
        portBytes[1] = (byte) ((port >> 8) & 0xFF);
        return createTlvSubAttribute(GSP_SUBATTR_TRANSPORT_PORT, portBytes);
    }

    /**
     * Create PTR record SSI (for service discovery/publish)
     *
     * @param serviceName Service name (e.g., "ESP-Demo_udp")
     * @param instanceName Instance name (e.g., "ESP-Server")
     * @param txtInfo Optional TXT info bytes
     * @return Complete SSI with header and TLV attributes
     */
    static byte[] createPtrRecordSsi(String serviceName, String instanceName, byte[] txtInfo) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Add header
            baos.write(createSsiHeader());

            // Add service name TLV
            if (serviceName != null && !serviceName.isEmpty()) {
                baos.write(createTlvSubAttribute(GSP_SUBATTR_SERVICE_NAME, serviceName));
            }

            // Add instance name TLV
            if (instanceName != null && !instanceName.isEmpty()) {
                baos.write(createTlvSubAttribute(GSP_SUBATTR_INSTANCE_NAME, instanceName));
            }

            // Add TXT info TLV if provided
            if (txtInfo != null && txtInfo.length > 0) {
                baos.write(createTlvSubAttribute(GSP_SUBATTR_TEXTINFO, txtInfo));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error creating PTR record SSI: " + e.getMessage());
            return null;
        }

        byte[] ssi = baos.toByteArray();
        Log.d(TAG, "Created PTR record SSI: serviceName=" + serviceName +
              ", instanceName=" + instanceName + ", length=" + ssi.length);
        return ssi;
    }

    /**
     * Create SRV record SSI (for datapath response)
     *
     * @param hostname Hostname string
     * @param port Port number
     * @return Complete SSI with header and TLV attributes
     */
    static byte[] createSrvRecordSsi(String hostname, int port) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Add header
            baos.write(createSsiHeader());

            // Add port TLV
            baos.write(createPortTlv(port));

            // Add hostname (instance name) TLV
            if (hostname != null && !hostname.isEmpty()) {
                baos.write(createTlvSubAttribute(GSP_SUBATTR_INSTANCE_NAME, hostname));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error creating SRV record SSI: " + e.getMessage());
            return null;
        }

        byte[] ssi = baos.toByteArray();
        Log.d(TAG, "Created SRV record SSI: hostname=" + hostname +
              ", port=" + port + ", length=" + ssi.length);
        return ssi;
    }

    /**
     * Verify if SSI has valid WFA OUI and GSP protocol
     *
     * @param ssi SSI bytes
     * @return true if valid WFA GSP header
     */
    static boolean isValidGspSsi(byte[] ssi) {
        if (ssi == null || ssi.length < SSI_HEADER_SIZE) {
            return false;
        }

        boolean validOui = (ssi[0] == WFA_OUI[0] &&
                           ssi[1] == WFA_OUI[1] &&
                           ssi[2] == WFA_OUI[2]);
        boolean validProto = (ssi[3] == WIFI_SVC_PROTO_GENERIC);

        return validOui && validProto;
    }

    /**
     * Parse GSP SSI and extract all sub-attributes
     *
     * @param ssi SSI bytes to parse
     * @return ParsedGspData containing extracted attributes, or null on error
     */
    static ParsedGspData parseGspSsi(byte[] ssi) {
        if (ssi == null || ssi.length < SSI_HEADER_SIZE) {
            Log.e(TAG, "Invalid SSI: null or too short");
            return null;
        }

        // Verify header
        if (!isValidGspSsi(ssi)) {
            Log.e(TAG, "Invalid SSI: WFA OUI or protocol mismatch");
            Log.d(TAG, "SSI header: " + String.format("%02X %02X %02X %02X",
                  ssi[0], ssi[1], ssi[2], ssi[3]));
            return null;
        }

        ParsedGspData data = new ParsedGspData();
        int offset = SSI_HEADER_SIZE;

        // Parse TLV sub-attributes
        while (offset + 3 <= ssi.length) {
            int subAttrId = ssi[offset] & 0xFF;
            int valueLen = (ssi[offset + 1] & 0xFF) | ((ssi[offset + 2] & 0xFF) << 8);
            offset += 3;

            // Check if we have enough data for the value
            if (offset + valueLen > ssi.length) {
                Log.e(TAG, "Invalid SSI: value length exceeds buffer size at offset " + offset);
                break;
            }

            // Extract value
            byte[] value = new byte[valueLen];
            System.arraycopy(ssi, offset, value, 0, valueLen);

            // Store raw attribute
            data.rawAttributes.put(subAttrId, value);

            // Parse known attributes
            switch (subAttrId) {
                case GSP_SUBATTR_TRANSPORT_PORT:
                    if (valueLen == 2) {
                        data.port = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
                        Log.d(TAG, "Parsed port: " + data.port);
                    }
                    break;

                case GSP_SUBATTR_SERVICE_NAME:
                    data.serviceName = new String(value);
                    Log.d(TAG, "Parsed service name: " + data.serviceName);
                    break;

                case GSP_SUBATTR_INSTANCE_NAME:
                    data.instanceName = new String(value);
                    data.hostname = data.instanceName; // Also used as hostname
                    Log.d(TAG, "Parsed instance name: " + data.instanceName);
                    break;

                case GSP_SUBATTR_TEXTINFO:
                    data.txtInfo = value;
                    Log.d(TAG, "Parsed TXT info, length: " + valueLen);
                    break;

                default:
                    Log.d(TAG, "Skipping unknown sub-attribute ID: 0x" +
                          String.format("%02X", subAttrId) + ", length: " + valueLen);
                    break;
            }

            offset += valueLen;
        }

        Log.d(TAG, "Parsed GSP SSI: " + data.toString());
        return data;
    }

    /**
     * Convert SSI bytes to hex string for debugging
     */
    static String toHexString(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /**
     * Create GSP SSI request for SRV query
     *
     * @param serviceName Service name to query
     * @param port Port number (0 for request)
     * @param hostname Hostname (empty for request)
     * @return SSI bytes for SRV request
     */
    static byte[] createGspSsiRequest(String serviceName, int port, String hostname) {
        // For SRV request, we typically send empty GSP SSI (header only)
        return createEmptyGspSsi();
    }
}
