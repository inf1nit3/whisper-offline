using System.Runtime.InteropServices;
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

        // Nur eine Instanz: Liegt bereits eine im Infobereich, würde ein
        // zweiter Start deren Hotkey-Registrierung stören und ein zweites
        // Tray-Symbol hinterlassen. Stattdessen das Fenster der bestehenden
        // Instanz nach vorn holen.
        if (!SingleInstance.TryAcquire())
        {
            SingleInstance.FocusRunning();
            return;
        }
        try
        {
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        }
        finally
        {
            SingleInstance.Release();
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace();
}

/// Benannter Mutex pro Anmeldesitzung; Fenster per Titel finden und aktivieren.
internal static class SingleInstance
{
    private static Mutex? mutex;
    private const string MutexName = @"Local\ScheisssewassersWhisper";
    private const string WindowTitle = "Scheisssewasser's Whisper";

    public static bool TryAcquire()
    {
        mutex = new Mutex(true, MutexName, out bool first);
        if (first) return true;
        mutex.Dispose();
        mutex = null;
        return false;
    }

    public static void Release()
    {
        try { mutex?.ReleaseMutex(); } catch { }
        mutex?.Dispose();
        mutex = null;
    }

    /// Fenster der schon laufenden Instanz zeigen (auch wenn es im
    /// Infobereich versteckt ist) und in den Vordergrund holen.
    public static void FocusRunning()
    {
        if (!OperatingSystem.IsWindows()) return;
        var hwnd = FindWindow(null, WindowTitle);
        if (hwnd == IntPtr.Zero) return;
        _ = ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
    }

    private const uint SW_RESTORE = 9;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr FindWindow(string? className, string windowName);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, uint nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
