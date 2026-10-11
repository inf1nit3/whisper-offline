#!/bin/zsh
# Baut beide nativen Android-Bibliotheken und legt sie gestrippt in die App:
#   libwhisper_jni.so  (engine/jni,  whisper.cpp)
#   libllm_jni.so      (engine/llm,  llama.cpp — Enhance)
#
#   tools/build-engine-android.sh               # arm64-v8a (Geräte)
#   tools/build-engine-android.sh x86_64        # zusätzlich für den Emulator
set -e
ROOT="${0:A:h:h}"
NDK=/opt/homebrew/share/android-ndk
STRIP=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
ABIS=(arm64-v8a "$@")

for ABI in $ABIS; do
    for PART in jni llm; do
        DIR="$ROOT/engine/$PART"
        TARGET=$([ $PART = jni ] && echo whisper_jni || echo llm_jni)
        if [ ! -f "$DIR/build-$ABI/build.ninja" ]; then
            cmake -S "$DIR" -B "$DIR/build-$ABI" -G Ninja \
                -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
                -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
        fi
        cmake --build "$DIR/build-$ABI" --target $TARGET -j8
        mkdir -p "$ROOT/app/android/app/src/main/jniLibs/$ABI"
        # Gradle schafft das Strippen nicht selbst (22 MB → 2,9 MB bzw. 90 MB → 6 MB)
        $STRIP --strip-unneeded -o "$ROOT/app/android/app/src/main/jniLibs/$ABI/lib$TARGET.so" \
            "$DIR/build-$ABI/lib$TARGET.so"
    done
done
ls -la "$ROOT"/app/android/app/src/main/jniLibs/*/
