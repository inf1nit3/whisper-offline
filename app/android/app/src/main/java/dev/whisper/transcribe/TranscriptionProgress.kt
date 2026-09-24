package dev.whisper.transcribe

import android.content.Context
import kotlin.math.ceil

/// Schätzt, wie lange eine Transkription dauert, damit die Oberfläche einen
/// flüssigen Fortschrittsbalken zeigen kann.
///
/// Die Engine meldet nur grob: Whisper je 30-s-Fenster, Parakeet gar nicht
/// zwischendurch. Deshalb lernt die App pro Modell die Rechenzeit auf genau
/// diesem Gerät und rechnet die Zeit gegen. Beim ersten Lauf eines Modells
/// gibt es noch keine Schätzung — dann zeigt die Oberfläche einen
/// unbestimmten Balken.
object TranscriptionProgress {

    private const val PREFS = "progress_calibration"

    /// Bis hierhin füllt die Schätzung; den Rest zeigt erst das Ergebnis.
    const val ESTIMATE_CAP = 0.95f

    /// Aufwandseinheiten eines Laufs. Whisper rechnet immer ganze
    /// 30-s-Fenster — 3 s Audio kosten fast so viel wie 29 s. Parakeet
    /// skaliert mit der Audiolänge.
    private fun units(audioSeconds: Float, parakeet: Boolean): Double =
        if (parakeet) audioSeconds / 30.0
        else ceil(audioSeconds / 30.0).coerceAtLeast(1.0)

    private fun key(model: String, gpu: Boolean) = "$model|${if (gpu) "gpu" else "cpu"}"

    /// Erwartete Dauer in ms oder null, solange das Modell noch nie lief.
    fun expectedMs(context: Context, model: String, gpu: Boolean,
                   audioSeconds: Float, parakeet: Boolean): Long? {
        val perUnit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(key(model, gpu), -1f)
        if (perUnit <= 0f) return null
        return (perUnit * units(audioSeconds, parakeet)).toLong()
    }

    /// Nach einem erfolgreichen Lauf die gemessene Dauer einrechnen. Gleitender
    /// Mittelwert, damit ein Ausreißer (Hitzedrosselung, App im Hintergrund)
    /// die Schätzung nicht umwirft.
    fun record(context: Context, model: String, gpu: Boolean,
               audioSeconds: Float, parakeet: Boolean, elapsedMs: Long) {
        val u = units(audioSeconds, parakeet)
        if (u <= 0.0 || elapsedMs <= 0) return
        val measured = (elapsedMs / u).toFloat()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getFloat(key(model, gpu), -1f)
        val next = if (old <= 0f) measured else old * 0.6f + measured * 0.4f
        prefs.edit().putFloat(key(model, gpu), next).apply()
    }

    /// Anzeigewert 0..1 aus Engine-Meldung und Zeitschätzung — der größere
    /// gewinnt. null = unbestimmt (keine Schätzung, Engine noch bei 0).
    fun fraction(engineProgress: Int, elapsedMs: Long, expectedMs: Long?): Float? {
        val engine = (engineProgress / 100f).coerceIn(0f, 0.99f)
        val estimate = expectedMs?.let { (elapsedMs.toFloat() / it).coerceIn(0f, ESTIMATE_CAP) }
        return when {
            estimate != null -> maxOf(engine, estimate)
            engine > 0f -> engine
            else -> null
        }
    }
}
