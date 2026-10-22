package io.freemytv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple TV's aerial videos for the screensaver. Only the list is bundled (assets/aerials.txt, one "place TAB url" per
 * line, the 1080p H.264 version of each clip); the videos are streamed from Apple's servers when the screensaver runs.
 */
final class Aerials {
    private Aerials() {}

    static final class Clip {
        final String place, url;

        Clip(String place, String url) {
            this.place = place;
            this.url = url;
        }
    }

    private static List<Clip> all;

    private static synchronized List<Clip> all(Context c) {
        if (all != null) return all;
        List<Clip> list = new ArrayList<>();
        try (InputStream in = c.getAssets().open("aerials.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || !line.startsWith("https://", tab + 1)) continue;
                list.add(new Clip(line.substring(0, tab).trim(), line.substring(tab + 1).trim()));
            }
        } catch (IOException e) {
            AppLog.w("aerials list unavailable: " + e.getMessage());
        }
        all = list;
        return all;
    }

    /** Every clip once in random order, then again in another order: no repeats until all have played. */
    static final class Playlist {
        private final List<Clip> clips;
        private final List<Clip> queue = new ArrayList<>();

        Playlist(Context c) {
            clips = all(c);
        }

        boolean isEmpty() {
            return clips.isEmpty();
        }

        Clip next() {
            if (clips.isEmpty()) return null;
            if (queue.isEmpty()) {
                queue.addAll(clips);
                Collections.shuffle(queue);
            }
            return queue.remove(queue.size() - 1);
        }
    }
}
