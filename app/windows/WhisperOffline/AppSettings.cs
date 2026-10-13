using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WhisperOffline;

/// Einstellungen pro App (Prozessname): fester Modus, ausgeschlossen.
public sealed class AppProfile
{
    public FormatMode Mode { get; set; } = FormatMode.Auto;
    /// Ausgeschlossen: rohes Diktat, kein Enhance, keine Auswahl-Transformation.
    public bool Excluded { get; set; } = false;
}

/// Persistente Einstellungen neben der EXE (settings.json).
public sealed class AppSettings
{
    public string Model { get; set; } = "";

    /// Standard "de" statt "auto": bei "auto" führt whisper.cpp vor der
    /// eigentlichen Transkription einen kompletten zusätzlichen Encoder-Durchlauf
    /// zur Spracherkennung aus — das kostet annähernd die doppelte Zeit, weil der
    /// Encoder über 90 % der Rechenzeit ausmacht.
    public string Language { get; set; } = "de";

    /// Encoder-Fenster auf die tatsächliche Audiolänge kürzen.
    /// whisper.h führt das als experimentell mit möglichem Qualitätsverlust.
    public bool ShortCtx { get; set; } = false;

    /// Hotkey-Modifikatoren als MOD_*-Bitmaske (siehe HotkeyHook).
    public uint HotkeyModifiers { get; set; } = HotkeyHook.MOD_CONTROL | HotkeyHook.MOD_ALT;

    /// Virtueller Tastencode (VK_*). Standard: Leertaste.
    public uint HotkeyVk { get; set; } = 0x20;

    /// Beim Anmelden automatisch starten (Registry, Run-Schlüssel des Benutzers).
    public bool Autostart { get; set; } = false;

    /// Direkt in den Infobereich starten, ohne Fenster.
    public bool StartMinimized { get; set; } = false;

    /// Einführung beim ersten Start; nach dem Durchlauf dauerhaft erledigt.
    public bool OnboardingDone { get; set; } = false;

    /// Gelernte Rechenzeit je Modell (ms pro Aufwandseinheit) für die
    /// Fortschrittsschätzung, siehe TranscriptionEstimate.
    public Dictionary<string, double> ProgressCalibration { get; set; } = new();

    // ---- Wörterbuch ----

    /// Eigene Wörter: Whisper-Prompt + unscharfe Korrektur (Vocabulary).
    public List<string> Vocabulary { get; set; } = new();
    /// Wörterbuch wurde schon einmal mit der Standardliste vorbelegt —
    /// passiert nur beim ersten Start, nie wieder danach.
    public bool VocabularySeeded { get; set; } = false;
    public List<ReplacementRule> Replacements { get; set; } = new();
    public List<Snippet> Snippets { get; set; } = new();

    // ---- Diktat ----

    /// Ins Englische übersetzen (nur Whisper).
    public bool Translate { get; set; } = false;
    /// Kurzer Ton bei Start, Ende und Fehlern.
    public bool AudioFeedback { get; set; } = true;
    /// Andere Wiedergabe während der Aufnahme leiser stellen.
    public bool Ducking { get; set; } = true;
    /// Lautstärke anderer Apps während der Aufnahme in Prozent.
    public int DuckingLevel { get; set; } = 25;
    /// WASAPI-Geräte-ID des Mikrofons; leer = Standardgerät.
    public string MicDeviceId { get; set; } = "";
    /// „Komma“, „neuer Absatz“ … umsetzen.
    public bool SpokenCommands { get; set; } = true;
    /// Text schon während der Aufnahme zeigen.
    public bool LiveTranscription { get; set; } = false;
    /// Beim Einfügen die vorherige Zwischenablage wiederherstellen.
    public bool PreserveClipboard { get; set; } = true;
    /// Kleines Fenster unten am Bildschirm während eines Diktats.
    public bool ShowOverlay { get; set; } = true;
    /// Taste gedrückt halten statt zweimal drücken.
    public bool PushToTalk { get; set; } = false;
    /// Einzeltaste als Auslöser (z. B. rechte Strg, VK 0xA3); 0 = Kombination oben.
    public uint SingleKeyVk { get; set; } = 0;
    /// Zweiter Hotkey: Diktat mit Enhance. 0 = aus.
    public uint EnhanceHotkeyModifiers { get; set; } = 0;
    public uint EnhanceHotkeyVk { get; set; } = 0;
    /// Hotkey „Auswahl bearbeiten“. 0 = aus.
    public uint TransformHotkeyModifiers { get; set; } = 0;
    public uint TransformHotkeyVk { get; set; } = 0;
    /// Prozessnamen (ohne .exe), in denen nach dem Einfügen Enter gedrückt wird.
    public List<string> AutoEnterApps { get; set; } = new();

    // ---- Verlauf ----

    public bool HistoryEnabled { get; set; } = true;

    // ---- Enhance ----

    /// Diktate automatisch verbessern (normaler Hotkey).
    public bool EnhanceAuto { get; set; } = false;
    /// "local" (llm-runner) oder "cli" (eigener Befehl, z. B. Claude Code).
    public string EnhanceEngine { get; set; } = "local";
    /// Dateiname des Sprachmodells in models/llm/.
    public string LlmModel { get; set; } = "";
    public bool LlmKeepLoaded { get; set; } = false;
    /// Befehl für Enhance per CLI; Prompt per stdin und Umgebungsvariablen.
    public string CliCommand { get; set; } = "";
    public int CliTimeoutSeconds { get; set; } = 90;
    /// Eigene Enhance-Regeln; null = Standard.
    public string? EnhancePrompt { get; set; }
    /// Formatierung an die Ziel-App anpassen.
    public bool AppContext { get; set; } = true;
    /// Schlüssel: Prozessname klein, ohne .exe.
    public Dictionary<string, AppProfile> AppProfiles { get; set; } = new();

    // ---- Meeting ----

    public bool MeetingNotesAuto { get; set; } = true;
    /// "mic", "system" oder "both".
    public string MeetingSource { get; set; } = "both";

    // -----------------------------------------------------------------------

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    [JsonIgnore]
    public static string Path => System.IO.Path.Combine(WhisperCli.BaseDir, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(Path))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Path), JsonOpts) ?? new AppSettings();
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            File.WriteAllText(Path, JsonSerializer.Serialize(this, JsonOpts));
        }
        catch { }
    }

    /// Einmalige Vorbelegung des Wörterbuchs mit der mitgelieferten Standardliste
    /// (häufige englische Begriffe im Deutschen). Eine bereits gepflegte Liste
    /// bleibt unangetastet.
    public void SeedVocabulary()
    {
        if (VocabularySeeded || VocabularyDefaults.Words.Count == 0) return;
        VocabularySeeded = true;
        if (Vocabulary.Count == 0) Vocabulary = new List<string>(VocabularyDefaults.Words);
        Save();
    }

    // -----------------------------------------------------------------------

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "ScheisssewassersWhisper";

    /// Trägt die EXE im Run-Schlüssel des angemeldeten Benutzers ein bzw. aus.
    /// Bewusst HKCU statt HKLM: keine Administratorrechte nötig.
    public static void ApplyAutostart(bool enabled)
    {
        if (!OperatingSystem.IsWindows()) return;
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey, true);
            if (key == null) return;
            if (enabled)
            {
                var exe = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
                if (string.IsNullOrEmpty(exe)) return;
                // --tray: startet ohne Fenster direkt in den Infobereich
                key.SetValue(RunValue, $"\"{exe}\" --tray");
            }
            else
            {
                key.DeleteValue(RunValue, throwOnMissingValue: false);
            }
        }
        catch { }
    }
}
