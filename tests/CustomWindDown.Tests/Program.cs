using CustomWindDown.Core;

// Dependency-free regression runner: dotnet run --project tests/CustomWindDown.Tests
// No test instantiates WindowsShutdownService or calls a real shutdown executable.
var zone = TimeZoneInfo.CreateCustomTimeZone("Test +08", TimeSpan.FromHours(8), "Test +08", "Test +08");
var root = Path.GetFullPath(Path.Combine("artifacts", "test-data", Guid.NewGuid().ToString("N")));
Directory.CreateDirectory(root);
var passed = 0;
var failed = 0;

void Test(string name, Action body)
{
    try { body(); Console.WriteLine("PASS " + name); passed++; }
    catch (Exception e) { Console.WriteLine("FAIL " + name + ": " + e.Message); failed++; }
}

DateTimeOffset At(string value) => DateTimeOffset.Parse(value + "+08:00");
void Check(bool value, string message = "Assertion failed") { if (!value) throw new Exception(message); }
ScheduleEngine Engine(string now = "2026-10-18T22:19:00")
{
    var engine = new ScheduleEngine(new AppState
    {
        Settings = new ScheduleSettings { Enabled = true, AuthorizedMachine = "TEST-PC" }
    }, zone);
    engine.Initialize(At(now));
    return engine;
}
Test("First run is disabled and scheduled for 22:30 daily", () =>
{
    var result = new StateStore(Path.Combine(root, "absent.json"), "TEST-PC").Load();
    Check(!result.State.Settings.Enabled && result.State.Settings.Time == "22:30" && result.State.Settings.Days == 127);
    Check(result.Warning is null);
});
Test("Daily schedule and midnight boundary", () =>
{
    var settings = new ScheduleSettings();
    Check(ScheduleEngine.FindNext(settings, At("2026-10-18T22:30:00"), zone) == At("2026-10-19T22:30:00"));
    settings.Time = "00:05";
    Check(ScheduleEngine.FindNext(settings, At("2026-10-18T23:59:00"), zone) == At("2026-10-19T00:05:00"));
});
Test("Weekdays and selected days skip the weekend", () =>
{
    var settings = new ScheduleSettings { Days = 62 };
    Check(ScheduleEngine.FindNext(settings, At("2026-10-16T23:00:00"), zone) == At("2026-10-19T22:30:00"));
    settings.Days = 1 << (int)DayOfWeek.Sunday;
    Check(ScheduleEngine.FindNext(settings, At("2026-10-18T22:31:00"), zone) == At("2026-10-25T22:30:00"));
});
Test("Reminder appears at ten minutes and only once", () =>
{
    var engine = Engine();
    Check(!engine.Tick(At("2026-10-18T22:19:59")).ShowReminder);
    Check(engine.Tick(At("2026-10-18T22:20:00")).ShowReminder);
    Check(!engine.Tick(At("2026-10-18T22:20:01")).ShowReminder);
});
Test("Snooze repeats and restores after restart", () =>
{
    var engine = Engine();
    Check(engine.Snooze(At("2026-10-18T22:19:01")));
    Check(engine.Snooze(At("2026-10-18T22:19:02")));
    Check(engine.State.Current!.TargetAt == At("2026-10-18T23:00:00"));
    var store = new StateStore(Path.Combine(root, "snooze.json"), "TEST-PC");
    store.Save(engine.State);
    var restarted = new ScheduleEngine(store.Load().State, zone);
    restarted.Initialize(At("2026-10-18T22:50:00"));
    Check(restarted.State.Current!.TargetAt == At("2026-10-18T23:00:00"));
    Check(restarted.Tick(At("2026-10-18T22:50:01")).ShowReminder);
});
Test("Skip persists across restart and does not affect subsequent days", () =>
{
    var engine = Engine();
    Check(engine.Skip(At("2026-10-18T22:19:01")));
    var store = new StateStore(Path.Combine(root, "skip.json"), "TEST-PC");
    store.Save(engine.State);
    var restarted = new ScheduleEngine(store.Load().State, zone);
    restarted.Initialize(At("2026-10-18T22:20:00"));
    Check(restarted.State.Current!.TargetAt == At("2026-10-19T22:30:00"));
});
Test("A missed target at app launch is never executed", () =>
{
    var engine = Engine();
    var restarted = new ScheduleEngine(engine.State, zone);
    restarted.Initialize(At("2026-10-18T22:30:01"));
    Check(!restarted.Tick(At("2026-10-18T22:30:02")).RequestShutdown);
    Check(restarted.State.Current!.TargetAt == At("2026-10-19T22:30:00"));
});
Test("Resume one second after target skips rather than catching up", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    engine.Reconcile(At("2026-10-18T22:30:01"));
    Check(!engine.Tick(At("2026-10-18T22:30:02")).RequestShutdown);
    Check(engine.State.Current!.TargetAt == At("2026-10-19T22:30:00"));
});
Test("Long stalls and forward clock jumps cannot trigger shutdown", () =>
{
    var engine = Engine();
    Check(!engine.Tick(At("2026-10-18T23:00:00")).RequestShutdown);
    Check(engine.State.Current!.TargetAt == At("2026-10-19T22:30:00"));
});
Test("Clock moving backwards does not duplicate an occurrence", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    Check(engine.Tick(At("2026-10-18T22:30:00")).RequestShutdown);
    Check(!engine.Tick(At("2026-10-18T22:00:00")).RequestShutdown);
    Check(engine.State.Current!.ScheduledAt == At("2026-10-19T22:30:00"));
});
Test("Saving a new plan recomputes only future times", () =>
{
    var engine = Engine();
    engine.UpdatePlan(new ScheduleSettings { Enabled = true, Time = "21:00" }, At("2026-10-18T22:20:00"));
    Check(engine.State.Current!.TargetAt == At("2026-10-19T21:00:00"));
    engine.UpdatePlan(new ScheduleSettings(), At("2026-10-18T22:21:00"));
    Check(engine.State.Current is null && !engine.Tick(At("2026-10-18T22:30:00")).RequestShutdown);
});
Test("Settings corruption fails closed and retains a previous copy on save", () =>
{
    var path = Path.Combine(root, "corrupt.json");
    File.WriteAllText(path, "{broken");
    var store = new StateStore(path, "TEST-PC");
    var result = store.Load();
    Check(!result.State.Settings.Enabled && result.Warning is not null);
    store.Save(result.State);
    Check(File.ReadAllText(path + ".previous") == "{broken");
    store.Save(result.State); // Exercise replacement when a previous copy already exists.
    Check(store.Load().Warning is null);
});
Test("Invalid time, empty days, and invalid persisted status are rejected", () =>
{
    Check(!new ScheduleSettings { Time = "25:00" }.IsValid());
    Check(!new ScheduleSettings { Time = "9:00" }.IsValid());
    Check(!new ScheduleSettings { Days = 0 }.IsValid());
    var path = Path.Combine(root, "invalid.json");
    File.WriteAllText(path, "{\"Settings\":{\"Time\":\"25:00\"}}");
    Check(new StateStore(path, "TEST-PC").Load().Warning is not null);
});
Test("Copied settings cannot enable a second computer", () =>
{
    var store = new StateStore(Path.Combine(root, "device.json"), "ANOTHER-PC");
    store.Save(Engine().State);
    var result = store.Load();
    Check(!result.State.Settings.Enabled && result.State.Current is null && result.Warning is not null);
});
Test("Durably recorded shutdown is requested exactly once", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    var store = new StateStore(Path.Combine(root, "requested.json"), "TEST-PC");
    var calls = 0;
    var shutdown = new FakeShutdown(() =>
    {
        Check(store.Load().State.Current!.Status == OccurrenceStatus.ShutdownRequested, "Must persist before OS call");
        calls++;
    });
    var coordinator = new ShutdownCoordinator(engine, store.Save, shutdown);
    Check(coordinator.Tick(At("2026-10-18T22:30:00")).RequestShutdown);
    coordinator.Tick(At("2026-10-18T22:30:01"));
    coordinator.Tick(At("2026-10-18T22:30:02"));
    Check(calls == 1);
    var restarted = new ScheduleEngine(store.Load().State, zone);
    restarted.Initialize(At("2026-10-18T22:30:03"));
    Check(restarted.State.Current!.ScheduledAt == At("2026-10-19T22:30:00"));
});
Test("Persistence failure prevents the shutdown side effect", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    var calls = 0;
    var coordinator = new ShutdownCoordinator(engine, _ => throw new IOException("Disk failure"), new FakeShutdown(() => calls++));
    try { coordinator.Tick(At("2026-10-18T22:30:00")); throw new Exception("Expected IOException"); }
    catch (IOException) { }
    Check(calls == 0);
    Check(!engine.Tick(At("2026-10-18T22:30:01")).RequestShutdown);
});
Test("Rejected shutdown is recorded and never retried", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    var calls = 0;
    var coordinator = new ShutdownCoordinator(engine, _ => { }, new FakeShutdown(() =>
    { calls++; throw new InvalidOperationException("Blocked"); }));
    coordinator.Tick(At("2026-10-18T22:30:00"));
    Check(engine.State.Current!.Status == OccurrenceStatus.Failed);
    Check(engine.State.LastEvent.Contains("失败"));
    coordinator.Tick(At("2026-10-18T22:30:01"));
    Check(calls == 1);
});
Test("Normal shutdown never includes force or a nonzero timeout", () =>
{
    Check(ShutdownCommand.Arguments.SequenceEqual(new[] { "/s", "/t", "0" }));
});
Test("Snooze across midnight does not schedule a second shutdown for the original day", () =>
{
    var engine = Engine("2026-10-18T22:29:59");
    for (var i = 0; i < 7; i++) engine.Snooze(At("2026-10-18T22:29:59"));
    Check(engine.State.Current!.TargetAt == At("2026-10-19T00:15:00"));
    engine.Initialize(At("2026-10-19T00:14:59"));
    Check(engine.Tick(At("2026-10-19T00:15:00")).RequestShutdown);
    engine.Tick(At("2026-10-19T00:15:01"));
    Check(engine.State.Current!.TargetAt == At("2026-10-19T22:30:00"));
});
Test("Daylight saving invalid times are skipped and ambiguous times occur once", () =>
{
    var dstZone = TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time");
    var settings = new ScheduleSettings { Time = "02:30" };
    var next = ScheduleEngine.FindNext(settings, DateTimeOffset.Parse("2026-03-08T00:00:00-05:00"), dstZone);
    Check(next.Date == new DateTime(2026, 3, 9));
    settings.Time = "01:30";
    next = ScheduleEngine.FindNext(settings, DateTimeOffset.Parse("2026-11-01T00:00:00-04:00"), dstZone);
    Check(next == DateTimeOffset.Parse("2026-11-01T01:30:00-05:00"));
});
Test("Changing time zone skips the outstanding plan and schedules in the new zone", () =>
{
    var engine = Engine();
    var newZone = TimeZoneInfo.CreateCustomTimeZone("Test +09", TimeSpan.FromHours(9), "Test +09", "Test +09");
    engine.Reconcile(At("2026-10-18T22:19:01"), newZone);
    Check(engine.State.Current!.ScheduledAt == DateTimeOffset.Parse("2026-10-19T22:30:00+09:00"));
    Check(!engine.Tick(At("2026-10-18T22:30:00")).RequestShutdown);
});
Test("Reopening an already shown reminder does not change the target", () =>
{
    var engine = Engine("2026-10-18T22:20:00");
    Check(engine.Tick(At("2026-10-18T22:20:01")).ShowReminder);
    var restarted = new ScheduleEngine(engine.State, zone);
    restarted.Initialize(At("2026-10-18T22:21:00"));
    Check(restarted.Tick(At("2026-10-18T22:21:01")).ShowReminder);
    Check(restarted.State.Current!.TargetAt == At("2026-10-18T22:30:00"));
});
Test("A short forward clock change is detected against monotonic elapsed time", () =>
{
    var engine = Engine("2026-10-18T22:29:40");
    var result = engine.Tick(At("2026-10-18T22:30:05"), TimeSpan.FromSeconds(1));
    Check(!result.RequestShutdown);
    Check(engine.State.Current!.ScheduledAt == At("2026-10-19T22:30:00"));
});
Test("A time-zone change while the app is closed is detected on launch", () =>
{
    var original = Engine();
    var newZone = TimeZoneInfo.CreateCustomTimeZone("Test +09", TimeSpan.FromHours(9), "Test +09", "Test +09");
    var restarted = new ScheduleEngine(original.State, newZone);
    restarted.Initialize(At("2026-10-18T22:19:01"));
    Check(restarted.State.TimeZoneId == newZone.Id);
    Check(restarted.State.Current!.TargetAt == DateTimeOffset.Parse("2026-10-19T22:30:00+09:00"));
});
Test("A normal full reminder-to-shutdown flow produces a single request", () =>
{
    var engine = Engine();
    var reminders = 0;
    var requests = 0;
    for (var now = At("2026-10-18T22:19:01"); now <= At("2026-10-18T22:31:00"); now = now.AddSeconds(1))
    {
        var result = engine.Tick(now);
        if (result.ShowReminder) reminders++;
        if (result.RequestShutdown) requests++;
    }
    Check(reminders == 1 && requests == 1);
});

Console.WriteLine($"\n{passed} passed, {failed} failed. Test fixtures: {root}");
return failed == 0 ? 0 : 1;

sealed class FakeShutdown(Action action) : IShutdownService
{
    public void RequestShutdown() => action();
}
