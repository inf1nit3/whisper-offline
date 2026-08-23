# Changelog

## v1.9

- Kompatibilität: Die Android-Engine benötigt nicht mehr die CPU-Erweiterung
  i8mm (erst ab Cortex-A710/X2-Klasse, ~2021) — Baseline ist jetzt
  armv8.2+dotprod+fp16 und läuft damit auf praktisch allen ARM-Geräten ab
  ~2018 (u. a. Redmi Note 15 Pro)
- Konkrete Fehlerursache bei Modell-Ladefehlern in der Meldung (CPU zu alt,
  Backend fehlt, Datei beschädigt) statt pauschal „Modellfehler"

## v1.8

- „Datei transkribieren" prüft jetzt vorab den Dateityp: PDFs, Bilder und
  Dokumente werden mit verständlicher Meldung abgelehnt statt mit dem
  technischen Fehler „Failed to instantiate extractor"

## v1.7

- Fehlerbehebung „Modellfehler" beim Start: Die App speichert ein Modell nur
  noch bei erfolgreichem Laden — das Aktivieren eines nicht ladefähigen Modells
  (z. B. Parakeet) vergiftet die Startauswahl nicht mehr dauerhaft
- Die Modell-Auswahl lässt sich jetzt schließen, sobald lokal Modelle vorhanden
  sind — auch wenn der letzte Ladeversuch fehlschlug (keine Sackgasse mehr)
- Android-Zurück-Taste schließt Modell-Auswahl, Verlauf und Changelog statt
  die App sofort zu beenden
- Tote Modell-Referenzen (Datei gelöscht) werden beim Start aufgeräumt

## v1.6

- Android: Schalter „Kurzes Audio beschleunigen" entfernt — Messung zeigte
  korrumpierte, sich wiederholende Transkripte (Whisper erwartet das volle
  30-Sekunden-Fenster)
- Modelle lassen sich in der Auswahl jetzt direkt löschen (Papierkorb-Button),
  mit Bestätigungsdialog — befreit Speicher von nicht mehr genutzten Modellen

## v1.5

- Test- und Messanzeigen aus der Oberfläche entfernt:
  Android Timing-Aufschlüsselung und Backend-Info, Windows ShortCtx-Testschalter
  und Engine-Diagnosetext — die Apps zeigen jetzt nur noch nutzerelevante Infos

## v1.4

- Teilen-Button für das Transkript:
  - Android: natives Teilen-Menü des Systems (WhatsApp, Signal, Mail, …)
  - Windows: Teilen-Menü mit WhatsApp (vorbefüllter Text), E-Mail-Entwurf
    und Zwischenablage

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
