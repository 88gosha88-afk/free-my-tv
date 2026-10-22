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

/**
 * What the screensaver shows, in the mode picked in Home Settings (Prefs.screensaver):
 *  - "aerial": Apple's aerial videos, streamed one after another (muted) under the clock, which DreamView draws on top;
 *  - "photos": the recommendations' photos (DreamView alone);
 *  - "clock": the big clock over the aurora (DreamView alone).
 * If the video can't be played (no network, the server is unreachable), it falls back to the clock over the aurora:
 * the screen is never left black.
 */
final class ScreensaverStage extends FrameLayout {
    private static final long STALL_MS = 30_000, FADE_MS = 1200;
    private static final int MAX_ERRORS = 3;

    private final DreamView dream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextureView texture;
    private Surface surface;
    private MediaPlayer player;
    private Aerials.Playlist playlist;
    private boolean running, aerial, ready;
    private int errors;
    private final Runnable stalled = () -> {
        AppLog.w("aerial video stalled");
        fail();
    };

    ScreensaverStage(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        dream = new DreamView(c);
        String mode = Prefs.screensaver();
        aerial = mode.equals("aerial");
        if (aerial) {
            playlist = new Aerials.Playlist(c);
            if (playlist.isEmpty()) aerial = false;
        }
        dream.setMode(aerial ? DreamView.VIDEO : mode.equals("photos") ? DreamView.PHOTOS : DreamView.CLOCK);
        if (aerial) {
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
        if (aerial && surface != null && player == null) playNext();
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        dream.stop();
    }

    private void playNext() {
        if (!running || !aerial || surface == null) return;
        releasePlayer();
        Aerials.Clip clip = playlist.next();
        if (clip == null) {
            giveUp();
            return;
        }
        dream.setCaption(clip.place);
        ready = false;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                ready = true;
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    handler.removeCallbacks(stalled);
                    errors = 0;
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
                AppLog.w("aerial video error " + what + "/" + extra);
                if (p == player) fail();
                return true;
            });
            mp.setDataSource(clip.url);
            mp.prepareAsync();
            handler.removeCallbacks(stalled);
            handler.postDelayed(stalled, STALL_MS);
        } catch (Exception e) {
            AppLog.w("aerial video failed: " + e.getMessage());
            fail();
        }
    }

    /** One clip failed: try another after a pause; after a few in a row, stay with the clock over the aurora. */
    private void fail() {
        handler.removeCallbacks(stalled);
        releasePlayer();
        if (!running) return;
        if (++errors >= MAX_ERRORS) {
            giveUp();
            return;
        }
        handler.postDelayed(this::playNext, 2000);
    }

    private void giveUp() {
        aerial = false;
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
