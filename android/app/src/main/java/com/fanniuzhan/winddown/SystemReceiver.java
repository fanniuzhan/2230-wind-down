package com.fanniuzhan.winddown;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action) && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) return;
        // Never starts an accessibility service or enables a plan: only Android/user can bind it.
        NightGuardService.refreshIfRunning();
        BoundaryScheduler.update(context);
    }
}
