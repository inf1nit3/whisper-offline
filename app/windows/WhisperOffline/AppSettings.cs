using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WhisperOffline;

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

    // -----------------------------------------------------------------------

    [JsonIgnore]
    public static string Path => System.IO.Path.Combine(WhisperCli.BaseDir, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(Path))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Path)) ?? new AppSettings();
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            File.WriteAllText(Path, JsonSerializer.Serialize(this,
                new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
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
