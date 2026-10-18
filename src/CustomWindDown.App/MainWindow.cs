using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CustomWindDown.Core;
using Microsoft.Win32;
using Forms = System.Windows.Forms;

namespace CustomWindDown.App;

internal sealed class MainWindow : Window
{
    private readonly StateStore _store;
    private readonly ScheduleEngine _engine;
    private readonly ShutdownCoordinator _coordinator;
    private readonly Forms.NotifyIcon _tray;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly TextBlock _status = Ui.Text("", 13);
    private readonly TextBlock _nextTime = Ui.Text("", 62, Brushes.White);
    private readonly TextBlock _nextDate = Ui.Text("", 14, Ui.Brush("#C6DAD4"));
    private readonly TextBlock _countdown = Ui.Text("", 17, Brushes.White);
    private readonly TextBlock _history = Ui.Text("", 13, Ui.Muted);
    private readonly TextBlock _notice = Ui.Text("", 12, Ui.Accent);
    private readonly TextBlock _saveHint = Ui.Text("", 12, Ui.Accent);
    private readonly CheckBox _enabled = new() { Content = "在这台电脑启用定时关机" };
    private readonly CheckBox _startup = new() { Content = "随 Windows 登录启动" };
    private readonly TextBox _time = new() { Text = "22:30", FontSize = 28, Width = 132,
        Padding = new Thickness(10, 5, 10, 5), HorizontalAlignment = HorizontalAlignment.Left };
    private readonly CheckBox[] _days = new CheckBox[7];
    private readonly Button _snooze = Ui.Button("延后 15 分钟");
    private readonly Button _skip = Ui.Button("跳过本次");
    private ReminderWindow? _reminder;
    private ReminderWindow? _preview;
    private bool _quitting;
    private bool _refreshing;
    private volatile bool _suspended;
    private volatile bool _clockChanged;
    private long _lastTickStamp;
    public bool HasStartupWarning { get; }

