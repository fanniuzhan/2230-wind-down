"""Explicit, dedicated-AVD screen recovery regression; never controls a phone."""
import argparse
import runpy
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enable-test-accessibility', action='store_true', required=True)
    parser.add_argument('--formal-plan', action='store_true',
                        help='Temporarily enable a formal plan on the dedicated AVD; restore settings afterwards.')
    args = parser.parse_args()
    t = runpy.run_path('scripts/test-android-emulator.py')
    a = t['adb']
    if 'CustomWindDown_API36' not in a('emu', 'avd', 'name').splitlines():
        raise RuntimeError('Refusing to operate on any other device.')

    def values():
        xml = a('exec-out', 'run-as', t['PACKAGE'], 'cat', 'shared_prefs/night_guard.xml')
        return {node.get('name'): node.get('value', node.text) for node in t['ET'].fromstring(xml)}

    before = values()
    if before.get('enabled') == 'true' or before.get('compatibility') == 'true' or int(before.get('trial_until', '0')):
        raise RuntimeError('Requires an idle test emulator with formal plan and compatibility confirmation disabled.')
    a('install', '-r', str(t['ROOT'] / 'artifacts/CustomWindDown-android-0.1.2-debug.apk'))
    a('install', '-r', '-t', str(t['ROOT'] / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    t['reconnect_test_service']()
    mode = 'formal_recovery' if args.formal_plan else 'screen_recovery'
    print('Running ' + mode + '; formal mode includes a 70-second sleep and actual schedule expiry.', flush=True)
    output = a('shell', 'am', 'instrument', '-w', '-r', '-e', mode, 'true',
               t['PACKAGE'] + '.test/com.fanniuzhan.winddown.ClockLaunchInstrumentation',
               timeout=240 if args.formal_plan else 90)
    (t['OUT'] / ('formal-screen-recovery-results.txt' if args.formal_plan else 'screen-recovery-results.txt')).write_text(output, encoding='utf-8')
    print(output, flush=True)
    expected = 'PASS: 17 formal-plan' if args.formal_plan else 'PASS: 9 trial'
    if expected not in output or 'INSTRUMENTATION_CODE: -1' not in output:
        raise AssertionError('Window lifecycle regression failed.')
    assert all(values().get(key) == before.get(key) for key in before if key != 'last_error'), 'Test state not restored.'
    if args.formal_plan:
        assert values().get('trial_until') == '0' and values().get('enabled') != 'true'
        a('pull', '/sdcard/winddown-formal-restored.png', str(t['OUT'] / '22-formal-unlock-restored.png'))
        a('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', t['PACKAGE'] + '/.MainActivity')
        print('PASS formal plan: repeated unlock, 70-second screen-off, ordinary apps, real expiry, restored settings.', flush=True)
        return

    t['reconnect_test_service']()
    a('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    a('shell', 'wm', 'dismiss-keyguard')
    a('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', t['PACKAGE'] + '/.MainActivity')
    root = t['ui']()
    if any(node.get('text') == 'Wait' for node in root.iter('node')):
        t['tap']('Wait')  # AOSP System UI may pause after a cold boot on this resource-limited PC.
    t['open_checks']()
    t['tap']('准备 60 秒试锁', scroll=True)
    t['tap']('开始试锁')
    deadline = int(values()['trial_until'])
    a('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    t['expect_cover'](True, 'trial covers ordinary apps before screen tests')
    for cycle in range(2):
        a('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        t['expect_cover'](False, f'system lock is left alone during screen off ({cycle + 1})')
        a('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        a('shell', 'wm', 'dismiss-keyguard')
        a('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        t['expect_cover'](True, f'cover returns after power-button wake and unlock ({cycle + 1})')
    t['capture']('20-screen-unlock-restored')

    a('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    def wait_audio_mode(mode):
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if '- Actual mode = ' + mode in a('shell', 'dumpsys', 'audio'):
                return
            time.sleep(.2)
        raise AssertionError('Simulated call did not enter ' + mode)

    def remains_uncovered(message):
        t['expect_cover'](False, message)
        for _ in range(4):
            time.sleep(.4)
            assert not t['covered'](), 'Cover returned during ' + message

    try:
        a('emu', 'gsm', 'call', '15555215554')
        wait_audio_mode('MODE_RINGTONE')
        a('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        remains_uncovered('ringing simulated call stays uncovered after screen off')
        t['capture']('21-screen-off-incoming-call')
        a('shell', 'input', 'keyevent', 'KEYCODE_CALL')
        wait_audio_mode('MODE_IN_CALL')
        remains_uncovered('answered simulated call remains usable')
    finally:
        a('emu', 'gsm', 'cancel', '15555215554', check=False)
    a('shell', 'wm', 'dismiss-keyguard')
    a('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    t['expect_cover'](True, 'ordinary apps are covered again after the call')
    a('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    print('Waiting for the trial to expire while the screen is off...', flush=True)
    while int(a('shell', 'date', '+%s').strip()) * 1000 <= deadline + 1000:
        time.sleep(1)
    a('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    a('shell', 'wm', 'dismiss-keyguard')
    a('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    t['expect_cover'](False, 'expired trial is not restarted by wake or unlock')
    after = values()
    assert after.get('enabled') != 'true' and after.get('trial_until') == '0'
    assert all(before.get(key) == after.get(key) for key in ('start', 'end', 'days', 'enabled', 'compatibility'))
    a('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', t['PACKAGE'] + '/.MainActivity')
    print('PASS screen cycles, screen-off call, trial expiry, and unchanged formal schedule.', flush=True)


if __name__ == '__main__':
    main()
