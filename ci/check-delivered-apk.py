#!/usr/bin/env python3
"""Black-box checks of the exact already delivered binary; never re-sign or rebuild it."""
import hashlib,json,re,shlex,sqlite3,subprocess,tempfile,time,traceback,xml.etree.ElementTree as ET
from pathlib import Path
PKG='com.nezabudka.preview'
ACT=PKG+'/com.nezabudka.testharness.FinalMainActivity'
APK=Path('source/downloads/Nezabudka-bad642de-debug.apk')
EXPECTED='3e3e460d05c4a8993a4599dc82de14719efe42d2b2aa4b3d350a45cbbdeb55b3'
OUT=Path('evidence/delivered-apk');OUT.mkdir(parents=True,exist_ok=True)
RESULTS=[]
def adb(*args,data=None,check=True,timeout=35):
    r=subprocess.run(['adb',*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if check and r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:1500])
    return r.stdout
def shell(cmd,**kw):return adb('shell',cmd,**kw).decode(errors='replace')
def runwrite(path,data):
    return adb('shell','-T',f'run-as {PKG} sh -c '+shlex.quote('cat > '+path),data=data)
def stop():shell('am force-stop '+PKG)
def start():shell('am start -W -n '+ACT)
def dump():
    for _ in range(4):
        shell('uiautomator dump /sdcard/apk-ui.xml',check=False)
        raw=shell('cat /sdcard/apk-ui.xml',check=False)
        try:return ET.fromstring(raw)
        except ET.ParseError:time.sleep(.4)
    raise AssertionError('UI hierarchy unavailable')
def nodes(text=None,desc=None):
    return [n for n in dump().iter('node') if (text is None or n.get('text')==text) and (desc is None or n.get('content-desc')==desc)]
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(text=None,desc=None):
    found=nodes(text,desc)
    assert found, f'No UI element text={text!r} desc={desc!r}'
    x1,y1,x2,y2=rect(found[0]);shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')
def wait(check,label,seconds=20):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if check():return
        time.sleep(.3)
    raise AssertionError(label)
def activity(suffix):
    return any(suffix in l for l in shell('dumpsys activity activities').splitlines() if 'mResumedActivity' in l or 'topResumedActivity' in l)
