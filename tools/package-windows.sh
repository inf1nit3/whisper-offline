#!/bin/zsh
# Baut das Windows-Gesamtpaket: GUI-Exe + whisper-Engine + Modell
set -e
ROOT="/Users/scheisssewasser/dev/Whisper"
OUT="$ROOT/dist/WhisperOffline-windows-x64"
DOTNET="$ROOT/tools/dotnet/dotnet"
WINPTHREAD=$(ls /opt/homebrew/Cellar/mingw-w64/*/toolchain-x86_64/x86_64-w64-mingw32/bin/libwinpthread-1.dll | head -1)

rm -rf "$OUT"
mkdir -p "$OUT/engine" "$OUT/models"

# 1. GUI (selbstständige Single-File-Exe, cross-kompiliert von macOS)
cd "$ROOT/app/windows/WhisperOffline"
"$DOTNET" publish -c Release -r win-x64 --self-contained true \
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true \
    -o "$OUT"

# 2. Engine (per MinGW kreuzkompiliert, inkl. CPU-Dispatch-DLLs)
#    whisper_shim.dll hält das Modell im Prozess der App geladen; whisper-cli.exe
#    bleibt als Rückfallweg und für die Dateitranskription (Formatunterstützung).
SHIM="$ROOT/engine/shim/build-win64"
if [ -f "$SHIM/whisper_shim.dll" ]; then
    cp "$SHIM/whisper_shim.dll" "$OUT/engine/"
    cp "$SHIM"/bin/*.dll "$OUT/engine/"
    cp "$SHIM"/bin/whisper-cli.exe "$OUT/engine/"
    # MinGW-Laufzeit (GPL mit Runtime-Exception, Weitervertrieb erlaubt) —
    # libwhisper.dll importiert sie, ohne sie startet nichts.
    cp "$ROOT"/dist/windows/libgcc_s_seh-1.dll "$OUT/engine/"
    cp "$ROOT"/dist/windows/libstdc++-6.dll "$OUT/engine/"
    # libstdc++ und ggml-base importieren libwinpthread — fehlte bis v2.5 im Paket,
    # die Engine ließ sich auf Rechnern ohne MinGW dann nicht laden.
    cp "$WINPTHREAD" "$OUT/engine/"
else
    echo "WARNUNG: whisper_shim.dll fehlt — die App fällt auf whisper-cli zurück."
    echo "         Bauen mit: engine/shim (siehe README)."
    cp "$ROOT/dist/windows/whisper-cli.exe" "$OUT/engine/"
    cp "$ROOT"/dist/windows/*.dll "$OUT/engine/"
fi

# Lokales Sprachmodell (Enhance): llm-runner.exe als eigener Prozess mit
# eigenen ggml-DLLs aus llama.cpp — getrennt von denen der Whisper-Engine.
LLM="$ROOT/engine/llm/build-win64"
if [ -f "$LLM/llm-runner.exe" ]; then
    mkdir -p "$OUT/engine/llm"
    cp "$LLM/llm-runner.exe" "$LLM"/bin/*.dll "$OUT/engine/llm/"
    cp "$ROOT"/dist/windows/libgcc_s_seh-1.dll "$ROOT"/dist/windows/libstdc++-6.dll "$WINPTHREAD" "$OUT/engine/llm/"
    cp "$ROOT/engine/llama.cpp/LICENSE" "$OUT/engine/llm/LICENSE-llama.cpp.txt"
else
    echo "WARNUNG: engine/llm/build-win64/llm-runner.exe fehlt — Enhance nur per eigenem Befehl möglich."
fi

# Silero-VAD (MIT-Lizenz): Whisper überspringt damit Stille. Gleiche Datei
# wie in den Android-Assets.
cp "$ROOT/app/android/app/src/main/assets/vad/ggml-silero-v6.2.0.bin" "$OUT/engine/"
cp "$ROOT/app/android/app/src/main/assets/vad/LICENSE-silero-vad.txt" "$OUT/engine/"

# 3. Modellordner anlegen — Modelle kommen beim ersten Start vom VPS
#    (siehe server/README-server.md). Zum Bündeln einfach .bin-Dateien hier kopieren.
mkdir -p "$OUT/models"

# 4. Zip
cd "$ROOT/dist"
rm -f WhisperOffline-windows-x64.zip
zip -qr WhisperOffline-windows-x64.zip WhisperOffline-windows-x64
echo "=== Paketinhalt ==="
ls -lhR "$OUT" | head -30
echo "=== Zip ==="
ls -lh WhisperOffline-windows-x64.zip
