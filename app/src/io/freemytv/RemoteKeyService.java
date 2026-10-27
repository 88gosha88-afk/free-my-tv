package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    /**
     * The system binds this service on every boot, even when the firmware blocks BOOT_COMPLETED for third-party apps
     * (the Formovie projector does). So it also opens our home screen once per boot where the stock launcher stays.
     */
    @Override protected void onServiceConnected() {
        try {
            if (SystemClock.elapsedRealtime() > 5 * 60_000L) return;   // service restarted later, not a boot
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            SharedPreferences sp = getSharedPreferences("boot", Context.MODE_PRIVATE);
            if (boot != -1 && sp.getInt("opened", -2) == boot) return;
            if (!HomeMode.projectorWithOwnHome(this)) return;
            sp.edit().putInt("opened", boot).commit();
            ModeJob.schedule(this, ModeJob.OPEN_HOME, "", 8_000);
        } catch (Throwable ignored) {}
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
