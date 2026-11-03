package io.freemytv.library.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

/**
 * For providers whose DNS answers 127.0.0.1 for some sites: asks the address over HTTPS (Cloudflare, then Google),
 * then connects to that address while still speaking to the real name (SNI, certificate and Host header).
 */
public final class Doh {
    private Doh() {}

    private static final String[] RESOLVERS = {
        "https://1.1.1.1/dns-query?type=A&name=", "https://8.8.8.8/resolve?type=A&name="
    };
    private static final Map<String, String> CACHE = new HashMap<>();

    /** The answer of a GET: the status and the body. */
    public static final class Reply {
        public int code;
        public byte[] data = new byte[0];
        public String body = "";
    }

    /** GET over HTTPS to the address found by DoH, sending the real name as SNI and Host (Android's own client sends none for an address). */
    public static Reply get(String url, String bearer) throws IOException {
        URL u = new URL(url);
        String name = u.getHost();
        String ip = resolve(name);
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(ip, 443), 8000);
            plain.setSoTimeout(12000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, name, 443, true);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, s.getSession())) throw new IOException("certificate is not for " + name);
            StringBuilder req = new StringBuilder("GET ").append(u.getFile()).append(" HTTP/1.1\r\nHost: ").append(name)
                .append("\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
            if (bearer != null) req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) raw.write(buf, 0, n);
            byte[] all = raw.toByteArray();
            int split = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') { split = i; break; }
            }
            if (split < 0) throw new IOException("bad answer");
            String head = new String(all, 0, split, StandardCharsets.ISO_8859_1);
            Reply r = new Reply();
            String[] status = head.split("\r\n")[0].split(" ");
            r.code = status.length > 1 ? Integer.parseInt(status[1]) : 0;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int pos = split + 4;
            if (head.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) {
                while (pos < all.length) {
                    int eol = pos;
                    while (eol + 1 < all.length && !(all[eol] == '\r' && all[eol + 1] == '\n')) eol++;
                    String size = new String(all, pos, eol - pos, StandardCharsets.ISO_8859_1).trim();
                    int semi = size.indexOf(';');
                    if (semi >= 0) size = size.substring(0, semi);
                    int len = Integer.parseInt(size, 16);
                    pos = eol + 2;
                    if (len == 0) break;
                    body.write(all, pos, Math.min(len, all.length - pos));
                    pos += len + 2;
                }
            } else {
                body.write(all, pos, all.length - pos);
            }
            r.data = body.toByteArray();
            r.body = new String(r.data, StandardCharsets.UTF_8);
            return r;
        } catch (NumberFormatException e) {
            throw new IOException("bad answer: " + e.getMessage());
        } finally {
            try { plain.close(); } catch (IOException ignored) { }
        }
    }

    private static String resolve(String name) throws IOException {
        synchronized (CACHE) {
            String have = CACHE.get(name);
            if (have != null) return have;
        }
        IOException last = null;
        for (String r : RESOLVERS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(r + name).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/dns-json");
                if (c.getResponseCode() != 200) throw new IOException("DoH HTTP " + c.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Object json = MiniJson.parse(out.toString(StandardCharsets.UTF_8.name()));
                List<Object> answers = MiniJson.list(MiniJson.map(json).get("Answer"));
                for (Object a : answers) {
                    String data = MiniJson.str(a, "data");
                    if (MiniJson.num(a, "type") == 1 && data.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && !data.startsWith("127.") && !data.startsWith("0.")) {
                        synchronized (CACHE) { CACHE.put(name, data); }
                        return data;
                    }
                }
                throw new IOException("DoH: no address for " + name);
            } catch (IOException e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("DoH failed");
    }
}
