package com.fanniuzhan.winddown;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.fanniuzhan.winddown.core.NightSchedule;
import java.time.Instant;
import java.time.ZoneId;

final class BoundaryScheduler {
    private BoundaryScheduler() { }
    static void update(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent alarm = PendingIntent.getBroadcast(context, 10,
                new Intent(context, BoundaryReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(alarm);
        NightSchedule schedule = new SettingsStore(context).schedule();
        if (!schedule.enabled) return;
        long target = schedule.nextTransition(Instant.now(), ZoneId.systemDefault()).toEpochMilli();
        // No special exact-alarm permission is requested. While the user is interacting,
        // the accessibility service independently checks actual time on every window event.
        manager.setAndAllowWhileIdle(AlarmManager.RTC, target, alarm);
    }
}
