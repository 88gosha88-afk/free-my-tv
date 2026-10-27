package io.freemytv;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Diagnosis only: when a TLS check fails, writes to the log what the server really sent (who issued its certificates,
 * and until when they are valid), once for each host. Nothing is sent to the server and nothing is trusted.
 */
final class TlsProbe {
    private TlsProbe() {}

    private static final Set<String> DONE = new HashSet<>();

    static void log(String host) {
        synchronized (DONE) {
            if (!DONE.add(host)) return;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(host, 443), 8_000);
                socket.setSoTimeout(8_000);
                socket.startHandshake();
                AppLog.w("tls " + host + ": now " + new java.util.Date() + ", " + socket.getInetAddress().getHostAddress());
                int i = 0;
                for (Certificate cert : socket.getSession().getPeerCertificates()) {
                    X509Certificate x = (X509Certificate) cert;
                    AppLog.w("tls " + host + " #" + i++ + ": " + x.getSubjectDN() + " <- " + x.getIssuerDN()
                            + " (" + x.getNotBefore() + " .. " + x.getNotAfter() + ")");
                }
            }
        } catch (Exception e) {
            AppLog.w("tls " + host + ": probe failed: " + e);
        }
    }
}
