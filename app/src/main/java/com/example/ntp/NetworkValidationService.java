package com.example.ntp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.net.Inet4Address;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Foreground service started at boot (and whenever the kiosk launches the app) that produces the
 * network traffic Google Testrun grades, with no manual steps:
 * <ul>
 *   <li>NTPv4 (first byte 0x23) to the configured server whenever a network comes up, on every
 *       IPv4 address change, then every 64 s (every 1024 s after 30 minutes without changes).
 *       Each request leaves on the network that has a route to the server (see
 *       {@link NetworkSelector}), not blindly on Android's default network.</li>
 *   <li>A TLS 1.2+ HTTPS connection once per network (and hourly), only on networks Android has
 *       validated for internet access, so an isolated lab never sees half-open connections to
 *       public addresses (Testrun fails those as non-TLS client traffic).</li>
 * </ul>
 * Tag: NTPValidation
 */
public class NetworkValidationService extends Service {
    private static final String TAG = "NTPValidation";
    private static final String CHANNEL_ID = "network_validation";
    private static final int NOTIFICATION_ID = 1001;
    private static final String EXTRA_TRIGGER = "trigger";

    private static final int NTP_TIMEOUT_MS = 3000;
    private static final int TLS_TIMEOUT_MS = 10000;
    private static final long POLL_FAST_MS = 64_000L;      // RFC 5905 minpoll, 2^6 s
    private static final long POLL_SLOW_MS = 1_024_000L;   // RFC 5905 default maxpoll, 2^10 s
    private static final long FAST_POLL_WINDOW_MS = 30 * 60_000L;
    private static final long TLS_REPEAT_MS = 60 * 60_000L;
    private static final long MIN_NTP_SPACING_MS = 5_000L;
    private static final long NOT_YET = Long.MIN_VALUE / 2;

    private HandlerThread workerThread;
    private Handler worker;
    private ConnectivityManager connectivity;
    private PowerManager.WakeLock wakeLock;

    // Touched only on the worker thread.
    private final Map<Network, String> ipv4ByNetwork = new HashMap<>();
    private final Map<Network, Long> lastTlsByNetwork = new HashMap<>();
    private long lastNetworkChangeMs;
    private boolean primaryAnsweredSinceChange;
    private long lastNtpAttemptMs = NOT_YET;

    private volatile String ntpLine = "NTP: waiting for network";
    private volatile String tlsLine = "TLS: waiting for validated internet";

