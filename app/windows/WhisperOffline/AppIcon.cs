using System;
using Avalonia.Controls;
using Avalonia.Platform;

namespace WhisperOffline;

/// App-Symbol aus Assets/app.ico (16–256 px; die kleinen Größen sind eine
/// vereinfachte Fassung des Logos) — für alle Fenster und das Tray-Symbol.
public static class AppIcon
{
    private static WindowIcon? icon;

    public static WindowIcon Get() =>
        icon ??= new WindowIcon(AssetLoader.Open(new Uri("avares://WhisperOffline/Assets/app.ico")));
}
