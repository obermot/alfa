#!/usr/bin/env bash
set -uo pipefail
adb logcat -c
# The grant is applied after install by the test runner where supported.
cd source
gradle --no-daemon --max-workers=2 :app:connectedDebugAndroidTest --stacktrace > ../evidence/instrumentation.log 2>&1
result=$?
cd ..
adb logcat -d -v threadtime > evidence/logcat.txt
adb shell dumpsys activity activities > evidence/activities.txt
adb shell dumpsys alarm > evidence/alarms.txt
adb shell screencap -p /sdcard/device-final.png
adb pull /sdcard/device-final.png evidence/ || true
adb pull /sdcard/Android/data/com.nezabudka.preview/files/device-evidence evidence/ || true
cat evidence/instrumentation.log
exit "$result"
