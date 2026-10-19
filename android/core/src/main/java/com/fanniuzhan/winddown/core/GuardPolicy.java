package com.fanniuzhan.winddown.core;

import java.util.Set;

/** A phone/clock exception cannot authorize unrelated foreground applications. */
public final class GuardPolicy {
    private GuardPolicy() { }

    public static boolean shouldCover(boolean restricted, boolean systemLocked, boolean ringing,
                                      String foregroundPackage, Set<String> phonePackages,
                                      Set<String> clockPackages) {
        if (!restricted || systemLocked || ringing) return false;
        if (foregroundPackage == null) return true;
        return !phonePackages.contains(foregroundPackage) && !clockPackages.contains(foregroundPackage);
    }
}
