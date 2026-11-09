#!/usr/bin/env bash
set -uo pipefail
python3 - <<'CAPABILITIES'
import json,subprocess
from pathlib import Path
def adb(*args): return subprocess.run(['adb','shell',*args],capture_output=True,text=True).stdout.strip()
values={key:adb('getprop',key) for key in ('ro.build.version.sdk','ro.build.version.release','ro.product.model','ro.product.cpu.abi')}
values['page_size']=adb('getconf','PAGE_SIZE')
Path('evidence/device-capabilities.json').write_text(json.dumps(values,indent=2))
print('DEVICE CAPABILITIES',json.dumps(values))
CAPABILITIES
adb logcat -c
python3 control/ci/check-delivered-apk.py > evidence/delivered-apk.log 2>&1
binary_result=$?
cat evidence/delivered-apk.log
# The grant is applied after install by the test runner where supported.
cd source
filter_args=()
# A request may narrow validation after an isolated change; absent means the full suite.
DEVICE_TEST_FILTER="${DEVICE_TEST_FILTER:-$(sed -n 's/^test_filter=//p' ../control/ci/DEVICE_TEST_REQUEST)}"
if [ -n "${DEVICE_TEST_FILTER:-}" ]; then
    filter_args+=("-Pandroid.testInstrumentationRunnerArguments.class=$DEVICE_TEST_FILTER")
fi
gradle --no-daemon --max-workers=2 :app:connectedDebugAndroidTest "${filter_args[@]}" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --stacktrace > ../evidence/instrumentation.log 2>&1
result=$?
cd ..
adb logcat -d -v threadtime > evidence/logcat.txt
adb shell dumpsys activity activities > evidence/activities.txt
adb shell dumpsys alarm > evidence/alarms.txt
adb shell screencap -p /sdcard/device-final.png
adb pull /sdcard/device-final.png evidence/ || true
if ! adb pull /sdcard/Android/data/com.nezabudka.preview/files/device-evidence evidence/; then
    # Android11 scoped storage may deny shell but permit the debug application's own UID.
    adb exec-out run-as com.nezabudka.preview tar -C /sdcard/Android/data/com.nezabudka.preview/files -cf - device-evidence | tar -xf - -C evidence || true
fi
cat evidence/instrumentation.log
python3 - <<'REPORTS'
from pathlib import Path
import base64,re,subprocess,io
from PIL import Image
import xml.etree.ElementTree as ET
for p in Path('source/app/build/outputs/androidTest-results').rglob('*.xml'):
    print('DEVICE TEST XML:',p)
    print(p.read_text()[:40000])
failed = set()
for report in Path('source/app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        failed.update(c.attrib['name'] for c in ET.parse(report).getroot().iter('testcase') if c.find('failure') is not None)
    except ET.ParseError:
        pass
for p in Path('evidence').rglob('*.semantics.txt'):
    print('DEVICE SEMANTICS:',p)
    if p.stem.replace('.semantics','') in failed:
        print(p.read_text()[:18000])
    else:
        print('Passed-case semantics retained in artifact')
log = Path('evidence/logcat.txt')
if log.exists():
    print('DEVICE APP LOGCAT (focused):')
    lines = log.read_text(errors='replace').splitlines()
    markers = ('FinalRepeatEdit', 'FinalToggle', 'FinalAlarm', 'AndroidRuntime', 'FATAL EXCEPTION', 'EXACT_ALARM_PERMISSION', 'ANR in')
    print('\n'.join(line for line in lines if any(mark in line for mark in markers))[-24000:])
# Signed artifact download URLs can be unavailable to the executor. Small chunks let
# it inspect existing public synthetic screenshots through the read-only job log.
selected = {'fullAlarmSidePaddingContinuesTheOriginalLowerColor', 'realOfflineVoskStartsMicPulseAndStopsWithoutBlockingMain', 'actualAlarmManagerDeliveryCompactTapFullAndClose', 'photo-viewer-menu', 'launcher-icon', 'editor-header-full-width', 'editor-stable-scale-with-ime', 'home-equal-memo-card', 'custom-clock-past-warning', 'clock-optical-centres', 'appearance-light-dark-disabled', 'calendar-selected-summary-row', 'third-long-melody-ellipsis', 'third-what-panel-keeps-underlay'}
for p in sorted(Path('evidence').rglob('*.png')):
    if p.stem not in failed | selected and 'delivered-apk' not in p.parts:
        continue
    # Keep connector logs below transport limits; the artifact retains original PNGs.
    pic = Image.open(p)
    pic.thumbnail((540, 1400))
    buffer = io.BytesIO()
    pic.save(buffer, format='PNG')
    data = base64.b64encode(buffer.getvalue()).decode('ascii')
    for index in range(0, len(data), 4096):
        print('DEVICE_PNG', p.name, index // 4096, data[index:index + 4096])
REPORTS
if [ "$binary_result" -ne 0 ]; then exit "$binary_result"; fi
exit "$result"
