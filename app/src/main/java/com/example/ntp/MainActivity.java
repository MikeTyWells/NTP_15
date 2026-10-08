package com.example.ntp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "NTPValidation";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText serverField;
    private EditText portField;
    private TextView output;
    private TextView autoStatusOutput;
    private Button ntpButton;
    private Button tlsButton;
    private Button copyButton;
    private SharedPreferences statusPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (prefs, key) -> refreshAutoStatus();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "Application started on API " + Build.VERSION.SDK_INT);
        ValidationStore.applyConfigExtras(this, getIntent());
        NetworkValidationService.start(this, "app launch");
        statusPrefs = ValidationStore.prefs(this);
        configureAndroid15Insets();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusPrefs.registerOnSharedPreferenceChangeListener(statusListener);
        refreshAutoStatus();
    }

    @Override
    protected void onPause() {
        statusPrefs.unregisterOnSharedPreferenceChangeListener(statusListener);
        super.onPause();
    }

    private void configureAndroid15Insets() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 35) {
            window.getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Google Test Run - Android 15 Network Validator");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("NTPv4 over UDP + TLS 1.2/1.3 diagnostics");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        // Automatic (boot-time) validation section
        TextView autoTitle = new TextView(this);
        autoTitle.setText("AUTOMATIC VALIDATION (starts at boot)");
        autoTitle.setTextSize(16);
        autoTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        autoTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(autoTitle);

        TextView autoNote = new TextView(this);
        autoNote.setText("NTPv4 is sent when the network comes up and every 64 s. "
                + "TLS runs once Android confirms internet access.");
        autoNote.setTextSize(12);
        autoNote.setPadding(0, 0, 0, dp(4));
        root.addView(autoNote);

        autoStatusOutput = new TextView(this);
        autoStatusOutput.setTextSize(13);
        autoStatusOutput.setPadding(dp(8), dp(4), dp(8), dp(8));
        autoStatusOutput.setBackgroundColor(Color.LTGRAY);
        autoStatusOutput.setTypeface(Typeface.MONOSPACE);
        autoStatusOutput.setTextIsSelectable(true);
        root.addView(autoStatusOutput, fullWidth());

        // Manual Test Section
        TextView manualTitle = new TextView(this);
        manualTitle.setText("MANUAL VALIDATION");
        manualTitle.setTextSize(16);
        manualTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        manualTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(manualTitle);

        serverField = new EditText(this);
        serverField.setHint("NTP server");
        serverField.setSingleLine(true);
        serverField.setText(ValidationStore.ntpServer(this));
        root.addView(serverField, fullWidth());

        portField = new EditText(this);
        portField.setHint("UDP port");
        portField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portField.setSingleLine(true);
        portField.setText(String.valueOf(ValidationStore.DEFAULT_NTP_PORT));
        root.addView(portField, fullWidth());

        ntpButton = new Button(this);
        ntpButton.setText("Run NTPv4 Test");
        ntpButton.setOnClickListener(v -> runNtpTest());
        root.addView(ntpButton, fullWidth());

        tlsButton = new Button(this);
        tlsButton.setText("Run TLS 1.2+ Test");
        tlsButton.setOnClickListener(v -> runTlsTest());
        root.addView(tlsButton, fullWidth());

        Button saveServerButton = new Button(this);
        saveServerButton.setText("Use This Server for Automatic Validation");
        saveServerButton.setOnClickListener(v -> saveAutomaticServer());
        root.addView(saveServerButton, fullWidth());

        copyButton = new Button(this);
        copyButton.setText("Copy Results to Clipboard");
        copyButton.setOnClickListener(v -> copyResults());
        root.addView(copyButton, fullWidth());

        output = new TextView(this);
        output.setText("Ready.");
        output.setTextIsSelectable(true);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(14);
        output.setGravity(Gravity.START);
        output.setPadding(0, dp(12), 0, 0);
        root.addView(output, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void refreshAutoStatus() {
        if (autoStatusOutput != null) {
            autoStatusOutput.setText(ValidationStore.statusReport(this));
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void runNtpTest() {
        String host = serverField.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            output.setText("FAIL - Port must be 1-65535");
            return;
        }
        if (host.isEmpty()) {
            output.setText("FAIL - Enter an NTP hostname or IP address");
            return;
        }
        setBusy(true, "Running NTPv4 query...");
        final int finalPort = port;
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        executor.execute(() -> {
            try {
                Network network = NetworkSelector.forHost(cm, host);
                NtpV4Client.Result result = NtpV4Client.query(network, host, finalPort, 5000, null);
                String via = "Sent via: " + NetworkSelector.describe(cm, network) + "\n";
                runOnUiThread(() -> setBusy(false, result.summary() + via));
            } catch (Exception e) {
                Log.e(TAG, "NTP Test Failed", e);
                runOnUiThread(() -> setBusy(false, "FAIL - NTPv4\n" + e.getClass().getSimpleName() + ": " + safeMessage(e)));
            }
        });
    }

    private void runTlsTest() {
        setBusy(true, "Running TLS 1.2+ HTTPS probe...");
        final String url = ValidationStore.tlsUrl(this);
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        executor.execute(() -> {
            try {
                Network network = NetworkSelector.forUrl(cm, url);
                Tls12Probe.Result result = Tls12Probe.probe(network, url, 10000);
                String via = "Sent via: " + NetworkSelector.describe(cm, network) + "\n";
                runOnUiThread(() -> setBusy(false, result.summary() + via));
            } catch (Exception e) {
                Log.e(TAG, "TLS Test Failed", e);
                runOnUiThread(() -> setBusy(false, "FAIL - TLS 1.2+\n" + e.getClass().getSimpleName() + ": " + safeMessage(e)));
            }
        });
    }

    private void saveAutomaticServer() {
        String host = serverField.getText().toString().trim();
        if (host.isEmpty()) {
            output.setText("FAIL - Enter an NTP hostname or IP address");
            return;
        }
        ValidationStore.setNtpServer(this, host);
        NetworkValidationService.start(this, "automatic server changed");
        Toast.makeText(this, "Automatic NTP server set to " + host, Toast.LENGTH_SHORT).show();
    }

    private void copyResults() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "AUTOMATIC VALIDATION\n" + ValidationStore.statusReport(this)
                + "\n\nMANUAL VALIDATION\n" + output.getText();
        clipboard.setPrimaryClip(ClipData.newPlainText("Validation Results", text));
        Toast.makeText(this, "Results copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void setBusy(boolean busy, String message) {
        ntpButton.setEnabled(!busy);
        tlsButton.setEnabled(!busy);
        copyButton.setEnabled(!busy);
        output.setText(message);
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? "No error detail" : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Application destroying, shutting down executor.");
        executor.shutdownNow();
        super.onDestroy();
    }
}
