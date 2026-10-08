package com.example.ntp;

import android.net.Network;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

/**
 * Minimal RFC 5905-compatible NTPv4 client for validation/diagnostics.
 * Tag: NTPValidation
 */
public final class NtpV4Client {
    private static final String TAG = "NTPValidation";
    private static final long NTP_UNIX_EPOCH_DELTA_SECONDS = 2208988800L;
    private static final int PACKET_SIZE = 48;

    private NtpV4Client() {}

    public static Result query(String host, int port, int timeoutMs) throws Exception {
        return query(null, host, port, timeoutMs, null);
    }

    /**
     * Sends one NTPv4 client request. When {@code network} is non-null the hostname is resolved
     * on, and the socket is bound to, that network so boot-time requests leave on the interface
     * that just came up. {@code onSent} runs once the request is on the wire.
     */
    public static Result query(Network network, String host, int port, int timeoutMs, Runnable onSent)
            throws Exception {
        Log.i(TAG, "NTP Test Start: " + host + ":" + port);
        // A literal IP (e.g. 10.10.10.5) needs no DNS, so it works on isolated test networks.
        InetAddress address = network != null ? network.getByName(host) : InetAddress.getByName(host);
        Log.i(TAG, "Resolved " + host + " to " + address.getHostAddress());

        byte[] request = new byte[PACKET_SIZE];

        // LI=0 (00b), VN=4 (100b), Mode=3 (011b) -> 00100011b -> 0x23
        request[0] = 0x23;
        Log.i(TAG, "Request NTP Version: 4 (First byte: 0x23)");

        long t1 = System.currentTimeMillis();
        writeTimestamp(request, 40, t1);

        DatagramPacket outgoing = new DatagramPacket(request, request.length, address, port);
        byte[] response = new byte[PACKET_SIZE];
        DatagramPacket incoming = new DatagramPacket(response, response.length);

        try (DatagramSocket socket = new DatagramSocket()) {
            if (network != null) {
                network.bindSocket(socket);
            }
            socket.setSoTimeout(timeoutMs);
            socket.send(outgoing);
            if (onSent != null) {
                onSent.run();
            }
            socket.receive(incoming);
        }

        long t4 = System.currentTimeMillis();
        Log.i(TAG, "NTP Response received. Length: " + incoming.getLength());

        if (incoming.getLength() < PACKET_SIZE) {
            throw new IllegalStateException("Short NTP response: " + incoming.getLength() + " bytes");
        }

        int first = response[0] & 0xFF;
        int leap = (first >>> 6) & 0x3;
        int version = (first >>> 3) & 0x7;
        int mode = first & 0x7;
        int stratum = response[1] & 0xFF;

        Log.i(TAG, "Response NTP Version: " + version);
        Log.i(TAG, "Response Stratum: " + stratum);

        if (mode != 4 && mode != 5) {
            throw new IllegalStateException("Unexpected NTP response mode: " + mode);
        }

        // Ensure we don't treat NTPv3 as success if NTPv4 was required for validation
        // (Though NTPv4 servers often respond to NTPv3, we are validating Version 4 here).
        if (version < 4) {
            Log.w(TAG, "Server responded with older NTP version: v" + version);
        }

        // RFC 5905: a reply must echo our transmit timestamp in its origin field.
        boolean originMatches = true;
        for (int i = 0; i < 8; i++) {
            if (response[24 + i] != request[40 + i]) {
                originMatches = false;
                break;
            }
        }
        if (!originMatches) {
            Log.w(TAG, "NTP origin timestamp does not match request transmit timestamp");
        }

        // Stratum 0 is either a Kiss-o'-Death (4-char ASCII code in the reference ID) or an
        // unsynchronized server. Testrun's own NTP server has no upstream and answers with
        // LI=3, stratum 0 - the request still counts, so this is reported rather than thrown.
        String kissCode = null;
        if (stratum == 0) {
            String refId = new String(response, 12, 4, StandardCharsets.US_ASCII);
            if (refId.matches("[A-Z]{4}")) {
                kissCode = refId;
                Log.w(TAG, "NTP Kiss-o'-Death received: " + kissCode);
            }
        }
        boolean serverSynchronized = leap != 3 && stratum >= 1 && stratum <= 15;
        if (!serverSynchronized && kissCode == null) {
            Log.w(TAG, "NTP server is unsynchronized (LI=" + leap + ", stratum " + stratum + ")");
        }

        long t2 = readTimestamp(response, 32); // server receive time
        long t3 = readTimestamp(response, 40); // server transmit time

        // Offset = [(T2 - T1) + (T3 - T4)] / 2
        double offsetMs = ((t2 - t1) + (t3 - t4)) / 2.0;
        // Delay = (T4 - T1) - (T3 - T2)
        double delayMs = (t4 - t1) - (t3 - t2);

        Log.i(TAG, String.format(Locale.US, "NTP Result - Offset: %.2f ms, Delay: %.2f ms", offsetMs, delayMs));

        return new Result(host, incoming.getAddress().getHostAddress(), port, leap, version, mode,
                stratum, t1, t2, t3, t4, offsetMs, delayMs, serverSynchronized, kissCode, originMatches);
    }

