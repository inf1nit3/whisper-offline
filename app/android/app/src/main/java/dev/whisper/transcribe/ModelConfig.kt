package dev.whisper.transcribe

import android.content.Context

/// Zentrale Konfiguration des Modell-Backends.
/// Pfad zu den Modelldateien = Verzeichnis des Manifests + "file"-Eintrag.
object ModelConfig {
    const val MANIFEST_URL = "https://whisper.scheisssewasser.xyz/models.json"
}
