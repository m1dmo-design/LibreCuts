#!/usr/bin/env bash
# Emulator smoke + monkey stress test for LibreCuts. Exits non-zero if the app crashes.
set -x

APK=$(find "$GITHUB_WORKSPACE" -name "*-x86_64-release.apk" | head -1)
if [ -z "$APK" ]; then
  APK=$(find "$GITHUB_WORKSPACE" -name "*.apk" | head -1)
fi
echo "APK=$APK"
ls -la "$APK"

adb wait-for-device
echo "SDK: $(adb shell getprop ro.build.version.sdk)"

echo "--- install ---"
adb install -r -g "$APK" || exit 2

echo "--- launch ---"
adb shell am start -W -n com.tharunbirla.librecuts/.MainActivity || exit 3
sleep 15

echo "--- crash buffer after launch ---"
adb shell "logcat -d -b crash" | tail -60

echo "--- monkey stress (5000 events) ---"
adb shell monkey -p com.tharunbirla.librecuts --throttle 150 -v 5000
MONKEY_EXIT=$?
echo "MONKEY_EXIT=$MONKEY_EXIT"

echo "--- crash buffer after stress ---"
adb shell "logcat -d -b crash" | tail -120

echo "--- process state ---"
adb shell pidof com.tharunbirla.librecuts || echo "not running"

exit $MONKEY_EXIT
