package com.fanniuzhan.winddown;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.fanniuzhan.winddown.core.GuardPolicy;
import com.fanniuzhan.winddown.core.ForegroundWindow;
import com.fanniuzhan.winddown.core.NightSchedule;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public final class NightGuardService extends AccessibilityService {
    private static WeakReference<NightGuardService> current = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SettingsStore store;
    private AllowedApps allowed;
    private WindowManager windows;
    private View cover;
    private TextView countdown;
    private TextView heading;
    private long lastDiscovery;
    private long coverAddedAt;
    private long nextTickAt;
    private long lastShadeDismissal;
    private boolean stopped;
    private boolean screenReceiverRegistered;
    private final Runnable tick = () -> { nextTickAt = 0; evaluate(); };
    private final Runnable wakeCheck = this::evaluate;
    private final Runnable windowCheck = this::evaluate;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handler.removeCallbacks(wakeCheck);
                handler.removeCallbacks(windowCheck);
                hideCover();
                handler.removeCallbacks(tick);
                nextTickAt = 0;
                scheduleCheck(1000L);
            } else {
                recheckAfterWake();
            }
        }
    };

    static boolean connected() { return current.get() != null; }
    static void refreshIfRunning() {
        NightGuardService service = current.get();
        if (service != null) service.handler.post(service::evaluate);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        stopped = false;
        store = new SettingsStore(this);
        allowed = new AllowedApps(this);
        lastDiscovery = android.os.SystemClock.elapsedRealtime();
        windows = getSystemService(WindowManager.class);
        current = new WeakReference<>(this);
        android.util.Log.i("WindDown", "Night guard connected");
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (!screenReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            screenReceiverRegistered = true;
        }
        BoundaryScheduler.update(this);
        recheckAfterWake();
    }

    private void recheckAfterWake() {
        handler.removeCallbacks(wakeCheck);
        hideCover(); // A retained Java view is not proof the system still displays its window.
        evaluate();
        // Manufacturer keyguard/window state may settle after SCREEN_ON or USER_PRESENT.
        for (long delay : new long[] { 150L, 600L, 1500L }) handler.postDelayed(wakeCheck, delay);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null || stopped) return;
        evaluate();
        // A window event can arrive before the interactive-window snapshot settles.
        handler.removeCallbacks(windowCheck);
        handler.postDelayed(windowCheck, 150L);
    }

    private ForegroundWindow.Window frontWindow() {
        // Inspect ownership only: never read node text, descriptions, children or input.
        List<AccessibilityWindowInfo> visible = getWindows();
        List<ForegroundWindow.Window> snapshots = new ArrayList<>();
        for (AccessibilityWindowInfo window : visible) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            if (!window.isFocused() && !window.isActive()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            String owner = null;
            if (root != null) {
                CharSequence name = root.getPackageName();
                owner = name == null ? null : name.toString();
                root.recycle();
            }
            snapshots.add(new ForegroundWindow.Window(owner, window.getLayer(), window.isFocused(),
                    window.isActive(), window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION, false));
        }
        // No cached package fallback: a previous phone/clock must never grant an indefinite exemption.
        return ForegroundWindow.select(snapshots);
    }

    private void scheduleCheck(long delay) {
        long due = android.os.SystemClock.uptimeMillis() + delay;
        if (nextTickAt != 0 && nextTickAt <= due) return; // Events must not postpone an earlier check.
        handler.removeCallbacks(tick);
        nextTickAt = due;
        handler.postDelayed(tick, delay);
    }

    private void evaluate() {
        if (store == null || stopped) return;
        try {
            long nowMillis = System.currentTimeMillis();
            store.finishTrialIfDue(nowMillis);
            long elapsed = android.os.SystemClock.elapsedRealtime();
            if (elapsed - lastDiscovery > 30_000) {
                allowed = new AllowedApps(this); lastDiscovery = elapsed;
            }
            Instant now = Instant.ofEpochMilli(nowMillis);
            NightSchedule schedule = store.schedule();
            NightSchedule.Window active = schedule.activeWindow(now, ZoneId.systemDefault());
            boolean trial = store.isTrialActive(nowMillis);
            boolean restricted = active != null || trial;
            PowerManager power = getSystemService(PowerManager.class);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            AudioManager audio = getSystemService(AudioManager.class);
            ForegroundWindow.Window front = frontWindow();
            String owner = front == null ? null : front.owner;
            boolean interactive = power == null || power.isInteractive();
            boolean systemLocked = keyguard != null && ForegroundWindow.systemLocked(
                    keyguard.isDeviceLocked(), keyguard.isKeyguardLocked(), front);
            boolean ringing = audio != null && audio.getMode() == AudioManager.MODE_RINGTONE;
            boolean alarmRinging = false;
            if (restricted && audio != null) {
                // Public, anonymized playback metadata only. Never capture audio or request microphone access.
                for (AudioPlaybackConfiguration playback : audio.getActivePlaybackConfigurations()) {
                    if (playback.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ALARM) {
                        alarmRinging = true; break;
                    }
                }
            }
            if (!allowed.ready()) {
                hideCover();
                store.error("未能识别电话或闹钟组件，未开始拦截；请先完成本机兼容性检查。");
            } else if (interactive && GuardPolicy.shouldCover(restricted,
                    systemLocked, ringing, alarmRinging, owner, allowed.phones, allowed.clocks)) {
                if (Build.VERSION.SDK_INT >= 31 && ForegroundWindow.isSystemSurface(owner)
                        && elapsed - lastShadeDismissal >= 500L) {
                    // Close the shade only while restricting an unlocked, non-ringing device.
                    // This does not navigate away from phone/clock or alter their audio.
                    lastShadeDismissal = elapsed;
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                }
                Instant until = active != null ? active.end : Instant.ofEpochMilli(store.trialUntil());
                showCover(until, trial && active == null);
                store.error("");
            } else {
                hideCover();
            }
            long delay = restricted ? (interactive ? 250L : 1000L) : schedule.enabled
                    ? Math.max(100L, Math.min(60_000L, Duration.between(now,
                            schedule.nextTransition(now, ZoneId.systemDefault())).toMillis())) : 60_000L;
            scheduleCheck(delay);
        } catch (RuntimeException error) {
            hideCover();
            try { store.error("夜间拦截暂未生效：" + error.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { /* Storage may be unavailable; never block call UI on failure. */ }
            scheduleCheck(5000L);
        }
    }

    private void showCover(Instant until, boolean trial) {
        if (cover != null && ((!cover.isAttachedToWindow()
                && android.os.SystemClock.uptimeMillis() - coverAddedAt >= 500L)
                || cover.getVisibility() != View.VISIBLE || cover.getWindowVisibility() != View.VISIBLE)) hideCover();
        if (cover == null) {
            ScrollView surface = new ScrollView(this);
            surface.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) {
                    if (cover != view) return; // Intentional removals clear the reference first.
                    cover = null; heading = null; countdown = null;
                    if (!stopped) {
                        handler.removeCallbacks(wakeCheck);
                        handler.postDelayed(wakeCheck, 150L);
                    }
                }
            });
            surface.setFillViewport(true); surface.setBackgroundColor(Ui.DARK); surface.setClickable(true);
            LinearLayout panel = Ui.column(this);
            panel.setGravity(Gravity.CENTER);
            int padding = Ui.dp(this, 32); panel.setPadding(padding, padding, padding, padding);
            panel.setBackgroundColor(Ui.DARK); panel.setClickable(true);
            panel.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(padding, padding + insets.getSystemWindowInsetTop(), padding,
                        padding + insets.getSystemWindowInsetBottom());
                return insets;
            });
            panel.addView(Ui.moon(this), new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80)));
            TextView label = nightText("收工助手  /  NIGHT", 11, Ui.PALE);
            label.setLetterSpacing(.16f); label.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 24)); panel.addView(label);
            heading = nightText("今晚，好好休息。", 28, Color.WHITE); panel.addView(heading);
            panel.addView(nightText("让手机休息，也让自己休息。", 14, Ui.PALE));
            countdown = nightText("", 20, Color.WHITE);
            countdown.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 28)); panel.addView(countdown);
            panel.addView(nightText("普通应用已暂停 · 电话与闹钟保留", 12, Ui.PALE));
            LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
            Button phone = Ui.button(this, "电话", false);
            phone.setOnClickListener(view -> launchAllowed(allowed.phoneIntent()));
            Button clock = Ui.button(this, "闹钟", false);
            clock.setOnClickListener(view -> {
                if (!allowed.openClock(this)) Toast.makeText(this,
                        "未能打开系统时钟，请在白天重新检查闹钟兼容性。", Toast.LENGTH_LONG).show();
            });
            for (Button action : new Button[] {phone, clock}) {
                LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1);
                actionParams.topMargin = Ui.dp(this, 16);
                if (action == phone) actionParams.rightMargin = Ui.dp(this, 10);
                actions.addView(action, actionParams);
            }
            panel.addView(actions);
            Button emergency = Ui.button(this, "紧急拨号 · 仅打开拨号盘", false);
            emergency.setTextSize(13); emergency.setTextColor(Ui.PALE);
            android.graphics.drawable.GradientDrawable emergencyBackground = Ui.background(Ui.DARK, this, 14);
            emergencyBackground.setStroke(Ui.dp(this, 1), 0xFF526E60);
            emergency.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22CADBD0), emergencyBackground, Ui.background(Color.WHITE, this, 14)));
            emergency.setOnClickListener(view -> launchAllowed(allowed.emergencyIntent())); panel.addView(emergency);
            TextView note = nightText("紧急拨号预填 112，可编辑，不会自动呼叫。\n此时段不提供临时解除。", 11, Ui.PALE);
            note.setPadding(0, Ui.dp(this, 16), 0, 0); panel.addView(note);
            surface.addView(panel);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setTitle("收工助手 · 夜间限制");
            cover = surface;
            coverAddedAt = android.os.SystemClock.uptimeMillis();
            windows.addView(surface, params);
        }
        heading.setText(trial ? "60 秒兼容性试锁" : "今晚，好好休息。");
        long seconds = Math.max(0, Duration.between(Instant.now(), until).getSeconds());
        countdown.setText(trial ? String.format(Locale.CHINA, "%d 秒后自动结束试锁", seconds)
                : String.format(Locale.CHINA, "%s 自动恢复\n还有 %02d 小时 %02d 分 %02d 秒",
                        until.atZone(ZoneId.systemDefault()).toLocalTime().toString(),
                        seconds / 3600, seconds / 60 % 60, seconds % 60));
    }

    private TextView nightText(String text, int size, int color) {
        TextView view = Ui.text(this, text, size, color); view.setGravity(Gravity.CENTER); return view;
    }

    private void launchAllowed(Intent intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { Toast.makeText(this, "未能打开该系统界面，请在白天检查兼容性。", Toast.LENGTH_LONG).show(); }
    }

    private void hideCover() {
        if (cover == null) return;
        View old = cover; cover = null;
        heading = null; countdown = null;
        try { windows.removeView(old); }
        catch (IllegalArgumentException ignored) { /* Window already removed by Android. */ }
    }

    @Override public void onInterrupt() {
        hideCover();
        if (!stopped) {
            handler.removeCallbacks(wakeCheck);
            handler.postDelayed(wakeCheck, 150L);
        }
    }
    @Override public boolean onUnbind(Intent intent) {
        cleanup(); return super.onUnbind(intent);
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    private void cleanup() {
        stopped = true;
        android.util.Log.i("WindDown", "Night guard disconnected");
        handler.removeCallbacksAndMessages(null); nextTickAt = 0; hideCover();
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        if (current.get() == this) current = new WeakReference<>(null);
    }
}
