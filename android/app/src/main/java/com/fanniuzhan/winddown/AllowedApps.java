package com.fanniuzhan.winddown;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.telecom.TelecomManager;
import java.util.HashSet;
import java.util.Set;

/** Discover actual installed telephony and alarm components; never allow the generic "android" resolver. */
final class AllowedApps {
    final Set<String> phones = new HashSet<>();
    final Set<String> clocks = new HashSet<>();
    private final PackageManager packages;

    AllowedApps(Context context) {
        packages = context.getPackageManager();
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null) add(phones, telecom.getDefaultDialerPackage());
        addResolved(phones, phoneIntent());
        for (ResolveInfo service : packages.queryIntentServices(new Intent("android.telecom.InCallService"), 0)) {
            if (service.serviceInfo != null && "android.permission.BIND_INCALL_SERVICE".equals(service.serviceInfo.permission)
                    && isSystem(service.serviceInfo.applicationInfo)) add(phones, service.serviceInfo.packageName);
        }
        for (String name : new String[] { "com.hihonor.contacts", "com.hihonor.incallui", "com.huawei.contacts",
                "com.huawei.incallui", "com.android.incallui", "com.android.phone", "com.android.emergency" }) {
            addInstalledSystem(phones, name);
        }
        for (String name : new String[] { "com.hihonor.deskclock", "com.hihonor.android.deskclock",
                "com.huawei.deskclock", "com.android.deskclock", "com.google.android.deskclock" }) {
            addInstalledSystem(clocks, name);
        }
        for (String action : new String[] { AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM }) {
            for (ResolveInfo activity : packages.queryIntentActivities(new Intent(action), 0)) {
                if (activity.activityInfo != null && isSystem(activity.activityInfo.applicationInfo))
                    add(clocks, activity.activityInfo.packageName);
            }
        }
    }

    boolean ready() { return !phones.isEmpty() && !clocks.isEmpty(); }
    Intent phoneIntent() { return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")); }
    Intent clockIntent() { return new Intent(AlarmClock.ACTION_SHOW_ALARMS); }
    Intent emergencyIntent() {
        // Public SDK only: opens an editable system dialer, never places a call.
        // The Android keyguard's own emergency interface is left untouched.
        return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
    }
    private void addResolved(Set<String> result, Intent intent) {
        ResolveInfo info = packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) add(result, info.activityInfo.packageName);
    }
    private void addInstalledSystem(Set<String> result, String name) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(name, 0);
            if (isSystem(info) && info.enabled) add(result, name);
        } catch (PackageManager.NameNotFoundException ignored) { /* Not installed on this manufacturer. */ }
    }
    private static boolean isSystem(ApplicationInfo info) {
        return info != null && (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }
    private static void add(Set<String> result, String name) {
        if (name != null && !name.isEmpty() && !"android".equals(name)) result.add(name);
    }
}
