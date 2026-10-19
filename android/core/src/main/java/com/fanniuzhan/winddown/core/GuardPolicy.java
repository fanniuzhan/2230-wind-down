package com.fanniuzhan.winddown.core;

import java.util.Set;

/** Yield to calls and alarms, then cover ordinary applications again. */
public final class GuardPolicy {
    private GuardPolicy() { }

    public static boolean shouldCover(boolean restricted, boolean systemLocked, boolean ringing, boolean alarmRinging,
                                      String foregroundPackage, Set<String> phonePackages,
                                      Set<String> clockPackages) {
        if (!restricted || systemLocked || ringing || alarmRinging) return false;
        if (foregroundPackage == null) return true;
        return !phonePackages.contains(foregroundPackage) && !clockPackages.contains(foregroundPackage);
    }
}