    public MainWindow()
    {
        Title = "Custom Wind-down · 收工助手";
        Width = 1040; Height = 820; MinWidth = 940; MinHeight = 740;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = Ui.Canvas; FontFamily = new FontFamily("Microsoft YaHei UI"); FontSize = 14;
        Icon = new BitmapImage(new Uri("pack://application:,,,/Assets/app.ico"));
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CustomWindDown", "settings.json");
        _store = new StateStore(path, Environment.MachineName);
        var loaded = _store.Load();
        HasStartupWarning = loaded.Warning is not null;
        _engine = new ScheduleEngine(loaded.State, TimeZoneInfo.Local);
        _coordinator = new ShutdownCoordinator(_engine, _store.Save, new WindowsShutdownService());
        _engine.Initialize(DateTimeOffset.Now);

        BuildContent();
        LoadControls();
        _notice.Text = loaded.Warning ?? "只作用于当前电脑；不会连接或控制工位电脑。";
        _tray = new Forms.NotifyIcon
        {
            Text = "Custom Wind-down · 收工助手", Visible = true,
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application
        };
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开收工助手", null, (_, _) => Dispatcher.Invoke(ShowMain));
        menu.Items.Add("跳过本次", null, (_, _) => Dispatcher.Invoke(Skip));
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("彻底退出（停止计时）", null, (_, _) => Dispatcher.Invoke(Quit));
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowMain);
        Closing += OnClosing;
        SystemEvents.PowerModeChanged += PowerChanged;
        SystemEvents.TimeChanged += TimeChanged;
        Application.Current.Exit += (_, _) => Cleanup();
        Persist();
        Refresh();
        _timer.Tick += (_, _) => Tick();
        _lastTickStamp = Stopwatch.GetTimestamp();
        _timer.Start();
    }

    private void BuildContent()
    {
        var page = new StackPanel { Margin = new Thickness(32, 24, 32, 24) };
        var header = new Grid { Margin = new Thickness(0, 0, 0, 22) };
        header.ColumnDefinitions.Add(new ColumnDefinition());
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var brand = new StackPanel();
        brand.Children.Add(Ui.Text("◒  CUSTOM WIND-DOWN", 12, Ui.Accent));
        var title = Ui.Text("今天，也要准时收工。", 28); title.FontWeight = FontWeights.SemiBold;
        brand.Children.Add(title);
        brand.Children.Add(Ui.Text("为休息留一点时间。", 13, Ui.Muted));
        header.Children.Add(brand);
        _status.Padding = new Thickness(14, 8, 14, 8);
        var badge = Ui.Card(_status, "#E4EBE2", new Thickness(0));
        badge.VerticalAlignment = VerticalAlignment.Top; badge.Margin = new Thickness(0, 10, 0, 0);
        Grid.SetColumn(badge, 1); header.Children.Add(badge); page.Children.Add(header);

        var columns = new Grid();
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.5, GridUnitType.Star) });
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(20) });
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var left = new StackPanel();
        var hero = new StackPanel();
        hero.Children.Add(Ui.Text("NEXT WIND-DOWN  /  下次收工", 11, Ui.Brush("#C6DAD4")));
        _nextTime.FontWeight = FontWeights.Light; _nextTime.Margin = new Thickness(0, 8, 0, 0);
        hero.Children.Add(_nextTime); hero.Children.Add(_nextDate);
        _countdown.Margin = new Thickness(0, 18, 0, 0); hero.Children.Add(_countdown);
        var heroCard = Ui.Card(hero, "#193F3B");
        heroCard.MinHeight = 242; heroCard.Margin = new Thickness(0, 0, 0, 18); left.Children.Add(heroCard);

        var settings = new StackPanel();
        settings.Children.Add(Ui.Text("收工计划", 18));
        settings.Children.Add(Ui.Text("本地时间 · 24 小时制", 12, Ui.Muted));
        _time.Margin = new Thickness(0, 2, 0, 16);
        AutomationProperties.SetName(_time, "收工时间 HH:mm");
        settings.Children.Add(_time);
        var weekdays = new WrapPanel { Margin = new Thickness(0, 0, 0, 18) };
        string[] names = ["日", "一", "二", "三", "四", "五", "六"];
        for (var index = 0; index < 7; index++)
        {
            var day = (index + 1) % 7;
            _days[day] = new CheckBox { Content = names[day], IsChecked = true,
                Margin = new Thickness(0, 5, 12, 5), VerticalContentAlignment = VerticalAlignment.Center };
            AutomationProperties.SetName(_days[day], "周" + names[day]);
            weekdays.Children.Add(_days[day]);
        }
        settings.Children.Add(weekdays);
        _enabled.Margin = new Thickness(0, 0, 0, 16);
        _enabled.Click += (_, _) =>
        {
            if (_enabled.IsChecked != false || !_engine.State.Settings.Enabled) return;
            var previous = _engine.State.Settings;
            _engine.UpdatePlan(new ScheduleSettings { Time = previous.Time, Days = previous.Days,
                AuthorizedMachine = previous.AuthorizedMachine }, DateTimeOffset.Now);
            CloseReminder();
            if (Persist()) _saveHint.Text = "定时关机已立即关闭。";
            Refresh();
        };
        settings.Children.Add(_enabled);
        var save = Ui.Button("保存计划", true); save.HorizontalAlignment = HorizontalAlignment.Left;
        save.Click += (_, _) => SavePlan(); settings.Children.Add(save);
        _saveHint.Margin = new Thickness(0, 10, 0, 0); settings.Children.Add(_saveHint);
        left.Children.Add(Ui.Card(settings));
        columns.Children.Add(left);

        var right = new StackPanel();
        var device = new StackPanel();
        device.Children.Add(Ui.Text("仅此设备", 18));
        var machine = Ui.Text(Environment.MachineName, 17); machine.FontWeight = FontWeights.SemiBold;
        machine.Margin = new Thickness(0, 8, 0, 10); device.Children.Add(machine);
        _notice.Margin = new Thickness(0, 0, 0, 18); device.Children.Add(_notice);
        _startup.Margin = new Thickness(0, 0, 0, 8);
        _startup.Click += (_, _) => ChangeStartup(); device.Children.Add(_startup);
        device.Children.Add(Ui.Text("关闭窗口后继续在托盘计时。", 12, Ui.Muted));
        var deviceCard = Ui.Card(device); deviceCard.Margin = new Thickness(0, 0, 0, 18); right.Children.Add(deviceCard);

        var actions = new StackPanel();
        actions.Children.Add(Ui.Text("给自己一点余地", 18));
        actions.Children.Add(Ui.Text("延后或跳过只影响本次收工。", 12, Ui.Muted));
        foreach (var button in new[] { _snooze, _skip })
        {
            button.HorizontalAlignment = HorizontalAlignment.Stretch;
            button.Margin = new Thickness(0, 4, 0, 7); actions.Children.Add(button);
        }
        _snooze.Click += (_, _) => Snooze(); _skip.Click += (_, _) => Skip();
        var preview = Ui.Button("预览提醒 · 不会关机");
        preview.Margin = new Thickness(0, 12, 0, 0); preview.Click += (_, _) => Preview(); actions.Children.Add(preview);
        var actionCard = Ui.Card(actions); actionCard.Margin = new Thickness(0, 0, 0, 18); right.Children.Add(actionCard);
        var log = new StackPanel(); log.Children.Add(Ui.Text("最近动态", 14)); log.Children.Add(_history);
        right.Children.Add(Ui.Card(log, "#E9EDE5", new Thickness(20)));
        Grid.SetColumn(right, 2); columns.Children.Add(right); page.Children.Add(columns);

        var footer = new DockPanel { Margin = new Thickness(0, 16, 0, 0) };
        var quit = Ui.Button("彻底退出");
        quit.MinHeight = 24; quit.Padding = new Thickness(8, 3, 8, 3); quit.FontSize = 12;
        quit.Margin = new Thickness(10, 0, 0, 0);
        quit.ToolTip = "停止计时并退出助手";
        quit.Click += (_, _) => Quit();
        DockPanel.SetDock(quit, Dock.Right); footer.Children.Add(quit);
        var footerText = Ui.Text("提前 10 分钟提醒  ·  正常关机，未保存工作可能阻止  ·  错过不补执行", 12, Ui.Muted);
        footerText.VerticalAlignment = VerticalAlignment.Center; footerText.Margin = new Thickness(0);
        footer.Children.Add(footerText); page.Children.Add(footer);
        Content = new ScrollViewer { Content = page, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    private void LoadControls()
    {
        _refreshing = true;
        var settings = _engine.State.Settings;
        _time.Text = settings.Time;
        _enabled.IsChecked = settings.Enabled;
        for (var day = 0; day < 7; day++) _days[day].IsChecked = (settings.Days & (1 << day)) != 0;
        try { _startup.IsChecked = LoginStartup.IsEnabled(); }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        { _startup.IsChecked = false; _notice.Text = "无法读取登录启动设置。"; }
        _refreshing = false;
    }

    private void SavePlan()
    {
        var settings = new ScheduleSettings
        {
            Time = _time.Text.Trim(), Enabled = _enabled.IsChecked == true,
            Days = Enumerable.Range(0, 7).Where(d => _days[d].IsChecked == true).Sum(d => 1 << d),
            AuthorizedMachine = Environment.MachineName
        };
        if (!settings.IsValid())
        { _saveHint.Text = "请输入 HH:mm 格式的时间，并选择至少一个执行日期。"; return; }
        if (settings.Enabled && !_engine.State.Settings.Enabled && MessageBox.Show(this,
            $"仅在当前电脑 {Environment.MachineName} 启用定时关机？\n\n到时将请求正常关机，请提前保存工作。\n其他电脑不会受到影响。",
            "确认在本机启用", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes)
        { _enabled.IsChecked = false; return; }
        CloseReminder();
        _engine.UpdatePlan(settings, DateTimeOffset.Now);
        if (Persist()) _saveHint.Text = "已保存，即刻生效。";
        _enabled.IsChecked = _engine.State.Settings.Enabled;
        Refresh();
    }

    private void ChangeStartup()
    {
        if (_refreshing) return;
        try { LoginStartup.SetEnabled(_startup.IsChecked == true); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.SecurityException or InvalidOperationException)
        {
            _refreshing = true; _startup.IsChecked = !_startup.IsChecked; _refreshing = false;
            _notice.Text = "无法修改登录启动设置：" + e.Message;
        }
    }

    private void Tick()
    {
        if (_quitting || _suspended || _clockChanged) return;
        try
        {
            var stamp = Stopwatch.GetTimestamp();
            var elapsed = Stopwatch.GetElapsedTime(_lastTickStamp, stamp);
            _lastTickStamp = stamp;
            var result = _coordinator.Tick(DateTimeOffset.Now, elapsed);
            if (result.RequestShutdown || _engine.State.Current?.ReminderShown != true) CloseReminder();
            if (result.ShowReminder && _engine.State.Current is { } current) ShowReminder(current.TargetAt);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException)
        { DisableOnError(e); }
        Refresh();
    }

    private bool Persist()
    {
        try { _store.Save(_engine.State); return true; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        { DisableOnError(e); return false; }
    }

    private void DisableOnError(Exception error)
    {
        _engine.State.Settings.Enabled = false; _engine.State.Current = null;
        _enabled.IsChecked = false; CloseReminder();
        _notice.Text = "计划已停止：无法可靠保存或执行。请检查文件权限后重新保存。";
        _engine.State.LastEvent = "已停止计时：" + error.Message;
        _saveHint.Text = "未启用，需重新保存计划。";
    }

    private void Refresh()
    {
        var active = _engine.State.Settings.Enabled;
        _status.Text = active ? "●  计划已启用" : "○  计划未启用";
        var current = _engine.State.Current;
        if (active && current is { Status: OccurrenceStatus.Pending })
        {
            var local = current.TargetAt.ToLocalTime();
            _nextTime.Text = local.ToString("HH:mm");
            _nextDate.Text = local.ToString("M 月 d 日  dddd", System.Globalization.CultureInfo.GetCultureInfo("zh-CN"))
                + (current.TargetAt != current.ScheduledAt ? " · 已延后" : " · 本地时间");
            var remaining = current.TargetAt - DateTimeOffset.Now;
            _countdown.Text = remaining > TimeSpan.Zero ? $"还有  {(int)remaining.TotalHours:00} 小时 {remaining.Minutes:00} 分 {remaining.Seconds:00} 秒" : "正在处理本次计划…";
        }
        else
        {
            _nextTime.Text = active ? "已请求" : "— — : — —";
            _nextDate.Text = active ? "等待 Windows 处理" : "设置时间，准备好后再启用";
            _countdown.Text = active ? "不会强制结束应用" : "当前不会执行关机";
        }
        _snooze.IsEnabled = _skip.IsEnabled = active && current is { Status: OccurrenceStatus.Pending };
        _history.Text = _engine.State.LastEvent;
    }

    private void Snooze()
    {
        if (_engine.Snooze(DateTimeOffset.Now)) { CloseReminder(); Persist(); Refresh(); }
    }

    private void Skip()
    {
        if (_engine.Skip(DateTimeOffset.Now)) { CloseReminder(); Persist(); Refresh(); }
    }

    private void ShowReminder(DateTimeOffset target)
    {
        if (_reminder is not null) { _reminder.Activate(); return; }
        _reminder = new ReminderWindow(false, target, Snooze, Skip);
        _reminder.Closed += (_, _) => _reminder = null;
        _reminder.Show();
    }

    private void CloseReminder() { _reminder?.Close(); }

    private void Preview()
    {
        if (_preview is not null) { _preview.Activate(); return; }
        _preview = new ReminderWindow(true, DateTimeOffset.Now.AddMinutes(10), () => { }, () => { });
        _preview.Closed += (_, _) => _preview = null;
        _preview.Show();
    }

    private void PowerChanged(object sender, PowerModeChangedEventArgs e)
    {
        // Pause immediately on the system-event thread, before dispatcher timers can run.
        if (e.Mode == PowerModes.Suspend) _suspended = true;
        else if (e.Mode == PowerModes.Resume) Dispatcher.BeginInvoke(new Action(() =>
        {
            Reconcile();
            _suspended = false;
        }));
    }

    private void TimeChanged(object? sender, EventArgs e)
    {
        _clockChanged = true;
        Dispatcher.BeginInvoke(new Action(() => { Reconcile(); _clockChanged = false; }));
    }

    private void Reconcile()
    {
        if (_quitting) return;
        TimeZoneInfo.ClearCachedData();
        _engine.Reconcile(DateTimeOffset.Now, TimeZoneInfo.Local);
        _lastTickStamp = Stopwatch.GetTimestamp();
        CloseReminder(); Persist(); Refresh();
    }

    public void ShowMain()
    {
        Show(); if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_quitting) return;
        e.Cancel = true; Hide();
        _tray.ShowBalloonTip(3500, "收工助手仍在运行", "在托盘中右键选择“彻底退出”可停止计时。", Forms.ToolTipIcon.Info);
    }

    private void Quit()
    {
        _quitting = true;
        _timer.Stop(); CloseReminder(); _preview?.Close();
        Application.Current.Shutdown();
    }

    private void Cleanup()
    {
        _quitting = true; _timer.Stop();
        SystemEvents.PowerModeChanged -= PowerChanged;
        SystemEvents.TimeChanged -= TimeChanged;
        _tray.Visible = false;
        _tray.ContextMenuStrip?.Dispose(); _tray.Icon?.Dispose(); _tray.Dispose();
    }
}