    private final Runnable pollRunnable = () -> runNtpCycle("scheduled poll");

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            ipv4ByNetwork.put(network, null);
            // onCapabilitiesChanged/onLinkPropertiesChanged for this network are already queued;
            // posting runs the cycle after them, with complete link information.
            worker.post(() -> {
                Log.i(TAG, "Network available: " + NetworkSelector.describe(connectivity, network));
                networkChanged();
                runNtpCycle("network available");
            });
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (NetworkSelector.isValidated(caps) && !lastTlsByNetwork.containsKey(network)) {
                // Mark now so repeated capability updates queue only one probe; queue it behind
                // any pending NTP cycle so NTP always goes first.
                lastTlsByNetwork.put(network, SystemClock.elapsedRealtime());
                worker.post(() -> runTlsProbe(network, "internet validated"));
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            List<String> addresses = new ArrayList<>();
            for (LinkAddress address : lp.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) addresses.add(address.toString());
            }
            Collections.sort(addresses);
            String current = addresses.toString();
            String previous = ipv4ByNetwork.put(network, current);
            if (previous != null && !previous.equals(current)) {
                Log.i(TAG, "IPv4 address change on " + lp.getInterfaceName() + ": " + previous + " -> " + current);
                networkChanged();
                runNtpCycle("IP address change");
            }
        }

        @Override
        public void onLost(Network network) {
            ipv4ByNetwork.remove(network);
            lastTlsByNetwork.remove(network);
            Log.i(TAG, "Network lost: " + network);
            networkChanged();
            if (ipv4ByNetwork.isEmpty()) {
                worker.removeCallbacks(pollRunnable);
                ntpLine = "NTP: waiting for network";
                updateNotification();
            }
        }
    };

    /** Starts (or pokes) the service. Safe to call repeatedly. */
    static void start(Context context, String trigger) {
        Intent intent = new Intent(context, NetworkValidationService.class)
                .putExtra(EXTRA_TRIGGER, trigger);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start network validation service (" + trigger + ")", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        enterForeground();

        // Kiosk tablets are mains-powered; keep the CPU awake so polls are not frozen by suspend.
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NTPValidation:poll");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        workerThread = new HandlerThread("network-validation");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        connectivity = getSystemService(ConnectivityManager.class);
        NetworkRequest allInternetNetworks = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivity.registerNetworkCallback(allInternetNetworks, networkCallback, worker);
        Log.i(TAG, "Network validation service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String extra = intent != null ? intent.getStringExtra(EXTRA_TRIGGER) : null;
        String trigger = extra != null ? extra : "restarted by system";
        Log.i(TAG, "Network validation service start: " + trigger);
        ValidationStore.recordServiceStart(this, trigger);
        worker.post(() -> runNtpCycle(trigger));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Network validation service destroyed");
        try {
            connectivity.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Not registered.
        }
        worker.removeCallbacksAndMessages(null);
        workerThread.quitSafely();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void networkChanged() {
        lastNetworkChangeMs = SystemClock.elapsedRealtime();
        primaryAnsweredSinceChange = false;
        lastNtpAttemptMs = NOT_YET;
        ValidationStore.recordNetworkState(this, NetworkSelector.describeAll(connectivity));
    }

    private void runNtpCycle(String trigger) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNtpAttemptMs < MIN_NTP_SPACING_MS) {
            Log.d(TAG, "NTP cycle (" + trigger + ") skipped; last attempt " + (now - lastNtpAttemptMs) + " ms ago");
            return;
        }
        worker.removeCallbacks(pollRunnable);
        if (ipv4ByNetwork.isEmpty()) {
            Log.i(TAG, "NTP cycle (" + trigger + "): no network yet, waiting for connectivity");
            return;
        }
        lastNtpAttemptMs = now;

        String primary = ValidationStore.ntpServer(this);
        String fallback = ValidationStore.ntpFallback(this);
        if (queryNtp(primary, trigger)) {
            primaryAnsweredSinceChange = true;
        } else if (!primaryAnsweredSinceChange && !fallback.isEmpty() && !fallback.equalsIgnoreCase(primary)) {
            // Only while the primary has not answered since the last network change, so a lab
            // whose server answers (Testrun) never sees traffic to a second, non-DHCP server.
            Log.i(TAG, "Primary NTP server has not answered; trying fallback " + fallback);
            queryNtp(fallback, trigger + ", fallback");
        }

        long sinceChange = SystemClock.elapsedRealtime() - lastNetworkChangeMs;
        worker.postDelayed(pollRunnable, sinceChange < FAST_POLL_WINDOW_MS ? POLL_FAST_MS : POLL_SLOW_MS);

        for (Map.Entry<Network, Long> entry : new ArrayList<>(lastTlsByNetwork.entrySet())) {
            if (SystemClock.elapsedRealtime() - entry.getValue() >= TLS_REPEAT_MS) {
                runTlsProbe(entry.getKey(), "hourly");
            }
        }
        updateNotification();
    }

    /** @return true when the server answered (even if it reports itself unsynchronized). */
    private boolean queryNtp(String server, String trigger) {
        Network network = NetworkSelector.forHost(connectivity, server);
        if (network == null) {
            Log.w(TAG, "No network has a route to NTP server " + server + "; request not sent");
            ValidationStore.recordNtp(this, false, "--- NTPv4 Test Results ---\nStatus: FAIL\nHost: " + server
                    + "\nRequest not sent: no network has a route to this server\n");
            ntpLine = "NTPv4 " + server + ": no route";
            return false;
        }
        String via = NetworkSelector.describe(connectivity, network);
        Log.i(TAG, "Boot NTP Test (" + trigger + "): " + server + ":" + ValidationStore.DEFAULT_NTP_PORT
                + " via " + via + " (NTPv4 request byte 0x23)");
        try {
            NtpV4Client.Result result = NtpV4Client.query(network, server, ValidationStore.DEFAULT_NTP_PORT,
                    NTP_TIMEOUT_MS, () -> ValidationStore.recordNtpRequestSent(this));
            Log.i(TAG, "Boot NTP " + (result.isPass() ? "PASS" : "FAIL") + ": v" + result.version
                    + " from " + result.resolvedAddress + ", server " + result.serverState());
            ValidationStore.recordNtp(this, result.isPass(), result.summary() + "Sent via: " + via + "\n");
            ntpLine = "NTPv4 " + server + ": " + (result.isPass() ? "answered" : "FAIL")
                    + (result.serverSynchronized ? "" : " (server unsynchronized)");
            return true;
        } catch (Exception e) {
            boolean sent = e instanceof SocketTimeoutException;
            Log.e(TAG, "Boot NTP FAIL (" + server + " via " + via + "): " + e);
            ValidationStore.recordNtp(this, false, "--- NTPv4 Test Results ---\n"
                    + "Status: FAIL\n"
                    + "Host: " + server + "\n"
                    + "Sent via: " + via + "\n"
                    + (sent ? "NTPv4 request sent; no response within " + NTP_TIMEOUT_MS + " ms\n"
                            : "Request not sent: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n"));
            ntpLine = "NTPv4 " + server + ": " + (sent ? "no response" : "not sent");
            return false;
        }
    }

    private void runTlsProbe(Network network, String trigger) {
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        if (!NetworkSelector.isValidated(caps)) {
            Log.i(TAG, "TLS probe (" + trigger + ") skipped: no validated internet access on " + network);
            return;
        }
        lastTlsByNetwork.put(network, SystemClock.elapsedRealtime());

        String url = ValidationStore.tlsUrl(this);
        String via = NetworkSelector.describe(connectivity, network);
        Log.i(TAG, "Boot TLS Test (" + trigger + "): " + url + " via " + via);
        try {
            Tls12Probe.Result result = Tls12Probe.probe(network, url, TLS_TIMEOUT_MS);
            Log.i(TAG, "Boot TLS " + (result.isPass() ? "PASS" : "FAIL") + ": " + result.protocol
                    + " " + result.cipherSuite);
            ValidationStore.recordTls(this, result.isPass(), result.summary() + "Sent via: " + via + "\n");
            tlsLine = "TLS: " + result.protocol + " " + (result.isPass() ? "OK" : "FAIL");
        } catch (Exception e) {
            Log.e(TAG, "Boot TLS FAIL: " + e);
            ValidationStore.recordTls(this, false, "--- TLS 1.2+ Test Results ---\n"
                    + "Status: FAIL\n"
                    + "URL: " + url + "\n"
                    + "Sent via: " + via + "\n"
                    + "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            tlsLine = "TLS: failed";
        }
        Log.i(TAG, "Boot validation complete");
        updateNotification();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Network validation",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Boot-time NTPv4 and TLS 1.2+ validation");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void enterForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            // Keep going: the first NTP exchange happens within seconds of network-up either way.
            Log.e(TAG, "Could not enter foreground state", e);
        }
    }

    private Notification buildNotification() {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NTPv4 / TLS 1.2 validation running")
                .setContentText(ntpLine)
                .setStyle(new Notification.BigTextStyle().bigText(ntpLine + "\n" + tlsLine))
                .setOngoing(true)
                .setContentIntent(openApp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }
}
