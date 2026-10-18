using System;
using System.Linq;
using System.Security.Principal;
using System.Threading;
using System.Windows;

namespace CustomWindDown.App;

internal static class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        var user = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        using var mutex = new Mutex(true, @"Local\CustomWindDown-" + user, out var first);
        using var activation = new EventWaitHandle(false, EventResetMode.AutoReset,
            @"Local\CustomWindDown-Activate-" + user);
        if (!first) { activation.Set(); return; }

        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var window = new MainWindow();
        app.MainWindow = window;
        var stopping = false;
        var listener = new Thread(() =>
        {
            while (true)
            {
                activation.WaitOne();
                if (Volatile.Read(ref stopping)) return;
                app.Dispatcher.BeginInvoke(new Action(window.ShowMain));
            }
        }) { IsBackground = true, Name = "Window activation" };
        listener.Start();
        app.Exit += (_, _) =>
        {
            Volatile.Write(ref stopping, true);
            activation.Set();
            listener.Join(1000);
        };
        if (!args.Contains("--minimized", StringComparer.OrdinalIgnoreCase) || window.HasStartupWarning)
            window.Show();
        app.Run();
        mutex.ReleaseMutex();
    }
}
