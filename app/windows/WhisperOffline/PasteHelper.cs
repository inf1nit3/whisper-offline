using System.Runtime.InteropServices;

namespace WhisperOffline;

/// Simuliert Tastendrücke (Strg+V) und liest/setzt das Vordergrundfenster.
internal static class PasteHelper
{
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_V = 0x56;

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public INPUTUNION u;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    public static IntPtr ForegroundWindow() => GetForegroundWindow();

    public static bool FocusWindow(IntPtr hWnd) =>
        hWnd != IntPtr.Zero && SetForegroundWindow(hWnd);

    /// Strg+V ans fokussierte Fenster senden (Einfügen in z. B. den Chat).
    public static void CtrlV()
    {
        Key(VK_CONTROL, down: true);
        Key(VK_V, down: true);
        Key(VK_V, down: false);
        Key(VK_CONTROL, down: false);
    }

    private static void Key(ushort vk, bool down)
    {
        var input = new INPUT
        {
            type = INPUT_KEYBOARD,
            u = new INPUTUNION
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    dwFlags = down ? 0 : KEYEVENTF_KEYUP,
                },
            },
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }
}
