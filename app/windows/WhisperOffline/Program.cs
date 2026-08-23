using Avalonia;

namespace WhisperOffline;

internal static class Program
{
    /// Mit --tray startet die App ohne Fenster direkt in den Infobereich und
    /// wartet dort auf den Hotkey. Der Autostart-Eintrag benutzt genau das.
    public static bool StartHidden { get; private set; }

    [STAThread]
    public static void Main(string[] args)
    {
        StartHidden = args.Any(a => a is "--tray" or "-t");
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace();
}
