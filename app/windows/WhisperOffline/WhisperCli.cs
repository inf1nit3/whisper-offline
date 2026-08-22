using System.Diagnostics;
using System.IO;

namespace WhisperOffline;

/// Ruft das gebündelte whisper-cli als Unterprozess auf.
/// Erwartet die Ordnerstruktur: <App>/engine/whisper-cli.exe und <App>/models/ggml-base.bin
public static class WhisperCli
{
    public static string BaseDir { get; } = AppContext.BaseDirectory;
    public static string EngineDir => Path.Combine(BaseDir, "engine");
    public static string EnginePath => Path.Combine(EngineDir, "whisper-cli.exe");
    public static string ModelPath => Path.Combine(BaseDir, "models", "ggml-base.bin");
    public static bool EnginePathExists => File.Exists(EnginePath);
    public static bool ModelPathExists => File.Exists(ModelPath);

    public static string Transcribe(string audioPath, string language, out string error)
    {
        error = "";
        if (!EnginePathExists) { error = "whisper-cli.exe nicht gefunden (engine/)"; return ""; }
        if (!ModelPathExists) { error = "ggml-base.bin nicht gefunden (models/)"; return ""; }

        var args = $"-m \"{ModelPath}\" -f \"{audioPath}\" -nt -np";
        if (language.Length > 0) args += $" -l {language}";

        var psi = new ProcessStartInfo
        {
            FileName = EnginePath,
            Arguments = args,
            WorkingDirectory = EngineDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        using var p = Process.Start(psi)!;
        string stdout = p.StandardOutput.ReadToEnd();
        string stderr = p.StandardError.ReadToEnd();
        p.WaitForExit();

        if (p.ExitCode != 0)
        {
            error = $"whisper-cli beendet mit Code {p.ExitCode}";
        }
        // Log-Zeilen von stderr ignorieren, nur harte Fehler melden
        return stdout.Trim();
    }
}
