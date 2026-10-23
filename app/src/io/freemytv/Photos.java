package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own photos. A computer puts them in the app's external files folder (tools/photos.py: adb push to
 * Android/data/io.freemytv/files/photos, no permission needed); the next time the home screen, the screensaver or the
 * settings open, each one is imported once into the app's private folder, turned upright (EXIF), cropped to 16:9 and
 * re-encoded at the screensaver's size (which also drops its GPS and other tags), and the original is deleted. While
 * they are on (Home Settings > My Photos), the screensaver shows them instead of the picks' photos, in the order they
 * came, and without picks (no TMDB key) one of them is the home screen's background, a new one every hour.
 */
final class Photos {
    private Photos() {}

    /** The screensaver's photo size (DreamView loads them 10% larger than the screen, for the slow zoom). */
    static final int W = 2112, H = 1188;
    /** At most this many (about 300 KB each); later ones wait in the folder until some are removed. */
    static final int MAX = 100;
    /** Smaller ones are icons and thumbnails, not photos. */
    private static final int MIN_SIDE = 640;

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    /** Where tools/photos.py puts them (the app's own external folder: adb can write there, and no permission). */
    private static File inbox(Context c) {
        return c.getExternalFilesDir("photos");
    }

    // MARK: - The imported photos

    private static long listedStamp = -1;
    private static List<File> listed = new ArrayList<>();

    /** The imported photos, in the order they came (one stat of the folder while nothing changed). */
    static synchronized List<File> files(Context c) {
        File d = dir(c);
        long stamp = d.exists() ? d.lastModified() : 0;
        if (stamp != listedStamp) {
            List<File> out = new ArrayList<>();
            File[] all = d.listFiles();
            if (all != null) for (File f : all) if (f.getName().endsWith(".jpg")) out.add(f);
            Collections.sort(out);
            listed = out;
            listedStamp = stamp;
        }
        return listed;
    }

    /** On in Home Settings and something imported. */
    static boolean on(Context c) {
        return Prefs.photos() && !files(c).isEmpty();
    }

