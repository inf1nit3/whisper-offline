# Scheisssewasser's Whisper — Windows & Android

Lokale, vollständig offline laufende Sprachtranskription mit OpenAI Whisper (whisper.cpp),
nach dem Vorbild von Whisper Bar unter macOS. Keine Cloud, keine Netzwerknutzung — Modell und
Engine liegen auf dem Gerät.

Ausgangspunkt war das von Whisper Bar mitgelieferte `ggml-base.bin` (Whisper „base", MIT-Lizenz).

## Struktur

```
Whisper/
├── engine/
│   ├── whisper.cpp/          # Upstream-Quellcode (Git-Klon)
│   ├── core/                 # gemeinsamer Engine-Kern (Threads, Tuning, Timings)
│   ├── jni/                  # JNI-Brücke (C) für Android + CMake-Setup
│   ├── shim/                 # whisper_shim.dll: flache C-API für die Windows-App
│   ├── build-win64/          # Kreuzkompilierte Windows-Engine (MinGW)
│   └── whisper-macos         # Whisper Bars Runner (macOS-Referenz)
├── app/
│   ├── android/              # Kotlin/Compose-App (Gradle-Projekt)
│   └ windows/WhisperOffline/ # Avalonia-App (.NET, cross-publishbar)
├── models/ggml-base.bin      # Whisper base (141 MB)
├── dist/                     # Fertige Pakete
├── tools/                    # Gradle, .NET SDK, Skripte
└── docs/
```

## Architektur

Beide Apps nutzen dieselbe Engine (whisper.cpp) und dasselbe Modell, aber getrennte UIs:

| Plattform | UI | Engine-Einbindung | Modell |
|---|---|---|---|
| Android | Kotlin + Jetpack Compose | `libwhisper_jni.so` per JNI (arm64-v8a, x86_64) | Download vom VPS → filesDir |
| Windows | Avalonia (.NET 10) | `whisper_shim.dll` per P/Invoke, Modell bleibt geladen; `whisper-cli.exe` als Rückfall und für Dateiformate | `models/` neben der EXE |

Die Tuning-Arbeit, die auf beiden Plattformen gleich ist — Threadzahl nach
großen Kernen, greedy statt Beam Search, `single_segment` bei kurzem Audio,
optionales Kürzen des Encoder-Fensters, Zeitmessung — steht einmal in
`engine/core/whisper_engine.c`. JNI und DLL-Shim enthalten nur noch Marshalling.

Die Windows-Engine wählt beim Start automatisch die passende CPU-Variante
(SSE4.2 bis AVX-512/AMX) und läuft daher auf jedem x64-Windows ab Windows 7.
Die beiden MinGW-Runtime-DLLs (`libgcc_s_seh-1.dll`, `libstdc++-6.dll`) werden
mitgeliefert (GPL mit Runtime-Exception, Weitervertrieb erlaubt) — ohne sie
startet die EXE auf Windows-Rechnern ohne MinGW nicht.

## Fertige Pakete

- **Windows:** `dist/WhisperOffline-windows-x64.zip` (57 MB) — entpacken, `WhisperOffline.exe`
  starten, Modell einmalig vom eigenen Server laden. Keine Installation nötig.
- **Android:** `dist/WhisperOffline-android-debug.apk` (18 MB; das unverkleinerte
  Debug-Dex dominiert, die Engine selbst sind 2,9 MB) — installieren, beim ersten
  Start Modell wählen und laden (für Play-Vertrieb `./gradlew assembleRelease` mit eigenem Keystore).

## Features (beide Apps)

- Erster Start: Modellauswahl mit Vor-/Nachteilen, Download vom eigenen VPS mit Fortschritt
  und SHA256-Prüfung; später umschaltbar
- Mikrofonaufnahme → Transkription (16 kHz Mono)
- Datei-Transkription (Windows: wav/mp3/flac/ogg/m4a/mp4…, Android: alles von MediaCodec Dekodierbare)
- Sprachauswahl: Automatisch / Deutsch / English
- Kopieren des Transkripts

## Alles von macOS aus neu bauen

Voraussetzungen: Homebrew, Android SDK (Plattform 36), JDK 17.

```bash
brew install cmake ninja mingw-w64 android-ndk android-commandlinetools

# Engine: Windows (whisper_shim.dll + whisper-cli.exe, Flags stehen im CMakeLists)
cd engine/shim
cmake -B build-win64 -G Ninja \
  -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
  -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
  -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build-win64 --target whisper_shim whisper-cli -j8

# Engine: Android (je ABI)
cd ../jni
NDK=/opt/homebrew/share/android-ndk
for ABI in arm64-v8a x86_64; do
  cmake -B build-$ABI -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
  cmake --build build-$ABI -j8
  # Gradle schafft das Strippen nicht selbst: 22 MB -> 2,9 MB
  $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip \
    --strip-unneeded build-$ABI/libwhisper_jni.so
done
# GPU-Pfad zum Gegentesten: -DGGML_VULKAN=ON und -DANDROID_PLATFORM=android-28
# (ggml-vulkan braucht vkGetPhysicalDeviceFeatures2, das erst ab API 28 exportiert
# wird); dann auch minSdk in app/build.gradle.kts auf 28 setzen.
cp build-arm64-v8a/libwhisper_jni.so ../../app/android/app/src/main/jniLibs/arm64-v8a/
cp build-x86_64/libwhisper_jni.so   ../../app/android/app/src/main/jniLibs/x86_64/

# Android-App
cd ../../app/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug

# Windows-App + Gesamtpaket
../../tools/package-windows.sh
```

## Tests

- macOS-Referenz: `engine/whisper-macos` transkribiert Testaudio korrekt (0,3 s, Metal).
- Windows: Engine per MinGW kreuzkompiliert; Import-Tabelle geprüft — alle benötigten DLLs
  (inkl. MinGW-Runtime) liegen im Paket. Ein Laufzeit-Test auf Windows selbst steht noch aus.
- Android: APK baut sauber; **auf einem echten Gerät verifiziert** (Aug 2026): App startet,
  Modell wird aus den Assets entpackt und geladen („whisper base bereit (offline)"), UI
  vollständig funktional. Der Emulator-Verifikationslauf (`tools/test-android.sh`) scheitert
  auf diesem Mac an einem Umgebungsproblem (Emulator bleibt headless im Zustand „offline"
  hängen, unabhängig von der App).
- Performance-Build (Aug 2026): arm64-Engine mit dotprod/i8mm/fp16 gebaut, im
  Objekt nachgewiesen (904× `sdot`, 244× `smmla`; vorher jeweils 0). Auf einem
  Vivo X300 Pro gemessen: Whisper small q5_1 transkribiert 13,4 s Audio in 6,6 s
  (~2× Echtzeit). CPU gegen Vulkan gegengetestet, siehe „Android-Performance".
  Kein Vorher-Wert vom alten Build erhoben — der Zugewinn ist damit belegt, aber
  nicht beziffert.

## Modelle

Die Modelle sind **nicht** mehr in den Apps gebündelt. Beim ersten Start holt die App
ein Manifest vom eigenen VPS (`server/models.json`), zeigt jede Modellkarte mit
Vor- und Nachteilen, Größe und Prüfsumme, lädt die Auswahl einmalig herunter
(SHA256-verifiziert, mit Fortschrittsanzeige) und aktiviert sie. Später jederzeit
über „Modell wechseln" umschaltbar. Die Auswahl wird persistiert (Android:
SharedPreferences; Windows: `settings.json` neben der EXE).

Messwerte (deutscher Testsatz, „Umsatzsteuervormeldung" als Stolperstein, macOS/Metal):

| Modell | Größe | Zeit | Qualität |
|---|---|---|---|
| ggml-base.bin | 141 MB | 0,5 s | mäßig (Fachwort falsch) |
| ggml-small-q5_1.bin (**empfohlen**) | 181 MB | 1,4 s | deutlich besser |
| ggml-large-v3-turbo-q5_0.bin | 547 MB | 2,4 s | exakt, nahe large-v3 |

## Android-Performance

Die arm64-Engine wird mit `-march=armv8.6-a+dotprod+i8mm+fp16` gebaut
(`GGML_CPU_ARM_ARCH` in `engine/jni/CMakeLists.txt`). Ohne diese Flags baut der
NDK auf der `armv8-a`-Baseline — die quantisierten Matmuls laufen dann ohne
`sdot`/`smmla` um ein Vielfaches langsamer. Kontrolle am fertigen Objekt:

```bash
llvm-objdump -d build-arm64-v8a/whisper.cpp/ggml/src/libggml-cpu.a | grep -cE '\bsmmla\b'
```

Preis: die Engine setzt i8mm voraus (Cortex-A710/X2-Klasse, ab ~2021).
`whisper_jni.c` prüft das beim Laden über `HWCAP2_I8MM` und meldet sauber,
statt mit SIGILL abzustürzen.

Weitere Stellschrauben:

- **Threads** werden zur Laufzeit aus `/proc/cpuinfo` bestimmt: gezählt werden
  nur große Kerne (Little-Cluster über die ARM-Part-Nummer erkannt). ggml
  synchronisiert nach jeder Operation, der langsamste Thread taktet den Graphen
  — Effizienzkerne mitrechnen zu lassen bremst. Auf einem Dimensity 9500
  (1× C1-Ultra, 3× C1-Premium, 4× C1-Pro, kein Little-Cluster) ergibt das 8.
- **GPU (Vulkan)** ist aus, und zwar gemessen begründet. Vivo X300 Pro
  (Dimensity 9500), Whisper small q5_1, beide Läufe im selben 30-s-Fenster:

  | Backend | Audio | Dauer | pro Audio-Sekunde |
  |---|---|---|---|
  | CPU (8 Threads) | 13,4 s | **6,6 s** | 0,49 s |
  | Vulkan | 9,9 s | 14,1 s | 1,42 s |

  Faktor 2,1 zugunsten der CPU bei identischer Encoder-Arbeit. Der Vulkan-Pfad
  ist deshalb nicht mehr einkompiliert (`.so`: 68 MB → 2,9 MB). Die App fragt
  `WhisperBridge.hasGpuBackend()` ab und zeigt den Schalter nur, wenn ein
  GPU-Backend vorhanden ist. Die Statuszeile nennt das aktive Backend
  (`CPU · 8 Threads`).
- **Modell-Reuse:** `loadModel` erkennt ein bereits geladenes Modell und liest
  nicht erneut 180+ MB von der Flash — wichtig für die Diktat-Kachel.
- **Zeitaufschlüsselung in der App.** whisper.cpp schreibt seine Messung über
  den Log-Callback nach stderr, was unter Android verpufft. `whisper_jni.c`
  hängt sich per `whisper_log_set` ein, leitet alles nach logcat und fängt die
  Ausgabe von `whisper_print_timings` in einen Puffer ab — die App zeigt sie
  nach jedem Lauf an: mel, encode, decode, batchd, prompt und `fallbacks`
  (Neuansätze des Decoders mit höherer Temperatur).

- **Sprache fest einstellen statt „Automatisch".** Der größte Einzelposten.
  Gemessen auf dem X300 Pro, 9,5 s Audio, Whisper small q5_1, Sprache „auto":

  ```
  fallbacks   =    0 p /  0 h
  mel time    =    6.31 ms
  encode time = 4897.04 ms /  2 runs (2448.52 ms per run)
  decode time =  356.22 ms / 39 runs (   9.13 ms per run)
  total time  = 5299.16 ms
  ```

  Der Encoder macht **92 % der Gesamtzeit** aus und läuft bei „auto" **zweimal**:
  `whisper_full` ruft für die Spracherkennung `whisper_lang_auto_detect_with_state`
  auf (`src/whisper.cpp`, Zeile 6851), und die Funktion startet in Zeile 4066
  einen vollständigen eigenen Encoder-Durchlauf, bevor die Transkription
  überhaupt beginnt. Kosten: annähernd Faktor 2, unabhängig von der Audiolänge.

  Deshalb ist der Standard jetzt `de`, die Auswahl wird persistiert (vorher
  sprang sie bei jedem Start auf „auto" zurück), und das Diktat-Overlay benutzt
  dieselbe Einstellung statt eines fest verdrahteten „auto". Im Auswahlmenü
  heißt der Eintrag „Automatisch (2× so lang)".

- **`single_segment` bei kurzem Audio.** Passt das Audio in ein 30-s-Fenster,
  wird es in einem Durchlauf verarbeitet: sonst kann whisper.cpp das Segment am
  Zeitstempel des letzten Tokens beenden, `seek` dorthin setzen und ein weiteres
  volles Fenster encodieren. `single_segment` setzt `seek_delta` über das Ende
  hinaus (Zeile 7420). Nur bei kurzem Audio gesetzt — `seek` wird ungeprüft
  weitergezählt, bei längeren Dateien fiele der Rest sonst hinten runter.
  Eigener Messwert dafür steht aus; der doppelte Lauf oben kam von der
  Spracherkennung, nicht von der Seek-Schleife.

- **Kurzes Audio beschleunigen** (Schalter, Standard **aus**) setzt
  `whisper_full_params.audio_ctx` auf die tatsächliche Audiolänge, statt immer
  das volle 30-Sekunden-Fenster zu encodieren. whisper.cpp verlangt
  `n_frames <= audio_ctx * 2` bei 100 Mel-Frames je Sekunde, also 50 Positionen
  je Audiosekunde; `audio_ctx_for()` rechnet das aus, legt einen halben
  256er-Block Reserve drauf und rundet auf ein Vielfaches von 256 auf.
  Ab ~29 s Audio wird nicht mehr gekürzt.

  Wirkt, aber weniger als erhofft — die Encoder-Kosten fallen nicht linear mit
  der Kontextlänge. Gemessen, Sprache „auto", also je 2 Encoder-Läufe:

  | Fenster | Audio | ms je Encoder-Lauf | Gesamt |
  |---|---|---|---|
  | 1500 | 8,1 s | 2429,9 | 5,1 s |
  | 768 | 8,6 s | 1801,5 | 3,9 s |

  Rund 26 % je Durchlauf. Steht trotzdem auf **aus**: whisper.h führt `audio_ctx`
  unter „[EXPERIMENTAL] speed-up techniques" mit dem Hinweis auf möglichen
  Qualitätsverlust, und der ist an gesprochenem Deutsch noch nicht geprüft.
  Die Statuszeile zeigt das benutzte Fenster (`Fenster: 768`).

## Fallstricke

- **`verticalScroll` in einem `LazyColumn`-Element braucht vorher eine
  Höhenbegrenzung.** `LazyColumn` misst seine Kinder in Scrollrichtung mit
  unbegrenzter Höhe; ein vertikal scrollbares Element wirft unter
  Infinity-Constraints eine `IllegalStateException`. Die Reihenfolge der
  Modifier entscheidet, weil Constraints von außen nach innen fließen:

  ```kotlin
  Modifier.verticalScroll(…).heightIn(max = 160.dp)  // Absturz
  Modifier.heightIn(max = 160.dp).verticalScroll(…)  // richtig
  ```

  Der Verlauf hat die App deshalb beim Öffnen kommentarlos beendet.

## Parakeet neben Whisper

Whisper Bar unter macOS benutzt inzwischen gar kein Whisper mehr, sondern
**NVIDIA Parakeet TDT 0.6B v3** über FluidAudio als CoreML-Modell auf der Neural
Engine (`~/Library/Application Support/FluidAudio/Models/parakeet-tdt-0.6b-v3`).

CoreML ist nicht portierbar — der Weg dorthin ist aber trotzdem offen: das
whisper.cpp in `engine/` bringt bereits eine native Parakeet-Implementierung mit
(`src/parakeet.cpp`, `include/parakeet.h`, `examples/parakeet-cli`,
`models/convert-parakeet-to-ggml.py`, `scripts/quantize-parakeet.sh`). Fertige
GGUF-Quantisierungen liegen auf HuggingFace (`parakeet-GGUF`, q8_0/q4_k/q2_k).

Parakeet TDT ist ein Transducer: kein 30-Sekunden-Zwangsfenster, kein
autoregressives Decoding, und mangels Sprachparameter auch keine
Spracherkennungs-Runde. v3 ist mehrsprachig.

### Gemessener Vergleich

Beides nativ auf demselben Mac gebaut und ausgeführt, **CPU-only** (`-ng`),
8 Threads, deutsche Testsätze aus `say -v Anna`. Angegeben ist die Rechenzeit je
Äußerung (mel + encode + decode); Parakeets ausgewiesene „total time" enthält
zusätzlich die einmalige State-Allokation und ist hier nicht vergleichbar.

| Audio | Whisper small q5_1 | Parakeet TDT q8_0 |
|---|---|---|
| 2,3 s (Diktatsatz) | 655 ms (encode 608, fix) | **85 ms** (encode 82) |
| 10,6 s | 702 ms (encode 535, fix) | **293 ms** (encode 280) |

Der Unterschied kommt genau von der Fensterlogik: Whispers Encoder kostet
konstant ~550–600 ms, egal ob 2 oder 10 Sekunden gesprochen wurden. Parakeets
Encoder skaliert mit der tatsächlichen Länge. Für kurzes Diktat sind das rund
Faktor 7, bei zehn Sekunden noch Faktor 2,4.

Qualität am selben Satz — Whisper zerlegt das Kompositum, Parakeet nicht:

```
Whisper : Die Umsatzsteuer voranmeldung für das dritte Quartal …
Parakeet: Die Umsatzsteuervoranmeldung für das dritte Quartal …
```

Preis: Modell 638 MB (q8_0) statt 181 MB, plus ~340 MB Compute-Puffer für den
Encoder. Auf einem PC unkritisch, auf dem Handy spürbar.

Einschränkung der Messung: synthetische Stimme statt echter Aufnahme, und macOS-
CPU statt der Zielhardware. Die Größenordnung ist belastbar, die exakten Zahlen
sind es nicht.

### Einbau

Beide Familien laufen über denselben Kern. Kein `engine`-Feld im Manifest nötig:
`we_load` rät die Familie am Dateinamen und probiert bei Fehlschlag die andere —
beide Formate tragen dieselbe ggml-Magic, ein misslungener Ladeversuch ist also
der einzige verlässliche Test. Ein Modell mit irreführendem Namen wird trotzdem
korrekt erkannt.

Wo Parakeet aktiv ist, blenden beide Apps Sprachauswahl und Kurzaudio-Schalter
aus; beides hat dort keine Wirkung. Die Statuszeile nennt die Familie
(`Parakeet · CPU · 8 Threads`).

Zwei Fallstricke, die beim Einbau aufgeschlagen sind:

- **ggml sucht Backend-Module nur im Verzeichnis der ausführbaren Datei und im
  Arbeitsverzeichnis** (`ggml-backend-reg.cpp`), nicht neben der DLL, die es
  geladen hat. Unter Windows liegt `whisper_shim.dll` aber in `engine/` und die
  EXE eine Ebene höher — der native Pfad hätte nie funktioniert. Der Shim
  ermittelt jetzt sein eigenes Verzeichnis (`GetModuleHandleEx`/`dladdr`) und
  gibt es über `we_set_backend_dir` an `ggml_backend_load_all_from_path` weiter.
- **Ohne registriertes Backend bricht whisper.cpp den ganzen Prozess ab**
  (`GGML_ASSERT` in `make_buft_list`), statt NULL zurückzugeben. `we_load` prüft
  deshalb vorher `ggml_backend_dev_count()` — sonst stürbe die Windows-App,
  statt auf `whisper-cli` zurückzufallen.

### Backend

Statisches Hosting auf dem eigenen VPS hinter TLS (Let.s Encrypt):
`https://whisper.scheisssewasser.xyz/models.json` — siehe `server/README-server.md`.
URL in beiden Apps an einer Stelle konfiguriert:
Android `ModelConfig.kt`, Windows `ModelConfig.cs`.

## Hintergrundbetrieb

**Windows.** Die App lebt im Infobereich; Fenster schließen beendet sie nicht.
Der Hotkey-Thread hängt in `GetMessage` und kostet im Leerlauf keine CPU — es
wird nichts gepollt.

- **Frei belegbarer Hotkey:** Schaltfläche neben „Diktat-Hotkey" anklicken, dann
  die gewünschte Kombination drücken (Esc bricht ab). Mindestens ein Modifikator
  ist Pflicht, sonst schluckte der Hotkey die Taste systemweit. Ist die
  Kombination schon vergeben, meldet das die Oberfläche, statt still zu
  scheitern — `RegisterHotKey` gibt das zurück. Die Neubelegung läuft ohne
  Neustart über eine Nachricht an den Hotkey-Thread; `RegisterHotKey` bindet an
  den Thread, der das Fenster erzeugt hat, deshalb der Umweg.
- **Autostart:** Eintrag im `Run`-Schlüssel unter `HKEY_CURRENT_USER` — keine
  Administratorrechte nötig. Gestartet wird mit `--tray`, also ohne Fenster.
- **Modell bleibt geladen.** Bisher startete jedes Diktat `whisper-cli.exe` als
  neuen Prozess und las die komplette Modelldatei neu von der Platte. Jetzt
  hält `whisper_shim.dll` den Kontext im Prozess der App; beim Start wird einmal
  vorgeladen. Die Statuszeile zeigt „Engine geladen im Hintergrund · CPU · N
  Threads". Fehlt die DLL, fällt die App automatisch auf `whisper-cli` zurück
  und sagt das auch.

  Die Dateitranskription läuft weiter über `whisper-cli`, weil dort die
  Formatunterstützung (mp3, m4a, ogg …) hängt.

**Android.** Ein dauerhaft wartender Prozess ist ohne Vordergrunddienst mit
sichtbarer Benachrichtigung nicht vorgesehen — das System beendet die App.
Stattdessen gibt es mehrere Auslöser, und der Start wurde entkoppelt: die
Aufnahme läuft sofort los, das Modell lädt parallel dazu. Vorher wurde erst
geladen und dann aufgenommen, man sprach also bei kaltem Prozess ins Leere.

| Auslöser | Einrichtung |
|---|---|
| Schnelleinstellungs-Kachel | Schnelleinstellungen bearbeiten → „Whisper Diktat" |
| Eigenes App-Symbol „Whisper Diktat" | liegt im App-Menü; für Tastenbeleger, die nur ganze Apps kennen |
| Launcher-Shortcut | App-Symbol lang drücken → „Diktat" (auch auf den Startbildschirm ziehbar) |
| Assistenten-Geste / Ein-Aus-Taste | Einstellungen → Standard-Apps → Digitaler Assistent → diese App |
| Tastenbeleger, Tasker, MacroDroid | Intent `dev.whisper.transcribe.action.DICTATE` auf `dev.whisper.transcribe/.DictationActivity` |

`DictationActivity` ist dafür `exported`. Das heißt, jede App könnte den Dialog
öffnen — aufgenommen wird aber nur sichtbar im Vordergrund, und der Text landet
erst in der Zwischenablage, wenn „Fertig" angetippt wird.

## Diktat-Modus

**Windows:** App läuft (minimierbar in den Tray). **Strg+Alt+Leertaste** startet überall
die Aufnahme — unabhängig davon, welche App im Vordergrund ist. Erneut drücken: Text wird
transkribiert, in die Zwischenablage gelegt und per simuliertem **Strg+V automatisch ins
zuvor fokussierte Fenster eingefügt** (Chat, E-Mail, Dokument …).

**Android:** Schnelleinstellungs-Kachel „Whisper Diktat" (Schnelleinstellungen bearbeiten →
Kachel hinzufügen). Ein Tipp öffnet das Diktat-Overlay, nimmt sofort auf, transkribiert nach
„Fertig" und kopiert den Text in die Zwischenablage — danach im Chat einfügen (Android
erlaubt Apps ohne Sonderrechte kein automatisches Einfügen in fremde Apps; direktes
Einfügen wäre über eine eigene Tastatur/RecognitionService möglich, siehe Roadmap).

## Roadmap-Ideen

- Android: eigenes IME bzw. RecognitionService → Diktattext landet direkt im Textfeld
  (wie FUTO Voice Input), statt über die Zwischenablage
- Automatische Spracherkennung pro Segment, Modell-Wechsel ohne Neustart des Downloads
- Aufnahmestopp durch Stille (VAD)

## Lizenzhinweise

- Whisper-Modelle: MIT (OpenAI)
- whisper.cpp: MIT
- Die App itself: eigene Arbeit des Workspace-Besitzers
