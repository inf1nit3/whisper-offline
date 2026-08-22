package dev.whisper.transcribe

/// Zentrale Konfiguration des Modell-Backends.
/// Pfad zu den Modelldateien = Verzeichnis des Manifests + "file"-Eintrag.
object ModelConfig {
    const val MANIFEST_URL = "http://169.58.211.209:8901/models.json"
}
