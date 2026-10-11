using System.Runtime.InteropServices;

namespace WhisperOffline;

/// Was ein Hotkey auslöst.
public enum HotkeyAction { Dictate = 1, DictateEnhance = 2, Transform = 3, SingleKey = 4 }

/// Gedrückt, losgelassen — oder abgebrochen (bei der Einzeltaste: eine andere
/// Taste kam dazu, es war also ein normaler Kurzbefehl wie Strg+C).
public enum HotkeyEdge { Pressed, Released, Cancelled }

/// Frei belegbare systemweite Hotkeys über ein message-only Fenster.
/// Läuft auf eigenem Thread mit eigener Nachrichtenpumpe; die Belegungen
/// lassen sich zur Laufzeit ohne Neustart umsetzen.
///
/// Push-to-Talk braucht das Loslassen. RegisterHotKey meldet nur das Drücken —
/// danach wird der Zustand der Taste abgefragt, bis sie oben ist. Eine
/// Einzeltaste (z. B. rechte Strg) lässt sich gar nicht registrieren; sie wird
/// ebenso abgefragt. Beides ohne systemweiten Tastatur-Hook, der jede
/// Eingabe des Rechners sehen würde.
internal static class HotkeyHook
{
    private const uint WM_HOTKEY = 0x0312;
    private const uint WM_APP_REBIND = 0x8001;

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

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int vKey);

    private static WndProcDelegate? _proc; // statisch halten, sonst GC → Crash
    private static Action<HotkeyAction, HotkeyEdge>? _handler;
    private static IntPtr _hwnd = IntPtr.Zero;

    private static readonly Dictionary<HotkeyAction, (uint mods, uint vk)> _bindings = new();
    private static readonly Dictionary<HotkeyAction, bool> _ok = new();
    private static volatile uint _singleVk;

    /// Wurde die Belegung vom System angenommen? false heißt in der Regel:
    /// eine andere Anwendung hält die Kombination bereits.
    public static bool BindOk(HotkeyAction a) => !_ok.TryGetValue(a, out var ok) || ok;

    /// Kompatibel zur bisherigen Oberfläche (Diktat-Hotkey).
    public static bool LastBindOk => BindOk(HotkeyAction.Dictate);

    public static void Start(Action<HotkeyAction, HotkeyEdge> handler)
    {
        if (!OperatingSystem.IsWindows() || _handler != null) return;
        _handler = handler;
        var t = new Thread(Run) { IsBackground = true, Name = "hotkey" };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        new Thread(PollSingleKey) { IsBackground = true, Name = "hotkey-single" }.Start();
    }

    /// Neue Belegung setzen; vk = 0 schaltet den Hotkey ab. Das eigentliche
    /// Registrieren passiert auf dem Hotkey-Thread (RegisterHotKey bindet an
    /// den Thread, der das Fenster erzeugt hat).
    public static void SetBinding(HotkeyAction a, uint modifiers, uint vk)
    {
        lock (_bindings) _bindings[a] = (modifiers, vk);
        if (_hwnd != IntPtr.Zero) PostMessageW(_hwnd, WM_APP_REBIND, IntPtr.Zero, IntPtr.Zero);
    }

    /// Einzeltaste als Diktat-Auslöser (z. B. 0xA3 = rechte Strg); 0 = aus.
    public static void SetSingleKey(uint vk) => _singleVk = vk;

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
        ApplyBindings();

        // Blockiert bis zur nächsten Nachricht — der Thread kostet im Leerlauf
        // keine CPU, es wird nicht gepollt.
        while (GetMessageW(out _, IntPtr.Zero, 0, 0) > 0) { }
    }

    private static void ApplyBindings()
    {
        KeyValuePair<HotkeyAction, (uint mods, uint vk)>[] all;
        lock (_bindings) all = _bindings.ToArray();
        foreach (HotkeyAction a in new[] { HotkeyAction.Dictate, HotkeyAction.DictateEnhance, HotkeyAction.Transform })
        {
            UnregisterHotKey(_hwnd, (int)a);
            var b = all.FirstOrDefault(x => x.Key == a).Value;
            _ok[a] = b.vk == 0 || RegisterHotKey(_hwnd, (int)a, b.mods | MOD_NOREPEAT, b.vk);
        }
    }

    private static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_HOTKEY)
        {
            var a = (HotkeyAction)wParam.ToInt32();
            _handler?.Invoke(a, HotkeyEdge.Pressed);
            uint vk;
            lock (_bindings) vk = _bindings.TryGetValue(a, out var b) ? b.vk : 0;
            if (vk != 0) WatchRelease(a, vk);
        }
        else if (msg == WM_APP_REBIND)
        {
            ApplyBindings();
        }
        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }

    private static bool IsDown(uint vk) => (GetAsyncKeyState((int)vk) & 0x8000) != 0;

    /// Wartet, bis die Haupttaste losgelassen ist (Push-to-Talk).
    private static void WatchRelease(HotkeyAction a, uint vk)
    {
        Task.Run(async () =>
        {
            while (IsDown(vk)) await Task.Delay(25);
            _handler?.Invoke(a, HotkeyEdge.Released);
        });
    }

    /// Einzeltaste: gedrückt → Pressed; losgelassen → Released. Kommt
    /// währenddessen eine andere Taste dazu, war es ein Kurzbefehl → Cancelled.
    private static void PollSingleKey()
    {
        bool down = false, other = false;
        while (true)
        {
            Thread.Sleep(25);
            uint vk = _singleVk;
            if (vk == 0) { down = false; continue; }
            bool now = IsDown(vk);
            if (now && !down)
            {
                down = true;
                other = false;
                _handler?.Invoke(HotkeyAction.SingleKey, HotkeyEdge.Pressed);
            }
            else if (now && !other && AnyOtherKeyDown(vk))
            {
                other = true;
                _handler?.Invoke(HotkeyAction.SingleKey, HotkeyEdge.Cancelled);
            }
            else if (!now && down)
            {
                down = false;
                if (!other) _handler?.Invoke(HotkeyAction.SingleKey, HotkeyEdge.Released);
            }
        }
    }

    /// Irgendeine Taste außer der Auslösetaste (und deren Seitenvarianten) gedrückt?
    private static bool AnyOtherKeyDown(uint trigger)
    {
        for (uint k = 0x08; k <= 0xFE; k++)
        {
            if (k == trigger) continue;
            // Allgemeine Modifikator-Codes melden die Auslösetaste mit
            if (k is 0x10 or 0x11 or 0x12) continue;
            if (IsDown(k)) return true;
        }
        return false;
    }
}
