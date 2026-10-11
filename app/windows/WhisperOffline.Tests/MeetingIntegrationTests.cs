using WhisperOffline;
using Xunit;

/// Läuft nur mit WHISPER_TEST_MEETING=<wav> und WHISPER_TEST_MODELS=<Ordner mit den
/// beiden Sprecher-Modellen> — prüft die sherpa-onnx-Anbindung und den PDF-Export.
public class MeetingIntegrationTests
{
    [Fact] public void DiarizesTwoVoices()
    {
        var wav = Environment.GetEnvironmentVariable("WHISPER_TEST_MEETING");
        var models = Environment.GetEnvironmentVariable("WHISPER_TEST_MODELS");
        if (wav == null || models == null) return;
        Directory.CreateDirectory(Diarizer.ModelDir);
        foreach (var f in Directory.GetFiles(models, "*.onnx")) File.Copy(f, Path.Combine(Diarizer.ModelDir, Path.GetFileName(f)), true);
        Assert.True(Diarizer.Available);
        using var d = new Diarizer(0);
        var r = d.Run(wav, 0, null, CancellationToken.None);
        var speakers = r.Turns.Select(t => t.Speaker).Distinct().Count();
        Assert.Equal(2, speakers);
        Assert.Equal(2, r.Embeddings.Count);
        // Abwechselnd: A B A B
        var order = r.Turns.OrderBy(t => t.Start).Select(t => t.Speaker).ToList();
        Assert.Equal(order[0], order[2]);
        Assert.NotEqual(order[0], order[1]);
    }

    [Fact] public void WritesPdf()
    {
        var path = Path.Combine(Path.GetTempPath(), "whisper-test.pdf");
        PdfWriter.Write(path, "Test-Meeting Quartalszahlen",
            "## Zusammenfassung\nDer Umsatz ist um 12 % gestiegen – Kosten 80.000 €. Größe, Übersicht (Klammern) \\ Backslash.\n\n" +
            string.Join("\n", Enumerable.Range(1, 120).Select(i => $"Sprecher {i % 2 + 1}: Zeile {i} mit etwas Text, damit der Umbruch greift und mehrere Seiten entstehen.")));
        var bytes = File.ReadAllBytes(path);
        var head = System.Text.Encoding.ASCII.GetString(bytes, 0, 8);
        Assert.StartsWith("%PDF-1.4", head);
        Assert.Contains("%%EOF", System.Text.Encoding.ASCII.GetString(bytes[^10..]));
    }
}
