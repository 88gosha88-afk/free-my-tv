package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own photos, imported from a USB stick (Home Settings → My Photos). They are copied into the app at the
 * screensaver's size (cropped to 16:9, turned upright), so the stick can come out afterwards and the TV never decodes
 * a 12-megapixel original. While they are on, the screensaver shows them instead of the picks' photos, and the home
 * screen's background is one of them (a new one every hour), blurred like the picks' are.
 */
final class Photos {
    private Photos() {}

    /** The screensaver's photo size (DreamView loads them 10% larger than the screen, for the slow zoom). */
    static final int W = 2112, H = 1188;
    /** At most this many (about 300 KB each): a stick with thousands gives an even sample of them. */
    static final int MAX = 100;
    /** Smaller ones are icons and thumbnails, not photos. */
    private static final int MIN_SIDE = 640;
    private static final int DEPTH = 6;
    private static final Set<String> SKIP = new java.util.HashSet<>(Arrays.asList(
            "android", "lost.dir", "system volume information", "$recycle.bin", "thumbnails"));

    /** Reading the stick: READ_MEDIA_IMAGES from Android 13, READ_EXTERNAL_STORAGE before. */
    static final String PERMISSION = Build.VERSION.SDK_INT >= 33
            ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE";

    interface Progress { void step(int done, int total); }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    // MARK: - The imported photos

    private static long listedStamp = -1;
    private static List<File> listed = new ArrayList<>();

    /** The imported photos, in name order (one stat of the folder while nothing changed). */
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

    /** For the screensaver: the photos as picks (no title, no caption), shuffled differently each day. */
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
        Collections.shuffle(out, new Random(System.currentTimeMillis() / 86_400_000L));
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

    // MARK: - Importing from the stick

    /** The mounted USB sticks (and SD cards): Android's public removable volumes, under /storage. */
    static List<File> sticks(Context c) {
        Set<File> out = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= 30) {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState()) && v.getDirectory() != null) {
                        out.add(v.getDirectory());
                    }
                }
            }
        }
        File[] all = new File("/storage").listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory() && !f.getName().equals("emulated") && !f.getName().equals("self")) out.add(f);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Replaces the imported photos with the ones on the stick(s). Runs in the background; returns how many were
     * imported (0: none found, and the previous ones stay).
     */
    static int importAll(Context c, Progress progress) throws IOException {
        List<File> found = new ArrayList<>();
        for (File root : sticks(c)) find(root, found);
        Collections.sort(found);
        AppLog.w("photos: " + found.size() + " images on " + sticks(c));
        if (found.size() > MAX) {
            List<File> sample = new ArrayList<>();
            for (int i = 0; i < MAX; i++) sample.add(found.get(i * found.size() / MAX));
            found = sample;
        }
        if (found.isEmpty()) return 0;
        File next = new File(c.getFilesDir(), "photos.new");
        clear(next);
        if (!next.mkdirs()) throw new IOException("can't write " + next);
        int count = 0;
        for (int i = 0; i < found.size(); i++) {
            progress.step(i, found.size());
            File src = found.get(i);
            try {
                Bitmap b = decode(src, W, H);
                if (b == null) continue;
                try (OutputStream out = new FileOutputStream(new File(next, String.format(Locale.US, "%03d.jpg", count)))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 86, out);
                }
                b.recycle();
                count++;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("photos: skipped " + src.getName() + ": " + e.getMessage());
            }
        }
        progress.step(found.size(), found.size());
        if (count == 0) {
            clear(next);
            return 0;
        }
        File d = dir(c);
        clear(d);
        if (!next.renameTo(d)) throw new IOException("can't replace " + d);
        Prefs.setPhotos(true);
        return count;
    }

    /** Deletes the imported photos. */
    static void removeAll(Context c) {
        clear(dir(c));
        synchronized (Photos.class) {
            backdrop = null;
            built = null;
        }
    }

    /** Images under `root`, skipping hidden and system folders, a few levels deep (DCIM/Camera/2024/… fits). */
    private static void find(File root, List<File> out) {
        ArrayDeque<File[]> queue = new ArrayDeque<>();
        queue.add(new File[] {root});
        int depth = 0;
        while (!queue.isEmpty() && depth <= DEPTH && out.size() < MAX * 50) {
            int n = queue.size();
            for (int q = 0; q < n; q++) {
                for (File d : queue.poll()) {
                    File[] all = d.listFiles();
                    if (all == null) continue;
                    List<File> dirs = new ArrayList<>();
                    for (File f : all) {
                        String name = f.getName().toLowerCase(Locale.ROOT);
                        if (name.startsWith(".") || SKIP.contains(name)) continue;
                        if (f.isDirectory()) dirs.add(f);
                        else if (image(name)) out.add(f);
                    }
                    if (!dirs.isEmpty()) queue.add(dirs.toArray(new File[0]));
                }
            }
            depth++;
        }
    }

    private static boolean image(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || (Build.VERSION.SDK_INT >= 28 && (name.endsWith(".heic") || name.endsWith(".heif")));
    }

    /** The photo upright (EXIF), its center cropped to w:h and filtered down to w × h; null if too small or unreadable. */
    private static Bitmap decode(File f, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (Math.min(bounds.outWidth, bounds.outHeight) < MIN_SIDE) return null;
        int degrees = rotation(f);
        boolean sideways = degrees == 90 || degrees == 270;
        int sw = sideways ? bounds.outHeight : bounds.outWidth, sh = sideways ? bounds.outWidth : bounds.outHeight;
        // Only what the crop keeps decides the sample size: a portrait photo is cut to its middle band.
        float ratio = (float) w / h;
        int cw = Math.min(sw, Math.round(sh * ratio)), ch = Math.min(sh, Math.round(cw / ratio));
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (cw / (opts.inSampleSize * 2) >= w && ch / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) return null;
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.setRotate(degrees);
            Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (turned != src) src.recycle();
            src = turned;
        }
        cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        return out;
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
