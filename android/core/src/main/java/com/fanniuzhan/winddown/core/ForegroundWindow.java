package com.fanniuzhan.winddown.core;

import java.util.List;

/** Select the current interactive window, including system surfaces above an allowed app. */
public final class ForegroundWindow {
    private ForegroundWindow() { }

    public static final class Window {
        public final String owner;
        public final int layer;
        public final boolean focused, active, application, guardOverlay;

        public Window(String owner, int layer, boolean focused, boolean active,
                      boolean application, boolean guardOverlay) {
            this.owner = owner; this.layer = layer; this.focused = focused; this.active = active;
            this.application = application; this.guardOverlay = guardOverlay;
        }
    }

    public static Window select(List<Window> windows) {
        Window selected = null;
        for (Window window : windows) {
            if (window.guardOverlay || (!window.focused && !window.active)) continue;
            if (selected == null || window.layer > selected.layer
                    || (window.layer == selected.layer && window.focused && !selected.focused)) selected = window;
        }
        return selected;
    }

    public static boolean systemLocked(boolean deviceLocked, boolean keyguardLocked, Window front) {
        // Keyguard visibility can lag after authentication. Never override a genuinely locked device.
        return deviceLocked || (keyguardLocked && (front == null || !front.focused || !front.application
                || front.owner == null || isSystemSurface(front.owner)));
    }

    public static boolean isSystemSurface(String owner) {
        return "com.android.systemui".equals(owner) || "com.hihonor.systemui".equals(owner)
                || "com.huawei.systemui".equals(owner);
    }
}
