package com.fanniuzhan.winddown;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
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
    private TextView morning;
    private TextView morningLabel;
    private TextView setupSummary;
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
        // Opening the page should show its heading, including with an emulator keyboard attached.
        page.setFocusableInTouchMode(true); page.requestFocus();
        int padding = Ui.dp(this, 20);
        page.setPadding(padding, padding, padding, padding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            // Keep scrolling content out of system bars, including while a night overlay is visible.
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView brand = Ui.text(this, "WIND-DOWN   /   夜间手记", 11, Ui.ACCENT);
        brand.setLetterSpacing(.14f); page.addView(brand);
        TextView title = Ui.title(this, "把夜晚，还给自己。"); title.setTextSize(28);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); page.addView(title);
        TextView subtitle = Ui.text(this, "在一天的尽头，慢慢安静下来。", 13, Ui.MUTED);
        subtitle.setPadding(0, 0, 0, Ui.dp(this, 14)); page.addView(subtitle);

        LinearLayout hero = Ui.card(this, Ui.DARK);
        hero.setBackground(Ui.nightScene(this));
        LinearLayout heroTop = row(); heroTop.setGravity(Gravity.CENTER_VERTICAL);
        heroTop.setMinimumHeight(Ui.dp(this, 58));
        TextView nightLabel = Ui.text(this, "一段留给休息的时间", 12, Ui.PALE);
        heroTop.addView(nightLabel, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(heroTop);
        LinearLayout times = row();
        times.setGravity(Gravity.CENTER_VERTICAL);
        if (getResources().getConfiguration().fontScale > 1.3f) times.setOrientation(LinearLayout.VERTICAL);
        period = Ui.text(this, "22:30", 36, Color.WHITE);
        morning = Ui.text(this, "06:30", 36, Color.WHITE);
        period.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        morning.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        addTimeColumn(times, "开始休息", period);
        if (times.getOrientation() == LinearLayout.HORIZONTAL) {
            TextView arrow = Ui.text(this, "·", 18, Ui.GOLD); arrow.setGravity(Gravity.CENTER);
            arrow.setPadding(0, Ui.dp(this, 20), 0, 0);
            times.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 30), -2));
        }
        morningLabel = addTimeColumn(times, "次日恢复", morning);
        hero.addView(times);
        View divider = new View(this); divider.setBackgroundColor(0xFF3B5C50);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        dividerParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 16)); hero.addView(divider, dividerParams);
        status = Ui.text(this, "计划未启用", 14, Color.WHITE); hero.addView(status);
        countdown = Ui.text(this, "完成本机检查后，即可开启。", 12, Ui.PALE); hero.addView(countdown);
        page.addView(hero);

        LinearLayout plan = Ui.card(this, Ui.PAPER);
        int planPadding = Ui.dp(this, 16); plan.setPadding(planPadding, planPadding, planPadding, planPadding);
        enabled = new Switch(this); enabled.setText("夜间计划"); enabled.setTextSize(17); enabled.setTextColor(Ui.INK);
        enabled.setTypeface(null, Typeface.BOLD); enabled.setMinHeight(Ui.dp(this, 48));
        enabled.setSwitchMinWidth(Ui.dp(this, 48));
        enabled.setContentDescription("启用夜间使用限制");
        enabled.setOnCheckedChangeListener(this::toggleEnabled); plan.addView(enabled);
        LinearLayout timeInputs = row();
        if (getResources().getConfiguration().fontScale > 1.3f) timeInputs.setOrientation(LinearLayout.VERTICAL);
        start = Ui.button(this, "开始  " + NightSchedule.formatMinute(startMinute), false);
        start.setOnClickListener(view -> chooseTime(true));
        end = Ui.button(this, "结束  " + NightSchedule.formatMinute(endMinute), false);
        end.setOnClickListener(view -> chooseTime(false));
        for (Button control : new Button[] {start, end}) {
            LinearLayout.LayoutParams params = timeInputs.getOrientation() == LinearLayout.HORIZONTAL
                    ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = Ui.dp(this, 8);
            if (control == start && timeInputs.getOrientation() == LinearLayout.HORIZONTAL) params.rightMargin = Ui.dp(this, 8);
            timeInputs.addView(control, params);
        }
        plan.addView(timeInputs);
        TextView repeat = Ui.text(this, "重复 · 选择开始的星期", 12, Ui.MUTED);
        repeat.setPadding(0, Ui.dp(this, 18), 0, 0); plan.addView(repeat);
        HorizontalScrollView dayScroll = new HorizontalScrollView(this);
        dayScroll.setHorizontalScrollBarEnabled(false); dayScroll.setFillViewport(true);
        LinearLayout weekdays = row(); weekdays.setMinimumWidth(Ui.dp(this, 336));
        LinearLayout.LayoutParams weekdayParams = new LinearLayout.LayoutParams(-1, -2);
        dayScroll.setLayoutParams(weekdayParams);
        String[] labels = { "日", "一", "二", "三", "四", "五", "六" };
        for (int index = 0; index < 7; index++) {
            int day = (index + 1) % 7;
            ToggleButton button = Ui.weekday(this, labels[day]);
            button.setChecked((schedule.daysMask & (1 << day)) != 0);
            button.setContentDescription("星期" + labels[day]);
            weekdays.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
            days[day] = button;
        }
        dayScroll.addView(weekdays); plan.addView(dayScroll);
        save = Ui.button(this, "保存计划", true); save.setOnClickListener(view -> saveSchedule()); plan.addView(save);
        page.addView(plan);

        LinearLayout setup = Ui.card(this, Ui.PAPER);
        LinearLayout setupHeader = row(); setupHeader.setGravity(Gravity.CENTER_VERTICAL);
        setupHeader.setMinimumHeight(Ui.dp(this, 56));
        LinearLayout setupLabels = Ui.column(this);
        setupLabels.addView(Ui.title(this, "本机检查"));
        setupSummary = Ui.text(this, "完成 3 步，即可开启", 12, Ui.MUTED); setupLabels.addView(setupSummary);
        setupHeader.addView(setupLabels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView disclosure = Ui.text(this, "展开  ﹀", 12, Ui.ACCENT);
        disclosure.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); setupHeader.addView(disclosure, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout details = Ui.column(this); details.setVisibility(View.GONE);
        setupHeader.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14276555), null, Ui.background(Color.WHITE, this, 8)));
        setupHeader.setFocusable(true); setupHeader.setContentDescription("本机检查，展开");
        setupHeader.setOnClickListener(view -> {
            boolean expand = details.getVisibility() != View.VISIBLE;
            details.setVisibility(expand ? View.VISIBLE : View.GONE);
            disclosure.setText(expand ? "收起  ⌃" : "展开  ﹀");
            setupHeader.setContentDescription(expand ? "本机检查，收起" : "本机检查，展开");
        });
        setup.addView(setupHeader);
        TextView introduction = Ui.text(this, "只需三步，确认这台手机能安心收工。", 12, Ui.MUTED);
        introduction.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8)); details.addView(introduction);
        LinearLayout permissionStep = Ui.step(this, "01", "开启服务", "授权后，才能在夜间覆盖普通应用。");
        capabilities = Ui.text(this, "", 12, Ui.ACCENT); permissionStep.addView(capabilities);
        permissions = Ui.button(this, "开启无障碍服务", true);
        permissions.setOnClickListener(view -> disclose()); permissionStep.addView(permissions);
        details.addView(permissionStep);
        LinearLayout trialStep = Ui.step(this, "02", "试试电话与闹钟", "准备另一部手机来电，并设一个即将响铃的闹钟。试锁会在 60 秒后自动结束。");
        trial = Ui.button(this, "准备 60 秒试锁", true);
        trial.setOnClickListener(view -> startTrial()); trialStep.addView(trial); details.addView(trialStep);
        LinearLayout confirmStep = Ui.step(this, "03", "确认结果", "试锁结束后，亲自确认：电话可接听，闹钟能响铃并关闭。确认后即可开启夜间计划。");
        verified = new CheckBox(this);
        verified.setText("我已确认来电与闹钟正常");
        verified.setTextColor(Ui.INK); verified.setTextSize(13); verified.setMinHeight(Ui.dp(this, 52));
        verified.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        verified.setBackground(Ui.background(Ui.SOFT, this, 12));
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
        }); confirmStep.addView(verified); details.addView(confirmStep);
        details.addView(Ui.text(this, "使用须知", 14, Ui.INK));
        details.addView(Ui.text(this, "按本地时间执行，跨午夜时归属开始的星期。限制期间无法在 App 内临时解除或修改计划，到结束时间自动恢复。", 12, Ui.MUTED));
        details.addView(Ui.text(this, "不修改音量、勿扰或锁屏密码。普通 App 可在系统中停用或卸载；重启、强行停止及 MagicOS 后台管理可能影响拦截。", 12, Ui.MUTED));
        setup.addView(details); page.addView(setup);
        error = Ui.text(this, "", 12, Ui.ACCENT); page.addView(error);
        TextView footnote = Ui.text(this, "晚安，明天见。\n仅存本机 · 无需联网", 12, Ui.MUTED);
        footnote.setGravity(Gravity.CENTER); footnote.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8)); page.addView(footnote);
        scroll.addView(page); setContentView(scroll); scroll.requestApplyInsets();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view;
    }

    private TextView addTimeColumn(LinearLayout parent, String label, TextView time) {
        LinearLayout column = Ui.column(this);
        TextView caption = Ui.text(this, label, 11, Ui.PALE);
        column.addView(caption); column.addView(time);
        parent.addView(column, parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
        return caption;
    }

    private void chooseTime(boolean beginning) {
        if (configurationLocked()) return;
        int value = beginning ? startMinute : endMinute;
        TimePickerDialog dialog = new TimePickerDialog(this, (picker, hour, minute) -> {
            if (configurationLocked()) return;
            if (beginning) { startMinute = hour * 60 + minute; start.setText(getString(R.string.start_time, NightSchedule.formatMinute(startMinute))); }
            else { endMinute = hour * 60 + minute; end.setText(getString(R.string.end_time, NightSchedule.formatMinute(endMinute))); }
        }, value / 60, value % 60, true);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("确定");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("取消");
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
                .setMessage("为了在夜间拦截普通应用、放行电话和闹钟，本应用需要你手动开启无障碍服务。\n\n系统会授予窗口访问能力；代码仅检查应用包名及系统提供的来电、闹铃播放状态，不读取或保存屏幕文字、描述、输入或按键，不录音，不联网。\n\n本功能不是面向残障人士的辅助工具。系统仍允许停用或卸载普通 App，无法保证绝对不可绕过。\n\n同意后将打开系统设置，请自行选择“收工助手 · 夜间使用限制”；此操作不会自动开启夜间计划。")
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
        LinearLayout preparation = Ui.column(this);
        int padding = Ui.dp(this, 24); preparation.setPadding(padding, Ui.dp(this, 8), padding, 0);
        preparation.addView(Ui.text(this, "闹钟 · 约一分钟后响铃", 15, Ui.INK));
        preparation.addView(Ui.text(this, "先设置好闹钟，再回来开始试锁。若按钮打不开，可从手机桌面打开“时钟”。", 12, Ui.MUTED));
        Button clock = Ui.button(this, "打开系统闹钟", false);
        clock.setOnClickListener(view -> {
            if (!allowed.openClock(this)) toast("未能打开系统时钟，请从手机桌面打开“时钟”设置闹钟，再返回试锁。");
        }); preparation.addView(clock);
        TextView phone = Ui.text(this, "来电 · 准备另一部手机", 15, Ui.INK);
        phone.setPadding(0, Ui.dp(this, 20), 0, 0); preparation.addView(phone);
        preparation.addView(Ui.text(this, "让对方在试锁期间拨入，检查接听与挂断。", 12, Ui.MUTED));
        preparation.addView(Ui.text(this, "60 秒仅做测试，熄屏期间也计时，不会开启整晚计划。电话、闹钟和紧急呼叫保留，其余应用被覆盖；原有正式计划会关闭，确认兼容后需重新启用。", 12, Ui.MUTED));
        ScrollView preparationScroll = new ScrollView(this); preparationScroll.addView(preparation);
        new AlertDialog.Builder(this).setTitle("先准备，再试锁")
                .setView(preparationScroll)
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
        period.setText(NightSchedule.formatMinute(schedule.startMinute));
        morning.setText(NightSchedule.formatMinute(schedule.endMinute));
        morningLabel.setText(schedule.endMinute < schedule.startMinute ? "次日恢复" : "恢复使用");
        if (store.isTrialActive(now.toEpochMilli())) {
            status.setText("兼容性试锁中");
            countdown.setText(getString(R.string.trial_seconds, Math.max(0, (store.trialUntil() - now.toEpochMilli()) / 1000)));
        } else if (!schedule.enabled) {
            status.setText("计划未启用");
            countdown.setText(store.compatibilityConfirmed() ? "开启夜间计划，让休息准时开始。"
                    : store.trialCompleted() ? "60 秒试锁已结束；确认兼容后，还需开启夜间计划。" : "完成本机检查后，即可开启。");
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
        setupSummary.setText(!connected ? "待完成 · 开启服务与兼容性试锁" : !store.trialCompleted()
                ? "待完成 · 60 秒兼容性试锁" : !store.compatibilityConfirmed() ? "待完成 · 确认电话与闹钟兼容" : "已完成 · 电话与闹钟兼容性已确认");
        error.setText(store.lastError());
        error.setVisibility(store.lastError().isEmpty() ? View.GONE : View.VISIBLE);
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
