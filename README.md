# Whisper Offline — Windows & Android

Lokale, vollständig offline laufende Sprachtranskription mit OpenAI Whisper (whisper.cpp),
nach dem Vorbild von Whisper Bar unter macOS. Keine Cloud, keine Netzwerknutzung — Modell und
Engine liegen auf dem Gerät.

Ausgangspunkt war das von Whisper Bar mitgelieferte `ggml-base.bin` (Whisper „base", MIT-Lizenz).

## Struktur

```
Whisper/
├── engine/
│   ├── whisper.cpp/          # Upstream-Quellcode (Git-Klon)
│   ├── jni/                  # JNI-Brücke (C) für Android + CMake-Setup
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
| Android | Kotlin + Jetpack Compose | `libwhisper_jni.so` per JNI (arm64-v8a, x86_64) | aus Assets → filesDir |
| Windows | Avalonia (.NET 10) | `whisper-cli.exe` als Unterprozess (CPU-Dispatch-DLLs wählen zur Laufzeit AVX2/AVX512) | `models/ggml-base.bin` neben der EXE |

Die Windows-Engine wählt beim Start automatisch die passende CPU-Variante
(SSE4.2 bis AVX-512/AMX) und läuft daher auf jedem x64-Windows ab Windows 7.
Die beiden MinGW-Runtime-DLLs (`libgcc_s_seh-1.dll`, `libstdc++-6.dll`) werden
mitgeliefert (GPL mit Runtime-Exception, Weitervertrieb erlaubt) — ohne sie
startet die EXE auf Windows-Rechnern ohne MinGW nicht.

## Fertige Pakete

- **Windows:** `dist/WhisperOffline-windows-x64.zip` (57 MB) — entpacken, `WhisperOffline.exe`
  starten, Modell einmalig vom eigenen Server laden. Keine Installation nötig.
- **Android:** `dist/WhisperOffline-android-debug.apk` (56 MB) — installieren, beim ersten
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

# Engine: Windows
cd engine/whisper.cpp
cmake -B build-win64 -G Ninja \
  -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
  -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
  -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
  -DBUILD_SHARED_LIBS=ON -DGGML_NATIVE=OFF \
  -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF -DWHISPER_SDL2=OFF
cmake --build build-win64 --target whisper-cli -j8

# Engine: Android (je ABI)
cd ../jni
for ABI in arm64-v8a x86_64; do
  cmake -B build-$ABI -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=$(brew --prefix android-ndk)/share/android-ndk/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
  cmake --build build-$ABI -j8
done
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

### Backend

Statisches Hosting auf dem eigenen VPS (nginx auf Port 8901):
`http://<vps>:8901/models.json` — siehe `server/README-server.md`.
URL in beiden Apps an einer Stelle konfiguriert:
Android `ModelConfig.kt`, Windows `ModelConfig.cs`.

## Lizenzhinweise

- Whisper-Modelle: MIT (OpenAI)
- whisper.cpp: MIT
- Die App itself: eigene Arbeit des Workspace-Besitzers
