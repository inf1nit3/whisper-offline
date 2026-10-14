using System.Reflection;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using WhisperOffline;
using Xunit;

[assembly: AvaloniaTestApplication(typeof(UiTestApp))]

public class UiTestApp
{
    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>()
        .UseSkia()
        .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false });
}

/// Rendert die Bereiche des Hauptfensters zu PNG (WHISPER_UI_SHOTS=<Ordner>),
/// um das Layout ohne Windows-Rechner zu prüfen.
public class UiRenderTests
{
    private static void Shot(MainWindow w, string dir, string name)
    {
        Avalonia.Threading.Dispatcher.UIThread.RunJobs();
        w.CaptureRenderedFrame()?.Save(Path.Combine(dir, name + ".png"));
    }

    /// Der DispatcherTimer feuert headless kaum — Wellenform-Ticks direkt auslösen.
    private static void Settle(params WaveformView[] waves)
    {
        var tick = typeof(WaveformView).GetMethod("Tick", BindingFlags.NonPublic | BindingFlags.Instance)!;
        for (int i = 0; i < 30; i++)
        {
            Thread.Sleep(16);
            foreach (var wv in waves) tick.Invoke(wv, null);
            Avalonia.Threading.Dispatcher.UIThread.RunJobs();
        }
        AvaloniaHeadlessPlatform.ForceRenderTimerTick();
    }

    [AvaloniaFact] public void RenderPanels()
    {
        var dir = Environment.GetEnvironmentVariable("WHISPER_UI_SHOTS");
        if (dir == null) return;
        Directory.CreateDirectory(dir);
        File.WriteAllText(AppSettings.Path, "{\"OnboardingDone\":true,\"Vocabulary\":[\"Scheisssewasser\",\"Claude Code\"]," +
            "\"Snippets\":[{\"Trigger\":\"meine Adresse\",\"Text\":\"Musterstraße 1\"}],\"EnhanceHotkeyModifiers\":3,\"EnhanceHotkeyVk\":69}");
        var w = new MainWindow { Width = 900, Height = 760 };
        w.Show();
        var t = typeof(MainWindow);
        void Call(string m, params object[] a) => t.GetMethod(m, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(w, a);
        Call("ClosePicker");
        Shot(w, dir, "0-main");
        // Wellenform während einer Aufnahme (Timer laufen lassen, bis die Welle steht)
        var wave = (WaveformView)t.GetField("Waveform", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(w)!;
        wave.ShowRecording(() => 0.3f);
        Settle(wave);
        Shot(w, dir, "0-main-recording");
        wave.ShowIdle();
        Settle(wave);
        Call("ShowSettings"); Shot(w, dir, "1-settings");
        Call("ShowDictionary", 0); Shot(w, dir, "2-dictionary");
        Call("ShowDictionary", 2); Shot(w, dir, "3-snippets");
        Call("ShowStats", 30, null!); Shot(w, dir, "4-stats");
        Call("ShowHistory", "", 0, false); Shot(w, dir, "5-history");
        Call("ShowMeeting"); Shot(w, dir, "6-meeting");
        Call("ShowBatch"); Shot(w, dir, "7-batch");
        Call("ShowProfiles"); Shot(w, dir, "8-profiles");
        var o = new DictationOverlay();
        o.ShowRecording(() => 0.4f, null, "✨ E-Mail");
        o.SetLive("Hallo Herr Müller, vielen Dank für Ihre Nachricht, ich schicke Ihnen die Unterlagen bis Freitag");
        Settle((WaveformView)typeof(DictationOverlay).GetField("level", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(o)!);
        o.CaptureRenderedFrame()?.Save(Path.Combine(dir, "9-overlay.png"));
        var tw = new TransformWindow("hey, schick mir mal bis morgen die zahlen, sonst wird das nix mit der präsi", () => true, () => Task.FromResult<string?>(null));
        tw.Show();
        Avalonia.Threading.Dispatcher.UIThread.RunJobs();
        tw.CaptureRenderedFrame()?.Save(Path.Combine(dir, "10-transform.png"));
    }
}
