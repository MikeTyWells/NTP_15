package com.example.ntp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Configuration and last-result storage shared by the boot service and the UI.
 *
 * Uses device-protected storage so the service can run (and record results) right after
 * LOCKED_BOOT_COMPLETED, before the tablet has been unlocked.
 * Tag: NTPValidation
 */
final class ValidationStore {
    private static final String TAG = "NTPValidation";
    private static final String PREFS_NAME = "network_validation";

    // Defaults replicate the passing Testrun v13 run: NTPv4 to Testrun's NTP server (10.10.10.5),
    // which is also the address Testrun's ntp.network.ntp_dhcp test treats as the DHCP-provided server.
    static final String DEFAULT_NTP_SERVER = "10.10.10.5";
    // Used only when the primary server has not answered on the current network.
    static final String DEFAULT_NTP_FALLBACK = "time.google.com";
    static final int DEFAULT_NTP_PORT = 123;
    static final String DEFAULT_TLS_URL = "https://www.google.com/generate_204";

    // Intent extras a kiosk app, MDM or adb can pass to MainActivity / AutoStartActivity.
    static final String EXTRA_NTP_SERVER = "ntp_server";
    static final String EXTRA_NTP_FALLBACK = "ntp_fallback";
    static final String EXTRA_TLS_URL = "tls_url";

    static final String CFG_NTP_SERVER = "cfg_ntp_server";
    static final String CFG_NTP_FALLBACK = "cfg_ntp_fallback";
    static final String CFG_TLS_URL = "cfg_tls_url";

    static final String SERVICE_STARTED = "service_started";
    static final String SERVICE_TRIGGER = "service_trigger";
    static final String NETWORK_STATE = "network_state";
    static final String NTP_REQUESTS_SENT = "ntp_requests_sent";
    static final String NTP_LAST = "ntp_last";
    static final String NTP_LAST_TIME = "ntp_last_time";
    static final String NTP_LAST_OK = "ntp_last_ok";
    static final String TLS_LAST = "tls_last";
    static final String TLS_LAST_TIME = "tls_last_time";
    static final String TLS_LAST_OK = "tls_last_ok";

    private ValidationStore() {}

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String ntpServer(Context context) {
        return prefs(context).getString(CFG_NTP_SERVER, DEFAULT_NTP_SERVER);
    }

    static String ntpFallback(Context context) {
        return prefs(context).getString(CFG_NTP_FALLBACK, DEFAULT_NTP_FALLBACK);
    }

    static String tlsUrl(Context context) {
        return prefs(context).getString(CFG_TLS_URL, DEFAULT_TLS_URL);
    }

    static void setNtpServer(Context context, String server) {
        prefs(context).edit().putString(CFG_NTP_SERVER, server.trim()).apply();
    }

    /** Saves any configuration extras on the launching intent. An empty fallback disables it. */
    static void applyConfigExtras(Context context, Intent intent) {
        if (intent == null) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        boolean changed = false;
        String server = intent.getStringExtra(EXTRA_NTP_SERVER);
        if (server != null && !server.trim().isEmpty()) {
            editor.putString(CFG_NTP_SERVER, server.trim());
            changed = true;
        }
        String fallback = intent.getStringExtra(EXTRA_NTP_FALLBACK);
        if (fallback != null) {
            editor.putString(CFG_NTP_FALLBACK, fallback.trim());
            changed = true;
        }
        String tlsUrl = intent.getStringExtra(EXTRA_TLS_URL);
        if (tlsUrl != null && tlsUrl.trim().startsWith("https://")) {
            editor.putString(CFG_TLS_URL, tlsUrl.trim());
            changed = true;
        }
        if (changed) {
            editor.apply();
            Log.i(TAG, "Configuration updated from launch intent");
        }
    }

    static void recordServiceStart(Context context, String trigger) {
        prefs(context).edit()
                .putString(SERVICE_STARTED, now())
                .putString(SERVICE_TRIGGER, trigger)
                .apply();
    }

    static void recordNetworkState(Context context, String state) {
        prefs(context).edit().putString(NETWORK_STATE, state + " (" + now() + ")").apply();
    }

    static void recordNtpRequestSent(Context context) {
        SharedPreferences p = prefs(context);
        p.edit().putInt(NTP_REQUESTS_SENT, p.getInt(NTP_REQUESTS_SENT, 0) + 1).apply();
    }

    static void recordNtp(Context context, boolean ok, String summary) {
        prefs(context).edit()
                .putBoolean(NTP_LAST_OK, ok)
                .putString(NTP_LAST, summary)
                .putString(NTP_LAST_TIME, now())
                .apply();
    }

    static void recordTls(Context context, boolean ok, String summary) {
        prefs(context).edit()
                .putBoolean(TLS_LAST_OK, ok)
                .putString(TLS_LAST, summary)
                .putString(TLS_LAST_TIME, now())
                .apply();
    }

    static String statusReport(Context context) {
        SharedPreferences p = prefs(context);
        StringBuilder sb = new StringBuilder();
        sb.append("Service started: ").append(p.getString(SERVICE_STARTED, "not yet"))
                .append("\nStarted by: ").append(p.getString(SERVICE_TRIGGER, "-"))
                .append("\nNetwork: ").append(p.getString(NETWORK_STATE, "unknown"))
                .append("\nNTP server: ").append(ntpServer(context));
        String fallback = ntpFallback(context);
        sb.append("\nNTP fallback: ").append(fallback.isEmpty() ? "(disabled)" : fallback)
                .append("\nNTPv4 requests sent: ").append(p.getInt(NTP_REQUESTS_SENT, 0))
                .append("\n\nLast NTP (").append(p.getString(NTP_LAST_TIME, "never")).append("):\n")
                .append(p.getString(NTP_LAST, "-"))
                .append("\n\nLast TLS (").append(p.getString(TLS_LAST_TIME, "never")).append("):\n")
                .append(p.getString(TLS_LAST, "-"));
        return sb.toString();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date());
    }
}
