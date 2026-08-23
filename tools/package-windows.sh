#!/bin/zsh
# Baut das Windows-Gesamtpaket: GUI-Exe + whisper-Engine + Modell
set -e
ROOT="/Users/scheisssewasser/dev/Whisper"
OUT="$ROOT/dist/WhisperOffline-windows-x64"
DOTNET="$ROOT/tools/dotnet/dotnet"

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
else
    echo "WARNUNG: whisper_shim.dll fehlt — die App fällt auf whisper-cli zurück."
    echo "         Bauen mit: engine/shim (siehe README)."
    cp "$ROOT/dist/windows/whisper-cli.exe" "$OUT/engine/"
    cp "$ROOT"/dist/windows/*.dll "$OUT/engine/"
fi

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
