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
    static final int KEEP = 2, MAX_FILES = 4;
    private static final long MIN_FREE = 400L << 20;

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
    private volatile String wantPlace = "";
    private volatile String harvestPlace;
    private volatile boolean saverWanted;
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
        return cached("");
    }

    /** The downloaded clips from `place` ("" = any). */
    List<Cached> cached(String place) {
        List<Cached> list = new ArrayList<>();
        for (Aerials.Clip clip : Aerials.all(app)) {
            if (!clip.isFrom(place)) continue;
            File f = fileOf(clip);
            if (f.isFile() && f.length() > 0) list.add(new Cached(clip, f));
        }
        return list;
    }

    /**
     * A random downloaded clip from `place` ("" = any), not the one that just played (if there is another); null if
     * there is none yet.
     */
    Cached pick(Cached avoid, String place) {
        List<Cached> list = cached(place);
        if (list.isEmpty()) return null;
        if (list.size() > 1 && avoid != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).file.equals(avoid.file)) list.remove(i);
            }
        }
        return list.get((int) (Math.random() * list.size()));
    }

    /**
     * Starts downloading clips from `place` ("" = any) until KEEP of them are on disk (does nothing if they already
     * are). The callback hears about every finished clip and about repeated failures; clear it (null) when the
     * screensaver stops.
     */
    synchronized void download(Callback cb, String place) {
        if (cb != null) callback = cb;
        wantPlace = place == null ? "" : place;
        if (cached(wantPlace).size() >= KEEP) return;
        saverWanted = true;
        startWorker();
    }

    /**
     * Gets new photos for the home screen's background (Stills) from a clip of `place` that has none yet: it is downloaded,
     * three or four frames are saved, and it is deleted again unless the screensaver wants it.
     */
    synchronized void harvest(String place) {
        harvestPlace = place == null ? "" : place;
        startWorker();
    }

    private synchronized void startWorker() {
        if (worker != null) return;
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

    /** Deletes every downloaded video (and half-done one); the background photos stay. */
    void clear() {
        cancelled = true;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Whether a download (of videos or of photos) is going on. */
    synchronized boolean busy() {
        return worker != null;
    }

    /** The disk the videos use, in bytes. */
    long size() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    private void run() {
        int failures = 0;
        java.util.Set<String> failed = new java.util.HashSet<>();
        while (!cancelled) {
            synchronized (this) {
                if (harvestPlace == null && !saverWanted) {
                    worker = null;
                    return;
                }
            }
            String hp = harvestPlace;
            if (hp != null) {
                harvestPlace = null;
                harvestOne(hp);
                continue;
            }
            String place = wantPlace;
            if (cached(place).size() >= KEEP) {
                saverWanted = false;
                continue;
            }
            Aerials.Clip next = null;
            for (Aerials.Clip clip : Aerials.shuffled(app)) {
                if (clip.isFrom(place) && !fileOf(clip).isFile() && !failed.contains(clip.file())) {
                    next = clip;
                    break;
                }
            }
            if (next == null) {            // every clip from there is on disk (or failed): nothing more to fetch
                saverWanted = false;
                continue;
            }
            if (dir.getUsableSpace() < MIN_FREE) makeRoom(place);
            if (dir.getUsableSpace() < MIN_FREE) {
                AppLog.w("aerials: not enough free space (" + (dir.getUsableSpace() >> 20) + " MB free)");
                if (cached(place).isEmpty()) post(false);   // something to play already: no need to give up
                saverWanted = false;
                continue;
            }
            if (fetch(next, fileOf(next))) {
                failures = 0;
                AppLog.w("aerials: downloaded " + next.file() + " (" + cached(place).size() + " of " + KEEP + " for '" + place + "')");
                Stills.harvest(app, next, fileOf(next));
                prune(place);
                post(true);
            } else {
                failed.add(next.file());
                if (!cancelled && ++failures >= 3) {
                    post(false);
                    saverWanted = false;
                }
            }
        }
        synchronized (this) {
            saverWanted = false;
            harvestPlace = null;
            worker = null;
        }
    }

    private void harvestOne(String place) {
        Aerials.Clip pick = null;
        boolean onDisk = false;
        for (Aerials.Clip clip : Aerials.shuffled(app)) {            // one without photos, preferring one already here
            if (!clip.isFrom(place) || Stills.has(app, clip)) continue;
            boolean here = fileOf(clip).isFile() && fileOf(clip).length() > 0;
            if (pick == null || (here && !onDisk)) {
                pick = clip;
                onDisk = here;
            }
        }
        if (pick == null) {                                          // all of them have photos already
            Prefs.setStillsAt(System.currentTimeMillis());
            return;
        }
        File file = fileOf(pick);
        if (!onDisk) {
            if (dir.getUsableSpace() < MIN_FREE) makeRoom(place);
            if (dir.getUsableSpace() < MIN_FREE) {
                AppLog.w("photos: not enough free space (" + (dir.getUsableSpace() >> 20) + " MB free)");
                return;
            }
            if (!fetch(pick, file)) return;
        }
        boolean ok = Stills.harvest(app, pick, file);
        boolean wanted = Prefs.screensaver().equals("aerial") && pick.isFrom(Prefs.saverPlace())
                && cached(Prefs.saverPlace()).size() <= KEEP;
        if (!onDisk && !wanted) file.delete();
        if (ok) main.post(() -> Ambient.get(app).stillsChanged());
    }

    /**
     * Frees disk for the next clip: half-done downloads of other places go first, then the oldest finished clips from
     * other places (the ones for the place that is wanted stay).
     */
    private void makeRoom(String place) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".part")) continue;
                boolean wanted = false;
                for (Aerials.Clip clip : Aerials.all(app)) {
                    if ((clip.file() + ".part").equals(f.getName()) && clip.isFrom(place)) wanted = true;
                }
                if (!wanted) f.delete();
            }
        }
        List<Cached> all = cached();
        java.util.Collections.sort(all, (x, y) -> Long.compare(x.file.lastModified(), y.file.lastModified()));
        for (Cached c : all) {
            if (dir.getUsableSpace() >= MIN_FREE) break;
            if (!c.clip.isFrom(place)) c.file.delete();
        }
    }

    /** Keeps the disk use bounded: over MAX_FILES clips, the oldest ones from other places go first. */
    private void prune(String place) {
        List<Cached> all = cached();
        int count = all.size();
        if (count <= MAX_FILES) return;
        java.util.Collections.sort(all, (x, y) -> Long.compare(x.file.lastModified(), y.file.lastModified()));
        for (Cached c : all) {
            if (count <= MAX_FILES) break;
            if (!c.clip.isFrom(place) && c.file.delete()) count--;
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
            if (e instanceof javax.net.ssl.SSLException || e instanceof GeneralSecurityException) {
                try { TlsProbe.log(new URL(clip.url).getHost()); } catch (IOException ignored) {}
            }
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
