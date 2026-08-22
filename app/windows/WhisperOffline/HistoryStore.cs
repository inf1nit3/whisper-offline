using System.IO;
using System.Text.Json;

namespace WhisperOffline;

public record HistoryEntry(long TimeMs, string Text, string Model, string Language, float AudioSeconds)
{
    public string DateText =>
        DateTimeOffset.FromUnixTimeMilliseconds(TimeMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm");
}

/// Transkriptions-Verlauf als history.json neben der EXE.
public static class HistoryStore
{
    private static string FilePath => Path.Combine(WhisperCli.BaseDir, "history.json");

    public static List<HistoryEntry> Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new();
            return JsonSerializer.Deserialize<List<HistoryEntry>>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { return new(); }
    }

    public static void Add(HistoryEntry entry)
    {
        try
        {
            var all = Load();
            all.Add(entry);
            all.Reverse();                       // neueste zuerst …
            File.WriteAllText(FilePath,
                JsonSerializer.Serialize(all.Take(500).ToList()));
        }
        catch { } // Verlauf darf nie die Transkription blockieren
    }

    public static void Delete(HistoryEntry e)
    {
        try
        {
            File.WriteAllText(FilePath,
                JsonSerializer.Serialize(Load().Where(x => x.TimeMs != e.TimeMs).ToList()));
        }
        catch { }
    }

    public static void Clear()
    {
        try { File.Delete(FilePath); } catch { }
    }
}