    private static void writeTimestamp(byte[] buffer, int offset, long unixMillis) {
        long seconds = (unixMillis / 1000L) + NTP_UNIX_EPOCH_DELTA_SECONDS;
        long millis = unixMillis % 1000L;
        long fraction = (millis * 0x100000000L) / 1000L;
        write32(buffer, offset, seconds);
        write32(buffer, offset + 4, fraction);
    }

    private static long readTimestamp(byte[] buffer, int offset) {
        long seconds = read32(buffer, offset);
        long fraction = read32(buffer, offset + 4);
        long unixSeconds = seconds - NTP_UNIX_EPOCH_DELTA_SECONDS;
        long millis = (fraction * 1000L) >>> 32;
        return unixSeconds * 1000L + millis;
    }

    private static long read32(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFFL) << 24)
                | ((buffer[offset + 1] & 0xFFL) << 16)
                | ((buffer[offset + 2] & 0xFFL) << 8)
                | (buffer[offset + 3] & 0xFFL);
    }

    private static void write32(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
    }

    public static final class Result {
        public final String requestedHost;
        public final String resolvedAddress;
        public final int port;
        public final int leap;
        public final int version;
        public final int mode;
        public final int stratum;
        public final long localSendTimeMs;
        public final long serverReceiveTimeMs;
        public final long serverTransmitTimeMs;
        public final long localReceiveTimeMs;
        public final double clockOffsetMs;
        public final double roundTripDelayMs;
        public final boolean serverSynchronized;
        public final String kissCode;
        public final boolean originMatches;
        public final String timestamp;

        Result(String requestedHost, String resolvedAddress, int port, int leap, int version, int mode,
               int stratum, long localSendTimeMs, long serverReceiveTimeMs, long serverTransmitTimeMs,
               long localReceiveTimeMs, double clockOffsetMs, double roundTripDelayMs,
               boolean serverSynchronized, String kissCode, boolean originMatches) {
            this.requestedHost = requestedHost;
            this.resolvedAddress = resolvedAddress;
            this.port = port;
            this.leap = leap;
            this.version = version;
            this.mode = mode;
            this.stratum = stratum;
            this.localSendTimeMs = localSendTimeMs;
            this.serverReceiveTimeMs = serverReceiveTimeMs;
            this.serverTransmitTimeMs = serverTransmitTimeMs;
            this.localReceiveTimeMs = localReceiveTimeMs;
            this.clockOffsetMs = clockOffsetMs;
            this.roundTripDelayMs = roundTripDelayMs;
            this.serverSynchronized = serverSynchronized;
            this.kissCode = kissCode;
            this.originMatches = originMatches;
            this.timestamp = new Date().toString();
        }

        /** The NTPv4 exchange itself succeeded (independent of the server's own sync state). */
        public boolean isPass() {
            return version == 4 && kissCode == null;
        }

        public String serverState() {
            if (kissCode != null) return "Kiss-o'-Death " + kissCode;
            if (serverSynchronized) return "synchronized";
            return "unsynchronized (LI=" + leap + ", stratum " + stratum + ")";
        }

        public String summary() {
            String status = isPass() ? "PASS" : "FAIL (v" + version + (kissCode != null ? ", " + kissCode : "") + ")";
            String offsetLine = serverSynchronized
                    ? String.format(Locale.US, "Clock Offset: %.2f ms\n", clockOffsetMs)
                    : "Clock Offset: n/a (server not synchronized)\n";
            return String.format(Locale.US,
                    "--- NTPv4 Test Results ---\n" +
                    "Status: %s\n" +
                    "Timestamp: %s\n" +
                    "Host: %s\n" +
                    "Resolved IP: %s\n" +
                    "UDP Port: %d\n" +
                    "Request Version: 4\n" +
                    "Response Version: %d\n" +
                    "Server State: %s\n" +
                    "Stratum: %d\n" +
                    "Leap Indicator: %d\n" +
                    "Round-Trip Delay: %.2f ms\n" +
                    "%s",
                    status, timestamp, requestedHost, resolvedAddress, port, version, serverState(),
                    stratum, leap, roundTripDelayMs, offsetLine);
        }
    }
}