    /** For the screensaver: the photos as picks (no title, no caption). */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!on(c)) return out;
        List<File> list = files(c);
        for (int i = 0; i < list.size(); i++) {
            Recs.Pick p = new Recs.Pick();
            p.kind = "photo";
            p.id = i;
            p.photo = list.get(i);
            out.add(p);
        }
        return out;
    }

    // MARK: - The home screen's background

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File built, building;
    private static Backdrop backdrop;

    /**
     * This hour's photo, ready to draw as the home screen's background, or null (off, or the first one still being
     * built). When the hour changes, the previous one stays until the new one is ready; then `ready` runs.
     */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        if (!on(c)) {
            backdrop = null;
            built = null;
            return null;
        }
        List<File> list = files(c);
        File want = list.get((int) ((System.currentTimeMillis() / 3_600_000L) % list.size()));
        if (!want.equals(built) && !want.equals(building)) {
            building = want;
            worker.execute(() -> {
                Backdrop b = null;
                try {
                    Bitmap src = BitmapFactory.decodeFile(want.getPath());
                    if (src != null) b = Backdrop.make(src, true, true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("photos: background from " + want.getName() + ": " + e.getMessage());
                }
                synchronized (Photos.class) {
                    building = null;
                    if (b == null) return;
                    backdrop = b;
                    built = want;
                }
                main.post(ready);
            });
        }
        return backdrop;
    }

    // MARK: - Importing what the computer sent

    private static boolean importing;
    private static final List<Runnable> waiting = new ArrayList<>();

    /**
     * Imports what tools/photos.py put in the inbox, in the background, one photo at a time; `done` runs (on the main
     * thread) when something was imported, also when an import already under way (asked by another screen) brings it.
     * Cheap when the inbox is empty: one directory listing.
     */
    static void importInbox(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Photos.class) {
            if (done != null) waiting.add(done);
            if (importing) return;
            importing = true;
        }
        worker.execute(() -> {
            int n = 0;
            try {
                n = importNow(app);
            } catch (RuntimeException e) {
                AppLog.w("photos: import failed: " + e.getMessage());
            }
            List<Runnable> tell;
            synchronized (Photos.class) {
                importing = false;
                tell = new ArrayList<>(waiting);
                waiting.clear();
            }
            if (n > 0) main.post(() -> { for (Runnable r : tell) r.run(); });
        });
    }

    private static int importNow(Context c) {
        File in = inbox(c);
        File[] all = in == null ? null : in.listFiles();
        if (all == null || all.length == 0) return 0;
        Arrays.sort(all);
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            AppLog.w("photos: can't create " + d);
            return 0;
        }
        int have = files(c).size(), count = 0;
        for (File src : all) {
            if (!src.isFile()) continue;
            if (!image(src.getName().toLowerCase(Locale.ROOT))) {
                src.delete();
                continue;
            }
            if (have + count >= MAX) {
                AppLog.w("photos: " + MAX + " already, " + src.getName() + " waits");
                continue;
            }
            // Named by import time: the order they came is the order the screensaver shows them.
            File out = new File(d, String.format(Locale.US, "%013d.jpg", System.currentTimeMillis() + count));
            try {
                Bitmap b = decode(src, W, H);
                if (b == null) {
                    AppLog.w("photos: skipped " + src.getName() + " (too small or unreadable)");
                } else {
                    try (OutputStream os = new FileOutputStream(out)) {
                        b.compress(Bitmap.CompressFormat.JPEG, 86, os);
                    }
                    b.recycle();
                    count++;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                out.delete();
                AppLog.w("photos: skipped " + src.getName() + ": " + e.getMessage());
            }
            src.delete();
        }
        AppLog.w("photos: imported " + count + ", " + (have + count) + " in all");
        if (count > 0) Prefs.setPhotos(true);   // new photos show, even if they had been turned off
        return count;
    }

    /** Deletes the imported photos (and whatever is still waiting to be imported). */
    static void removeAll(Context c) {
        clear(dir(c));
        File in = inbox(c);
        File[] queued = in == null ? null : in.listFiles();
        if (queued != null) for (File f : queued) f.delete();
        synchronized (Photos.class) {
            backdrop = null;
            built = null;
        }
    }

    private static boolean image(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || (Build.VERSION.SDK_INT >= 28 && (name.endsWith(".heic") || name.endsWith(".heif")));
    }

    /**
     * The photo upright (EXIF), its center cropped to w:h and filtered down to w × h; null if too small or unreadable.
     * It is decoded subsampled to about the UI's size (a 12 MP photo: 2000 × 1500, 12 MB, not 48), then drawn straight
     * into the w × h result (cropped and scaled in one step) and only that is turned: at most the decoded photo and
     * one or two 10 MB bitmaps at a time.
     */
    private static Bitmap decode(File f, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (Math.min(bounds.outWidth, bounds.outHeight) < MIN_SIDE) return null;
        int degrees = rotation(f);
        boolean sideways = degrees == 90 || degrees == 270;
        // Everything below is in the photo's stored orientation: a sideways one is cropped and sized as h × w.
        int tw = sideways ? h : w, th = sideways ? w : h;
        float ratio = (float) tw / th;
        int cw = Math.min(bounds.outWidth, Math.round(bounds.outHeight * ratio));
        int ch = Math.min(bounds.outHeight, Math.round(cw / ratio));
        // Only what the crop keeps decides the sample size: a portrait photo is cut to its middle band.
        int keep = Math.round(Backdrop.W * 0.75f);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(cw, ch) / (opts.inSampleSize * 2) >= keep) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) return null;
        float sx = (float) src.getWidth() / bounds.outWidth, sy = (float) src.getHeight() / bounds.outHeight;
        float cx = cw * sx, cy = ch * sy, left = (src.getWidth() - cx) / 2, top = (src.getHeight() - cy) / 2;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(src, new android.graphics.Rect(Math.round(left), Math.round(top),
                Math.round(left + cx), Math.round(top + cy)), new android.graphics.Rect(0, 0, tw, th),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG));
        src.recycle();
        if (degrees == 0) return out;
        Matrix m = new Matrix();
        m.setRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(out, 0, 0, tw, th, m, true);
        if (turned != out) out.recycle();
        return turned;
    }

    private static int rotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void clear(File d) {
        File[] all = d.listFiles();
        if (all != null) for (File f : all) f.delete();
        d.delete();
    }
}
