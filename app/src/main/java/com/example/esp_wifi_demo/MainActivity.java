package com.example.esp_wifi_demo;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.navigation.NavigationView;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.AppCompatActivity;

import com.example.esp_wifi_demo.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.appBarMain.toolbar);
        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;
        // Configure navigation with Wi-Fi Aware as the only top level destination
        mAppBarConfiguration = new AppBarConfiguration.Builder(R.id.nav_home)
                .setOpenableLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        // Start CLI automation service in the background for backward compatibility with ADB scripts
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    startService(new Intent(MainActivity.this, com.example.esp_wifi_demo.cli.AdbAutomationService.class));
                } catch (Exception e) {
                    android.util.Log.e("MainActivity", "Failed to start AdbAutomationService", e);
                }
            }, 1000);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_help) {
            showHelpDialog();
            return true;
        } else if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private void showHelpDialog() {
        String helpText = "Getting Started:\n\n" +
                "1. Ensure WiFi Aware is enabled on your Android device\n\n" +
                "2. Power on your ESP32 device with WiFi Aware support\n\n" +
                "3. Tap 'DISCOVER WEB SERVERS' to start scanning\n\n" +
                "4. Wait for nearby services to appear in the list\n\n" +
                "5. Tap a discovered service to connect\n\n" +
                "6. The ESP32's web interface will load in the app\n\n" +
                "Note: Both devices must support WiFi Aware (NAN) and be within range.";

        new AlertDialog.Builder(this)
                .setTitle("Help Guide")
                .setMessage(helpText)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAboutDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("") // Will set message with clickable link
                .setPositiveButton("OK", null)
                .create();

        dialog.show();

        // Create message with clickable GitHub link
        String aboutText = "ESP Wi-Fi Aware Demo\n" +
                "Version: v0.1\n\n" +
                "A demonstration app for WiFi Aware (NAN) connectivity " +
                "between Android and ESP32 devices.\n\n" +
                "Features:\n" +
                "• Service discovery over WiFi Aware\n" +
                "• Direct peer-to-peer communication\n" +
                "• WebView integration for ESP32 control\n\n" +
                "GitHub: https://github.com/nachiketkukade/esp-android-demo";

        SpannableString spannableString = new SpannableString(aboutText);

        // Find the GitHub URL position
        int startIndex = aboutText.indexOf("https://");
        int endIndex = aboutText.length();

        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/nachiketkukade/esp-android-demo"));
                startActivity(browserIntent);
            }
        };

        spannableString.setSpan(clickableSpan, startIndex, endIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setText(spannableString);
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
}
