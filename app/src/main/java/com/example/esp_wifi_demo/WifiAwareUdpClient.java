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
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

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

        public void sendTcpMessage(String message, UdpCallback callback) {
            if (!isRunning || transport.getNanNetwork() == null || transport.getPeerIpv6() == null) {
                callback.onError("NAN network is not ready");
                return;
            }

            new Thread(() -> {
                Socket socket = null;
                try {
                    int port = transport.getPeerPort();
                    if (port <= 0) {
                        port = 3333;
                    }
                    socket = new Socket();
                    transport.getNanNetwork().bindSocket(socket);
                    socket.connect(new InetSocketAddress(transport.getPeerIpv6(), port), 10_000);
                    java.io.OutputStream out = socket.getOutputStream();
                    byte[] data = (message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.write(data);
                    out.flush();
                    Log.d(TAG, "Sent TCP message: " + message);
                    callback.onMessageReceived("TCP sent OK");
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send TCP message", e);
                    callback.onError("Send failed: " + e.getMessage());
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }).start();
        }

        public interface IperfListener {
            void onLine(String line);
            void onComplete(boolean success);
        }

        private static final int IPERF_SECONDS = 30;
        private static final int IPERF_INTERVAL_SECONDS = 3;
        private static final int IPERF_PORT = 5001;
        private static final int IPERF_UDP_BYTES = 1450;
        private volatile boolean iperfRunning = false;

        /**
         * One iperf client run over the existing NAN datapath.
         * Equivalent to: iperf -c PEER -p 5001 -t 30 -i 3 [-u]
         */
        public boolean runIperf(boolean tcp, IperfListener listener) {
            if (!isRunning || transport.getNanNetwork() == null || transport.getPeerIpv6() == null) {
                listener.onLine("NAN network is not ready");
                listener.onComplete(false);
                return false;
            }
            if (iperfRunning) {
                listener.onLine("iperf already running");
                listener.onComplete(false);
                return false;
            }
            iperfRunning = true;
            new Thread(() -> {
                boolean success = false;
                try {
                    int port = transport.getPeerPort();
                    if (port <= 0) {
                        port = IPERF_PORT;
                    }
                    Inet6Address peer = transport.getPeerIpv6();
                    listener.onLine(String.format(Locale.US,
                            "iperf -c %s -p %d -t %d%s",
                            peer.getHostAddress(), port, IPERF_SECONDS, tcp ? "" : " -u"));
                    if (tcp) {
                        runTcpIperf(peer, port, listener);
                    } else {
                        runUdpIperf(peer, port, listener);
                    }
                    success = true;
                } catch (IOException e) {
                    listener.onLine("iperf failed: " + e.getMessage());
                } finally {
                    iperfRunning = false;
                    listener.onComplete(success);
                }
            }, "iperf-client").start();
            return true;
        }

        private void runUdpIperf(Inet6Address peer, int port, IperfListener listener) throws IOException {
            Network network = transport.getNanNetwork();
            DatagramSocket socket = new DatagramSocket();
            try {
                network.bindSocket(socket);
                socket.setSendBufferSize(256 * 1024);
                byte[] payload = new byte[IPERF_UDP_BYTES];
                ByteBuffer id = ByteBuffer.wrap(payload);
                long start = System.nanoTime();
                long lastReportNs = start;
                long nextReport = start + IPERF_INTERVAL_SECONDS * 1_000_000_000L;
                long end = start + IPERF_SECONDS * 1_000_000_000L;
                long sent = 0;
                long intervalBytes = 0;
                int seq = 0;
                boolean loggedBufferFull = false;
                while (System.nanoTime() < end && isRunning) {
                    id.putInt(0, seq++);
                    DatagramPacket packet = new DatagramPacket(payload, payload.length, peer, port);
                    try {
                        socket.send(packet);
                        sent += payload.length;
                        intervalBytes += payload.length;
                    } catch (IOException e) {
                        // Expected: send buffer fills during a full-rate blast.
                        if (!loggedBufferFull) {
                            Log.d(TAG, "First send buffer full: " + e.getMessage());
                            loggedBufferFull = true;
                        }
                    }
                    long now = System.nanoTime();
                    if (now >= nextReport) {
                        listener.onLine(iperfInterval(start, now, lastReportNs, intervalBytes));
                        intervalBytes = 0;
                        lastReportNs = now;
                        nextReport += IPERF_INTERVAL_SECONDS * 1_000_000_000L;
                    }
                }
                listener.onLine(iperfTotal(start, System.nanoTime(), sent));
                listener.onLine("iperf finished");
            } finally {
                socket.close();
            }
        }

        private void runTcpIperf(Inet6Address peer, int port, IperfListener listener) throws IOException {
            Network network = transport.getNanNetwork();
            Socket socket = new Socket();
            network.bindSocket(socket);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            socket.connect(new InetSocketAddress(peer, port), 10_000);
            try {
                OutputStream out = socket.getOutputStream();
                byte[] payload = new byte[16 * 1024];
                long start = System.nanoTime();
                long lastReportNs = start;
                long nextReport = start + IPERF_INTERVAL_SECONDS * 1_000_000_000L;
                long end = start + IPERF_SECONDS * 1_000_000_000L;
                long sent = 0;
                long intervalBytes = 0;
                while (System.nanoTime() < end && isRunning) {
                    out.write(payload);
                    sent += payload.length;
                    intervalBytes += payload.length;
                    long now = System.nanoTime();
                    if (now >= nextReport) {
                        listener.onLine(iperfInterval(start, now, lastReportNs, intervalBytes));
                        intervalBytes = 0;
                        lastReportNs = now;
                        nextReport += IPERF_INTERVAL_SECONDS * 1_000_000_000L;
                    }
                }
                out.flush();
                listener.onLine(iperfTotal(start, System.nanoTime(), sent));
                listener.onLine("iperf finished");
            } finally {
                socket.close();
            }
        }

        private static String iperfInterval(long startNs, long nowNs, long lastReportNs, long bytes) {
            double endSec = (nowNs - startNs) / 1_000_000_000.0;
            double actualInterval = Math.max(0.001, (nowNs - lastReportNs) / 1_000_000_000.0);
            double startSec = Math.max(0, endSec - actualInterval);
            double mbps = (bytes * 8.0) / (actualInterval * 1_000_000.0);
            return String.format(Locale.US, "[  1] %4.1f-%4.1f sec  %6.2f MBytes  %6.2f Mbits/sec",
                    startSec, endSec, bytes / (1024.0 * 1024.0), mbps);
        }

        private static String iperfTotal(long startNs, long nowNs, long bytes) {
            double seconds = Math.max(0.001, (nowNs - startNs) / 1_000_000_000.0);
            double mbps = (bytes * 8.0) / (seconds * 1_000_000.0);
            return String.format(Locale.US, "[  1] %4.1f-%4.1f sec  %6.2f MBytes  %6.2f Mbits/sec",
                    0.0, seconds, bytes / (1024.0 * 1024.0), mbps);
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
