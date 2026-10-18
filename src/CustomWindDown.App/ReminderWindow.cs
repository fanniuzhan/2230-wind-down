using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace CustomWindDown.App;

internal sealed class ReminderWindow : Window
{
    private readonly TextBlock _target;
    private readonly TextBlock _countdown;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly bool _preview;
    private DateTimeOffset _targetAt;

    public ReminderWindow(bool preview, DateTimeOffset targetAt, Action snooze, Action skip)
    {
        _preview = preview;
        _targetAt = targetAt;
        Title = preview ? "预览提醒 · 不会关机" : "该收工了 · Custom Wind-down";
        Width = 540; Height = 470; ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = Ui.Canvas; FontFamily = new FontFamily("Microsoft YaHei UI");
        FontSize = 14; Topmost = true;
        var stack = new StackPanel { Margin = new Thickness(34) };
        stack.Children.Add(Ui.Text(preview ? "PREVIEW  /  安全预览" : "WIND-DOWN  /  收工提醒", 12, Ui.Accent));
        stack.Children.Add(Ui.Text("把今天，停在这里。", 29));
        stack.Children.Add(Ui.Text(preview ? "这是提醒预览，所有操作都不会改变真实计划或关闭电脑。"
            : "给正在做的事留个结尾，保存文件，然后安心休息。", 14, Ui.Muted));
        _target = Ui.Text("", 40); _target.FontWeight = FontWeights.SemiBold;
        _target.Margin = new Thickness(0, 16, 0, 0);
        _countdown = Ui.Text("", 15, Ui.Muted);
        stack.Children.Add(_target); stack.Children.Add(_countdown);
        var buttons = new WrapPanel { Margin = new Thickness(0, 24, 0, 16) };
        var delay = Ui.Button("延后 15 分钟", true);
        delay.Click += (_, _) =>
        {
            if (_preview) { _targetAt = _targetAt.AddMinutes(15); Update(); }
            else snooze();
        };
        var cancel = Ui.Button("跳过本次");
        cancel.Click += (_, _) => { if (_preview) Close(); else skip(); };
        buttons.Children.Add(delay); buttons.Children.Add(cancel); stack.Children.Add(buttons);
        stack.Children.Add(Ui.Text(preview ? "关闭此窗口可返回设置。" : "关闭提醒窗口不会取消计划；如需取消，请选择“跳过本次”。", 12, Ui.Muted));
        Content = stack;
        _timer.Tick += (_, _) => Update();
        Closed += (_, _) => _timer.Stop();
        Update(); _timer.Start();
    }

    private void Update()
    {
        _target.Text = _targetAt.ToLocalTime().ToString("HH:mm");
        var remaining = _targetAt - DateTimeOffset.Now;
        _countdown.Text = remaining > TimeSpan.Zero
            ? $"距离{(_preview ? "模拟" : "计划")}收工还有  {(int)remaining.TotalMinutes:00} 分 {remaining.Seconds:00} 秒"
            : _preview ? "预览已到时 · 不会执行关机" : "已到计划时间，请保存工作。";
    }
}
