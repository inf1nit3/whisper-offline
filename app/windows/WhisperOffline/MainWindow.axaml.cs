using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;

namespace WhisperOffline;

public partial class MainWindow : Window
{
    private readonly AudioRecorder recorder = new();
    private string language = "auto";
    private bool busy = false;

    public MainWindow()
    {
        InitializeComponent();
        LanguageBox.SelectionChanged += (_, _) =>
        {
            language = (LanguageBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "auto";
        };
        ModelLabel.Text = $"Modell: {Path.GetFileName(WhisperCli.ModelPath)} " +
                          $"({(WhisperCli.ModelPathExists ? "gefunden" : "FEHLT!")})";
    }

    private string Lang => language == "auto" ? "" : language;

    private async void OnRecordClick(object? sender, RoutedEventArgs e)
    {
        if (busy) return;
        if (!recorder.IsRecording)
        {
            if (!recorder.Start())
            {
                StatusLabel.Text = "Mikrofon konnte nicht geöffnet werden.";
                return;
            }
            RecordButton.Content = "⏹  Aufnahme beenden";
            StatusLabel.Text = "Aufnahme läuft…";
            return;
        }

        busy = true;
        var samples = recorder.Stop();
        RecordButton.Content = "🎙  Aufnahme starten";
        StatusLabel.Text = $"Transkribiere {samples.Length / 16000f:F1} s Audio…";
        var lang = Lang;
        var result = await Task.Run(() =>
        {
            var wav = Path.Combine(Path.GetTempPath(), "whisper_offline_rec.wav");
            WavWriter.Write16kMono(wav, samples);
            return WhisperCli.Transcribe(wav, lang, out var err) + (err.Length > 0 ? $"\n[{err}]" : "");
        });
        AppendTranscript(result);
        StatusLabel.Text = $"Fertig ({samples.Length / 16000f:F1} s Audio transkribiert).";
        busy = false;
    }

    private async void OnFileClick(object? sender, RoutedEventArgs e)
    {
        if (busy) return;
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Audio- oder Videodatei wählen",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType("Audio/Video")
                {
                    Patterns = new[] { "*.wav", "*.mp3", "*.flac", "*.ogg", "*.m4a", "*.mp4", "*.mkv", "*.webm" }
                },
                new FilePickerFileType("Alle Dateien") { Patterns = new[] { "*.*" } }
            }
        });
        if (files.Count == 0) return;

        busy = true;
        FileButton.IsEnabled = RecordButton.IsEnabled = false;
        var path = files[0].TryGetLocalPath();
        StatusLabel.Text = $"Transkribiere „{Path.GetFileName(path)}“…";
        var lang = Lang;
        var result = await Task.Run(() => WhisperCli.Transcribe(path!, lang, out var err)
                                                + (err.Length > 0 ? $"\n[{err}]" : ""));
        AppendTranscript(result);
        StatusLabel.Text = "Fertig.";
        FileButton.IsEnabled = RecordButton.IsEnabled = true;
        busy = false;
    }

    private void AppendTranscript(string text)
    {
        var t = text.Trim();
        if (t.Length == 0) return;
        TranscriptBox.Text = TranscriptBox.Text?.Length > 0
            ? TranscriptBox.Text + "\n\n" + t
            : t;
    }

    private async void OnCopyClick(object? sender, RoutedEventArgs e)
    {
        if (TranscriptBox.Text?.Length > 0)
            await Clipboard!.SetTextAsync(TranscriptBox.Text);
    }

    private void OnClearClick(object? sender, RoutedEventArgs e) => TranscriptBox.Text = "";
}
