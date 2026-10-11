using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace WhisperOffline;

/// Zwischenablage direkt über Win32: sichern, setzen, wiederherstellen.
///
/// Gesichert werden alle Formate, die als Speicherblock vorliegen (Text,
/// HTML, RTF, Dateilisten, Bilder als DIB …). GDI-Handles (CF_BITMAP u. ä.)
/// erzeugt Windows aus dem DIB ohnehin neu.
internal static class ClipboardSnapshot
{
    [DllImport("user32.dll", SetLastError = true)] private static extern bool OpenClipboard(IntPtr owner);
    [DllImport("user32.dll")] private static extern bool CloseClipboard();
    [DllImport("user32.dll")] private static extern bool EmptyClipboard();
    [DllImport("user32.dll")] private static extern uint EnumClipboardFormats(uint format);
    [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint format);
    [DllImport("user32.dll")] private static extern IntPtr SetClipboardData(uint format, IntPtr mem);
    [DllImport("user32.dll")] private static extern uint GetClipboardSequenceNumber();
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr mem);
    [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr mem);
    [DllImport("kernel32.dll")] private static extern UIntPtr GlobalSize(IntPtr mem);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalAlloc(uint flags, UIntPtr bytes);

    private const uint GMEM_MOVEABLE = 0x0002;
    private const uint CF_UNICODETEXT = 13;

    public static uint Sequence => OperatingSystem.IsWindows() ? GetClipboardSequenceNumber() : 0;

    /// Keine Speicherblöcke: Bitmaps, Metafiles, Paletten, Owner-Display, GDI-Objekte.
    private static bool IsHandleFormat(uint f) =>
        f is 2 or 3 or 9 or 14 or 0x80 or 0x82 or 0x83 or 0x8E || (f >= 0x300 && f <= 0x3FF);

    private static bool Open()
    {
        for (int i = 0; i < 10; i++)
        {
            if (OpenClipboard(IntPtr.Zero)) return true;
            Thread.Sleep(30); // eine andere App hält die Zwischenablage gerade
        }
        return false;
    }

    public static List<(uint Format, byte[] Data)>? Capture()
    {
        if (!OperatingSystem.IsWindows() || !Open()) return null;
        var list = new List<(uint, byte[])>();
        try
        {
            uint f = 0;
            while ((f = EnumClipboardFormats(f)) != 0)
            {
                if (IsHandleFormat(f)) continue;
                var h = GetClipboardData(f);
                if (h == IntPtr.Zero) continue;
                var size = (int)GlobalSize(h).ToUInt64();
                var p = GlobalLock(h);
                if (p == IntPtr.Zero || size <= 0) continue;
                try
                {
                    var data = new byte[size];
                    Marshal.Copy(p, data, 0, size);
                    list.Add((f, data));
                }
                finally { GlobalUnlock(h); }
            }
        }
        finally { CloseClipboard(); }
        return list;
    }

    public static void Restore(List<(uint Format, byte[] Data)> data)
    {
        if (!OperatingSystem.IsWindows() || !Open()) return;
        try
        {
            EmptyClipboard();
            foreach (var (format, bytes) in data) SetBlock(format, bytes);
        }
        finally { CloseClipboard(); }
    }

    public static bool SetText(string text)
    {
        if (!OperatingSystem.IsWindows() || !Open()) return false;
        try
        {
            EmptyClipboard();
            return SetBlock(CF_UNICODETEXT, Encoding.Unicode.GetBytes(text + "\0"));
        }
        finally { CloseClipboard(); }
    }

    public static string? GetText()
    {
        if (!OperatingSystem.IsWindows() || !Open()) return null;
        try
        {
            var h = GetClipboardData(CF_UNICODETEXT);
            if (h == IntPtr.Zero) return null;
            var p = GlobalLock(h);
            try { return p == IntPtr.Zero ? null : Marshal.PtrToStringUni(p); }
            finally { GlobalUnlock(h); }
        }
        finally { CloseClipboard(); }
    }

    private static bool SetBlock(uint format, byte[] bytes)
    {
        var mem = GlobalAlloc(GMEM_MOVEABLE, (UIntPtr)bytes.Length);
        if (mem == IntPtr.Zero) return false;
        var p = GlobalLock(mem);
        Marshal.Copy(bytes, 0, p, bytes.Length);
        GlobalUnlock(mem);
        // Bei Erfolg gehört der Block dem System, sonst bleibt er hier liegen
        // (selten, und ein paar KB) — kein GlobalFree nach Übergabe.
        return SetClipboardData(format, mem) != IntPtr.Zero;
    }
}

