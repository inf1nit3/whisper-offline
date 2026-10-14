# Changelog

## v3.6

**Neues Logo** — Windows und Android
- Eigenes App-Symbol im Stil von scheisssewasser.xyz: Mikrofon auf einem Chip,
  dazu eine Wellenform, im türkis-lila Neon-Ring
- Android: neues Launcher-Symbol; ab Android 13 auch als einfarbiges Themen-Icon
- Windows: Symbol der Exe, aller Fenster und des Tray-Symbols; in kleinen
  Größen eine vereinfachte, gut lesbare Fassung

## v3.5

**Windows:** keine Änderungen gegenüber v3.4.

**Android: Smart Routing mit TypeSafe Jev (optional, standardmäßig aus)**
- Nach der Transkription ordnet Jev Aufnahmen und Dateien in einer einzigen
  Anfrage ein: Kategorie, Projekt, Dringlichkeit, „braucht Handlung“, „braucht
  Glättung“ — als Badges am Transkript
- Bei unsicherer Kategorie bietet ein Chip die zweitwahrscheinlichste zum
  Umschalten an; „Text glätten“ erscheint nur, wenn der Text es braucht
- „Diktate automatisch verbessern“ läuft mit Jev nur noch, wenn der Text es braucht
- Kurze Aufnahmen aus reinem Rauschen (Mikrofontest) landen nicht im Verlauf
- Einstellungen → Smart Routing: Schalter (erst mit API-Key), Verbindungstest,
  Modell (gepinnt: jev-1.13.0), eigene Projekt-Tags
- Offline-first: ohne Internet, Key oder bei mehr als 1,5 s Wartezeit bleibt alles
  wie bisher. **Datenschutz:** nur wenn aktiviert, geht der Transkripttext an
  api.typesafe.ai — sonst verlässt nichts das Gerät
- Verlauf speichert die Klassifikation optional mit (ältere Einträge unverändert)

## v3.4

Feinschliff fürs neue Design — jetzt auch in allen Unterseiten.

**Windows**
- Einstellungen, Wörterbuch, Statistik, Verlauf, Meeting, Stapel,
  Sprachmodelle und App-Profile im Karten-Stil mit runden Knöpfen
- Wörterbuch: Wörter filtern; neue Wörter stehen oben
- Modellauswahl: heruntergeladene Modelle lassen sich löschen; das aktive
  Modell ist farbig umrandet
- Statistik: Balkendiagramm passt sich der Fensterbreite an
- Dialoge (Löschen, System-Prompt, Sprecher benennen) und „Auswahl
  bearbeiten“ im neuen Design, auch im dunklen Modus
- Verlauf: Text lässt sich markieren und kopieren

**Android**
- Verlauf zeigt die Quelle (Diktat, Datei, Meeting, Umgeformt)
- Wörterbuch: Wörter filtern (ab 9 Einträgen); neue Wörter stehen oben
- Ersetzungen: Regeln filtern (ab 7 Regeln)
- Transkript leeren auch während einer laufenden Transkription

## v3.3

Neues Design — Windows und Android sehen jetzt gleich aus.

**Windows: neues Hauptfenster**
- Kopfzeile mit Titel und Schnellzugriff (Meeting, Stapel, Verlauf,
  Wörterbuch, Statistik, Einstellungen, Changelog)
- Modell, Sprache, „→ EN“ und Hotkey als Chip-Leiste; der Modell-Chip zeigt
  einen lesbaren Namen („Whisper small“) und öffnet die Modellauswahl
- Aufnahme, Wellenform und Fortschritt in einer Karte, das Transkript in einer
  eigenen Karte mit Umformen, Kopieren, Teilen und Leeren
- Dunkles Design: folgt jetzt der Windows-Einstellung (Hell/Dunkel)
- Markenfarben wie auf Android; Einstellungen, Modellauswahl, Changelog und
  Update im selben Karten-Stil

**Android**
- Transkript leeren direkt in der Transkript-Karte
- Wellenform mit denselben Verlaufsfarben wie unter Windows
- Markenfarben (Geräte vor Android 12) mit passenden Fehlerfarben

## v3.2

