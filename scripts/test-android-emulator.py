"""Opt-in integration checks on this project's dedicated AVD, never a phone."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = ROOT / 'artifacts/android-toolchain/android-sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5580'
PACKAGE = 'com.fanniuzhan.winddown'
OUT = ROOT / 'artifacts/android-emulator'


def adb(*args, check=True, timeout=40):
    result = subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        detail = (result.stderr + result.stdout).decode('utf-8', errors='replace').strip()
        raise RuntimeError(f'ADB {args[:3]} exited {result.returncode}: {detail or "device connection interrupted"}')
    return result.stdout.decode('utf-8', errors='replace')


def capture(name):
    data = subprocess.run([str(ADB), '-s', SERIAL, 'exec-out', 'screencap', '-p'],
                          capture_output=True, check=True, timeout=30).stdout
    (OUT / (name + '.png')).write_bytes(data)


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/winddown-test-ui.xml')
    root = ET.fromstring(adb('exec-out', 'cat', '/sdcard/winddown-test-ui.xml'))
    # Android's dump command temporarily suppresses other accessibility services.
    # Let our service rebind and the page refresh before tapping. Cover/alarm/call
    # assertions use dumpsys directly and never attach UiAutomation during a trial.
    time.sleep(2.5)
    return root


def tap(text, scroll=False):
    for attempt in range(7 if scroll else 1):
        root = ui()
        node = next((n for n in root.iter('node') if n.get('text') == text or n.get('content-desc') == text), None)
        if node is not None:
            left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(.6)
            return
        if scroll:
            size = list(map(int, re.findall(r'\d+', adb('shell', 'wm', 'size'))))[-2:]
            width, height = size
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 4 // 5),
                str(width // 2), str(height // 3), '350')
    raise AssertionError('UI control not found: ' + text)


def open_checks():
    # Start at the top so a previous test's scroll position cannot hide the disclosure.
    width, height = list(map(int, re.findall(r'\d+', adb('shell', 'wm', 'size'))))[-2:]
    for _ in range(3):
        adb('shell', 'input', 'swipe', str(width // 2), str(height // 3),
            str(width // 2), str(height * 9 // 10), '180')
    root = ui()
    if not any(n.get('content-desc') == '本机检查，收起' for n in root.iter('node')):
        tap('本机检查，展开', scroll=True)


def reconnect_test_service():
    # Force-stop persistence checks can disconnect an accessibility service. Rebind only our test service.
    service = PACKAGE + '/.NightGuardService'
    existing = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
    others = [s for s in existing.split(':') if s not in ('', 'null', service,
               PACKAGE + '/' + PACKAGE + '.NightGuardService')]
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others) or 'null')
    time.sleep(.5)
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others + [service]))
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    time.sleep(2)


def covered():
    return '收工助手 · 夜间限制' in adb('shell', 'dumpsys', 'window', 'windows')


def expect_cover(expected, message, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if covered() == expected:
            print('PASS ' + message, flush=True)
            return
        time.sleep(.5)
    capture('failure')
    raise AssertionError(message)


def test_alarm():
    if 'CustomWindDown_API36' not in adb('emu', 'avd', 'name').splitlines():
        raise RuntimeError('Wrong device for alarm test.')
    # Clock caches its timezone in-process, and this fresh AOSP image starts with
    # notifications denied. Prepare only the system Clock in this dedicated AVD.
    adb('shell', 'am', 'force-stop', 'com.android.deskclock')
    adb('shell', 'pm', 'grant', 'com.android.deskclock', 'android.permission.POST_NOTIFICATIONS')
    label = 'WindDownComputerTest-' + str(time.time_ns())
    reconnect_test_service()
    adb('shell', 'am', 'start', '-W', '-f', '0x34000000', '-n', PACKAGE + '/.MainActivity')
    open_checks()
    hour, minute, second = map(int, adb('shell', 'date', '+%H:%M:%S').strip().split(':'))
    target = (hour * 60 + minute + 2) % 1440
    target_epoch = (int(adb('shell', 'date', '+%s').strip()) // 60 + 2) * 60
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.SET_ALARM',
        '--ei', 'android.intent.extra.alarm.HOUR', str(target // 60),
        '--ei', 'android.intent.extra.alarm.MINUTES', str(target % 60),
        '--ez', 'android.intent.extra.alarm.SKIP_UI', 'true',
        '--es', 'android.intent.extra.alarm.MESSAGE', label)
    try:
        # SINGLE_TOP preserves the expanded checks when returning from Clock.
        adb('shell', 'am', 'start', '-W', '-f', '0x34000000', '-n', PACKAGE + '/.MainActivity')
        tap('2. 开始 60 秒兼容性试锁', scroll=True)
        print(f'Preparing trial for emulator alarm at {target // 60:02d}:{target % 60:02d}...', flush=True)
        # Slow UI automation must not consume the remaining minute before the alarm.
        # Open the confirmation first, then start the 60-second trial near the alarm.
        while int(adb('shell', 'date', '+%s').strip()) < target_epoch - 35:
            time.sleep(1)
        tap('开始试锁')
        started = time.monotonic()
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        expect_cover(True, 'ordinary app covered before scheduled emulator alarm')
        print(f'Waiting for emulator alarm at {target // 60:02d}:{target % 60:02d}...', flush=True)
        deadline = started + 59
        while time.monotonic() < deadline:
            audio = adb('shell', 'dumpsys', 'audio')
            active_alarm = any('AudioPlaybackConfiguration' in line and 'state:started' in line
                               and 'usage=USAGE_ALARM' in line for line in audio.splitlines())
            if active_alarm:
                expect_cover(False, 'notification-only ringing alarm removes cover')
                capture('06-alarm-ringing')
                (OUT / 'alarm-audio-state.txt').write_text(audio, encoding='utf-8')
                print('PASS emulator system clock is actively playing USAGE_ALARM', flush=True)
                break
            time.sleep(.5)
        else:
            capture('alarm-failure')
            raise AssertionError('Scheduled emulator alarm playback was not observed during trial')
    finally:
        adb('shell', 'am', 'start', '-a', 'android.intent.action.DISMISS_ALARM',
            '--es', 'android.intent.extra.alarm.SEARCH_MODE', 'android.label',
            '--es', 'android.intent.extra.alarm.MESSAGE', label, check=False)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    if time.monotonic() - started < 50:
        expect_cover(True, 'cover returns after dismissing alarm')
    while time.monotonic() - started < 62:
        time.sleep(1)
    expect_cover(False, 'alarm trial also ends automatically')
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    capture('07-all-finished')


def test_layout():
    font = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
    try:
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
        adb('shell', 'wm', 'size', '630x1400')  # 360 dp wide on our fixed 280 dpi AVD.
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
        time.sleep(2)
        capture('08-large-font-main')
        tap('开始  22:30', scroll=True)
        capture('09-large-font-time-picker')
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        open_checks()
        tap('1. 了解并开启无障碍服务', scroll=True)
        capture('10-large-font-disclosure')
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        print('PASS large-font layout opens time picker and expanded checks on a 360 dp screen', flush=True)
    finally:
        adb('shell', 'settings', 'put', 'system', 'font_scale', font if font not in ('', 'null') else '1.0')
        adb('shell', 'wm', 'size', '720x1600')
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
        time.sleep(2)
        capture('11-final-home')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enable-test-accessibility', action='store_true', required=True,
                        help='Explicit permission to enable this app in the dedicated AVD only.')
    parser.add_argument('--reset-test-app', action='store_true', required=True,
                        help='Reset only this package in the dedicated AVD before testing.')
    parser.add_argument('--test-alarm', action='store_true', help='Also schedule a real alarm inside the AVD.')
    parser.add_argument('--test-layout', action='store_true', help='Also capture 150 percent font at 360 dp width; restore display settings afterward.')
    options = parser.parse_args()
    if 'CustomWindDown_API36' not in adb('emu', 'avd', 'name').splitlines():
        raise RuntimeError('Refusing to operate on any other emulator/device.')
    OUT.mkdir(parents=True, exist_ok=True)
    service = PACKAGE + '/.NightGuardService'
    existing = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
    others = [s for s in existing.split(':') if s not in ('', 'null', service,
               PACKAGE + '/' + PACKAGE + '.NightGuardService')]
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others) or 'null')
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1' if others else '0')
    adb('shell', 'pm', 'clear', PACKAGE)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    time.sleep(2)
    assert any(n.get('text') == '计划未启用' for n in ui().iter('node'))
    print('PASS first launch is disabled', flush=True)
    capture('01-main-disabled')
    open_checks()
    tap('1. 了解并开启无障碍服务', scroll=True)
    tap('了解并前往设置')
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services',
        ':'.join(others + [service]))
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    time.sleep(2)
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    open_checks()
    tap('2. 开始 60 秒兼容性试锁', scroll=True)
    tap('开始试锁')
    trial_started = time.monotonic()
    expect_cover(True, 'trial covers ordinary application')
    capture('02-trial-cover')
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
    expect_cover(True, 'ordinary settings has no exemption')
    adb('shell', 'am', 'start', '-a', 'android.intent.action.SHOW_ALARMS')
    expect_cover(False, 'system clock application is allowed')
    capture('03-clock-allowed')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    expect_cover(True, 'cover returns after leaving clock')
    try:
        adb('emu', 'gsm', 'call', '15555215554')
        expect_cover(False, 'simulated incoming call is allowed')
        capture('04-incoming-call')
        adb('shell', 'input', 'keyevent', 'KEYCODE_CALL')
        time.sleep(1)
        expect_cover(False, 'simulated answered call stays usable')
    finally:
        adb('emu', 'gsm', 'cancel', '15555215554', check=False)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    expect_cover(True, 'cover returns after call')
    print('Waiting for the real 60-second trial deadline...', flush=True)
    while time.monotonic() - trial_started < 62:
        time.sleep(1)
    expect_cover(False, 'trial ends automatically without user bypass')
    prefs = ET.fromstring(adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/night_guard.xml'))
    values = {n.get('name'): n.get('value') for n in prefs}
    assert values.get('trial_completed') == 'true' and values.get('enabled') != 'true'
    print('PASS completed trial persisted with formal schedule disabled', flush=True)
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    tap('一', scroll=True)
    tap('保存计划', scroll=True)
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    monday = next(n for n in ui().iter('node') if n.get('text') == '一')
    assert monday.get('checked') == 'false'
    print('PASS weekday change persisted across app restart', flush=True)
    tap('一')
    tap('保存计划', scroll=True)
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
    capture('05-trial-finished')
    if options.test_alarm:
        test_alarm()
    if options.test_layout:
        test_layout()
    print('Integration smoke checks completed; formal schedule remains disabled.', flush=True)


if __name__ == '__main__':
    main()
