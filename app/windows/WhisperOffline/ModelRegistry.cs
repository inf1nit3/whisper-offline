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
    List<string> Cons)
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

    private record ManifestDoc(List<ModelInfo> Models);

    private static string BaseUrl =>
        ModelConfig.ManifestUrl[..(ModelConfig.ManifestUrl.LastIndexOf('/') + 1)];

    /// Download mit Fortschritt und SHA256-Prüfung, atomar per .part-Datei.
    public static async Task DownloadModel(
        ModelInfo info, IProgress<double>? progress, CancellationToken ct = default)
    {
        Directory.CreateDirectory(ModelsDir);
        var dest = Path.Combine(ModelsDir, info.File);
        var part = dest + ".part";

        using var resp = await http.GetAsync(BaseUrl + info.File,
            HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        long total = resp.Content.Headers.ContentLength ?? info.Size;

        await using var input = await resp.Content.ReadAsStreamAsync(ct);
        await using var output = File.Create(part);
        var buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = await input.ReadAsync(buf, ct)) > 0)
        {
            await output.WriteAsync(buf.AsMemory(0, n), ct);
            done += n;
            if (total > 0) progress?.Report((double)done / total);
        }

        if (total > 0 && done != total)
            throw new InvalidOperationException($"Download unvollständig ({done}/{total} Bytes)");

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
