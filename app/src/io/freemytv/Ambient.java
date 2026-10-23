package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's "Apple photos" background: a still from one of the aerial videos that AerialStore has on disk,
 * blurred and lightened like any other background (Backdrop). A new still every half hour; each change cross-fades.
 * Needs at least one downloaded clip: in "apple" mode it starts the downloads itself; in "auto" mode (no picks) it
 * only uses clips that are already there.
 */
final class Ambient {
    private static final long REFRESH_MS = 30 * 60_000L;

    private static Ambient instance;

    static synchronized Ambient get(Context c) {
        if (instance == null) instance = new Ambient(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Backdrop backdrop;
    private volatile long builtAt;
    private volatile boolean building;
    private volatile Runnable listener;
    private File last;
    private volatile String builtPlace = "";

    private Ambient(Context c) {
        app = c;
    }

    /**
     * The current still's backdrop, or null while there is none yet (it builds one in the background and calls
     * `onReady` on the main thread). With one on screen and half an hour gone, a fresh one builds meanwhile.
     */
    Backdrop backdrop(boolean download, Runnable onReady) {
        listener = onReady;
        Backdrop b = backdrop;
        String place = Prefs.backgroundPlace();
        boolean stale = b == null || !place.equals(builtPlace) || SystemClock.elapsedRealtime() - builtAt >= REFRESH_MS;
        if (stale && !building) {
            building = true;
            AerialStore store = AerialStore.get(app);
            if (download && store.cached(place).isEmpty()) store.download(null, place);
            io.execute(this::build);
        }
        return b;
    }

    private void build() {
        try {
            String place = Prefs.backgroundPlace();
            List<AerialStore.Cached> clips = AerialStore.get(app).cached(place);
            if (clips.isEmpty()) return;
            AerialStore.Cached pick = clips.get((int) (Math.random() * clips.size()));
            if (clips.size() > 1 && pick.file.equals(last)) pick = clips.get((clips.indexOf(pick) + 1) % clips.size());
            Bitmap frame = frameOf(pick.file);
            if (frame == null) return;
            last = pick.file;
            // Sharper and more contrasty than the picks' photos (which go almost white): the scene should show.
            backdrop = Backdrop.makeAmbient(frame);
            builtPlace = place;
            builtAt = SystemClock.elapsedRealtime();
            main.post(() -> {
                Runnable r = listener;
                if (r != null) r.run();
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("ambient background: " + e.getMessage());
        } finally {
            building = false;
        }
    }

    /** A frame from somewhere in the middle of the clip. */
    private static Bitmap frameOf(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getPath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = d == null ? 0 : Long.parseLong(d);
            long at = ms <= 0 ? 0 : (long) (ms * 1000L * (0.1 + 0.7 * Math.random()));
            return r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException e) {
            AppLog.w("ambient frame: " + e.getMessage());
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
