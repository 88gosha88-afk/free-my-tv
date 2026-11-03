package io.freemytv.library.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny web server on 127.0.0.1 that hands a file of the network folder to the player over HTTP, with seeking (Range).
 * The player then needs no knowledge of the network folder's protocol, and any other player can open the same address.
 */
public final class MediaServer {
    private final Source source;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Long> sizes = new HashMap<>();
    private volatile boolean running;

    /** Where the server reports what it does (the app puts it in the system log). */
    public interface Log { void log(String line); }

    private volatile Log log;

    public MediaServer(Source source) { this.source = source; }

    public void setLog(Log log) { this.log = log; }

    private void say(String line) {
        Log l = log;
        if (l != null) l.log(line);
    }

    /** Starts listening and returns the port. */
    public synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "media-server");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        pool.shutdownNow();
    }

    /** The address a player opens for the file at `path` (the name at the end only helps players to pick a format). */
    public String url(String path) throws IOException {
        int port = start();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "http://127.0.0.1:" + port + "/m/" + enc(path) + "/" + enc(name);
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                say("connection from port " + s.getPort());
                pool.execute(new Runnable() {
                    @Override public void run() { handle(s); }
                });
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    /** One connection: players send several requests on it (they seek by asking for another range), so it stays open until they leave. */
    private void handle(Socket s) {
        long started = System.currentTimeMillis();
        int requests = 0;
        try {
            s.setSoTimeout(60_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (running) {
                String head = readHead(in);
                if (head == null) break;
                requests++;
                if (!serve(head, out)) break;
            }
        } catch (IOException | RuntimeException e) {
            say("connection ended: " + e);
        } finally {
            say("closed after " + requests + " request(s), " + (System.currentTimeMillis() - started) + " ms");
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Answers one request; false when the connection must be closed after it. */
    private boolean serve(String head, OutputStream out) throws IOException {
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return false;
        boolean headOnly = req[0].equals("HEAD");
        String target = req[1];
        long from = 0, to = -1;
        boolean ranged = false, keep = lines[0].endsWith("HTTP/1.1");
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            if (l.startsWith("connection:") && l.contains("close")) keep = false;
            if (l.startsWith("range:")) {
                String r = l.substring(6).trim();
                if (r.startsWith("bytes=")) {
                    String[] se = r.substring(6).split("-", -1);
                    try {
                        if (!se[0].isEmpty()) from = Long.parseLong(se[0]);
                        if (se.length > 1 && !se[1].isEmpty()) to = Long.parseLong(se[1]);
                        ranged = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (!target.startsWith("/m/")) { reply(out, 404, "Not Found", keep); return keep; }
        String rest = target.substring(3);
        int slash = rest.indexOf('/');
        String path = dec(slash < 0 ? rest : rest.substring(0, slash));
        if (path.contains("..")) { reply(out, 400, "Bad Request", keep); return keep; }
        long size;
        try {
            size = sizeOf(path);
        } catch (IOException e) {
            say("size of " + path + " failed: " + e);
            reply(out, 404, "Not Found", keep);
            return keep;
        }
        if (to < 0 || to >= size) to = size - 1;
        say(req[0] + " " + from + "-" + to + " of " + size + " " + path);
        if (from >= size) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: "
                    + (keep ? "keep-alive" : "close") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return keep;
        }
        long length = to - from + 1;
        StringBuilder h = new StringBuilder();
        h.append(ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(contentType(path)).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(length).append("\r\n");
        if (ranged) h.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
        h.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) {
            long sent = 0;
            try (InputStream file = source.open(path, from)) {
                byte[] buf = new byte[128 * 1024];
                long left = length;
                while (left > 0) {
                    int n = file.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    sent += n;
                    left -= n;
                }
            } catch (IOException e) {
                say("body stopped after " + sent + " of " + length + " bytes: " + e);   // usually the player left (a seek)
                return false;
            }
            if (sent < length) return false;   // the file ended early: the player must not wait for more
        }
        out.flush();
        return keep;
    }

    /** Remembers a file's size (known from the scan), so the first request does not wait for the router. */
    public void hintSize(String path, long size) {
        if (size <= 0) return;
        synchronized (sizes) { sizes.put(path, size); }
    }

    private long sizeOf(String path) throws IOException {
        synchronized (sizes) {
            Long v = sizes.get(path);
            if (v != null) return v;
        }
        long v = source.size(path);
        synchronized (sizes) { sizes.put(path, v); }
        return v;
    }

    private static void reply(OutputStream out, int code, String text, boolean keep) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\nConnection: " + (keep ? "keep-alive" : "close") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            b.append((char) c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) { state++; if (state == 4) return b.toString(); }
            else state = 0;
            if (b.length() > 16 * 1024) return null;
        }
        return null;
    }

    static String contentType(String path) {
        String e = Scanner.ext(path);
        switch (e) {
            case "mkv": return "video/x-matroska";
            case "mp4": case "m4v": return "video/mp4";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "wmv": return "video/x-ms-wmv";
            case "webm": return "video/webm";
            case "ts": case "m2ts": case "mts": return "video/mp2t";
            case "mpg": case "mpeg": return "video/mpeg";
            case "srt": return "application/x-subrip";
            case "vtt": return "text/vtt";
            default: return "application/octet-stream";
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }
}
