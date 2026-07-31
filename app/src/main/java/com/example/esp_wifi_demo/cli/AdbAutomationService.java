package com.example.esp_wifi_demo.cli;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.example.esp_wifi_demo.core.WifiAwareController;
import com.example.esp_wifi_demo.library.nan.NanServiceInfo;

import java.util.List;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class AdbAutomationService extends Service {
    private static final String TAG = "AdbAutomationService";

    public static final String ACTION_START_DISCOVERY = "com.example.esp_wifi_demo.START_DISCOVERY";
    public static final String ACTION_CONNECT_PEER = "com.example.esp_wifi_demo.CONNECT_PEER";
    public static final String ACTION_DISCONNECT = "com.example.esp_wifi_demo.DISCONNECT";
    public static final String ACTION_QUERY_PEERS = "com.example.esp_wifi_demo.QUERY_PEERS";
    public static final String ACTION_SEND_MESSAGE = "com.example.esp_wifi_demo.SEND_MESSAGE";

    private WifiAwareController controller;

    private final BroadcastReceiver adbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String action = intent.getAction();
            Log.d(TAG, "Received ADB intent: " + action);

            switch (action) {
                case ACTION_START_DISCOVERY:
                    String passphrase = intent.getStringExtra("passphrase");
                    String serviceType = intent.getStringExtra("service_type");
                    String protocol = intent.getStringExtra("protocol");
                    Log.i(TAG, "ADB Command: START_DISCOVERY. Passphrase=" +
                            (passphrase != null ? "***" : "none") + ", ServiceType=" +
                            (serviceType != null ? serviceType : "ESP-Demo") + ", Protocol=" +
                            (protocol != null ? protocol : "UDP"));
                    controller.startDiscovery(passphrase, serviceType, protocol);
                    break;

                case ACTION_CONNECT_PEER:
                    String instanceName = intent.getStringExtra("instance_name");
                    Log.i(TAG, "ADB Command: CONNECT_PEER. Instance=" + instanceName);
                    if (instanceName != null && !instanceName.isEmpty()) {
                        controller.connectToPeer(instanceName);
                    } else {
                        Log.e(TAG, "Missing instance_name extra for CONNECT_PEER");
                    }
                    break;

                case ACTION_DISCONNECT:
                    Log.i(TAG, "ADB Command: DISCONNECT");
                    controller.disconnect();
                    break;

                case ACTION_QUERY_PEERS:
                    Log.i(TAG, "ADB Command: QUERY_PEERS");
                    List<NanServiceInfo> peers = controller.getDiscoveredPeers();
                    Log.i(TAG, "Found " + peers.size() + " peers.");
                    for (NanServiceInfo peer : peers) {
                        Log.i(TAG, " - Peer: " + peer.getServiceName() + " (Handle: " +
                                (peer.getPeerHandle() != null ? peer.getPeerHandle().toString() : "null") + ")");
                    }
                    break;

                case ACTION_SEND_MESSAGE:
                    String message = intent.getStringExtra("message");
                    Log.i(TAG, "ADB Command: SEND_MESSAGE. Message=" + message);
                    if (message != null && !message.isEmpty()) {
                        controller.sendUdpMessage(message);
                    } else {
                        Log.e(TAG, "Missing message extra for SEND_MESSAGE");
                    }
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AdbAutomationService created");
        controller = WifiAwareController.getInstance(getApplicationContext());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_DISCOVERY);
        filter.addAction(ACTION_CONNECT_PEER);
        filter.addAction(ACTION_DISCONNECT);
        filter.addAction(ACTION_QUERY_PEERS);
        filter.addAction(ACTION_SEND_MESSAGE);

        ContextCompat.registerReceiver(this, adbReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AdbAutomationService destroyed");
        try {
            unregisterReceiver(adbReceiver);
        } catch (IllegalArgumentException e) {
            // Ignored
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AdbAutomationService started");
        // Keep service running to listen for broadcasts
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
