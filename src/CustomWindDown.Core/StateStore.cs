using System.Text.Json;
using System.Text.Json.Serialization;

namespace CustomWindDown.Core;

public readonly record struct LoadResult(AppState State, string? Warning);

public sealed class StateStore(string path, string machineName)
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public LoadResult Load()
    {
        if (!File.Exists(path)) return new(new AppState(), null);
        try
        {
            var state = JsonSerializer.Deserialize<AppState>(File.ReadAllText(path), JsonOptions);
            if (state is null || state.Version != 1 || state.Settings is null || !state.Settings.IsValid()
                || state.LastEvent is null || (state.Current is { } c &&
                    (c.ScheduledAt == default || c.TargetAt < c.ScheduledAt || !Enum.IsDefined(c.Status))))
                throw new JsonException("Invalid settings.");
            if (state.Settings.Enabled && state.Settings.AuthorizedMachine != machineName)
            {
                state.Settings.Enabled = false;
                state.Current = null;
                return new(state, "设备名称发生变化，计划已关闭。请在本机重新确认启用。");
            }
            return new(state, null);
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException or ArgumentException)
        {
            return new(new AppState(), "设置无法读取，已恢复为禁用状态。请重新配置；原文件保留为 settings.json.previous。");
        }
    }

    public void Save(AppState state)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(path))!;
        Directory.CreateDirectory(directory);
        var temporary = path + ".tmp";
        using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, state, JsonOptions);
            stream.Flush(flushToDisk: true);
        }
        if (File.Exists(path)) File.Replace(temporary, path, path + ".previous");
        else File.Move(temporary, path);
    }
}
