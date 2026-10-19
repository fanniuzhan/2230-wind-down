package com.fanniuzhan.winddown;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.AlarmClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Navigate only to discovered system clocks. Never create an alarm or open a generic chooser. */
final class ClockLauncher {
    interface Starter { void start(Intent intent); }

    static boolean open(Context context, Set<String> clockPackages) {
        return launch(candidates(context, clockPackages), context::startActivity);
    }

    static List<Intent> candidates(Context context, Set<String> clockPackages) {
        PackageManager packages = context.getPackageManager();
        List<Intent> result = new ArrayList<>();
        for (String name : clockPackages) {
            Intent show = new Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(name);
            for (ResolveInfo resolved : packages.queryIntentActivities(show, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (resolved.activityInfo == null || !resolved.activityInfo.exported || !resolved.activityInfo.enabled
                        || !name.equals(resolved.activityInfo.packageName)) continue;
                result.add(new Intent(show).setComponent(new ComponentName(name, resolved.activityInfo.name)));
            }
        }
        // Some manufacturer clocks reject SHOW_ALARMS or don't expose it at all.
        // Their launcher entry opens the clock UI without setting or modifying alarms.
        for (String name : clockPackages) {
            Intent launcher = packages.getLaunchIntentForPackage(name);
            if (launcher != null && launcher.getComponent() != null
                    && name.equals(launcher.getComponent().getPackageName())) result.add(launcher);
        }
        return result;
    }

    static boolean launch(List<Intent> candidates, Starter starter) {
        for (Intent candidate : candidates) {
            try {
                starter.start(new Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                Log.w("WindDown", "Clock entry unavailable: " + candidate.getComponent()
                        + " (" + unavailable.getClass().getSimpleName() + ")");
            }
        }
        return false;
    }
}
