package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
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
 * The photos are kept on disk (Stills), taken from the aerial videos; in "apple" mode they are renewed once a week.
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
    private long lastTry;

    private Ambient(Context c) {
        app = c;
    }

    /**
     * The current still's backdrop, or null while there is none yet (it builds one in the background and calls
     * `onReady` on the main thread). The photos are saved on disk (Stills); in "apple" mode new ones are fetched once a
     * week, and at the start when there are none. A new still is picked every half hour.
     */
    Backdrop backdrop(boolean download, Runnable onReady) {
        listener = onReady;
        Backdrop b = backdrop;
        String place = Prefs.backgroundPlace();
        if (download && Stills.due(app, place)) fetchNew(false);
        boolean stale = b == null || !place.equals(builtPlace) || SystemClock.elapsedRealtime() - builtAt >= REFRESH_MS;
        if (stale && !building) {
            building = true;
            io.execute(this::build);
        }
        return b;
    }

    /** Asks for new photos now (the settings' "Update photos"); at most every ten minutes unless forced. */
    void fetchNew(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastTry != 0 && now - lastTry < 10 * 60_000L) return;
        lastTry = now;
        AerialStore.get(app).harvest(Prefs.backgroundPlace());
    }

    /** New photos arrived: show one of them. */
    void stillsChanged() {
        builtAt = 0;
        backdrop = null;
        Runnable r = listener;
        if (r != null) r.run();
    }

    /** How many photos are saved for the background place. */
    int count() {
        return Stills.list(app, Prefs.backgroundPlace()).size();
    }

    private void build() {
        try {
            String place = Prefs.backgroundPlace();
            List<File> stills = Stills.list(app, place);
            if (stills.isEmpty()) {
                // Videos that are already on the disk give photos without any download.
                for (AerialStore.Cached c : AerialStore.get(app).cached(place)) Stills.harvest(app, c.clip, c.file);
                stills = Stills.list(app, place);
            }
            if (stills.isEmpty()) return;
            File pick = stills.get((int) (Math.random() * stills.size()));
            if (stills.size() > 1 && pick.equals(last)) pick = stills.get((stills.indexOf(pick) + 1) % stills.size());
            Bitmap frame = Stills.load(pick);
            if (frame == null) return;
            last = pick;
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
}
