using System.IO;
using System.Text.Json;

namespace WhisperOffline;

/// Source: "app" (Aufnahme im Fenster), "dictation" (Hotkey), "file", "meeting", "selection".
public record HistoryEntry(long TimeMs, string Text, string Model, string Language, float AudioSeconds,
    string Source = "app")
{
    public string DateText =>
        DateTimeOffset.FromUnixTimeMilliseconds(TimeMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm");
}

/// Transkriptions-Verlauf als history.json neben der EXE, neueste zuerst.
public static class HistoryStore
{
    private const int MaxEntries = 500;

    private static string FilePath => Path.Combine(WhisperCli.BaseDir, "history.json");

    /// Aus = nichts wird gespeichert (Einstellung „Verlauf speichern“).
    public static bool Enabled { get; set; } = true;

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
        if (!Enabled) return;
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

    /// Bearbeitetes Transkript: den jüngsten Eintrag mit dem alten Text anpassen.
    public static void ReplaceText(string oldText, string newText)
    {
        try
        {
            var all = Load();
            int i = all.FindIndex(e => e.Text == oldText);
            if (i < 0) return;
            all[i] = all[i] with { Text = newText };
            File.WriteAllText(FilePath, JsonSerializer.Serialize(all));
        }
        catch { }
    }

    public static void Clear()
    {
        try { File.Delete(FilePath); } catch { }
    }
}
