# Changelog

## v1.3

- Einführung beim ersten App-Start (4 Seiten): Was kann die App, von wem sie
  kommt und die Vorteile der Nutzung — einmalig, danach dauerhaft erledigt

## v1.2

- Changelog-Anzeige in der App (offline, über das Buch-Symbol bzw. den Changelog-Button)
- Windows: Update-Panel mit Release-Notes vor der Installation statt sofortigem Download
- Android: Update-Dialog zeigt den Changelog der neuen Version an

## v1.1

- Modell-Hinweistext geändert: „Modelle werden einmalig von einem Server von
  scheisssewasser.xyz bezogen und heruntergeladen"
- Wartungsrelease zur Verifikation der Update-Infrastruktur

## v1.0

- Erste vollständige Version für Windows und Android
- Lokale, offline laufende Transkription (whisper.cpp) mit Modellauswahl:
  tiny / base / small / large-v3-turbo (quantisiert)
- Modell-Backend über HTTPS (whisper.scheisssewasser.xyz) mit
  SHA256-Prüfung und Fortschrittsanzeige
- Diktat-Modus: Windows globaler Hotkey (Strg+Alt+Leertaste) mit automatischem
  Einfügen ins Zielfenster; Android Schnelleinstellungs-Kachel, Launcher-Symbol
  „Whisper Diktat" und Assistenten-Integration
- Transkriptions-Verlauf mit Zeitstempel, Modell, Kopieren und Löschen
- Performance-Tuning: Greedy-Decoding, Flash-Attention, 4 Performance-Threads,
  dotprod/i8mm-CPU-Build, optionales Vulkan-Backend
- In-App-Updater über GitHub Releases (automatischer Check beim Start)
