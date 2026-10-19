package com.fanniuzhan.winddown;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import com.fanniuzhan.winddown.core.NightSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class MainActivity extends Activity {
    private SettingsStore store;
    private AllowedApps allowed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };
    private TextView status;
    private TextView period;
    private TextView countdown;
    private TextView capabilities;
    private TextView error;
    private Button start;
    private Button end;
    private Button save;
    private Button permissions;
    private Button trial;
    private Switch enabled;
    private CheckBox verified;
    private final ToggleButton[] days = new ToggleButton[7];
    private int startMinute;
    private int endMinute;
    private boolean binding;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this); allowed = new AllowedApps(this);
        NightSchedule schedule = store.schedule();
        startMinute = schedule.startMinute; endMinute = schedule.endMinute;
        build(schedule);
        refresh();
    }

    private void build(NightSchedule schedule) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.CANVAS);
        LinearLayout page = Ui.column(this);
        int padding = Ui.dp(this, 22);
        page.setPadding(padding, padding, padding, padding);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding, padding + insets.getSystemWindowInsetTop(), padding,
                    padding + insets.getSystemWindowInsetBottom()); return insets;
        });
        page.addView(Ui.text(this, "◒  CUSTOM WIND-DOWN", 12, Ui.ACCENT));
        TextView title = Ui.title(this, "今晚，把手机放下。"); title.setTextSize(28); page.addView(title);
        page.addView(Ui.text(this, "给休息留一整夜。", 14, Ui.MUTED));
        LinearLayout hero = Ui.card(this, Ui.DARK);
        hero.addView(Ui.text(this, "NIGHT ROUTINE  /  夜间使用限制", 11, 0xFFC6DAD4));
        period = Ui.text(this, "22:30 → 06:30", 31, Color.WHITE); hero.addView(period);
        status = Ui.text(this, "尚未启用", 16, Color.WHITE); hero.addView(status);
        countdown = Ui.text(this, "首次安装默认关闭", 13, 0xFFC6DAD4); hero.addView(countdown);
        page.addView(hero);

        LinearLayout plan = Ui.card(this, Color.WHITE);
        plan.addView(Ui.title(this, "夜间计划"));
        plan.addView(Ui.text(this, "按本地时间执行，星期指开始限制的那一天。", 12, Ui.MUTED));
        start = Ui.button(this, "开始  " + NightSchedule.formatMinute(startMinute), false);
        start.setOnClickListener(view -> chooseTime(true)); plan.addView(start);
        end = Ui.button(this, "结束  " + NightSchedule.formatMinute(endMinute), false);
        end.setOnClickListener(view -> chooseTime(false)); plan.addView(end);
        LinearLayout weekdays = new LinearLayout(this);
        weekdays.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams weekdayParams = new LinearLayout.LayoutParams(-1, -2);
        weekdayParams.topMargin = Ui.dp(this, 12); weekdays.setLayoutParams(weekdayParams);
        String[] labels = { "日", "一", "二", "三", "四", "五", "六" };
        for (int index = 0; index < 7; index++) {
            int day = (index + 1) % 7;
            ToggleButton button = new ToggleButton(this);
            button.setTextOn(labels[day]); button.setTextOff(labels[day]);
            button.setChecked((schedule.daysMask & (1 << day)) != 0);
            button.setContentDescription("星期" + labels[day]);
            button.setTextSize(12); button.setMinWidth(0); button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            weekdays.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
            days[day] = button;
        }
        plan.addView(weekdays);
        enabled = new Switch(this); enabled.setText("启用夜间使用限制"); enabled.setTextSize(16);
        enabled.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 10));
        enabled.setOnCheckedChangeListener(this::toggleEnabled); plan.addView(enabled);
        save = Ui.button(this, "保存时间与星期", true); save.setOnClickListener(view -> saveSchedule()); plan.addView(save);
        plan.addView(Ui.text(this, "限制期间没有临时解除入口，计划只能在限制时段以外修改。", 12, Ui.MUTED));
        page.addView(plan);

        LinearLayout setup = Ui.card(this, Color.WHITE);
        setup.addView(Ui.title(this, "首次使用 · 本机检查"));
        setup.addView(Ui.text(this, "针对荣耀 MagicOS 10 / Android 16 设计；电话和闹钟仍需在本机实测。", 12, Ui.MUTED));
        capabilities = Ui.text(this, "", 13, Ui.ACCENT); setup.addView(capabilities);
        permissions = Ui.button(this, "1. 了解并开启无障碍服务", false);
        permissions.setOnClickListener(view -> disclose()); setup.addView(permissions);
        Button clock = Ui.button(this, "先打开闹钟，设置试锁期间响铃", false);
        clock.setOnClickListener(view -> open(allowed.clockIntent())); setup.addView(clock);
        trial = Ui.button(this, "2. 开始 60 秒兼容性试锁", false);
        trial.setOnClickListener(view -> startTrial()); setup.addView(trial);
        verified = new CheckBox(this);
        verified.setText("3. 已试锁，并确认来电能接听、闹钟能响铃和关闭");
        verified.setTextSize(13); verified.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        verified.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            if (!store.trialCompleted()) {
                binding = true; verified.setChecked(false); binding = false;
                toast("先完成 60 秒兼容性试锁，再确认实际结果。"); return;
            }
            try {
                store.confirmCompatibility(checked);
                BoundaryScheduler.update(this); NightGuardService.refreshIfRunning(); refresh();
            }
            catch (IllegalStateException exception) { toast(exception.getMessage()); }
        }); setup.addView(verified);
        setup.addView(Ui.text(this, "试锁会自动结束。请用另一部手机拨入，并让预先设置的闹钟在试锁期间响铃。确认两项正常后，再开启正式计划。", 12, Ui.MUTED));
        page.addView(setup);

        LinearLayout facts = Ui.card(this, 0xFFE9EDE5);
        facts.addView(Ui.title(this, "保留需要的，停下其余的"));
        facts.addView(Ui.text(this, "电话、闹钟和系统紧急呼叫保留；不修改音量、勿扰或系统锁屏密码。到结束时间自动恢复。", 13, Ui.INK));
        facts.addView(Ui.text(this, "机主仍可在 Android 系统中停用或卸载普通应用。重启、强行停止和 MagicOS 后台管理可能影响拦截，请按本机测试结果使用。", 12, Ui.MUTED));
        error = Ui.text(this, "", 12, Ui.ACCENT); facts.addView(error);
        page.addView(facts);
        page.addView(Ui.text(this, "仅保存在本机 · 不联网 · 不读取屏幕文字或输入", 11, Ui.MUTED));
        scroll.addView(page); setContentView(scroll); page.requestApplyInsets();
    }

    private void chooseTime(boolean beginning) {
        if (configurationLocked()) return;
        int value = beginning ? startMinute : endMinute;
        new TimePickerDialog(this, (picker, hour, minute) -> {
            if (configurationLocked()) return;
            if (beginning) { startMinute = hour * 60 + minute; start.setText(getString(R.string.start_time, NightSchedule.formatMinute(startMinute))); }
            else { endMinute = hour * 60 + minute; end.setText(getString(R.string.end_time, NightSchedule.formatMinute(endMinute))); }
        }, value / 60, value % 60, true).show();
    }

    private NightSchedule edited(boolean enable) {
        int mask = 0;
        for (int day = 0; day < 7; day++) if (days[day].isChecked()) mask |= 1 << day;
        return new NightSchedule(enable, startMinute, endMinute, mask);
    }

    private void saveSchedule() {
        if (configurationLocked()) { toast("夜间限制期间不能修改计划。"); return; }
        try {
            NightSchedule next = edited(store.schedule().enabled);
            if (next.activeWindow(Instant.now(), ZoneId.systemDefault()) != null) confirmEnable(next);
            else commit(next);
        } catch (IllegalArgumentException error) { toast(error.getMessage()); }
    }

    private void toggleEnabled(CompoundButton button, boolean checked) {
        if (binding) return;
        binding = true; enabled.setChecked(store.schedule().enabled); binding = false;
        if (configurationLocked()) { toast("此时段不提供临时解除，请等待结束时间。"); return; }
        if (!checked) {
            NightSchedule old = store.schedule();
            commit(new NightSchedule(false, old.startMinute, old.endMinute, old.daysMask)); return;
        }
        if (!NightGuardService.connected()) { toast("请先手动开启无障碍服务，并返回等待连接。"); return; }
        allowed = new AllowedApps(this);
        if (!allowed.ready()) { toast("尚未识别到电话或系统闹钟，不能启用。"); return; }
        if (!store.compatibilityConfirmed()) { toast("请先完成试锁，确认电话和闹钟兼容性。"); return; }
        try { confirmEnable(edited(true)); }
        catch (IllegalArgumentException error) { toast(error.getMessage()); }
    }

    private void confirmEnable(NightSchedule schedule) {
        new AlertDialog.Builder(this).setTitle("确认启用夜间使用限制")
                .setMessage(NightSchedule.formatMinute(schedule.startMinute) + " 至 "
                        + NightSchedule.formatMinute(schedule.endMinute)
                        + "，普通应用不可使用。电话、闹钟和系统紧急呼叫保留。期间没有 App 内临时解除入口。\n\n若当前已在该时段，将立即开始限制。")
                .setNegativeButton("取消", null).setPositiveButton("确认启用", (dialog, which) -> {
                    // A dialog opened before the boundary cannot modify an already-active plan.
                    if (!configurationLocked()) commit(schedule);
                }).show();
    }

    private void commit(NightSchedule schedule) {
        try {
            if (schedule.enabled && (!store.disclosureAccepted() || !store.compatibilityConfirmed()
                    || !NightGuardService.connected() || !new AllowedApps(this).ready())) {
                toast("请先完成无障碍授权与电话、闹钟兼容性确认。"); return;
            }
            store.save(schedule); store.error("");
            BoundaryScheduler.update(this); NightGuardService.refreshIfRunning(); refresh();
            toast("计划已保存。");
        } catch (RuntimeException exception) { toast("未能可靠保存计划：" + exception.getMessage()); }
    }

    private void disclose() {
        if (configurationLocked()) return;
        new AlertDialog.Builder(this).setTitle("无障碍服务用途与访问范围")
                .setMessage("为了在夜间拦截普通应用、放行电话和闹钟，本应用需要你手动开启无障碍服务。\n\n系统会授予窗口访问能力；代码仅检查应用包名，不读取或保存屏幕文字、描述、输入或按键，不联网。\n\n本功能不是面向残障人士的辅助工具。系统仍允许停用或卸载普通 App，无法保证绝对不可绕过。\n\n同意后将打开系统设置，请自行选择“收工助手 · 夜间使用限制”；此操作不会自动开启夜间计划。")
                .setNegativeButton("暂不开启", null).setPositiveButton("了解并前往设置", (dialog, which) -> {
                    try { store.acceptDisclosure(); open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (RuntimeException error) { toast(error.getMessage()); }
                }).show();
    }

    private void startTrial() {
        if (configurationLocked()) return;
        if (!store.disclosureAccepted() || !NightGuardService.connected()) { toast("请先了解并手动开启无障碍服务。"); return; }
        allowed = new AllowedApps(this);
        if (!allowed.ready()) { toast("没有识别到电话或系统闹钟，暂不试锁。"); return; }
        new AlertDialog.Builder(this).setTitle("准备好进行 60 秒试锁？")
                .setMessage("先设置一分钟内响铃的闹钟，并准备让另一部手机拨入。试锁中可打开电话、闹钟和系统紧急呼叫；其他应用会被覆盖。60 秒后自动结束。\n\n试锁会关闭原有正式计划，确认兼容后需重新启用。试锁不能代替你亲自确认接听、响铃和关闭闹钟是否正常。")
                .setNegativeButton("还没准备好", null).setPositiveButton("开始试锁", (dialog, which) -> {
                    if (configurationLocked()) return;
                    try { store.beginTrial(System.currentTimeMillis()); BoundaryScheduler.update(this); NightGuardService.refreshIfRunning(); refresh(); }
                    catch (RuntimeException exception) { toast(exception.getMessage()); }
                }).show();
    }

    private boolean configurationLocked() {
        return store.isTrialActive(System.currentTimeMillis())
                || store.schedule().activeWindow(Instant.now(), ZoneId.systemDefault()) != null;
    }

    private void refresh() {
        Instant now = Instant.now();
        try { store.finishTrialIfDue(now.toEpochMilli()); }
        catch (IllegalStateException exception) { error.setText(exception.getMessage()); }
        NightSchedule schedule = store.schedule();
        NightSchedule.Window active = schedule.activeWindow(now, ZoneId.systemDefault());
        boolean locked = configurationLocked();
        boolean connected = NightGuardService.connected();
        binding = true;
        enabled.setChecked(schedule.enabled); verified.setChecked(store.compatibilityConfirmed());
        binding = false;
        period.setText(getString(R.string.time_period, NightSchedule.formatMinute(schedule.startMinute), NightSchedule.formatMinute(schedule.endMinute)));
        if (store.isTrialActive(now.toEpochMilli())) {
            status.setText("兼容性试锁中");
            countdown.setText(getString(R.string.trial_seconds, Math.max(0, (store.trialUntil() - now.toEpochMilli()) / 1000)));
        } else if (!schedule.enabled) {
            status.setText("计划未启用"); countdown.setText("完成本机检查后，再主动开启。");
        } else if (!connected || !allowed.ready()) {
            status.setText("计划已保存 · 拦截未就绪");
            countdown.setText(!connected ? "无障碍服务未连接，普通应用目前无法被拦截。" : "电话或闹钟组件未就绪。");
        } else if (active != null) {
            status.setText("夜间限制中");
            long seconds = Math.max(0, Duration.between(now, active.end).getSeconds());
            countdown.setText(String.format(Locale.CHINA, "还有 %02d 小时 %02d 分结束", seconds / 3600, seconds / 60 % 60));
        } else {
            status.setText("已就绪 · 等待夜间时段");
            countdown.setText(getString(R.string.next_start, schedule.nextWindow(now, ZoneId.systemDefault()).start
                    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))));
        }
        capabilities.setText(getString(R.string.detected_apps, connected ? "● 无障碍服务已连接" : "○ 无障碍服务未连接",
                allowed.phones.size(), allowed.clocks.size()));
        error.setText(store.lastError());
        for (View control : new View[] { start, end, save, enabled, permissions, verified }) control.setEnabled(!locked);
        for (ToggleButton day : days) day.setEnabled(!locked);
        trial.setEnabled(!locked && connected && allowed.ready());
    }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException exception) { toast("该系统入口不可用，请在手机设置中查看。"); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() {
        super.onResume(); allowed = new AllowedApps(this);
        handler.removeCallbacks(ticker); handler.post(ticker);
        NightGuardService.refreshIfRunning(); BoundaryScheduler.update(this);
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
}
