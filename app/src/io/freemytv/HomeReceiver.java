package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            // A projector whose firmware won't take another home screen: open ours a few seconds after its own.
            if (HomeMode.projectorWithOwnHome(context)) ModeJob.schedule(context, ModeJob.OPEN_HOME, "", 12_000);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        }
    }
}
