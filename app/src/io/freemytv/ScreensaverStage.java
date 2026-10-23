package io.freemytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.FileInputStream;

/**
 * What the screensaver shows, in the mode picked in Home Settings (Prefs.screensaver):
 *  - "aerial": Apple's aerial videos (muted) under the clock, which DreamView draws on top. The TV's player can't
 *    stream these big files, so AerialStore downloads them first and they play from disk; until the first one is
 *    there, and whenever there is none, it shows the clock over the aurora;
 *  - "photos": the recommendations' photos (DreamView alone);
 *  - "clock": the big clock over the aurora (DreamView alone).
 * If the video can't be played, or can't be downloaded, it stays with the clock over the aurora: the screen is never
 * left black.
 */
final class ScreensaverStage extends FrameLayout {
    private static final long STALL_MS = 20_000, FADE_MS = 1200;
    private static final int MAX_ERRORS = 3;

    private final DreamView dream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AerialStore store;
    private TextureView texture;
    private Surface surface;
    private MediaPlayer player;
    private AerialStore.Cached playing;
    private boolean running, aerial;
    private int errors;
    private final Runnable stalled = () -> {
        AppLog.w("aerial video stalled");
        fail();
    };

    private final AerialStore.Callback downloads = new AerialStore.Callback() {
        @Override public void onReady() {
            if (running && aerial && player == null) playNext();
        }

        @Override public void onFailed() {
            if (running && aerial && player == null) giveUp();
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
        if (aerial) {
            store = AerialStore.get(c);
            texture = new TextureView(c);
            texture.setAlpha(0f);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    surface = new Surface(st);
                    if (running && player == null) playNext();
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    releasePlayer();
                    if (surface != null) surface.release();
                    surface = null;
                    return true;
                }

                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
            addView(texture, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        addView(dream, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void start() {
        if (running) return;
        running = true;
        errors = 0;
        dream.start();
        if (aerial) {
            store.download(downloads, Prefs.saverPlace());   // fills the cache in the background (does nothing if full)
            if (surface != null && player == null) playNext();
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (store != null) store.setCallback(null);   // a download in progress carries on, for next time
        releasePlayer();
        dream.stop();
    }

    /** Plays a downloaded clip from disk; with none yet, waits (the clock over the aurora) for a download to finish. */
    private void playNext() {
        if (!running || !aerial || surface == null) return;
        AerialStore.Cached next = store.pick(playing, Prefs.saverPlace());
        releasePlayer();
        if (next == null) {
            dream.setMode(DreamView.CLOCK);
            return;
        }
        playing = next;
        dream.setCaption(next.clip.place);
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p == player) p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    handler.removeCallbacks(stalled);
                    errors = 0;
                    dream.setMode(DreamView.VIDEO);
                    texture.animate().alpha(1f).setDuration(FADE_MS).start();
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                texture.animate().cancel();
                texture.setAlpha(0f);
                playNext();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                AppLog.w("aerial video error " + what + "/" + extra + " in " + next.file.getName());
                if (p == player) fail();
                return true;
            });
            try (FileInputStream in = new FileInputStream(next.file)) {
                mp.setDataSource(in.getFD());
            }
            mp.prepareAsync();
            handler.removeCallbacks(stalled);
            handler.postDelayed(stalled, STALL_MS);
        } catch (Exception e) {
            AppLog.w("aerial video failed: " + e.getMessage());
            fail();
        }
    }

    /** One clip failed to play: try another after a pause; after a few in a row, stay with the clock over the aurora. */
    private void fail() {
        handler.removeCallbacks(stalled);
        releasePlayer();
        if (texture != null) {
            texture.animate().cancel();
            texture.setAlpha(0f);
        }
        dream.setMode(DreamView.CLOCK);
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
        if (store != null) {
            store.cancel();
            store.setCallback(null);
        }
        if (texture != null) texture.animate().alpha(0f).setDuration(FADE_MS).start();
        dream.setMode(DreamView.CLOCK);
    }

    private void releasePlayer() {
        handler.removeCallbacks(stalled);
        MediaPlayer mp = player;
        player = null;
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
