using System.Diagnostics;
using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;

namespace WhisperOffline;

// Stapel-Transkription: viele Dateien nacheinander, je eine .txt im Zielordner.
// Diktate haben Vorrang — der Stapel pausiert und setzt danach fort.
public partial class MainWindow
{
    private sealed class BatchItem
    {
        public string Path = "";
        public string State = "wartet"; // wartet, läuft, fertig, fehlgeschlagen, übersprungen
        public string? Error;
    }

    private readonly List<BatchItem> batch = new();
    private string batchTarget = "";
    private bool batchRunning;
    private CancellationTokenSource? batchCts;
    private string batchStatus = "";

    private static readonly string[] MediaExt =
        { ".wav", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".opus", ".wma", ".amr", ".3gp", ".mp4", ".mkv", ".webm", ".mov", ".avi" };

    private void OnOpenBatch(object? sender, RoutedEventArgs e) => ShowBatch();

    private void ShowBatch()
    {
        var list = new StackPanel { Spacing = 8 };
        foreach (var it in batch)
        {
            var remove = new Button { Content = "✕", Classes = { "chip" }, FontSize = 11, IsEnabled = !batchRunning || it.State != "läuft" };
            remove.Click += (_, _) => { batch.Remove(it); ShowBatch(); };
            list.Children.Add(Card(new DockPanel
            {
                Children =
                {
                    remove.With(r => DockPanel.SetDock(r, Dock.Right)),
                    new TextBlock
                    {
                        Text = $"{(it.State switch { "fertig" => "✓", "fehlgeschlagen" => "✗", "läuft" => "▶", "übersprungen" => "–", _ => "·" })}  {Path.GetFileName(it.Path)}" +
                               (it.Error != null ? $"  — {it.Error}" : it.State is "wartet" ? "" : $"  ({it.State})"),
                        VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis,
                        Foreground = it.State == "fehlgeschlagen" ? ErrorBrush : it.State == "fertig" ? TertiaryBrush : null,
                    },
                },
            }));
        }
        if (batch.Count == 0) list.Children.Add(Card(Hint("Noch keine Dateien — „Dateien hinzufügen“ klicken oder Dateien direkt hierher ziehen.")));

        var add = new Button { Content = "+ Dateien hinzufügen …", Classes = { "chip" } };
        add.Click += async (_, _) =>
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "Audio- oder Videodateien wählen", AllowMultiple = true,
                FileTypeFilter = new[] { new FilePickerFileType("Audio/Video") { Patterns = MediaExt.Select(x => "*" + x).ToArray() } },
            });
            AddBatchFiles(files.Select(f => f.TryGetLocalPath()).Where(p => p != null)!);
        };
        var clear = new Button { Content = "Liste leeren", Classes = { "chip" }, IsEnabled = !batchRunning && batch.Count > 0 };
        clear.Click += (_, _) => { batch.Clear(); ShowBatch(); };
        var retry = new Button { Content = "Fehlgeschlagene wiederholen", Classes = { "chip" }, IsEnabled = !batchRunning && batch.Any(b => b.State == "fehlgeschlagen") };
        retry.Click += (_, _) => { foreach (var b in batch.Where(b => b.State == "fehlgeschlagen")) { b.State = "wartet"; b.Error = null; } ShowBatch(); };
        var target = new TextBlock { Text = batchTarget.Length > 0 ? "Zielordner: " + batchTarget : "Zielordner: noch nicht gewählt", VerticalAlignment = VerticalAlignment.Center, Foreground = SubtleBrush, FontSize = 12 };
        var choose = new Button { Content = "📂 Zielordner wählen …", Classes = { "chip" } };
        choose.Click += async (_, _) =>
        {
            var dirs = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = "Wo sollen die Transkripte hin?" });
            var d = dirs.FirstOrDefault()?.TryGetLocalPath();
            if (d != null) { batchTarget = d; ShowBatch(); }
        };
        var start = new Button
        {
            Content = batchRunning ? "Abbrechen" : "▶  Stapel starten",
            Classes = { batchRunning ? "chip" : "primaryAction" },
            IsEnabled = batchRunning || batch.Any(b => b.State == "wartet"),
        };
        start.Click += (_, _) =>
        {
            if (batchRunning) { batchCts?.Cancel(); WhisperNative.Cancel(); return; }
            if (batchTarget.Length == 0) { batchStatus = "Bitte zuerst einen Zielordner wählen."; ShowBatch(); return; }
            _ = RunBatchAsync();
        };
        var open = new Button { Content = "Ordner öffnen", Classes = { "chip" }, IsEnabled = batchTarget.Length > 0 };
        open.Click += (_, _) => OpenExternalPath(batchTarget);
        var statusText = new TextBlock { Text = batchStatus, TextWrapping = TextWrapping.Wrap, FontWeight = FontWeight.SemiBold, VerticalAlignment = VerticalAlignment.Center };

        var content = new DockPanel
        {
            Children =
            {
                Card(new StackPanel
                {
                    Spacing = 10,
                    Children =
                    {
                        new WrapPanel { ItemSpacing = 8, LineSpacing = 6, Children = { add, clear, retry } },
                        new WrapPanel { ItemSpacing = 8, LineSpacing = 6, Children = { choose, open, target } },
                        new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { start, statusText } },
                        Hint("Je Datei entsteht eine gleichnamige .txt. Läuft ein Diktat, pausiert der Stapel und macht danach weiter."),
                    },
                }).With(s => { s.Margin = new Thickness(0, 0, 0, 12); DockPanel.SetDock(s, Dock.Top); }),
                Scroll(list),
            },
        };
        DragDrop.SetAllowDrop(content, true);
        content.AddHandler(DragDrop.DropEvent, (_, ev) =>
        {
            var files = ev.Data.GetFiles();
            if (files != null) AddBatchFiles(files.Select(f => f.TryGetLocalPath()).Where(p => p != null)!);
        });
        ShowPanel("Stapel-Transkription", $"{batch.Count(b => b.State == "fertig")} von {batch.Count} fertig", content);
    }

    private void AddBatchFiles(IEnumerable<string> paths)
    {
        foreach (var p in paths)
        {
            if (batch.Any(b => b.Path == p)) continue;
            var item = new BatchItem { Path = p };
            if (!MediaExt.Contains(Path.GetExtension(p).ToLowerInvariant())) { item.State = "übersprungen"; item.Error = "kein Audio-/Videoformat"; }
            batch.Add(item);
        }
        if (batchTarget.Length == 0 && batch.Count > 0) batchTarget = Path.GetDirectoryName(batch[0].Path) ?? "";
        ShowBatch();
    }

    private async Task RunBatchAsync()
    {
        if (!File.Exists(WhisperCli.SelectedModel)) { batchStatus = "Erst ein Modell laden."; ShowBatch(); return; }
        batchRunning = true;
        batchCts = new CancellationTokenSource();
        var ct = batchCts.Token;
        var clock = Stopwatch.StartNew();
        double doneAudio = 0;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var it = batch.FirstOrDefault(b => b.State == "wartet");
                if (it == null) break;
                // Diktat, Meeting oder Aufnahme im Fenster haben Vorrang
                while ((dictating || busy || MeetingActive || recorder.IsRecording) && !ct.IsCancellationRequested)
                {
                    batchStatus = "Pausiert, solange du diktierst …";
                    RefreshBatchIfOpen();
                    await Task.Delay(400);
                }
                it.State = "läuft";
                int pos = batch.IndexOf(it) + 1;
                var remaining = batch.Count(b => b.State is "wartet" or "läuft");
                var eta = doneAudio > 0 ? TimeSpan.FromSeconds(clock.Elapsed.TotalSeconds / doneAudio * remaining * (doneAudio / Math.Max(1, batch.Count(b => b.State == "fertig")))) : (TimeSpan?)null;
                batchStatus = $"Datei {pos} von {batch.Count}" + (eta is { } e ? $" · noch etwa {(int)e.TotalMinutes} min {e.Seconds} s" : "");
                RefreshBatchIfOpen();
                try
                {
                    var samples = await Task.Run(() => AudioFileDecoder.TryDecode(it.Path), ct);
                    string text;
                    if (samples is { Length: > 0 })
                        text = await Task.Run(() => Transcriber.Transcribe(samples, settings, Lang, settings.Translate), ct);
                    else if (samples != null) throw new InvalidOperationException("keine Audiodaten");
                    else
                    {
                        string err = "";
                        text = await Task.Run(() => Transcriber.Post(WhisperCli.Transcribe(it.Path, Lang, out err,
                            TextMatching.Prompt(settings.Vocabulary, VocabularyDefaults.Lower), settings.Translate), settings), ct);
                        if (err.Length > 0 && text.Length == 0) throw new InvalidOperationException(err);
                    }
                    if (WhisperNative.WasCancelled)
                    {
                        // Abgebrochen wegen Diktat oder vom Nutzer: Datei bleibt in der Warteschlange
                        it.State = "wartet";
                        if (ct.IsCancellationRequested) break;
                        continue;
                    }
                    var outPath = Path.Combine(batchTarget, Path.GetFileNameWithoutExtension(it.Path) + ".txt");
                    await File.WriteAllTextAsync(outPath, text, ct);
                    it.State = "fertig";
                    doneAudio += (samples?.Length ?? 0) / 16000.0;
                    AddHistory(text, (samples?.Length ?? 0) / 16000f, "file");
                }
                catch (OperationCanceledException) { it.State = "wartet"; break; }
                catch (Exception ex) { it.State = "fehlgeschlagen"; it.Error = ex.Message; }
                RefreshBatchIfOpen();
            }
        }
        finally
        {
            batchRunning = false;
            int ok = batch.Count(b => b.State == "fertig"), failed = batch.Count(b => b.State == "fehlgeschlagen");
            batchStatus = ct.IsCancellationRequested ? "Abgebrochen." : $"Fertig: {ok} erfolgreich, {failed} fehlgeschlagen.";
            StatusLabel.Text = "Stapel: " + batchStatus;
            if (tray != null) tray.ToolTipText = "Scheisssewasser's Whisper — Stapel " + batchStatus;
            RefreshBatchIfOpen();
        }
    }

    private void RefreshBatchIfOpen()
    {
        if (PanelHost.IsVisible && PanelTitle.Text == "Stapel-Transkription") ShowBatch();
    }
}
