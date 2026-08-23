using Avalonia.Input;

namespace WhisperOffline;

/// Übersetzt zwischen Avalonia-Tasteneingaben und den Win32-Codes, die
/// RegisterHotKey erwartet, und erzeugt die Beschriftung für die Oberfläche.
public static class HotkeySpec
{
    /// Reine Modifikatortasten taugen nicht als Hauptkurzbefehl.
    public static bool IsModifierKey(Key k) => k is
        Key.LeftCtrl or Key.RightCtrl or
        Key.LeftAlt or Key.RightAlt or
        Key.LeftShift or Key.RightShift or
        Key.LWin or Key.RWin or
        Key.System;

    public static uint ToModifiers(KeyModifiers m)
    {
        uint r = 0;
        if (m.HasFlag(KeyModifiers.Control)) r |= HotkeyHook.MOD_CONTROL;
        if (m.HasFlag(KeyModifiers.Alt)) r |= HotkeyHook.MOD_ALT;
        if (m.HasFlag(KeyModifiers.Shift)) r |= HotkeyHook.MOD_SHIFT;
        if (m.HasFlag(KeyModifiers.Meta)) r |= HotkeyHook.MOD_WIN;
        return r;
    }

    /// Avalonia-Key → virtueller Win32-Tastencode. 0 = nicht abbildbar.
    public static uint ToVk(Key k)
    {
        if (k >= Key.A && k <= Key.Z) return (uint) (0x41 + (k - Key.A));
        if (k >= Key.D0 && k <= Key.D9) return (uint) (0x30 + (k - Key.D0));
        if (k >= Key.F1 && k <= Key.F24) return (uint) (0x70 + (k - Key.F1));
        if (k >= Key.NumPad0 && k <= Key.NumPad9) return (uint) (0x60 + (k - Key.NumPad0));

        return k switch
        {
            Key.Space => 0x20,
            Key.Enter => 0x0D,
            Key.Tab => 0x09,
            Key.Escape => 0x1B,
            Key.Back => 0x08,
            Key.Insert => 0x2D,
            Key.Delete => 0x2E,
            Key.Home => 0x24,
            Key.End => 0x23,
            Key.PageUp => 0x21,
            Key.PageDown => 0x22,
            Key.Left => 0x25,
            Key.Up => 0x26,
            Key.Right => 0x27,
            Key.Down => 0x28,
            Key.OemPlus => 0xBB,
            Key.OemMinus => 0xBD,
            Key.OemComma => 0xBC,
            Key.OemPeriod => 0xBE,
            Key.Oem1 => 0xBA,
            Key.Oem2 => 0xBF,
            Key.Oem3 => 0xC0,
            Key.Oem4 => 0xDB,
            Key.Oem5 => 0xDC,
            Key.Oem6 => 0xDD,
            Key.Oem7 => 0xDE,
            Key.Pause => 0x13,
            Key.Scroll => 0x91,
            _ => 0,
        };
    }

    /// "Strg + Alt + Leertaste"
    public static string Describe(uint modifiers, uint vk)
    {
        var parts = new List<string>();
        if ((modifiers & HotkeyHook.MOD_CONTROL) != 0) parts.Add("Strg");
        if ((modifiers & HotkeyHook.MOD_ALT) != 0) parts.Add("Alt");
        if ((modifiers & HotkeyHook.MOD_SHIFT) != 0) parts.Add("Umschalt");
        if ((modifiers & HotkeyHook.MOD_WIN) != 0) parts.Add("Windows");
        parts.Add(DescribeVk(vk));
        return string.Join(" + ", parts);
    }

    private static string DescribeVk(uint vk)
    {
        if (vk >= 0x41 && vk <= 0x5A) return ((char) vk).ToString();
        if (vk >= 0x30 && vk <= 0x39) return ((char) vk).ToString();
        if (vk >= 0x70 && vk <= 0x87) return "F" + (vk - 0x70 + 1);
        if (vk >= 0x60 && vk <= 0x69) return "Num " + (vk - 0x60);

        return vk switch
        {
            0x20 => "Leertaste",
            0x0D => "Eingabe",
            0x09 => "Tab",
            0x1B => "Esc",
            0x08 => "Rücktaste",
            0x2D => "Einfg",
            0x2E => "Entf",
            0x24 => "Pos1",
            0x23 => "Ende",
            0x21 => "Bild auf",
            0x22 => "Bild ab",
            0x25 => "Links",
            0x26 => "Hoch",
            0x27 => "Rechts",
            0x28 => "Runter",
            0x13 => "Pause",
            0x91 => "Rollen",
            _ => $"Taste 0x{vk:X2}",
        };
    }
}
