namespace WhisperOffline;

/// Schätzt, wie lange eine Transkription dauert, damit der Fortschrittsbalken
/// flüssig läuft.
///
/// Die Engine meldet nur grob: Whisper je 30-s-Fenster, Parakeet gar nicht
/// zwischendurch. Deshalb lernt die App pro Modell die Rechenzeit auf genau
/// diesem Rechner und rechnet die Zeit gegen. Beim ersten Lauf eines Modells
/// gibt es noch keine Schätzung — dann läuft der Balken unbestimmt.
/// Gleiche Logik wie TranscriptionProgress.kt auf Android.
public static class TranscriptionEstimate
{
    /// Bis hierhin füllt die Schätzung; den Rest zeigt erst das Ergebnis.
    public const double Cap = 0.95;

    /// Whisper rechnet immer ganze 30-s-Fenster — 3 s Audio kosten fast so
    /// viel wie 29 s. Parakeet skaliert mit der Audiolänge.
    private static double Units(double seconds, bool parakeet) =>
        parakeet ? seconds / 30.0 : Math.Max(1.0, Math.Ceiling(seconds / 30.0));

    public static long? ExpectedMs(AppSettings s, string model, double seconds, bool parakeet) =>
        s.ProgressCalibration.TryGetValue(model, out var perUnit) && perUnit > 0
            ? (long)(perUnit * Units(seconds, parakeet))
            : null;

    /// Gleitender Mittelwert, damit ein Ausreißer die Schätzung nicht umwirft.
    public static void Record(AppSettings s, string model, double seconds, bool parakeet, long elapsedMs)
    {
        var u = Units(seconds, parakeet);
        if (u <= 0 || elapsedMs <= 0) return;
        var measured = elapsedMs / u;
        s.ProgressCalibration[model] = s.ProgressCalibration.TryGetValue(model, out var old) && old > 0
            ? old * 0.6 + measured * 0.4
            : measured;
        s.Save();
    }

    /// Anzeigewert 0..1 aus Engine-Meldung und Zeitschätzung — der größere
    /// gewinnt. null = unbestimmt.
    public static double? Fraction(int engineProgress, long elapsedMs, long? expectedMs)
    {
        var engine = Math.Clamp(engineProgress / 100.0, 0, 0.99);
        double? estimate = expectedMs is > 0 ? Math.Clamp((double)elapsedMs / expectedMs.Value, 0, Cap) : null;
        if (estimate != null) return Math.Max(engine, estimate.Value);
        return engine > 0 ? engine : null;
    }
}
