"""Run the clock navigation regression only on this project's dedicated emulator."""
import pathlib
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = ROOT / 'artifacts/android-toolchain/android-sdk/platform-tools/adb.exe'
PACKAGE = 'com.fanniuzhan.winddown'


def adb(*args):
    result = subprocess.run([str(ADB), '-s', 'emulator-5580', *args], capture_output=True, timeout=90)
    output = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError(output)
    return output


def settings():
    raw = adb('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/night_guard.xml')
    return {node.get('name'): (node.get('value'), node.text) for node in ET.fromstring(raw)}


if __name__ == '__main__':
    if 'CustomWindDown_API36' not in adb('emu', 'avd', 'name').splitlines():
        raise RuntimeError('Refusing to test on any device other than the dedicated emulator.')
    before = settings()
    if before.get('enabled', ('false', None))[0] == 'true':
        raise RuntimeError('Disable the dedicated emulator schedule before this navigation-only check.')
    if int(before.get('trial_until', ('0', None))[0]) != 0:
        raise RuntimeError('Finish the emulator trial before this navigation-only check.')
    apk = ROOT / 'artifacts/CustomWindDown-android-0.1.2-debug.apk'
    test_apk = ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    adb('install', '-r', str(apk))
    assert settings() == before, 'Updating the app must preserve its existing settings.'
    adb('install', '-r', '-t', str(test_apk))
    try:
        output = adb('shell', 'am', 'instrument', '-w', '-r',
                     PACKAGE + '.test/com.fanniuzhan.winddown.ClockLaunchInstrumentation')
        log = ROOT / 'artifacts/android-emulator/clock-navigation-results.txt'
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(output, encoding='utf-8')
        print(output, flush=True)
        if 'PASS: 12 clock navigation checks' not in output or 'INSTRUMENTATION_CODE: -1' not in output:
            raise AssertionError('Clock navigation regression failed.')
        assert settings() == before, 'Navigation test must preserve all existing application settings.'
        for _ in range(10):
            focused = adb('shell', 'dumpsys', 'activity', 'activities')
            if any('com.android.deskclock' in line and
                   ('mResumedActivity' in line or 'topResumedActivity' in line) for line in focused.splitlines()):
                break
            time.sleep(.5)
        else:
            raise AssertionError('Expected the emulator system Clock in the foreground.')
        print('PASS: existing application settings unchanged.', flush=True)
    finally:
        adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', PACKAGE + '/.MainActivity')
