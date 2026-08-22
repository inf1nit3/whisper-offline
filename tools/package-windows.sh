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

# 2. Engine (per MinGW kreuzkompiliertes whisper-cli inkl. CPU-Dispatch-DLLs)
cp "$ROOT/dist/windows/whisper-cli.exe" "$OUT/engine/"
cp "$ROOT"/dist/windows/*.dll "$OUT/engine/"

# 3. Modelle (base + small; weitere wie large-v3-turbo-q5_0 einfach dazulegen)
cp "$ROOT/models/ggml-base.bin" "$OUT/models/"
cp "$ROOT/models/ggml-small-q5_1.bin" "$OUT/models/"

# 4. Zip
cd "$ROOT/dist"
rm -f WhisperOffline-windows-x64.zip
zip -qr WhisperOffline-windows-x64.zip WhisperOffline-windows-x64
echo "=== Paketinhalt ==="
ls -lhR "$OUT" | head -30
echo "=== Zip ==="
ls -lh WhisperOffline-windows-x64.zip
