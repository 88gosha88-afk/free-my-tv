package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;
import io.freemytv.library.core.Scanner;
import io.freemytv.library.core.Tmdb;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The player (libVLC: every video format, DTS/AC3 sound, embedded and separate subtitles). It reads the file from the
 * network folder itself (smb://), or through the app's own address when that is chosen in the settings; if one way
 * fails it tries the other. Remote: OK pauses, left and right seek (longer when held), up chooses the soundtrack,
 * down the subtitles, Menu the speed. Back leaves; the place is remembered.
 */
public class PlayerActivity extends Activity {
    private static final Pattern RUSSIAN = Pattern.compile("(?iu).*(russian|\\brus\\b|\\bru\\b|рус|дубл|озвуч|многоголос|lostfilm|лостфильм|невафильм|hdrezka).*");
    private static final Pattern SUB_FILE = Pattern.compile("(?i)srt|ass|ssa|sub|vtt|idx");

    private final Handler h = new Handler(Looper.getMainLooper());
    private Library lib;
    private LibVLC vlc;
    private MediaPlayer player;
    private Entry entry;
    private final List<Entry> playlist = new ArrayList<>();
    private SurfaceView surface;
    private View overlay;
    private TextView titleView, timeView, hintView, centerView;
    private ProgressBar bar;
    private long time, length, lastSaved, seekTarget = -1;
    private boolean paused, errored, released, direct, triedOther;
    private int tracksTried;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        String path = getIntent().getStringExtra("path");
        entry = path == null ? null : lib.catalog.byPath(path);
        if (entry == null) { finish(); return; }
        if (entry.episode) {
            for (Catalog.Show s : lib.catalog.shows()) {
                if (!s.key.equals(entry.key())) continue;
                for (List<Entry> l : s.seasons.values()) playlist.addAll(l);
            }
        }
        direct = Prefs.direct();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        centerView = Ui.bold(this, "", 22, Ui.TEXT);
        centerView.setGravity(Gravity.CENTER);
        centerView.setShadowLayer(8, 0, 2, 0xFF000000);
        root.addView(centerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 48), Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 28));
        panel.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0xCC000000}));
        titleView = Ui.bold(this, "", 22, Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(titleView);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        bl.topMargin = Ui.dp(this, 12);
        panel.addView(bar, bl);
        timeView = Ui.text(this, "", 16, Ui.TEXT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 8);
        panel.addView(timeView, tl);
        hintView = Ui.text(this, "OK — пауза    ◀ ▶ — перемотка    ▲ — озвучка    ▼ — субтитры    Меню — скорость    Назад — выйти", 13, Ui.DIM);
        panel.addView(hintView);
        overlay = panel;
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=4000");
            options.add("--file-caching=4000");
            vlc = new LibVLC(this, options);
            player = new MediaPlayer(vlc);
        } catch (RuntimeException | UnsatisfiedLinkError x) {
            player = null;
            fail("не запустился проигрыватель (" + x + ")");
            return;
        }
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int hgt) {
                if (player != null && !released) player.getVLCVout().setWindowSize(w, hgt);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { }
        });
        IVLCVout vout = player.getVLCVout();
        vout.setVideoView(surface);
        vout.attachViews();
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(final MediaPlayer.Event ev) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { onPlayerEvent(ev); }
                });
            }
        });
        play(entry, getIntent().getBooleanExtra("restart", false));
    }

    // MARK: - Starting a file

    private void play(Entry e, boolean restart) {
        entry = e;
        errored = false;
        tracksTried = 0;
        time = 0;
        length = 0;
        seekTarget = -1;
        titleView.setText(titleOf(e));
        centerView.setText("Открываю…");
        final Entry now = e;
        final boolean fromStart = restart;
        new Thread(new Runnable() {
            @Override public void run() { prepare(now, fromStart); }
        }, "player-prepare").start();
        showOverlay();
    }

    /** Makes the addresses off the main thread (the local server may have to start), then starts the player. */
    private void prepare(final Entry e, final boolean restart) {
        try {
            final String url = lib.mediaUrl(e.path, e.size, direct);
            final List<String> extras = new ArrayList<>();
            for (String extra : e.extras) extras.add(lib.mediaUrl(extra, 0, direct));
            runOnUiThread(new Runnable() {
                @Override public void run() { start(e, url, extras, restart); }
            });
        } catch (final IOException | RuntimeException x) {
            runOnUiThread(new Runnable() {
                @Override public void run() { fail(String.valueOf(x.getMessage() != null ? x.getMessage() : x)); }
            });
        }
    }

    private void start(Entry e, String url, List<String> extras, boolean restart) {
        if (released || player == null || e != entry) return;
        try {
            android.util.Log.i("FreeMyLibrary", "player: " + (direct ? "direct " : "via server ") + e.path);
            Media media = new Media(vlc, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=4000");
            Catalog.Progress p = lib.catalog.progress(e.path);
            if (!restart && p != null && p.started()) media.addOption(":start-time=" + (p.position / 1000.0));
            for (int i = 0; i < extras.size(); i++) {
                String name = e.extras.get(i);
                int type = SUB_FILE.matcher(Scanner.ext(name)).matches() ? IMedia.Slave.Type.Subtitle : IMedia.Slave.Type.Audio;
                media.addSlave(new IMedia.Slave(type, 4, extras.get(i)));
            }
            player.setMedia(media);
            media.release();
            player.play();
        } catch (RuntimeException x) {
            fail(String.valueOf(x.getMessage() != null ? x.getMessage() : x));
        }
    }

    private String titleOf(Entry e) {
        Tmdb.Meta m = lib.metaOf(e);
        String name = m != null && m.found() && !m.title.isEmpty() ? m.title : e.title;
        return e.episode ? name + "  ·  S" + e.season + "E" + e.number : name;
    }

    // MARK: - Player events

    private void onPlayerEvent(MediaPlayer.Event ev) {
        if (released) return;
        switch (ev.type) {
            case MediaPlayer.Event.Playing:
                paused = false;
                centerView.setText("");
                if (tracksTried == 0) {
                    tracksTried = 1;
                    h.postDelayed(autoTracks, 1500);
                    h.postDelayed(autoTracks, 5000);
                }
                break;
            case MediaPlayer.Event.Paused:
                paused = true;
                showOverlay();
                break;
            case MediaPlayer.Event.Buffering:
                if (!paused) {
                    float v = ev.getBuffering();
                    centerView.setText(v >= 100f ? "" : "Загрузка " + (int) v + "%");
                }
                break;
            case MediaPlayer.Event.LengthChanged:
                length = ev.getLengthChanged();
                break;
            case MediaPlayer.Event.TimeChanged:
                time = ev.getTimeChanged();
                if (length <= 0) length = player.getLength();
                updateBar();
                if (time - lastSaved > 10_000 || lastSaved - time > 10_000) saveProgress();
                break;
            case MediaPlayer.Event.EndReached:
                ended();
                break;
            case MediaPlayer.Event.EncounteredError:
                if (!triedOther) {
                    triedOther = true;
                    direct = !direct;   // the other way of reading the disk, just for this time
                    centerView.setText("Пробую другой способ…");
                    play(entry, false);
                } else {
                    fail("плеер не смог открыть файл");
                }
                break;
            default:
                break;
        }
    }

    private void fail(String why) {
        errored = true;
        centerView.setText("Не удалось воспроизвести: " + why + "\nНазад — выйти");
        overlay.setVisibility(View.GONE);
    }

    private void ended() {
        lib.catalog.markWatched(entry.path, true);
        int i = playlist.indexOf(entry);
        if (i >= 0 && i + 1 < playlist.size()) {
            play(playlist.get(i + 1), true);
        } else {
            finish();
        }
    }

    private void saveProgress() {
        if (errored || length <= 0 || entry == null) return;
        lastSaved = time;
        lib.catalog.saveProgress(entry.path, time, length);
    }

    // MARK: - Soundtrack and subtitles

    /** The Russian soundtrack when the person prefers it; Russian subtitles when the sound is not Russian. */
    private final Runnable autoTracks = new Runnable() {
        @Override public void run() {
            if (released || player == null) return;
            boolean russianSound = false;
            MediaPlayer.TrackDescription[] audio = player.getAudioTracks();
            if (audio != null) {
                if ("ru".equals(Prefs.audio())) {
                    for (MediaPlayer.TrackDescription t : audio) {
                        if (t.id >= 0 && t.name != null && RUSSIAN.matcher(t.name).matches()) {
                            player.setAudioTrack(t.id);
                            russianSound = true;
                            break;
                        }
                    }
                }
                if (!russianSound) {
                    int cur = player.getAudioTrack();
                    for (MediaPlayer.TrackDescription t : audio) if (t.id == cur && t.name != null && RUSSIAN.matcher(t.name).matches()) russianSound = true;
                }
            }
            MediaPlayer.TrackDescription[] subs = player.getSpuTracks();
            if (subs != null && !russianSound && "ru".equals(Prefs.audio())) {
                for (MediaPlayer.TrackDescription t : subs) {
                    if (t.id >= 0 && t.name != null && RUSSIAN.matcher(t.name).matches()) {
                        player.setSpuTrack(t.id);
                        break;
                    }
                }
            }
        }
    };

    private void chooseTrack(final boolean audio) {
        final MediaPlayer.TrackDescription[] all = audio ? player.getAudioTracks() : player.getSpuTracks();
        if (all == null || all.length == 0) {
            centerView.setText(audio ? "Других звуковых дорожек нет" : "Субтитров нет");
            h.postDelayed(new Runnable() {
                @Override public void run() { if (!released && centerView.getText().length() < 40) centerView.setText(""); }
            }, 1800);
            return;
        }
        final List<MediaPlayer.TrackDescription> list = new ArrayList<>();
        for (MediaPlayer.TrackDescription t : all) if (!audio || t.id >= 0) list.add(t);
        String[] names = new String[list.size()];
        int checked = -1, current = audio ? player.getAudioTrack() : player.getSpuTrack();
        for (int i = 0; i < names.length; i++) {
            MediaPlayer.TrackDescription t = list.get(i);
            names[i] = t.id < 0 ? "Выключены" : t.name;
            if (t.id == current) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(audio ? "Озвучка" : "Субтитры")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int id = list.get(which).id;
                        if (audio) player.setAudioTrack(id); else player.setSpuTrack(id);
                        d.dismiss();
                    }
                })
                .show();
    }

    private void chooseSpeed() {
        final float[] rates = {0.75f, 1f, 1.25f, 1.5f};
        String[] names = {"0.75×", "Обычная", "1.25×", "1.5×"};
        int checked = 1;
        for (int i = 0; i < rates.length; i++) if (Math.abs(player.getRate() - rates[i]) < 0.01f) checked = i;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Скорость")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        player.setRate(rates[which]);
                        d.dismiss();
                    }
                })
                .show();
    }

    // MARK: - Remote

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player == null || errored) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (paused) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!paused) togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-step(event));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(step(event));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                if (event.getRepeatCount() == 0) chooseTrack(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CAPTIONS:
                if (event.getRepeatCount() == 0) chooseTrack(false);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (event.getRepeatCount() == 0) chooseSpeed();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private static long step(KeyEvent e) {
        int r = e.getRepeatCount();
        return r < 4 ? 10_000 : r < 12 ? 30_000 : 60_000;
    }

    private void togglePause() {
        if (paused) player.play(); else player.pause();
        showOverlay();
    }

    private final Runnable doSeek = new Runnable() {
        @Override public void run() {
            if (seekTarget >= 0 && player != null && !released) {
                player.setTime(seekTarget);
                time = seekTarget;
            }
            seekTarget = -1;
        }
    };

    /** Several quick presses are one jump to where the last one points. */
    private void seekBy(long delta) {
        long base = seekTarget >= 0 ? seekTarget : player.getTime();
        long len = length > 0 ? length : player.getLength();
        long t = Math.max(0, base + delta);
        if (len > 0 && t > len - 1500) t = Math.max(0, len - 1500);
        seekTarget = t;
        h.removeCallbacks(doSeek);
        h.postDelayed(doSeek, 450);
        updateBar();
        showOverlay();
    }

    // MARK: - The panel

    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (!paused && !errored) overlay.setVisibility(View.GONE);
        }
    };

    private void showOverlay() {
        if (errored) return;
        overlay.setVisibility(View.VISIBLE);
        updateBar();
        h.removeCallbacks(hide);
        h.postDelayed(hide, 4500);
    }

    private void updateBar() {
        long shown = seekTarget >= 0 ? seekTarget : time;
        long len = length > 0 ? length : 0;
        bar.setProgress(len > 0 ? (int) (shown * 1000 / len) : 0);
        timeView.setText(Ui.time(shown) + (len > 0 ? " / " + Ui.time(len) : "") + (paused ? "   ⏸" : ""));
    }

    // MARK: - Leaving

    @Override protected void onPause() {
        super.onPause();
        if (player != null && !released) {
            saveProgress();
            if (!paused) player.pause();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (player == null) return;
        saveProgress();
        released = true;
        final MediaPlayer p = player;
        final LibVLC v = vlc;
        try {
            p.setEventListener(null);
            p.getVLCVout().detachViews();
        } catch (RuntimeException ignored) {
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    p.stop();
                    p.release();
                    v.release();
                } catch (RuntimeException ignored) {
                }
            }
        }).start();
        lib.refreshView();
    }
}
