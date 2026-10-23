package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The aerial videos on the TV's disk (the app's cache folder). The TV's player can't stream these big files over HTTP
 * (error -2147483648), but plays them from a file: so a clip is downloaded first, then played. It keeps a few clips
 * (KEEP, about 220 MB each) and downloads in the background, one at a time, resuming a half-done download where it
 * stopped. The cache folder is the system's to empty when the TV needs space; the clips then download again.
 */
final class AerialStore {
    static final int KEEP = 3;
    private static final long MIN_FREE = 700L << 20;

    interface Callback {
        /** A clip finished downloading (on the main thread). */
        void onReady();

        /** Downloads keep failing (on the main thread). */
        void onFailed();
    }

    static final class Cached {
        final Aerials.Clip clip;
        final File file;

        Cached(Aerials.Clip clip, File file) {
            this.clip = clip;
            this.file = file;
        }
    }

    private static AerialStore instance;

    static synchronized AerialStore get(Context c) {
        if (instance == null) instance = new AerialStore(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Callback callback;
    private volatile boolean cancelled;
    private Thread worker;
    private SSLSocketFactory appleSockets;

    private AerialStore(Context c) {
        app = c;
        dir = new File(c.getCacheDir(), "aerials");
        dir.mkdirs();
    }

    private File fileOf(Aerials.Clip clip) {
        return new File(dir, clip.file());
    }

    /** The clips that are fully downloaded. */
    List<Cached> cached() {
        List<Cached> list = new ArrayList<>();
        for (Aerials.Clip clip : Aerials.all(app)) {
            File f = fileOf(clip);
            if (f.isFile() && f.length() > 0) list.add(new Cached(clip, f));
        }
        return list;
    }

    /** A random downloaded clip, not the one that just played (if there is another); null if there is none yet. */
    Cached pick(Cached avoid) {
        List<Cached> list = cached();
        if (list.isEmpty()) return null;
        if (list.size() > 1 && avoid != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).file.equals(avoid.file)) list.remove(i);
            }
        }
        return list.get((int) (Math.random() * list.size()));
    }

    /**
     * Starts downloading until KEEP clips are on disk (does nothing if it already is). The callback hears about every
     * finished clip and about repeated failures; clear it (null) when the screensaver stops.
     */
    synchronized void download(Callback cb) {
        if (cb != null) callback = cb;
        if (worker != null && worker.isAlive()) return;
        if (cached().size() >= KEEP) return;
        cancelled = false;
        worker = new Thread(this::run, "aerials");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    void setCallback(Callback cb) {
        callback = cb;
    }

    /** Stops downloading (the half-done file stays, to resume later). */
    void cancel() {
        cancelled = true;
    }

    private void run() {
        int failures = 0;
        for (Aerials.Clip clip : Aerials.shuffled(app)) {
            if (cancelled) return;
            if (cached().size() >= KEEP) return;
            File target = fileOf(clip);
            if (target.isFile()) continue;
            if (dir.getUsableSpace() < MIN_FREE) {
                AppLog.w("aerials: not enough free space");
                post(false);
                return;
            }
            if (fetch(clip, target)) {
                failures = 0;
                post(true);
            } else if (!cancelled && ++failures >= 3) {
                post(false);
                return;
            }
        }
    }

    /**
     * Apple's servers use Apple's own certificate authority ("Apple Server Authentication CA"), which Android doesn't
     * know: the TV rejects them with "Trust anchor for certification path not found". For these downloads only, trust
     * exactly that authority (assets/apple_server_ca.pem), which is more careful than trusting everything.
     */
    private SSLSocketFactory appleSockets() throws IOException, GeneralSecurityException {
        if (appleSockets != null) return appleSockets;
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        try (InputStream in = app.getAssets().open("apple_server_ca.pem")) {
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                anchors.setCertificateEntry("apple" + i++, cert);
            }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(anchors);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        appleSockets = context.getSocketFactory();
        return appleSockets;
    }

    private void post(boolean ready) {
        main.post(() -> {
            Callback cb = callback;
            if (cb == null) return;
            if (ready) cb.onReady();
            else cb.onFailed();
        });
    }

    /** Downloads one clip to "name.part", resuming it if it was started, then renames it. */
    private boolean fetch(Aerials.Clip clip, File target) {
        File part = new File(dir, clip.file() + ".part");
        HttpURLConnection c = null;
        try {
            long have = part.isFile() ? part.length() : 0;
            c = (HttpURLConnection) new URL(clip.url).openConnection();
            if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(appleSockets());
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            boolean append = code == HttpURLConnection.HTTP_PARTIAL;
            if (code != HttpURLConnection.HTTP_OK && !append) {
                AppLog.w("aerials: HTTP " + code + " for " + clip.file());
                if (code == 416) part.delete();   // the half-done file doesn't fit the server's: start over next time
                return false;
            }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancelled) return false;
                    out.write(buffer, 0, n);
                }
            }
            long expected = c.getContentLengthLong();
            if (expected > 0 && part.length() < have * (append ? 1 : 0) + expected) {
                AppLog.w("aerials: short download of " + clip.file());
                return false;
            }
            return part.renameTo(target);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            AppLog.w("aerials: download failed (" + clip.file() + "): " + e.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
