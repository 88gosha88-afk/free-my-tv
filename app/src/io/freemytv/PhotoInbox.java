package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Where tools/photos.py writes a photo when the TV doesn't let adb into the app's external folder (Android 11, and
 * Fire OS 8 on it):
 * `adb exec-in content write --uri content://io.freemytv.photos/<name>`. Only adb's shell (or root) may write; the
 * photo lands in Photos' private inbox and is imported (and deleted) once it's written.
 */
public class PhotoInbox extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends photos");
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.startsWith(".") || !mode.contains("w")) {
            throw new FileNotFoundException("photos only: content://io.freemytv.photos/<name>");
        }
        File in = Photos.privateInbox(getContext());
        if (!in.isDirectory() && !in.mkdirs()) throw new FileNotFoundException("can't create " + in);
        // Written under a hidden name, so an import that runs meanwhile leaves it alone; named once it's complete.
        File part = new File(in, "." + name), done = new File(in, name);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        if (part.renameTo(done)) Photos.importInbox(getContext(), null);
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
