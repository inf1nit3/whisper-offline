# Changelog

## v2.3

- Android 16+: Fortschritt als Live-Update in der Statusleiste und auf dem
  Sperrbildschirm — mit Prozent und „Abbrechen“. Darunter als normale
  Fortschrittsbenachrichtigung
- Transkriptionen laufen weiter, wenn du die App verlässt (Vordergrunddienst);
  ist sie im Hintergrund fertig geworden, meldet eine Benachrichtigung das
  Ergebnis. Kurze Aufnahmen bleiben ohne Benachrichtigung
- Neuer Menüpunkt „Diktat-Kachel hinzufügen“ (ab Android 13): legt die Kachel
  mit einem Tipp in die Schnelleinstellungen
- Neues App-Symbol, ab Android 13 als Themen-Icon in deinen Material-You-Farben
- Tablets, Foldables und Handy quer: zweispaltig — links Aufnahme, rechts
  Transkript; Modellauswahl, Verlauf und Einführung lesbar zentriert

## v2.2

- Fortschrittsanzeige beim Transkribieren: Balken mit Prozent und
  Restzeit („noch ca. 6 s“). Die App lernt pro Modell, wie schnell dein
  Gerät rechnet — ab der zweiten Transkription wird die Schätzung genau
- Transkription lässt sich abbrechen (Android, Diktat-Overlay und Windows)
- Android: neues Design — Material You (Farben passend zum Hintergrundbild
  ab Android 12), Dunkelmodus, großer Aufnahmeknopf mit Pegelanzeige,
  Modell und Sprache als Chips, Icons statt Emojis, randlose Darstellung
- Android: Modellauswahl, Verlauf und Einführung neu gestaltet; „Alle
  löschen“ im Verlauf fragt jetzt vorher nach
- Android: Diktat-Overlay ohne leeren Rahmen dahinter, mit Pegel und
  Fortschritt

## v2.1

- Android: Audio aus anderen Apps über „Teilen“ transkribieren — z. B.
  WhatsApp-Sprachnachricht lange drücken → Teilen → Scheisssewasser's Whisper
- Android: Datei-Transkription repariert und robuster — M4A/HE-AAC mit
  richtiger Geschwindigkeit, lange Dateien ohne Speicherüberlauf,
  Fortschritt in Prozent beim Dekodieren
- Windows: Diktat fügt den Text jetzt wirklich per Strg+V ins Zielfenster
  ein (vorher landete er nur in der Zwischenablage)
- Windows: Datei-Transkription nutzt das bereits geladene Modell — schneller,
  funktioniert mit Parakeet und zusätzlich mit M4A, MP4, AAC, WMA, MOV, AVI
- Verlauf: Einträge stehen wieder zuverlässig neueste zuerst (bisher geriet
  die Reihenfolge bei jedem neuen Eintrag durcheinander), mit Audiodauer
- Aufnahmedauer läuft sichtbar mit (Hauptfenster und Diktat-Overlay)
- Diktat-Overlay (Android): hängt nicht mehr nach dem Erteilen der
  Mikrofon-Berechtigung, lässt sich jederzeit abbrechen, und das Mikrofon
  wird beim Verlassen sofort freigegeben
- Modellwechsel während einer laufenden Transkription führt nicht mehr zum
  Absturz; Engine-Fehler landen nicht mehr als „Transkript“ im Verlauf

## v2.0

- Parakeet v3 ist wieder im Modell-Angebot: die schnellste Engine (jetzt mit der
  gemeinsamen Engine-Grundlage auf Android und Windows getestet) — 638 MB,
  braucht App 1.9+ und ca. 1,5 GB freien RAM
- Android: Release-APKs sind jetzt mit eigenem Keystore signiert
  ⚠️ Einmalige Neuinstallation nötig: alte App deinstallieren (Modelle werden
  dabei gelöscht und müssen einmal neu geladen werden), dann APK neu installieren.
  Alle künftigen Updates funktionieren danach wieder direkt aus der App.

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
