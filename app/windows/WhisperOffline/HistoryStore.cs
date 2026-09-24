using System.IO;
using System.Text.Json;

namespace WhisperOffline;

public record HistoryEntry(long TimeMs, string Text, string Model, string Language, float AudioSeconds)
{
    public string DateText =>
        DateTimeOffset.FromUnixTimeMilliseconds(TimeMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm");
}

/// Transkriptions-Verlauf als history.json neben der EXE, neueste zuerst.
public static class HistoryStore
{
    private const int MaxEntries = 500;

    private static string FilePath => Path.Combine(WhisperCli.BaseDir, "history.json");

    /// Sortiert nach Zeit: Dateien aus Versionen bis 2.0 liegen durcheinander,
    /// weil Add die Liste bei jedem Eintrag umgedreht hat.
    public static List<HistoryEntry> Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new();
            var all = JsonSerializer.Deserialize<List<HistoryEntry>>(File.ReadAllText(FilePath)) ?? new();
            return all.OrderByDescending(e => e.TimeMs).ToList();
        }
        catch { return new(); }
    }

    public static void Add(HistoryEntry entry)
    {
        try
        {
            var all = Load();
            all.Insert(0, entry);
            File.WriteAllText(FilePath,
                JsonSerializer.Serialize(all.Take(MaxEntries).ToList()));
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