def screenshot(label):
    (OUT/(label+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    try:(OUT/(label+'.xml')).write_text(ET.tostring(dump(),encoding='unicode'))
    except Exception:pass
def read_db():
    d=Path(tempfile.mkdtemp(prefix='delivered-db-'));p=d/'app.db'
    p.write_bytes(adb('exec-out','run-as',PKG,'cat','databases/nezabudka_final.db'))
    for ext in ('-wal','-shm'):
        content=adb('exec-out','run-as',PKG,'cat','databases/nezabudka_final.db'+ext,check=False)
        if content:(d/('app.db'+ext)).write_bytes(content)
    return p
BASE=None
def fixture(alarm=False):
    stop();p=Path(tempfile.mkdtemp())/'fixture.db';p.write_bytes(BASE)
    now=int(time.time()*1000);due=now+12000
    import datetime
    day=(datetime.datetime.fromtimestamp(due/1000).date()-datetime.date(1970,1,1)).days
    c=sqlite3.connect(p)
    for table in ('reminder_events','final_schedule_slots','final_reminders','final_memos'):c.execute('DELETE FROM '+table)
    c.execute('''INSERT INTO final_reminders(id,text,note,mode,state,epochDaysCsv,minutesOfDayCsv,excludedWeekdaysCsv,startEpochDay,createdAt,creationOrder)
        VALUES(1,?,'',?,'ACTIVE',?,'857','',?,?,?)''',('APK ALARM' if alarm else 'APK TIME','ONE_SHOT' if alarm else 'DAILY',str(day) if alarm else '',None if alarm else day+1,now,now))
    if alarm:
        c.execute('''INSERT INTO reminder_events(id,reminderId,dueAt,state,retryIndex,nextSignalAt,presentationGeneration) VALUES(1,1,?,'SCHEDULED',0,?,0)''',(due,due))
        c.execute('INSERT INTO final_schedule_slots(reminderId,epochDay,minuteOfDay,creationOrder) VALUES(1,?,857,?)',(day,now))
    c.execute("INSERT INTO final_memos(kind,text,createdAt,creationOrder) VALUES('TEXT','APK MEMO',?,?)",(now,now))
    c.commit();c.execute('PRAGMA wal_checkpoint(TRUNCATE)');c.execute('PRAGMA journal_mode=DELETE');c.close()
    shell(f'run-as {PKG} rm -f databases/nezabudka_final.db-wal databases/nezabudka_final.db-shm')
    runwrite('databases/nezabudka_final.db',p.read_bytes())
    shell(f'run-as {PKG} mkdir -p shared_prefs')
    runwrite('shared_prefs/nezabudka_final_prefs.xml',b'<?xml version="1.0" encoding="utf-8"?><map><boolean name="setup_done" value="true"/><string name="name">APK</string></map>')
    start();wait(lambda:bool(nodes('Текущие напоминания')),'Home did not render')
def case(label,fn):
    t=time.monotonic()
    try:fn();RESULTS.append(dict(name=label,passed=True,seconds=round(time.monotonic()-t,2)))
    except Exception as e:
        RESULTS.append(dict(name=label,passed=False,seconds=round(time.monotonic()-t,2),error=str(e),trace=traceback.format_exc()))
    finally:
        screenshot(label)
        (OUT/'results.json').write_text(json.dumps(dict(apk_sha256=EXPECTED,cases=RESULTS),ensure_ascii=False,indent=2))
        print('DELIVERED APK CASE:',json.dumps(RESULTS[-1],ensure_ascii=False),flush=True)
def first_launch():
    start();wait(lambda:bool(nodes('Далее')),'Onboarding Next not rendered')
    screenshot('onboarding')
    shell('input keyevent HOME');wait(lambda:not activity('FinalMainActivity'),'System Home failed')
def change_time():
    fixture();tap('APK TIME');tap('Ежедневно');tap('Изменить время')
    n=nodes('14')[0];x1,y1,x2,y2=rect(n);shell(f'input tap {int(x2+(x2-x1)*.22)} {(y1+y2)//2}')
    wait(lambda:bool(nodes('Сохранить')),'Save did not activate');tap('Сохранить')
    wait(lambda: not nodes('Сохранить') and not nodes('Сохраняю…'),'Save did not finish')
    screenshot('saved-time');stop()
    c=sqlite3.connect(read_db());v=c.execute('SELECT minutesOfDayCsv FROM final_reminders WHERE id=1').fetchone()[0];c.close()
    assert v=='917',f'Expected persisted 15:17, got {v}'
    start();wait(lambda:bool(nodes('APK TIME')),'Saved reminder disappeared')
def delete_memo():
    fixture();n=nodes('APK MEMO')[0];x1,y1,x2,y2=rect(n)
    shell(f'input swipe {x2-10} {(y1+y2)//2} {x1+10} {(y1+y2)//2} 400')
    wait(lambda:bool(nodes('Удалить навсегда?')),'No delete confirmation')
    assert nodes('Текущие напоминания'),'Home background disappeared'
    tap('Да');wait(lambda:not nodes('APK MEMO'),'Memo remained after delete')
def alarm():
    fixture(True)
    wait(lambda:activity('FinalCompactAlarmActivity'),'Automatic compact screen missing',30)
    screenshot('compact-alarm')
    size=list(map(int,re.findall(r'\d+',shell('wm size'))))[-2:]
    shell(f'input tap {size[0]//2} {size[1]//2}')
    wait(lambda:activity('FinalAlarmActivity'),'Tap did not open full alarm')
    wait(lambda:bool(nodes('Напомнить снова')),'Full alarm has wrong actions')
    screenshot('full-alarm');tap(desc='Закрыть')
    wait(lambda:not activity('FinalAlarmActivity'),'Full alarm did not close')
    stop();c=sqlite3.connect(read_db())
    state=c.execute('SELECT state FROM reminder_events WHERE id=1').fetchone()[0]
    reminder=c.execute('SELECT state FROM final_reminders WHERE id=1').fetchone()[0];c.close()
    assert state=='ACKNOWLEDGED' and reminder=='DELETED',(state,reminder)
try:
    digest=hashlib.sha256(APK.read_bytes()).hexdigest();assert digest==EXPECTED,digest
    adb('uninstall',PKG,check=False);adb('install',str(APK),timeout=90)
    api=int(shell('getprop ro.build.version.sdk').strip())
    if api>=33:shell(f'pm grant {PKG} android.permission.POST_NOTIFICATIONS')
    if api>=31:shell(f'appops set {PKG} SCHEDULE_EXACT_ALARM allow')
    shell(f'appops set {PKG} SYSTEM_ALERT_WINDOW deny')
    shell('svc wifi disable',check=False);shell('svc data disable',check=False)
    case('original-apk-onboarding-and-home',first_launch)
    stop();p=read_db();c=sqlite3.connect(p);c.execute('PRAGMA wal_checkpoint(TRUNCATE)');c.execute('PRAGMA journal_mode=DELETE');c.close();BASE=p.read_bytes()
    case('original-apk-change-time',change_time)
    case('original-apk-delete-memo',delete_memo)
    case('original-apk-alarm-compact-full-close',alarm)
finally:
    adb('uninstall',PKG,check=False)
    print('DELIVERED APK SUMMARY:',json.dumps(RESULTS,ensure_ascii=False),flush=True)
raise SystemExit(0 if RESULTS and all(r['passed'] for r in RESULTS) else 1)
