#!/usr/bin/env bash
set -uo pipefail
adb logcat -c
# The grant is applied after install by the test runner where supported.
cd source
gradle --no-daemon --max-workers=2 :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --stacktrace > ../evidence/instrumentation.log 2>&1
result=$?
cd ..
adb logcat -d -v threadtime > evidence/logcat.txt
adb shell dumpsys activity activities > evidence/activities.txt
adb shell dumpsys alarm > evidence/alarms.txt
adb shell screencap -p /sdcard/device-final.png
adb pull /sdcard/device-final.png evidence/ || true
adb pull /sdcard/Android/data/com.nezabudka.preview/files/device-evidence evidence/ || true
cat evidence/instrumentation.log
python3 - <<'REPORTS'
from pathlib import Path
for p in Path('source/app/build/outputs/androidTest-results').rglob('*.xml'):
    print('DEVICE TEST XML:',p)
    print(p.read_text()[:40000])
for p in Path('evidence').rglob('*.semantics.txt'):
    print('DEVICE SEMANTICS:',p)
    print(p.read_text()[:18000])
REPORTS
exit "$result"
