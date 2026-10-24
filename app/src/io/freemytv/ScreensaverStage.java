package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.FileInputStream;

/**
 * What the screensaver shows, in the mode picked in Home Settings (Prefs.screensaver):
 *  - "aerial": Apple's aerial videos (muted) under the clock, which DreamView draws on top. The TV's player can't
 *    stream these big files, so AerialStore downloads them first and they play from disk; until the first one is
 *    there, and whenever there is none, it shows the clock over the aurora;
 *  - "photos": the recommendations' photos (DreamView alone);
 *  - "clock": the big clock over the aurora (DreamView alone).
 * Every clip gets a view of its own (the TV's player doesn't take a second video on a surface it has just let go of):
 * when one ends, its last frame is kept as a picture, the next one prepares, and fades in over it: no blank screen.
 * If the video can't be played, or can't be downloaded, it stays with the clock over the aurora: the screen is never
 * left black.
 */
final class ScreensaverStage extends FrameLayout {
    private static final long STALL_MS = 20_000, FADE_MS = 1200;
    private static final int MAX_ERRORS = 3;

    /** One clip: its view, its surface and its player. */
    private static final class Slot {
        TextureView view;
        Surface surface;
        MediaPlayer player;
        AerialStore.Cached clip;
        boolean disposed;
    }

    private final DreamView dream;
    private final ImageView frozen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AerialStore store;
    private Slot current, pending;
    private AerialStore.Cached last;
    private boolean running, aerial;
    private int errors;
    private final Runnable stalled = () -> {
        AppLog.w("aerial video stalled");
        if (pending != null) failSlot(pending);
    };

    /** The time for the next clip has come, before this one ends (the setting "Change every"). */
    private final Runnable change = () -> {
        if (current != null && pending == null) ended(current);
    };

    private final AerialStore.Callback downloads = new AerialStore.Callback() {
        @Override public void onReady() {
            if (running && aerial && current == null && pending == null) playNext();
        }

        @Override public void onFailed() {
            if (running && aerial && current == null && pending == null) giveUp();
        }
    };

    ScreensaverStage(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        dream = new DreamView(c);
        String mode = Prefs.screensaver();
        aerial = mode.equals("aerial") && !Aerials.all(c).isEmpty();
        // Photos, or the clock over the aurora; with the aerials it is the clock until a video is playing.
        dream.setMode(mode.equals("photos") ? DreamView.PHOTOS : DreamView.CLOCK);
        frozen = new ImageView(c);
        frozen.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (aerial) store = AerialStore.get(c);
        addView(frozen, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(dream, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void start() {
        if (running) return;
        running = true;
        errors = 0;
        dream.start();
        if (aerial) {
            store.download(downloads, Prefs.saverPlace());   // fills the cache in the background (does nothing if full)
            playNext();
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (store != null) store.setCallback(null);   // a download in progress carries on, for next time
        dispose(pending);
        dispose(current);
        pending = null;
        current = null;
        frozen.setImageDrawable(null);
        dream.stop();
    }

    /** Starts the next downloaded clip in a new view; with none yet, waits (the clock over the aurora) for a download. */
    private void playNext() {
        if (!running || !aerial || pending != null) return;
        store.download(null, Prefs.saverPlace());   // keeps the next clip coming (does nothing if they are all here)
        AerialStore.Cached next = store.pick(last, Prefs.saverPlace());
        if (next == null) {
            if (current == null && frozen.getDrawable() == null) dream.setMode(DreamView.CLOCK);
            return;
        }
        final Slot s = new Slot();
        s.clip = next;
        s.view = new TextureView(getContext());
        s.view.setAlpha(0f);
        s.view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                s.surface = new Surface(st);
                if (s == pending && s.player == null) begin(s);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer(s);
                if (s.surface != null) s.surface.release();
                s.surface = null;
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        pending = s;
        addView(s.view, getChildCount() - 1, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        handler.removeCallbacks(stalled);
        handler.postDelayed(stalled, STALL_MS);
    }

    /** The view has its surface: play the clip from disk on it. */
    private void begin(final Slot s) {
        try {
            MediaPlayer mp = new MediaPlayer();
            s.player = mp;
            mp.setSurface(s.surface);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p == s.player) p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == s.player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && s == pending) show(s);
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p == s.player && s == current) ended(s);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                AppLog.w("aerial video error " + what + "/" + extra + " in " + s.clip.file.getName());
                if (p == s.player) failSlot(s);
                return true;
            });
            try (FileInputStream in = new FileInputStream(s.clip.file)) {
                mp.setDataSource(in.getFD());
            }
            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.w("aerial video failed: " + e.getMessage());
            failSlot(s);
        }
    }

    /** The new clip shows its first frame: it fades in over the last frame of the one before. */
    private void show(Slot s) {
        handler.removeCallbacks(stalled);
        errors = 0;
        pending = null;
        current = s;
        last = s.clip;
        dream.setCaption(s.clip.clip.place);
        dream.setMode(DreamView.VIDEO);
        handler.removeCallbacks(change);
        int minutes = Prefs.saverSwitch();
        if (minutes > 0) handler.postDelayed(change, minutes * 60_000L);
        s.view.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (s == current) frozen.setImageDrawable(null);
        }).start();
    }

    /** A clip ended: keep its last frame as a picture, let go of its player, and start the next one. */
    private void ended(Slot s) {
        handler.removeCallbacks(change);
        try {
            Bitmap frame = s.view.getBitmap(960, 540);
            if (frame != null) frozen.setImageBitmap(frame);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("aerial last frame: " + e.getMessage());
        }
        current = null;
        dispose(s);
        playNext();
    }

    /** One clip failed to play: try another after a pause; after a few in a row, stay with the clock over the aurora. */
    private void failSlot(Slot s) {
        handler.removeCallbacks(stalled);
        if (s == pending) pending = null;
        if (s == current) current = null;
        dispose(s);
        if (!running) return;
        if (++errors >= MAX_ERRORS) {
            giveUp();
            return;
        }
        handler.postDelayed(this::playNext, 2000);
    }

    private void giveUp() {
        AppLog.w("aerials: giving up for now, showing the clock");
        aerial = false;
        handler.removeCallbacksAndMessages(null);
        if (store != null) {
            store.cancel();
            store.setCallback(null);
        }
        dispose(pending);
        dispose(current);
        pending = null;
        current = null;
        frozen.setImageDrawable(null);
        dream.setMode(DreamView.CLOCK);
    }

    private void dispose(Slot s) {
        if (s == null || s.disposed) return;
        s.disposed = true;
        s.view.animate().cancel();
        releasePlayer(s);
        removeView(s.view);
    }

    private void releasePlayer(Slot s) {
        MediaPlayer mp = s.player;
        s.player = null;
        if (mp == null) return;
        try {
            mp.setOnPreparedListener(null);
            mp.setOnInfoListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.reset();
        } catch (RuntimeException ignored) {
        }
        mp.release();
    }
}
