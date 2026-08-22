using System.Runtime.InteropServices;

namespace WhisperOffline;

/// Systemweiter Hotkey (Strg+Alt+Leertaste) über ein message-only Fenster.
/// Läuft auf eigenem Thread mit eigener Nachrichtenpumpe.
internal static class HotkeyHook
{
    private const uint WM_HOTKEY = 0x0312;
    private const uint MOD_ALT = 0x0001;
    private const uint MOD_CONTROL = 0x0002;
    private const uint VK_SPACE = 0x20;

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct WNDCLASSW
    {
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int ptX;
        public int ptY;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern ushort RegisterClassW(ref WNDCLASSW wc);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CreateWindowExW(uint dwExStyle, string lpClassName,
        string lpWindowName, uint dwStyle, int x, int y, int w, int h,
        IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern short RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetModuleHandleW(string? lpModuleName);

    [DllImport("user32.dll")]
    private static extern int GetMessageW(out MSG msg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    private static WndProcDelegate? _proc; // statisch halten, sonst GC → Crash
    private static Action? _onHotkey;

    public static void Start(Action onHotkey)
    {
        if (_onHotkey != null) return;
        _onHotkey = onHotkey;
        var t = new Thread(Run) { IsBackground = true, Name = "hotkey" };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
    }

    private static void Run()
    {
        _proc = WndProc;
        var wc = new WNDCLASSW
        {
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_proc),
            hInstance = GetModuleHandleW(null),
            lpszClassName = "WhisperOfflineHotkeyWnd",
        };
        RegisterClassW(ref wc);
        // HWND_MESSAGE = new IntPtr(-3): unsichtbares Nachrichtenfenster
        var hwnd = CreateWindowExW(0, wc.lpszClassName, "", 0, 0, 0, 0, 0,
            new IntPtr(-3), IntPtr.Zero, wc.hInstance, IntPtr.Zero);
        RegisterHotKey(hwnd, 1, MOD_CONTROL | MOD_ALT, VK_SPACE);
        while (GetMessageW(out _, IntPtr.Zero, 0, 0) > 0) { }
    }

    private static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_HOTKEY && wParam.ToInt32() == 1)
            _onHotkey?.Invoke();
        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }
}
