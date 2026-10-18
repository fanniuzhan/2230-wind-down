using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Win32;
using CustomWindDown.Core;

namespace CustomWindDown.App;

internal sealed class WindowsShutdownService : IShutdownService
{
    public void RequestShutdown()
    {
        var command = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe"))
        {
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var argument in ShutdownCommand.Arguments) command.ArgumentList.Add(argument);
        using var process = Process.Start(command) ?? throw new InvalidOperationException("无法启动 Windows 关机程序。");
        if (!process.WaitForExit(5000)) throw new InvalidOperationException("Windows 尚未确认请求；不会自动重试。");
        if (process.ExitCode != 0) throw new InvalidOperationException($"Windows 返回错误代码 {process.ExitCode}。");
    }
}

internal static class LoginStartup
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "CustomWindDown";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(ValueName) is string value && !string.IsNullOrWhiteSpace(value);
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey)
            ?? throw new InvalidOperationException("无法访问当前用户的登录启动设置。");
        if (enabled)
        {
            var executable = Environment.ProcessPath ?? throw new InvalidOperationException("无法定位程序。");
            key.SetValue(ValueName, $"\"{executable}\" --minimized", RegistryValueKind.String);
        }
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
