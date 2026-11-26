package com.example.esp_wifi_demo.ui.wifiaware;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.esp_wifi_demo.WifiAwareUdpClient;
import com.example.esp_wifi_demo.core.WifiAwareController;
import com.example.esp_wifi_demo.databinding.FragmentWifiAwareBinding;
import com.example.esp_wifi_demo.library.nan.NanUtilities;
import com.example.esp_wifi_demo.library.nan.NanServiceInfo;

import java.net.Inet6Address;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiAwareFragment extends Fragment implements WifiAwareController.WifiAwareStateListener {

    private static final String TAG = "WifiAwareFragment";

    private FragmentWifiAwareBinding binding;
    private NanUtilities nanUtilities;
    private ServiceAdapter peersAdapter;
    private WifiAwareController controller;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        controller = WifiAwareController.getInstance(requireContext());

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (allGranted) {
                        showCapabilitiesDialog();
                    } else {
                        Toast.makeText(requireContext(),
                                "Permissions required for WiFi Aware",
                                Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentWifiAwareBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        nanUtilities = new NanUtilities();

        peersAdapter = new ServiceAdapter(requireContext(), controller.getDiscoveredPeers());
        binding.peersList.setAdapter(peersAdapter);

        binding.btnDeviceCapabilities.setOnClickListener(v -> checkPermissionsAndShowCapabilities());

        binding.secureCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.passphraseInput.setEnabled(isChecked);
            binding.passphraseInput.setAlpha(isChecked ? 1.0f : 0.4f);
            if (!isChecked) {
                binding.passphraseInput.setText("");
            }
        });

        binding.btnUdpServer.setOnClickListener(v -> {
            String serviceType = binding.serviceTypeInput.getText().toString().trim();
            String protocol = (String) binding.protocolSpinner.getSelectedItem();
            if (serviceType.isEmpty()) {
                Toast.makeText(requireContext(), "Service type is required", Toast.LENGTH_SHORT).show();
                return;
            }
            if (binding.secureCheckbox.isChecked()) {
                String pass = binding.passphraseInput.getText().toString().trim();
                if (pass.length() >= 8) {
                    startDiscoveryUI(pass, serviceType, protocol);
                } else {
                    Toast.makeText(requireContext(), "Passphrase must be 8+ characters", Toast.LENGTH_SHORT).show();
                }
            } else {
                startDiscoveryUI(null, serviceType, protocol);
            }
        });

        binding.btnBack.setOnClickListener(v -> showMainContent());

        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{"UDP", "TCP"});
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.protocolSpinner.setAdapter(protocolAdapter);

        binding.peersList.setOnItemClickListener((parent, view, position, id) -> {
            NanServiceInfo service = (NanServiceInfo) peersAdapter.getItem(position);
            controller.connectToPeer(service);
        });

        binding.btnDisconnect.setOnClickListener(v -> showMainContent());

        binding.btnSendUdp.setOnClickListener(v -> {
            appendLog("Sending UDP: Message from Android");
            controller.sendUdpMessage("Message from Android");
        });

        binding.btnSendTcp.setOnClickListener(v -> {
            appendLog("Sending TCP: Message from Android");
            controller.sendTcpMessage("Message from Android");
        });

        binding.btnRunIperf.setOnClickListener(v -> {
            binding.btnRunIperf.setEnabled(false);
            appendLog("Starting iperf client");
            controller.runIperf(new WifiAwareUdpClient.UdpConnect.IperfListener() {
                @Override
                public void onLine(String line) {
                    runOnUi(() -> appendLog(line));
                }

                @Override
                public void onComplete(boolean success) {
                    runOnUi(() -> {
                        if (binding != null) {
                            binding.btnRunIperf.setEnabled(true);
                        }
                    });
                }
            });
        });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        controller.addListener(this);
        // Refresh UI state in case it changed while backgrounded
        peersAdapter.updateServices(controller.getDiscoveredPeers());
    }

    @Override
    public void onPause() {
        super.onPause();
        controller.removeListener(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (binding == null) {
                return;
            }
            action.run();
        });
    }

    private void appendLog(String log) {
        if (binding != null && binding.udpLogs != null) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String current = binding.udpLogs.getText().toString();
            binding.udpLogs.setText(current + "\n[" + time + "] " + log);
        }
    }

    private void checkPermissionsAndShowCapabilities() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        } else {
            showCapabilitiesDialog();
        }
    }

    private void showCapabilitiesDialog() {
        WifiAwareManager manager = (WifiAwareManager) requireContext().getSystemService(Context.WIFI_AWARE_SERVICE);
        String capabilitiesText;

        if (manager == null) {
            capabilitiesText = "⚠ WiFi Aware is not supported on this device";
        } else {
            boolean success = nanUtilities.refreshCapabilities(manager);
            if (success) {
                capabilitiesText = nanUtilities.displayCapabilities();
            } else {
                capabilitiesText = "⚠ Unable to retrieve WiFi Aware capabilities";
            }
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Device Capabilities")
                .setMessage(capabilitiesText)
                .setPositiveButton("OK", null)
                .show();
    }



    private void startDiscoveryUI(String passphrase, String serviceType, String protocol) {
        binding.mainContent.setVisibility(View.GONE);
        binding.discoveryPanel.setVisibility(View.VISIBLE);
        binding.espControlPanel.setVisibility(View.GONE);
        binding.discoveryProgress.setVisibility(View.VISIBLE);
        binding.discoveryStatus.setText("Attaching to WiFi Aware...");

        peersAdapter.updateServices(controller.getDiscoveredPeers());

        controller.startDiscovery(passphrase, serviceType, protocol);
    }

    private void showMainContent() {
        binding.mainContent.setVisibility(View.VISIBLE);
        binding.discoveryPanel.setVisibility(View.GONE);
        binding.espControlPanel.setVisibility(View.GONE);
        controller.disconnect();
    }

    // --- WifiAwareStateListener Callbacks ---

    @Override
    public void onDiscoveryStarted() {
        if (binding == null) return;
        binding.discoveryStatus.setText("Scanning for services...");
    }

    @Override
    public void onDiscoveryFailed(String reason) {
        if (binding == null) return;
        binding.discoveryProgress.setVisibility(View.GONE);
        binding.discoveryStatus.setText("Discovery failed: " + reason);
        Toast.makeText(requireContext(), reason, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPeerDiscovered(NanServiceInfo service) {
        if (binding == null) return;
        peersAdapter.updateServices(controller.getDiscoveredPeers());

        String name = service.getServiceName() != null ? service.getServiceName() : "Unknown Service";
        binding.discoveryTitle.setText("Found " + peersAdapter.getCount() + " Server(s)");
        binding.discoveryStatus.setText("Found: " + name);
    }

    @Override
    public void onDiscoveryComplete(List<NanServiceInfo> services) {
        if (binding == null) return;
        binding.discoveryProgress.setVisibility(View.GONE);
        if (services.isEmpty()) {
            binding.discoveryStatus.setText("No UDP servers found");
            binding.discoveryTitle.setText("Discovery Complete");
        } else {
            binding.discoveryStatus.setText("Tap a service to connect");
        }
    }

    @Override
    public void onConnecting(String instanceName) {
        if (binding == null) return;
        binding.discoveryProgress.setVisibility(View.VISIBLE);
        binding.discoveryStatus.setText("Connecting to " + instanceName + "...");
    }

    @Override
    public void onConnected(String instanceName, Inet6Address peerIpv6, int peerPort) {
        if (binding == null) return;
        binding.discoveryPanel.setVisibility(View.GONE);
        binding.espControlPanel.setVisibility(View.VISIBLE);
        binding.udpLogs.setText("Logs:\nConnected to " + instanceName + "\n");
        String protocol = controller.getCurrentProtocol();
        boolean tcp = protocol != null && protocol.equalsIgnoreCase("TCP");
        binding.btnRunIperf.setText(tcp ? "Run iperf (TCP)" : "Run iperf (UDP)");
        Toast.makeText(requireContext(), "Connected!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConnectionFailed(String error) {
        if (binding == null) return;
        binding.discoveryProgress.setVisibility(View.GONE);
        binding.discoveryStatus.setText("Error: " + error);
        Toast.makeText(requireContext(), "Connection error: " + error, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisconnected() {
        if (binding == null) return;
        appendLog("Disconnected");
    }

    @Override
    public void onUdpMessageReceived(String message) {
        appendLog("Received: " + message);
    }

    @Override
    public void onMessageSent(String status) {
        appendLog(status);
    }

    @Override
    public void onMessageSendFailed(String error) {
        appendLog("Send error: " + error);
    }

    // --- Adapter ---

    private static class ServiceAdapter extends BaseAdapter {
        private final Context context;
        private List<NanServiceInfo> services;

        ServiceAdapter(Context context, List<NanServiceInfo> services) {
            this.context = context;
            this.services = services;
        }

        public void updateServices(List<NanServiceInfo> newServices) {
            this.services = newServices;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return services.size(); }
        @Override
        public Object getItem(int position) { return services.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(
                        android.R.layout.simple_list_item_2, parent, false);
            }
            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            NanServiceInfo s = services.get(position);
            String name = s.getServiceName() != null ? s.getServiceName() : "Unknown";
            text1.setText(name);
            text2.setText("Tap to connect");
            return convertView;
        }
    }
}
