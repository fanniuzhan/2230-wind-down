using System.Globalization;

namespace CustomWindDown.Core;

public sealed class ScheduleSettings
{
    public bool Enabled { get; set; }
    public string Time { get; set; } = "22:30";
    // Sunday = bit 0, Saturday = bit 6, matching System.DayOfWeek.
    public int Days { get; set; } = 127;
    public string AuthorizedMachine { get; set; } = "";

    public bool IsValid() => TimeOnly.TryParseExact(Time, "HH:mm", CultureInfo.InvariantCulture,
        DateTimeStyles.None, out _) && Days is > 0 and <= 127;
}

public enum OccurrenceStatus { Pending, ShutdownRequested, Failed }

public sealed class Occurrence
{
    public DateTimeOffset ScheduledAt { get; set; }
    public DateTimeOffset TargetAt { get; set; }
    public bool ReminderShown { get; set; }
    public OccurrenceStatus Status { get; set; }
}

public sealed class AppState
{
    public int Version { get; set; } = 1;
    public ScheduleSettings Settings { get; set; } = new();
    public string TimeZoneId { get; set; } = "";
    public Occurrence? Current { get; set; }
    public string LastEvent { get; set; } = "还没有执行记录。";
}

public readonly record struct TickResult(bool Changed = false, bool ShowReminder = false,
    bool RequestShutdown = false);

public interface IShutdownService
{
    void RequestShutdown();
}

public static class ShutdownCommand
{
    // A nonzero /t implicitly forces applications closed. Keep /t 0 and never add /f.
    public static IReadOnlyList<string> Arguments { get; } = Array.AsReadOnly(new[] { "/s", "/t", "0" });
}
