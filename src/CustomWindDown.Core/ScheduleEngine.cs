using System.Globalization;

namespace CustomWindDown.Core;

/// <summary>Pure scheduling state machine. It never calls the OS or writes files.</summary>
public sealed class ScheduleEngine(AppState state, TimeZoneInfo zone)
{
    private DateTimeOffset? _lastTick;
    public AppState State { get; } = state;

    public void Initialize(DateTimeOffset now)
    {
        _lastTick = now;
        var zoneChanged = !string.IsNullOrEmpty(State.TimeZoneId) && State.TimeZoneId != zone.Id;
        State.TimeZoneId = zone.Id;
        if (!State.Settings.Enabled) { State.Current = null; return; }
        if (zoneChanged)
        {
            SkipForTimeZoneChange(now);
            return;
        }
        if (State.Current is { Status: OccurrenceStatus.Pending } current && current.TargetAt > now)
        {
            // Reopening the app must not suppress a reminder that no longer has a window.
            current.ReminderShown = false;
            return;
        }
        if (State.Current is { Status: OccurrenceStatus.Pending })
            Record(now, "错过本次收工时间，已跳过；不会补关机。");
        Advance(now);
    }

    public void UpdatePlan(ScheduleSettings settings, DateTimeOffset now)
    {
        if (!settings.IsValid()) throw new ArgumentException("请选择有效时间和至少一个执行日期。");
        State.Settings = settings;
        State.Current = null;
        _lastTick = now;
        if (settings.Enabled) Advance(now);
        Record(now, settings.Enabled ? "收工计划已保存。" : "定时关机已关闭。");
    }

    public TickResult Tick(DateTimeOffset now, TimeSpan? elapsed = null)
    {
        var gap = _lastTick.HasValue ? now - _lastTick.Value : TimeSpan.Zero;
        _lastTick = now;
        if (!State.Settings.Enabled) return default;
        // Long stalls, sleep and clock jumps must never cause catch-up shutdowns.
        if (gap < TimeSpan.Zero || gap > TimeSpan.FromSeconds(60)
            || (elapsed.HasValue && Math.Abs((gap - elapsed.Value).TotalSeconds) > 2))
        {
            Reconcile(now);
            return new(Changed: true);
        }
        if (State.Current is null || State.Current.Status != OccurrenceStatus.Pending)
        {
            Advance(now);
            return new(Changed: true);
        }
        var current = State.Current;
        if (now >= current.TargetAt)
        {
            if (now - current.TargetAt > TimeSpan.FromSeconds(60))
            {
                Record(now, "错过本次收工时间，已跳过；不会补关机。");
                Advance(now);
                return new(Changed: true);
            }
            current.Status = OccurrenceStatus.ShutdownRequested;
            Record(now, "已发出关机请求；未保存工作可能阻止关机，不会自动重试。");
            return new(Changed: true, RequestShutdown: true);
        }
        if (!current.ReminderShown && current.TargetAt - now <= TimeSpan.FromMinutes(10))
        {
            current.ReminderShown = true;
            return new(Changed: true, ShowReminder: true);
        }
        return default;
    }

    public void Reconcile(DateTimeOffset now, TimeZoneInfo? newZone = null)
    {
        _lastTick = now;
        if (newZone is not null && (zone.Id != newZone.Id || !zone.HasSameRules(newZone)))
        {
            zone = newZone;
            State.TimeZoneId = zone.Id;
            if (!State.Settings.Enabled) return;
            SkipForTimeZoneChange(now);
            return;
        }
        if (!State.Settings.Enabled) return;
        if (State.Current is { Status: OccurrenceStatus.Pending } current && current.TargetAt > now)
        {
            current.ReminderShown = false;
            return;
        }
        if (State.Current is { Status: OccurrenceStatus.Pending })
            Record(now, "睡眠、时钟变化或暂停期间错过计划，已跳过。");
        Advance(now);
    }

    public bool Snooze(DateTimeOffset now)
    {
        if (!State.Settings.Enabled || State.Current is not { Status: OccurrenceStatus.Pending } current)
            return false;
        current.TargetAt = (current.TargetAt > now ? current.TargetAt : now).AddMinutes(15);
        current.ReminderShown = false;
        Record(now, "本次收工已延后 15 分钟。");
        return true;
    }

    public bool Skip(DateTimeOffset now)
    {
        if (!State.Settings.Enabled || State.Current is not { Status: OccurrenceStatus.Pending }) return false;
        Record(now, "已跳过本次收工，下次按正常计划执行。");
        Advance(now);
        return true;
    }

    public void ShutdownFailed(DateTimeOffset now, string message)
    {
        if (State.Current is not null) State.Current.Status = OccurrenceStatus.Failed;
        Record(now, "关机请求失败，不会重试：" + message);
    }

    private void Advance(DateTimeOffset now)
    {
        var after = State.Current is { } old && old.ScheduledAt > now ? old.ScheduledAt : now;
        var next = FindNext(State.Settings, after, zone);
        State.Current = new Occurrence { ScheduledAt = next, TargetAt = next };
    }

    private void SkipForTimeZoneChange(DateTimeOffset now)
    {
        // Do not move an outstanding occurrence unexpectedly closer to shutdown.
        var after = State.Current is { } old && old.TargetAt > now ? old.TargetAt : now;
        State.Current = null;
        Advance(after);
        Record(now, "时区已变化，已跳过当前计划；后续按新时区执行。");
    }

    private void Record(DateTimeOffset now, string message) =>
        State.LastEvent = $"{TimeZoneInfo.ConvertTime(now, zone):MM-dd HH:mm}  {message}";

    public static DateTimeOffset FindNext(ScheduleSettings settings, DateTimeOffset after, TimeZoneInfo zone)
    {
        if (!settings.IsValid()) throw new ArgumentException("Invalid schedule.");
        var time = TimeOnly.ParseExact(settings.Time, "HH:mm", CultureInfo.InvariantCulture);
        var start = TimeZoneInfo.ConvertTime(after, zone).Date;
        for (var day = 0; day <= 14; day++)
        {
            var date = start.AddDays(day);
            if ((settings.Days & (1 << (int)date.DayOfWeek)) == 0) continue;
            var local = DateTime.SpecifyKind(date.Add(time.ToTimeSpan()), DateTimeKind.Unspecified);
            if (zone.IsInvalidTime(local)) continue;
            // For an ambiguous DST hour choose its later occurrence, exactly once.
            var offset = zone.IsAmbiguousTime(local)
                ? zone.GetAmbiguousTimeOffsets(local).Min() : zone.GetUtcOffset(local);
            var candidate = new DateTimeOffset(local, offset);
            if (candidate > after) return candidate;
        }
        throw new InvalidOperationException("无法计算下一次执行时间。");
    }
}
