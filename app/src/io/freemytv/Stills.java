package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The home screen's background photos, kept on disk: a few frames taken from each aerial video that was downloaded
 * (960x540 JPEG, about 100 KB each), so the background doesn't need the big videos any more. They live in the app's
 * files folder, which the system doesn't empty. New ones come in once a week (or on request in the settings).
 */
final class Stills {
    private Stills() {}

    static final long WEEK_MS = 7 * 24 * 3600_000L;
    private static final float[] AT = {0.12f, 0.34f, 0.56f, 0.78f};

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "stills");
        d.mkdirs();
        return d;
    }

    /** The saved photos from `place` ("" = any). */
    static List<File> list(Context c, String place) {
        List<File> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".jpg")) continue;
            for (Aerials.Clip clip : Aerials.all(c)) {
                if (f.getName().startsWith(clip.file() + ".") && clip.isFrom(place)) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    static boolean has(Context c, Aerials.Clip clip) {
        File[] files = dir(c).listFiles();
        if (files == null) return false;
        for (File f : files) if (f.getName().startsWith(clip.file() + ".")) return true;
        return false;
    }

    /** Saves a few frames of a downloaded clip (nothing if it already has them). Returns whether there are photos for it. */
    static boolean harvest(Context c, Aerials.Clip clip, File video) {
        if (has(c, clip)) return true;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        boolean any = false;
        try {
            r.setDataSource(video.getPath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = d == null ? 0 : Long.parseLong(d);
            for (int i = 0; i < AT.length; i++) {
                Bitmap frame = r.getFrameAtTime((long) (ms * 1000L * AT[i]), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                Bitmap small = Bitmap.createScaledBitmap(frame, 960, 540, true);
                if (small != frame) frame.recycle();
                File tmp = new File(dir(c), clip.file() + "." + i + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    small.compress(Bitmap.CompressFormat.JPEG, 88, out);
                }
                small.recycle();
                any |= tmp.renameTo(new File(dir(c), clip.file() + "." + i + ".jpg"));
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            AppLog.w("stills: " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (any) Prefs.setStillsAt(System.currentTimeMillis());
        return any;
    }

    static Bitmap load(File f) {
        return BitmapFactory.decodeFile(f.getPath());
    }

    /** Whether it is time for new photos: none for `place` yet, or the last ones came more than a week ago. */
    static boolean due(Context c, String place) {
        return list(c, place).isEmpty() || System.currentTimeMillis() - Prefs.stillsAt() > WEEK_MS;
    }
}
