using System.Runtime.InteropServices;

namespace WhisperOffline;

/// Frei belegbarer systemweiter Hotkey über ein message-only Fenster.
/// Läuft auf eigenem Thread mit eigener Nachrichtenpumpe; die Kombination
/// lässt sich zur Laufzeit ohne Neustart umsetzen.
internal static class HotkeyHook
{
    private const uint WM_HOTKEY = 0x0312;
    private const uint WM_APP_REBIND = 0x8001;
    private const int HOTKEY_ID = 1;

    public const uint MOD_ALT = 0x0001;
    public const uint MOD_CONTROL = 0x0002;
    public const uint MOD_SHIFT = 0x0004;
    public const uint MOD_WIN = 0x0008;
    /// Ohne diese Flagge feuert der Hotkey beim Gedrückthalten dauernd.
    private const uint MOD_NOREPEAT = 0x4000;

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
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetModuleHandleW(string? lpModuleName);

    [DllImport("user32.dll")]
    private static extern int GetMessageW(out MSG msg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool PostMessageW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    private static WndProcDelegate? _proc; // statisch halten, sonst GC → Crash
    private static Action? _onHotkey;
    private static IntPtr _hwnd = IntPtr.Zero;

    private static uint _modifiers = MOD_CONTROL | MOD_ALT;
    private static uint _vk = 0x20; // VK_SPACE

    /// Wurde die zuletzt gewünschte Kombination vom System angenommen?
    /// false heißt in der Regel: eine andere Anwendung hält sie bereits.
    public static bool LastBindOk { get; private set; } = true;

    public static void Start(Action onHotkey, uint modifiers, uint vk)
    {
        _modifiers = modifiers;
        _vk = vk;
        if (_onHotkey != null) { Rebind(modifiers, vk); return; }

        _onHotkey = onHotkey;
        var t = new Thread(Run) { IsBackground = true, Name = "hotkey" };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
    }

    /// Neue Kombination setzen. Muss auf dem Hotkey-Thread passieren —
    /// RegisterHotKey bindet an den Thread, der das Fenster erzeugt hat.
    public static void Rebind(uint modifiers, uint vk)
    {
        _modifiers = modifiers;
        _vk = vk;
        if (_hwnd != IntPtr.Zero)
            PostMessageW(_hwnd, WM_APP_REBIND, IntPtr.Zero, IntPtr.Zero);
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
        _hwnd = CreateWindowExW(0, wc.lpszClassName, "", 0, 0, 0, 0, 0,
            new IntPtr(-3), IntPtr.Zero, wc.hInstance, IntPtr.Zero);
        ApplyBinding();

        // Blockiert bis zur nächsten Nachricht — der Thread kostet im Leerlauf
        // keine CPU, es wird nicht gepollt.
        while (GetMessageW(out _, IntPtr.Zero, 0, 0) > 0) { }
    }

    private static void ApplyBinding()
    {
        UnregisterHotKey(_hwnd, HOTKEY_ID);
        LastBindOk = RegisterHotKey(_hwnd, HOTKEY_ID, _modifiers | MOD_NOREPEAT, _vk);
    }

    private static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_HOTKEY && wParam.ToInt32() == HOTKEY_ID)
            _onHotkey?.Invoke();
        else if (msg == WM_APP_REBIND)
            ApplyBinding();
        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }
}
