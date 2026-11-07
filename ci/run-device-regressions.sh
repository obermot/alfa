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
import base64
for p in Path('source/app/build/outputs/androidTest-results').rglob('*.xml'):
    print('DEVICE TEST XML:',p)
    print(p.read_text()[:40000])
for p in Path('evidence').rglob('*.semantics.txt'):
    print('DEVICE SEMANTICS:',p)
    print(p.read_text()[:18000])
log = Path('evidence/logcat.txt')
if log.exists():
    print('DEVICE APP LOGCAT (focused):')
    lines = log.read_text(errors='replace').splitlines()
    markers = ('FinalRepeatEdit', 'FinalToggle', 'FinalAlarm', 'AndroidRuntime', 'FATAL EXCEPTION', 'EXACT_ALARM_PERMISSION', 'ANR in')
    print('\n'.join(line for line in lines if any(mark in line for mark in markers))[-24000:])
# Signed artifact download URLs can be unavailable to the executor. Small chunks let
# it inspect existing public synthetic screenshots through the read-only job log.
for p in sorted(Path('evidence').rglob('*.png')):
    data = base64.b64encode(p.read_bytes()).decode('ascii')
    for index in range(0, len(data), 4096):
        print('DEVICE_PNG', p.name, index // 4096, data[index:index + 4096])
REPORTS
exit "$result"
