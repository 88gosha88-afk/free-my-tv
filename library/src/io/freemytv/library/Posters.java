package io.freemytv.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.widget.ImageView;

import io.freemytv.library.core.Tmdb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Posters and backdrops: saved on the device after the first download, drawn from a small memory cache. */
final class Posters {
    private Posters() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    /** Shows the image at TMDB path `path` (size like "w342", "w780") in the view; a drawn card with the title until it arrives. */
    static void show(final Context c, final ImageView view, final String path, final String size, final String title) {
        final String key = path.isEmpty() ? "ph:" + title : size + path;
        view.setTag(key);
        Bitmap have = CACHE.get(key);
        if (have != null) { view.setImageBitmap(have); return; }
        if (path.isEmpty()) {
            Bitmap ph = placeholder(title, 300, 450);
            CACHE.put(key, ph);
            view.setImageBitmap(ph);
            return;
        }
        view.setImageBitmap(placeholder(title, 300, 450));
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = fetch(c, path, size);
                if (b == null) return;
                CACHE.put(key, b);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(view.getTag())) view.setImageBitmap(b);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(Context c, String path, String size) {
        File dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        File f = new File(dir, size + path.replace('/', '_'));
        if (!f.isFile() || f.length() == 0) {
            HttpURLConnection conn = null;
            File tmp = new File(f.getPath() + ".tmp");
            try {
                conn = (HttpURLConnection) new URL(Tmdb.IMAGES + size + path).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) return null;
            } catch (java.io.IOException e) {
                tmp.delete();
                android.util.Log.w("FreeMyLibrary", "poster " + path + " failed: " + e + "; trying DNS-over-HTTPS");
                if (!viaDoh(Tmdb.IMAGES + size + path, f)) return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return BitmapFactory.decodeFile(f.getPath());
    }

    private static boolean viaDoh(String url, File to) {
        try {
            io.freemytv.library.core.Doh.Reply r = io.freemytv.library.core.Doh.get(url, null);
            if (r.code != 200 || r.data.length == 0) throw new java.io.IOException("HTTP " + r.code);
            try (OutputStream out = new FileOutputStream(to)) { out.write(r.data); }
            return true;
        } catch (java.io.IOException e) {
            android.util.Log.w("FreeMyLibrary", "poster over DoH failed: " + e);
            return false;
        }
    }

    /** A card that stands in for a missing poster: a dark gradient with the title on it. */
    static Bitmap placeholder(String title, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int hue = Math.abs(title.hashCode()) % 360;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, Color.HSVToColor(new float[] {hue, 0.45f, 0.42f}),
                Color.HSVToColor(new float[] {(hue + 40) % 360, 0.55f, 0.18f}), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFF2F4F8);
        tp.setTextSize(w / 8f);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        StaticLayout layout = new StaticLayout(title, tp, w - 40, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        c.save();
        c.translate(20, (h - layout.getHeight()) / 2f);
        layout.draw(c);
        c.restore();
        return b;
    }
}
