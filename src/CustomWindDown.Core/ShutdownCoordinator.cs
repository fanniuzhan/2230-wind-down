namespace CustomWindDown.Core;

/// <summary>Durably consume a due occurrence BEFORE invoking the side effect.</summary>
public sealed class ShutdownCoordinator(ScheduleEngine engine, Action<AppState> persist, IShutdownService shutdown)
{
    public TickResult Tick(DateTimeOffset now, TimeSpan? elapsed = null)
    {
        var result = engine.Tick(now, elapsed);
        if (result.Changed) persist(engine.State); // A failed save must prevent shutdown.
        if (result.RequestShutdown)
        {
            try { shutdown.RequestShutdown(); }
            catch (Exception e)
            {
                engine.ShutdownFailed(now, e.Message);
                persist(engine.State);
            }
        }
        return result;
    }
}
