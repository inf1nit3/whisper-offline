using System.Diagnostics;
using System.IO;
using System.IO.Compression;

namespace WhisperOffline;

/// Lädt ein Release-Zip herunter und ersetzt die Installation über ein
/// Updater-Skript: Die laufende EXE kann sich nicht selbst überschreiben,
/// daher übernimmt das Skript das Kopieren nach dem App-Exit und startet
/// die neue Version. models/, settings.json und history.json bleiben
/// unangetastet (Kopiervorgang, kein Spiegeln/Löschen).
public static class SelfUpdater
{
    public static string WorkDir => Path.Combine(Path.GetTempPath(), "whisper_update");

    public static async Task<string> DownloadAndExtractAsync(
        string zipUrl, IProgress<double>? progress)
    {
        Directory.CreateDirectory(WorkDir);
        var zipPath = Path.Combine(WorkDir, "update.zip");
        var extractDir = Path.Combine(WorkDir, "files");

        using (var http = new HttpClient())
        using (var resp = await http.GetAsync(zipUrl, HttpCompletionOption.ResponseHeadersRead))
        {
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? -1;
            await using var input = await resp.Content.ReadAsStreamAsync();
            await using var output = File.Create(zipPath);
            var buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = await input.ReadAsync(buf)) > 0)
            {
                await output.WriteAsync(buf.AsMemory(0, n));
                done += n;
                if (total > 0) progress?.Report((double)done / total * 0.5);
            }
        }

        if (Directory.Exists(extractDir)) Directory.Delete(extractDir, true);
        // Zip enthält den Ordner WhisperOffline-windows-x64/
        ZipFile.ExtractToDirectory(zipPath, WorkDir, true);
        var inner = Directory.GetDirectories(WorkDir)
            .FirstOrDefault(d => d != extractDir)
            ?? throw new InvalidOperationException("Zip-Struktur unerwartet");
        progress?.Report(1);
        return inner;
    }

    /// Schreibt das Updater-Skript, startet es und beendet die App.
    /// Aufrufer muss danach selbst schließen.
    public static Process LaunchUpdater(string sourceDir)
    {
        var installDir = AppContext.BaseDirectory;
        var script = Path.Combine(WorkDir, "apply_update.cmd");
        File.WriteAllText(script, $"""
            @echo off
            set SRC={sourceDir}
            set DST={installDir}
            set N=0
            :wait
            timeout /t 1 /nobreak >nul
            tasklist /FI "IMAGENAME eq WhisperOffline.exe" 2>NUL | find /I "WhisperOffline.exe" >NUL
            if not errorlevel 1 (
                set /A N+=1
                if %N% lss 20 goto wait
            )
            robocopy "%SRC%" "%DST%" /E /NP /NDL /NFL /NJH /NJS >NUL
            start "" "{installDir}WhisperOffline.exe"
            del "%~f0"
            """);
        return Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
        }) ?? throw new InvalidOperationException("Updater konnte nicht gestartet werden");
    }
}
