package io.freemytv.library;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;
import io.freemytv.library.core.Scanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Plays a film with the player that is installed on the device (VLC, MX Player, the system's own): the file is handed
 * over as an address on this device (MediaServer), so seeking works. VLC and MX Player report where the person stopped,
 * and that is saved. A built-in player is planned for later.
 */
final class Player {
    private Player() {}

    static final int REQUEST = 4101;
    private static String current;

    static void open(final Activity a, final Library lib, final Entry e, final boolean restart) {
        if (Prefs.builtin()) {
            a.startActivity(new Intent(a, PlayerActivity.class).putExtra("path", e.path).putExtra("restart", restart).putExtra("size", e.size));
            return;
        }
        // The address is made off the main thread (the network folder is opened and the local server started there).
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Intent i = build(lib, e, restart);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try {
                                current = e.path;
                                a.startActivity(i);
                            } catch (ActivityNotFoundException x) {
                                Toast.makeText(a, "Нет проигрывателя. Установите VLC (в RuStore или Google Play) — он читает все форматы.", Toast.LENGTH_LONG).show();
                            } catch (RuntimeException x) {
                                fail(a, x);
                            }
                        }
                    });
                } catch (final Exception x) {
                    a.runOnUiThread(new Runnable() { @Override public void run() { fail(a, x); } });
                }
            }
        }, "player-open").start();
    }

    private static void fail(Activity a, Exception x) {
        android.util.Log.w("FreeMyLibrary", "open failed", x);
        String why = x.getMessage() != null ? x.getMessage() : x.getClass().getSimpleName();
        Toast.makeText(a, "Не удалось открыть файл: " + why, Toast.LENGTH_LONG).show();
    }

    private static Intent build(Library lib, Entry e, boolean restart) throws IOException {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // VLC opens as its own window (started for a result it failed on the TV box)
        boolean direct = Prefs.direct();
        i.setDataAndType(Uri.parse(direct ? lib.directUrl(e.path) : lib.urlOf(e.path, e.size)), mime(e.path));
        android.util.Log.i("FreeMyLibrary", (direct ? "direct " : "via server ") + e.path + " (" + e.size + " bytes)");
        i.putExtra("title", e.title);
        Catalog.Progress p = lib.catalog.progress(e.path);
        if (!restart && p != null && p.started()) i.putExtra("position", p.position);
        ArrayList<Uri> subs = new ArrayList<>();
        for (String extra : direct ? new ArrayList<String>() : e.extras) {
            String ext = Scanner.ext(extra);
            if (ext.equals("srt") || ext.equals("ass") || ext.equals("ssa") || ext.equals("sub") || ext.equals("vtt")) subs.add(Uri.parse(lib.urlOf(extra)));
        }
        if (!subs.isEmpty()) {
            i.putExtra("subtitles_location", subs.get(0).toString());   // VLC
            i.putExtra("subs", subs.toArray(new Uri[0]));               // MX Player
            i.putExtra("subs.enable", new Uri[] {subs.get(0)});
        }
        return i;
    }

    /** Called with the result the player sends back: where it stopped. */
    static void onResult(Library lib, int request, Intent data) {
        if (request != REQUEST || current == null || data == null) return;
        long position = data.getLongExtra("extra_position", -1);       // VLC
        long duration = data.getLongExtra("extra_duration", -1);
        if (position < 0) position = data.getIntExtra("position", -1);  // MX Player
        if (duration < 0) duration = data.getIntExtra("duration", -1);
        if ("playback_completion".equals(data.getStringExtra("end_by"))) {
            lib.catalog.markWatched(current, true);
        } else if (position >= 0 && duration > 0) {
            lib.catalog.saveProgress(current, position, duration);
        }
    }

    private static String mime(String path) {
        switch (Scanner.ext(path).toLowerCase(Locale.ROOT)) {
            case "mkv": return "video/x-matroska";
            case "mp4": case "m4v": return "video/mp4";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "wmv": return "video/x-ms-wmv";
            case "webm": return "video/webm";
            case "ts": case "m2ts": case "mts": return "video/mp2t";
            default: return "video/*";
        }
    }
}