/// Vordergrundfenster: Prozess und Titel, für App-Kontext und Enter-Liste.
/// Der Titel wird nur zur Moduswahl gelesen und nirgends gespeichert.
internal static class ForegroundInfo
{
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr hWnd, StringBuilder text, int max);

    /// Prozessname klein und ohne .exe, z. B. "whatsapp"; "" wenn unbekannt.
    public static string Process(IntPtr hwnd)
    {
        if (!OperatingSystem.IsWindows() || hwnd == IntPtr.Zero) return "";
        try
        {
            GetWindowThreadProcessId(hwnd, out var pid);
            using var p = System.Diagnostics.Process.GetProcessById((int)pid);
            return p.ProcessName.ToLowerInvariant();
        }
        catch { return ""; }
    }

    public static string Title(IntPtr hwnd)
    {
        if (!OperatingSystem.IsWindows() || hwnd == IntPtr.Zero) return "";
        var sb = new StringBuilder(512);
        GetWindowTextW(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    /// Laufende Programme mit Fenster, für die Auswahl in den Einstellungen.
    public static List<string> RunningApps() =>
        System.Diagnostics.Process.GetProcesses()
            .Where(p => { try { return p.MainWindowHandle != IntPtr.Zero; } catch { return false; } })
            .Select(p => p.ProcessName.ToLowerInvariant())
            .Distinct().OrderBy(n => n).ToList();
}

/// Einfügen ins Zielfenster: Text in die Zwischenablage, Strg+V, optional
/// Enter, und danach die vorherige Zwischenablage zurück.
internal static class Paster
{
    public static async Task PasteAsync(IntPtr target, string text, AppSettings s)
    {
        var saved = s.PreserveClipboard ? ClipboardSnapshot.Capture() : null;
        ClipboardSnapshot.SetText(text);
        uint ours = ClipboardSnapshot.Sequence;
        await Task.Delay(120);
        PasteHelper.FocusWindow(target);
        await Task.Delay(150); // Fokuswechsel abwarten
        PasteHelper.CtrlV();
        var proc = ForegroundInfo.Process(target);
        if (proc.Length > 0 && s.AutoEnterApps.Any(a => a.Equals(proc, StringComparison.OrdinalIgnoreCase)))
        {
            await Task.Delay(150);
            PasteHelper.Enter();
        }
        if (saved != null)
        {
            // Die Ziel-App liest die Zwischenablage nach Strg+V asynchron —
            // erst danach zurücklegen, und nur, wenn niemand sie inzwischen geändert hat.
            await Task.Delay(450);
            if (ClipboardSnapshot.Sequence == ours) ClipboardSnapshot.Restore(saved);
        }
    }

    /// Markierten Text im Vordergrundfenster per Strg+C holen. Liefert den
    /// Text und die vorher gesicherte Zwischenablage (zum Zurücklegen).
    public static async Task<(string? Text, List<(uint, byte[])>? Saved)> CopySelectionAsync()
    {
        var saved = ClipboardSnapshot.Capture();
        uint before = ClipboardSnapshot.Sequence;
        // Die Hotkey-Modifikatoren können noch gedrückt sein — kurz warten
        await Task.Delay(180);
        PasteHelper.CtrlC();
        for (int i = 0; i < 20 && ClipboardSnapshot.Sequence == before; i++) await Task.Delay(25);
        var text = ClipboardSnapshot.Sequence == before ? null : ClipboardSnapshot.GetText();
        return (text, saved);
    }
}
