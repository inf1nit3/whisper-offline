using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WhisperOffline;

public record ModelInfo(
    string File,
    string Label,
    string Tagline,
    long Size,
    string? Sha256,
    List<string> Pros,
    List<string> Cons,
    /// Absolute Download-Adresse; fehlt sie, liegt die Datei neben dem Manifest.
    string? Url = null)
{
    public string SizeText =>
        Size >= 1L << 30 ? $"{Size / 1073741824.0:F1} GB" : $"{Size / 1048576.0:F0} MB";
}

/// Lädt das Modell-Manifest und Modell-Dateien vom VPS nach models/.
public static class ModelRegistry
{
    private static readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(30) };

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public static string ModelsDir => WhisperCli.ModelsDir;

    public static List<string> LocalModelFiles() =>
        Directory.Exists(ModelsDir)
            ? Directory.GetFiles(ModelsDir, "ggml-*.bin").Select(Path.GetFileName).ToList()
            : new();

    public static async Task<List<ModelInfo>> FetchManifest()
    {
        var json = await http.GetStringAsync(ModelConfig.ManifestUrl);
        var doc = JsonSerializer.Deserialize<ManifestDoc>(json, JsonOpts)
                  ?? throw new InvalidOperationException("Manifest leer");
        return doc.Models;
    }

    private record ManifestDoc(List<ModelInfo> Models, List<ModelInfo>? Llm, List<ModelInfo>? Meeting);

    /// Alles aus dem Manifest: Spracherkennung, Sprachmodelle, Meeting-Modelle.
    public record Catalog(List<ModelInfo> Models, List<ModelInfo> Llm, List<ModelInfo> Meeting);

    public static async Task<Catalog> FetchCatalog()
    {
        var json = await http.GetStringAsync(ModelConfig.ManifestUrl);
        var doc = JsonSerializer.Deserialize<ManifestDoc>(json, JsonOpts)
                  ?? throw new InvalidOperationException("Manifest leer");
        return new Catalog(doc.Models, doc.Llm ?? new(), doc.Meeting ?? new());
    }

    private static string BaseUrl =>
        ModelConfig.ManifestUrl[..(ModelConfig.ManifestUrl.LastIndexOf('/') + 1)];

    /// Download mit Fortschritt und SHA256-Prüfung, atomar per .part-Datei.
    public static Task DownloadModel(
        ModelInfo info, IProgress<double>? progress, CancellationToken ct = default) =>
        DownloadTo(info, ModelsDir, progress, ct);

    /// Wie DownloadModel, in ein beliebiges Verzeichnis (Sprachmodelle, Meeting-Modelle).
    /// Ein abgebrochener Download wird über eine vorhandene .part-Datei per
    /// HTTP-Range fortgesetzt — bei Modellen im Gigabyte-Bereich erspart das
    /// komplette Neuladen. Kann der Server nicht fortsetzen, geht es von vorn
    /// los; die SHA256-Prüfung fängt jederzeit einen kaputten Stand ab.
    public static async Task DownloadTo(
        ModelInfo info, string dir, IProgress<double>? progress, CancellationToken ct = default)
    {
        Directory.CreateDirectory(dir);
        var dest = Path.Combine(dir, info.File);
        var part = dest + ".part";
        var url = info.Url ?? BaseUrl + info.File;

        long start = File.Exists(part) ? new FileInfo(part).Length : 0;
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        if (start > 0) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(start, null);
        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        if (start > 0 && resp.StatusCode != System.Net.HttpStatusCode.PartialContent)
            start = 0; // Server ignoriert Range: neu beginnen
        resp.EnsureSuccessStatusCode();
        long total = resp.Content.Headers.ContentLength ?? info.Size;
        if (start > 0) total += start; // Content-Length des Reststücks

        await using var input = await resp.Content.ReadAsStreamAsync(ct);
        await using var output = start > 0 ? new FileStream(part, FileMode.Append, FileAccess.Write) : File.Create(part);
        var buf = new byte[1 << 16];
        long done = start;
        int n;
        while ((n = await input.ReadAsync(buf, ct)) > 0)
        {
            await output.WriteAsync(buf.AsMemory(0, n), ct);
            done += n;
            if (total > 0) progress?.Report(Math.Min(1.0, (double)done / total));
        }

        if (total > 0 && done != total)
        {
            if (start > 0 && done < start) File.Delete(part); // fortsetzen ging schief
            throw new InvalidOperationException($"Download unvollständig ({done}/{total} Bytes)");
        }

        if (!string.IsNullOrEmpty(info.Sha256))
        {
            await output.DisposeAsync();
            string hash;
            await using (var fs = File.OpenRead(part))
                hash = Convert.ToHexString(await SHA256.HashDataAsync(fs)).ToLowerInvariant();
            if (hash != info.Sha256.ToLowerInvariant())
            {
                File.Delete(part);
                throw new InvalidOperationException("Prüfsumme falsch — Download beschädigt");
            }
        }

        if (File.Exists(dest)) File.Delete(dest);
        File.Move(part, dest);
    }
}
