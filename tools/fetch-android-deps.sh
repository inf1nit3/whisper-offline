#!/bin/zsh
# Lädt die vorgebaute sherpa-onnx-Bibliothek (Sprechertrennung im
# Meeting-Modus) nach app/android/app/libs/ — zu groß fürs Repo (38 MB).
# Statisch gegen onnxruntime gelinkt: eine .so je ABI statt zwei.
set -e
ROOT="${0:A:h:h}"
VER=1.13.8
SHA=b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
DEST="$ROOT/app/android/app/libs/sherpa-onnx-static-link-onnxruntime-$VER.aar"
mkdir -p "${DEST:h}"
if [ ! -f "$DEST" ] || [ "$(shasum -a 256 "$DEST" | cut -d' ' -f1)" != "$SHA" ]; then
    curl -fL -o "$DEST.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-static-link-onnxruntime-$VER.aar"
    [ "$(shasum -a 256 "$DEST.part" | cut -d' ' -f1)" = "$SHA" ] || { echo "Prüfsumme falsch"; exit 1; }
    mv "$DEST.part" "$DEST"
fi
echo "sherpa-onnx $VER bereit: $DEST"