**Anweisungen einfach mitsprechen**
- Am Anfang oder Ende eines Diktats: „…, bitte als Liste.“ macht eine
  Aufzählung, „als To-do-Liste“ eine Aufgabenliste mit Kästchen
- „Das Ganze bitte auf Englisch.“ / „in English please“ übersetzt das
  Diktat — Englisch, Deutsch, Französisch, Spanisch, Italienisch und mehr
- Die Anweisung selbst landet nicht im Text. Inhalt wie „Die Präsentation
  halte ich auf Englisch.“ bleibt unangetastet
- Braucht Enhance (lokales Sprachmodell)

**Enhance zuverlässiger**
- Antwortet das Sprachmodell, statt den Text zu bearbeiten („Hier ist der
  überarbeitete Text …“), oder ist das Ergebnis viel länger oder kürzer als
  das Diktat, wird der unveränderte Text eingefügt — mit Hinweis
- Meeting-Notizen: Aufgaben als Kästchen-Liste mit Person und Frist, sofern
  im Gespräch genannt

**Neue Aufnahme-Anzeige**
- Eine Wellenform folgt beim Sprechen dem Mikrofonpegel und wird beim
  Transkribieren ruhig — Android im Hauptbildschirm, Windows im Hauptfenster
  und im Diktat-Fenster
- Android: Der Aufnahmeknopf wird während der Aufnahme zum Stopp-Quadrat
  und zeigt beim Transkribieren den Fortschritt
- Windows: Der Aufnahmeknopf wird während der Aufnahme rot

**Windows**
- Wörterbuch beim ersten Start mit 76 häufigen englischen Begriffen
  vorbelegt (wie Android seit v3.1); leeres Wörterbuch: Knopf „Häufige
  englische Begriffe hinzufügen“

## v3.1

Kleines Update fürs Wörterbuch.

**Wörterbuch vorbelegt**
- Beim ersten Start enthält das Wörterbuch 76 häufige englische Begriffe,
  wie sie im Deutschen üblich sind (Deployment, Pull Request, Backlog,
  Stakeholder, Roadmap …) — die Spracherkennung schreibt sie in deutscher
  Umgebung sonst gern phonetisch oder eingedeutscht
- Einträge lassen sich wie eigene Wörter löschen; eine bestehende eigene
  Liste bleibt unangetastet
- Leeres Wörterbuch: Knopf „Häufige englische Begriffe hinzufügen“
- Im Whisper-Prompt stehen eigene Wörter jetzt vor der Standardliste

## v3.0

Großes Update — alles läuft weiterhin lokal auf deinem Gerät.

**Besser erkennen**
- Eigene Wörter (⋮ → Wörterbuch): Namen und Fachbegriffe in der gewünschten
  Schreibweise. Whisper bekommt sie vorab mit und schreibt sie richtig;
  ähnlich Klingendes („Scheiße Wasser“) wird danach korrigiert
- Snippets: nur die Trigger-Phrase sagen („meine Adresse“), und der
  hinterlegte Text wird eingefügt
- Sprachbefehle: „Komma“, „Punkt“, „Fragezeichen“, „neue Zeile“,
  „neuer Absatz“ werden beim Diktieren umgesetzt
- Ins Englische übersetzen (Whisper-Modelle)
- Live-Transkription (Einstellungen): Text erscheint schon beim Sprechen,
  das Ergebnis steht nach dem Stoppen fast sofort da

**Enhance — Text verbessern mit einem lokalen Sprachmodell**
- Sprachmodell einmalig laden (Einstellungen → Sprachmodell), Empfehlung
  Qwen3 4B; danach räumt es Diktate auf: Füllwörter raus, Selbstkorrekturen,
  Zeichensetzung, Absätze, Listen
- Formatiert passend zur App: Chat, E-Mail, Notiz, Dokument, Code,
  Aufgabenliste — automatisch erkannt (Android: Diktat-Tastatur, Windows:
  Programm bzw. Fenstertitel), pro App einstellbar oder ausschließbar
- Umformen: Text verbessern, kürzen, ausführlicher, professioneller,
  lockerer, Rechtschreibung, Aufzählung, Aufgabenliste, übersetzen oder eine
  eigene Anweisung (getippt oder gesprochen) — am Transkript (✨), in jeder
  Android-App über das Textauswahl-Menü („Whisper umformen“), in der
  Diktat-Tastatur über „Auswahl bearbeiten“ und unter Windows per Hotkey
