using System.Net.Http;
using System.Text.Json;

namespace WhisperOffline;

public record GhRelease(string Tag, string Name, string? ZipUrl, string Body);

/// Prüft GitHub-Releases auf neuere Versionen (öffentliches Repo, ohne Auth).
public static class UpdateChecker
{
    private const string LatestUrl =
        "https://api.github.com/repos/inf1nit3/whisper-offline/releases/latest";

    private static readonly HttpClient http = new();

    /// Installierte Version. Liest den ProductVersion aus der EXE, der
    /// <Version> im csproj folgt — beim Release nur eine Stelle bumpen.
    public static string CurrentVersion()
    {
        var path = Environment.ProcessPath;
        if (path != null && File.Exists(path))
        {
            var v = System.Diagnostics.FileVersionInfo.GetVersionInfo(path).ProductVersion;
            if (!string.IsNullOrEmpty(v)) return v.Split('+')[0];
        }
        return "0.0.0";
    }

    public static async Task<GhRelease?> FetchLatest()
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, LatestUrl);
            req.Headers.Add("User-Agent", "whisper-offline-updater"); // GitHub verlangt UA
            req.Headers.Add("Accept", "application/vnd.github+json");
            using var resp = await http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return null;
            using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
            var root = doc.RootElement;

            string? zip = null;
            if (root.TryGetProperty("assets", out var assets))
                foreach (var a in assets.EnumerateArray())
                    if (a.GetProperty("name").GetString()?.EndsWith(".zip") == true)
                    {
                        zip = a.GetProperty("browser_download_url").GetString();
                        break;
                    }

            return new GhRelease(
                root.GetProperty("tag_name").GetString() ?? "",
                root.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "",
                zip,
                root.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "");
        }
        catch
        {
            return null;
        }
    }

    /// "v1.2.3" vs "1.2" → true wenn remote neuer.
    public static bool IsNewer(string current, string remoteTag)
    {
        var cur = Parse(current);
        var rem = Parse(remoteTag);
        for (int i = 0; i < 3; i++)
            if (rem[i] != cur[i]) return rem[i] > cur[i];
        return false;
    }

    private static int[] Parse(string v)
    {
        var clean = v.TrimStart('v', 'V').Split('+')[0];
        var parts = clean.Split('.', '-');
        return new[] { 0, 1, 2 }.Select(i => int.TryParse(parts.ElementAtOrDefault(i), out var n) ? n : 0).ToArray();
    }
}
