using System.IO;
using System.Text.Json;

namespace WhisperOffline;

/// Source: "app" (Aufnahme im Fenster), "dictation" (Hotkey), "file", "meeting", "selection".
/// Die Jev-Felder sind null bei Einträgen ohne Klassifikation (offline, aus, ältere Versionen).
public record HistoryEntry(long TimeMs, string Text, string Model, string Language, float AudioSeconds,
    string Source = "app",
    string? IntentCategory = null, double? IntentConfidence = null, string? TopAlternativeIntent = null,
    string? ProjectTag = null, double? UrgencyScore = null, bool? IsActionable = null, bool? NeedsCleanup = null)
{
    public string DateText =>
        DateTimeOffset.FromUnixTimeMilliseconds(TimeMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm");

    /// Wörter im Transkript — für Statistik und Jev-Metadaten.
    public static int CountWords(string text) =>
        text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
            .Count(w => w.Any(char.IsLetterOrDigit));

    /// Gespeicherte Klassifikation; null, wenn keine vorliegt.
    public JevAnalysis? Analysis() => IntentCategory is { } intent
        ? new JevAnalysis(intent, IntentConfidence ?? 0.0, TopAlternativeIntent, ProjectTag,
            UrgencyScore, IsActionable, NeedsCleanup)
        : null;

    public HistoryEntry WithAnalysis(JevAnalysis a) => this with
    {
        IntentCategory = a.Intent,
        IntentConfidence = a.IntentConfidence,
        TopAlternativeIntent = a.TopAlternativeIntent,
        ProjectTag = a.ProjectTag,
        UrgencyScore = a.UrgencyScore,
        IsActionable = a.IsActionable,
        NeedsCleanup = a.NeedsCleanup,
    };
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

    /// Nutzer übersteuert die Jev-Kategorie: Klassifikation des jüngsten
    /// Eintrags mit diesem Text ersetzen.
    public static void UpdateAnalysis(string text, JevAnalysis analysis)
    {
        try
        {
            var all = Load();
            int i = all.FindIndex(e => e.Text == text);
            if (i < 0) return;
            all[i] = all[i].WithAnalysis(analysis);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(all));
        }
        catch { }
    }

    public static void Clear()
    {
        try { File.Delete(FilePath); } catch { }
    }
}
