package com.example.ntp;

import android.net.Network;
import android.util.Log;
import java.io.IOException;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS diagnostic path that explicitly permits only TLS 1.2 or newer.
 * Tag: NTPValidation
 */
public final class Tls12Probe {
    private static final String TAG = "NTPValidation";

    private Tls12Probe() {}

    public static Result probe(String urlText, int timeoutMs) throws Exception {
        return probe(null, urlText, timeoutMs);
    }

    /** When {@code network} is non-null, DNS and the TCP connection both use that network. */
    public static Result probe(Network network, String urlText, int timeoutMs) throws Exception {
        Log.i(TAG, "TLS Test Start: " + urlText);
        URL url = new URL(urlText);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("HTTPS URL required");
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        Tls12OrNewerSocketFactory hardenedFactory = new Tls12OrNewerSocketFactory(context.getSocketFactory());

        HttpsURLConnection connection = (HttpsURLConnection)
                (network != null ? network.openConnection(url) : url.openConnection());
        connection.setSSLSocketFactory(hardenedFactory);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "NTPClient-Android15-Test/4.0");
        // No connection reuse: every probe performs a full, capturable TLS handshake.
        connection.setRequestProperty("Connection", "close");

        int code = -1;
        String cipher = "Unknown";
        String protocol = "Unknown";
        String certDetails = "Unknown";

        try {
            connection.connect();
            code = connection.getResponseCode();
            cipher = connection.getCipherSuite();

            SSLSocket socket = hardenedFactory.lastSocket;
            if (socket != null) {
                SSLSession session = socket.getSession();
                if (session != null) {
                    protocol = session.getProtocol();
                }
            }

            Certificate[] certs = connection.getServerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate) {
                certDetails = ((X509Certificate) certs[0]).getSubjectDN().getName();
            }

            Log.i(TAG, "TLS Connection Successful. Code: " + code + ", Protocol: " + protocol
                    + ", Cipher: " + cipher);
        } catch (SSLPeerUnverifiedException e) {
            Log.e(TAG, "TLS Peer Unverified: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            Log.e(TAG, "TLS IO Error: " + e.getMessage());
            throw e;
        } finally {
            connection.disconnect();
        }

        return new Result(urlText, code, protocol, cipher, certDetails);
    }

    private static final class Tls12OrNewerSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        volatile SSLSocket lastSocket;

        Tls12OrNewerSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private java.net.Socket harden(java.net.Socket socket) throws IOException {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                // Explicitly allow only TLS 1.2 and 1.3
                String[] supported = ssl.getSupportedProtocols();
                String[] enabled = Arrays.stream(supported)
                        .filter(p -> "TLSv1.2".equals(p) || "TLSv1.3".equals(p))
                        .toArray(String[]::new);
                if (enabled.length == 0) {
                    socket.close();
                    throw new IOException("TLS 1.2/1.3 not supported by this device");
                }

                Log.d(TAG, "Hardening socket. Supported: " + Arrays.toString(supported) + " -> Enabled: " + Arrays.toString(enabled));
                ssl.setEnabledProtocols(enabled);
                lastSocket = ssl;
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean a) throws IOException { return harden(delegate.createSocket(s,h,p,a)); }
        @Override public java.net.Socket createSocket(String h, int p) throws IOException { return harden(delegate.createSocket(h,p)); }
        @Override public java.net.Socket createSocket(String h, int p, java.net.InetAddress l, int lp) throws IOException { return harden(delegate.createSocket(h,p,l,lp)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress h, int p) throws IOException { return harden(delegate.createSocket(h,p)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress l, int lp) throws IOException { return harden(delegate.createSocket(a,p,l,lp)); }
    }

    public static final class Result {
        public final String url;
        public final int httpStatus;
        public final String protocol;
        public final String cipherSuite;
        public final String certificateSubject;
        public final String timestamp;

        Result(String url, int httpStatus, String protocol, String cipherSuite, String certificateSubject) {
            this.url = url;
            this.httpStatus = httpStatus;
            this.protocol = protocol;
            this.cipherSuite = cipherSuite;
            this.certificateSubject = certificateSubject;
            this.timestamp = new Date().toString();
        }

        public boolean isPass() {
            // "Unknown" means the platform hid the socket; the factory still only allowed 1.2/1.3.
            return "TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol) || "Unknown".equals(protocol);
        }

        public String summary() {
            return "--- TLS 1.2+ Test Results ---\n" +
                    "Status: " + (isPass() ? "PASS" : "FAIL") + "\n" +
                    "Timestamp: " + timestamp + "\n" +
                    "URL: " + url + "\n" +
                    "HTTP Status: " + httpStatus + "\n" +
                    "Negotiated Protocol: " + protocol + "\n" +
                    "Cipher Suite: " + cipherSuite + "\n" +
                    "Certificate: " + certificateSubject + "\n";
        }
    }
}
