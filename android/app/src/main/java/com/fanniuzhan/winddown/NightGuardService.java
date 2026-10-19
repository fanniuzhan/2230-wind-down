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
import com.fanniuzhan.winddown.core.NightSchedule;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
    private String foreground;
    private long lastDiscovery;
    private boolean screenReceiverRegistered;
    private final Runnable tick = () -> evaluate(true);
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { evaluate(true); }
    };

    static boolean connected() { return current.get() != null; }
    static void refreshIfRunning() {
        NightGuardService service = current.get();
        if (service != null) service.handler.post(() -> service.evaluate(true));
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        store = new SettingsStore(this);
        allowed = new AllowedApps(this);
        lastDiscovery = android.os.SystemClock.elapsedRealtime();
        windows = getSystemService(WindowManager.class);
        current = new WeakReference<>(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        screenReceiverRegistered = true;
        BoundaryScheduler.update(this);
        evaluate(true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null) return;
        CharSequence owner = event.getPackageName();
        if (owner != null) foreground = owner.toString();
        evaluate(false);
    }

    private String activePackage() {
        // Inspect ownership only: never read node text, descriptions, children or input.
        List<AccessibilityWindowInfo> visible = getWindows();
        for (AccessibilityWindowInfo window : visible) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            CharSequence owner = root.getPackageName();
            String result = owner == null ? null : owner.toString();
            root.recycle();
            if (result != null) return result;
        }
        return foreground;
    }

    private void evaluate(boolean updateTimer) {
        if (store == null) return;
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
            boolean systemLocked = keyguard != null && keyguard.isKeyguardLocked();
            boolean ringing = audio != null && audio.getMode() == AudioManager.MODE_RINGTONE;
            if (!allowed.ready()) {
                hideCover();
                store.error("未能识别电话或闹钟组件，未开始拦截；请先完成本机兼容性检查。");
            } else if ((power == null || power.isInteractive()) && GuardPolicy.shouldCover(restricted,
                    systemLocked, ringing, activePackage(), allowed.phones, allowed.clocks)) {
                Instant until = active != null ? active.end : Instant.ofEpochMilli(store.trialUntil());
                showCover(until, trial && active == null);
                store.error("");
            } else {
                hideCover();
            }
            if (updateTimer) {
                handler.removeCallbacks(tick);
                long delay = restricted ? 1000L : schedule.enabled
                        ? Math.max(100L, Math.min(60_000L, Duration.between(now, schedule.nextTransition(now, ZoneId.systemDefault())).toMillis()))
                        : 60_000L;
                handler.postDelayed(tick, delay);
            }
        } catch (RuntimeException error) {
            hideCover();
            try { store.error("夜间拦截暂未生效：" + error.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { /* Storage may be unavailable; never block call UI on failure. */ }
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 5000L);
        }
    }

    private void showCover(Instant until, boolean trial) {
        if (cover == null) {
            ScrollView surface = new ScrollView(this);
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
            panel.addView(Ui.text(this, "CUSTOM WIND-DOWN  /  NIGHT", 12, 0xFFC6DAD4));
            heading = Ui.text(this, "今天就到这里。", 32, Color.WHITE); panel.addView(heading);
            panel.addView(Ui.text(this, "让手机休息，也让自己休息。", 16, 0xFFC6DAD4));
            countdown = Ui.text(this, "", 22, Color.WHITE);
            countdown.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 24)); panel.addView(countdown);
            panel.addView(Ui.text(this, "普通应用暂不可使用。电话与闹钟保留。\n期间不提供临时解除入口。", 14, 0xFFC6DAD4));
            Button phone = Ui.button(this, "电话", false);
            phone.setOnClickListener(view -> launchAllowed(allowed.phoneIntent())); panel.addView(phone);
            Button clock = Ui.button(this, "闹钟", false);
            clock.setOnClickListener(view -> launchAllowed(allowed.clockIntent())); panel.addView(clock);
            Button emergency = Ui.button(this, "紧急拨号 · 仅打开拨号盘", false);
            emergency.setOnClickListener(view -> launchAllowed(allowed.emergencyIntent())); panel.addView(emergency);
            panel.addView(Ui.text(this, "紧急拨号预填 112，可修改；不会自动呼叫。", 12, 0xFFC6DAD4));
            surface.addView(panel);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setTitle("收工助手 · 夜间限制");
            windows.addView(surface, params);
            cover = surface;
        }
        heading.setText(trial ? "60 秒兼容性试锁" : "今天就到这里。");
        long seconds = Math.max(0, Duration.between(Instant.now(), until).getSeconds());
        countdown.setText(trial ? String.format(Locale.CHINA, "%d 秒后自动结束试锁", seconds)
                : String.format(Locale.CHINA, "%s 自动恢复\n还有 %02d 小时 %02d 分 %02d 秒",
                        until.atZone(ZoneId.systemDefault()).toLocalTime().toString(),
                        seconds / 3600, seconds / 60 % 60, seconds % 60));
    }

    private void launchAllowed(Intent intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { Toast.makeText(this, "未能打开该系统界面，请在白天检查兼容性。", Toast.LENGTH_LONG).show(); }
    }

    private void hideCover() {
        if (cover == null) return;
        View old = cover; cover = null;
        try { windows.removeView(old); }
        catch (IllegalArgumentException ignored) { /* Window already removed by Android. */ }
    }

    @Override public void onInterrupt() { hideCover(); }
    @Override public boolean onUnbind(Intent intent) {
        cleanup(); return super.onUnbind(intent);
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    private void cleanup() {
        handler.removeCallbacksAndMessages(null); hideCover();
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        if (current.get() == this) current = new WeakReference<>(null);
    }
}
