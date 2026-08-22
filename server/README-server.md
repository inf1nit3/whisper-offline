# Modell-Backend (VPS)

Die Apps laden ihr Sprachmodell beim ersten Start vom eigenen Server. Es wird
kein Application-Server benötigt — statische Dateien über HTTPS reichen.

## Dateien auf dem VPS

```
/var/www/whisper/
├── models.json                     # Manifest (dieser Ordner: server/models.json)
├── ggml-base.bin
├── ggml-small-q5_1.bin
└── ggml-large-v3-turbo-q5_0.bin
```

## Bereitstellen

```bash
# 1. Modelle ins Serververzeichnis kopieren (Host/Pfad anpassen)
rsync -avP --relative models/./ggml-base.bin \
                   models/./ggml-small-q5_1.bin \
                   models/./ggml-large-v3-turbo-q5_0.bin \
                   server/models.json \
      USER@VPS:/var/www/whisper/

# 2. nginx-Serverblock (Auszug):
location /whisper/ {
    alias /var/www/whisper/;
    autoindex off;
    add_header Cache-Control "public, max-age=86400";
    # große Dateien: Sendeffizienz
    tcp_nopush on;
    sendfile on;
}
```

## URL konfigurieren

In beiden Apps steht die Manifest-URL an genau einer Stelle:

- Android: `app/android/.../ModelConfig.kt` → `MANIFEST_URL`
- Windows: `app/windows/WhisperOffline/ModelConfig.cs` → `ManifestUrl`

Format: `https://dein-vps.example/whisper/models.json`
Die Modell-Dateien liegen im selben Verzeichnis wie das Manifest
(URL = Manifest-Verzeichnis + `file`-Feld aus dem Manifest).

## Manifest erweitern

Neue Modelle einfach als weiteren Eintrag in `models.json` aufnehmen
(`file`, `label`, `tagline`, `size`, `sha256`, `pros[]`, `cons[]`).
Die Apps zeigen sie automatisch an. Ohne `sha256` wird die Prüfung übersprungen.
