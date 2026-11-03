package io.freemytv.library;

import android.app.Application;

public class LibraryApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
    }
}
