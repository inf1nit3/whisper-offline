#!/bin/zsh
# End-to-End-Smoke-Test der Android-App auf dem Emulator.
# Nutzt den vorhandenen AVD "Medium_Phone_API_36.1", installiert das APK,
# startet die App und prüft im Logcat, ob whisper-jni das Modell lädt.
set -x
SDK="$HOME/Library/Android/sdk"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
APK="/Users/scheisssewasser/dev/Whisper/app/android/app/build/outputs/apk/debug/app-debug.apk"

emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-boot-anim \
         -no-snapshot -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
EMU_PID=$!

adb wait-for-device
BOOT=""
for i in $(seq 1 120); do
    BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$BOOT" = "1" ] && break
    sleep 5
done
[ "$BOOT" = "1" ] || { echo "BOOT TIMEOUT"; kill $EMU_PID; exit 1; }
echo "=== Emulator gebootet ==="

adb install -r "$APK"
adb logcat -c
adb shell am start -n dev.whisper.transcribe/.MainActivity
sleep 45   # Modell (141 MB) aus Assets entpacken + laden
echo "=== Logcat whisper-jni ==="
adb logcat -d -s whisper-jni
echo "=== Abstürze? ==="
adb logcat -d | grep -E "FATAL EXCEPTION" | head -5
echo "=== App-Prozess läuft? ==="
PID=$(adb shell pidof dev.whisper.transcribe)
echo "pid=$PID"
kill $EMU_PID 2>/dev/null
[ -n "$PID" ] && exit 0 || exit 1