- Windows: statt des lokalen Modells auch ein eigener Befehl möglich
  (z. B. Claude Code, Codex, Gemini) — mit Testknopf und Hinweis, dass der
  Text dann zum Anbieter geht
- Eigener System-Prompt für Enhance

**Meeting-Modus**
- Lange Gespräche aufnehmen, nach Sprechern getrennt transkribieren,
  Redezeit je Person, Suche, Notizen (Zusammenfassung, Entscheidungen,
  Aufgaben, offene Fragen), Export als PDF
- Sprecher umbenennen, zusammenführen und Stimmen merken — in späteren
  Meetings werden sie automatisch erkannt
- Android: Aufnahme läuft auch bei ausgeschaltetem Bildschirm weiter;
  Windows: Mikrofon und Systemaudio (Calls) getrennt, du bist „Ich“
- Unterbrochene Aufnahmen bleiben erhalten und lassen sich später auswerten
- Sprechererkennung: zwei kleine Zusatzmodelle (28 MB), einmalig zu laden

**Diktat**
- Töne bei Start, Ende und Fehlern; andere Wiedergabe wird während der
  Aufnahme leiser; Mikrofon wählbar
- Android-Tastatur: in Passwortfeldern gesperrt; pro App „nach dem Diktat
  automatisch senden“
- Windows: Push-to-Talk (Taste gedrückt halten), Einzeltaste als Auslöser
  (z. B. rechte Strg), zweiter Hotkey „Diktat + Enhance“, Overlay unten am
  Bildschirm, Zwischenablage bleibt nach dem Einfügen erhalten, Enter nach
  dem Einfügen für ausgewählte Programme

**Verlauf und Statistik**
- Statistik: Wörter pro Tag, gesprochene Zeit, gesparte Zeit, Sprachen,
  nach Quelle (Diktat, Datei, Meeting)
- Verlauf filtern (7/30 Tage), sortieren, abschaltbar
- Windows: Verlauf durchsuchen, Ersetzungen, Stapel-Transkription vieler
  Dateien in einen Zielordner (pausiert, solange du diktierst)

**Behoben**
- Windows: Im Paket fehlte libwinpthread-1.dll — die Engine konnte sich auf
  Rechnern ohne MinGW deshalb nicht laden

## v2.5

- Stille wird übersprungen: keine erfundenen Sätze mehr aus Pausen oder
  Rauschen (Whisper „hörte“ dort z. B. „Thank you.“) und schneller bei
  Aufnahmen mit Pausen — für Whisper-Modelle auf Android und Windows
- Ersetzungen (⋮ → Ersetzungen): Wörter, die immer wieder falsch erkannt
  werden, einmal korrigieren — gilt in App, Tastatur und Diktat
- Transkript bearbeiten (Stift-Symbol); die Änderung landet auch im Verlauf
- Verlauf durchsuchen und als Textdatei teilen
- Mehrere Sprachnachrichten auf einmal teilen — sie werden nacheinander
  transkribiert, jede einzeln im Verlauf
- Lokales Fehlerprotokoll (⋮ → Fehlerbericht) — bleibt auf dem Gerät, nach
  einem Absturz weist die App darauf hin
- Windows: Updates werden ab dieser Version nur noch mit gültiger Signatur
  installiert — ein verändertes Update-Paket wird abgelehnt

## v2.4

- **Wichtig:** Absturz behoben — v2.3 beendete sich auf Android 14 und neuer
  beim Start jeder Transkription (Vordergrunddienst mit falschem Typ)
- Neu: Diktat-Tastatur — sprechen, und der Text landet direkt im Textfeld von
  WhatsApp, Mail & Co., ohne Zwischenablage. Einrichten über ⋮ →
  „Diktat-Tastatur einrichten“; mit dem Tastatur-Knopf geht es zurück zur
  gewohnten Tastatur
- Transkripte ohne überflüssige Leerzeichen am Zeilenanfang
- Getestet auf Android 16 (Emulator): Hintergrund-Transkription mit
  Fortschritts- und Fertig-Benachrichtigung, Tastatur, Querformat

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
