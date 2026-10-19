package com.fanniuzhan.winddown;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BoundaryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NightGuardService.refreshIfRunning();
        BoundaryScheduler.update(context);
    }
}
