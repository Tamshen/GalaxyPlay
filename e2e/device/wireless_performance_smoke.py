#!/usr/bin/env python3
"""独占 Android 11 AVD：真实 ADB/shell 读取与扫描租约、AudioTrack 并行和重复启动；结束恢复 ADB。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--apk', required=True)
parser.add_argument('--test-apk', default='common/build/outputs/apk/androidTest/debug/common-debug-androidTest.apk')
parser.add_argument('--output-dir', default='build/e2e/upstream-performance/device')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '仅允许 AVD'
base = [args.adb, '-s', args.serial]
output = Path(args.output_dir)
output.mkdir(parents=True, exist_ok=True)
test_package = 'com.shilapi.xcertplay.host.test'
runner = test_package + '/com.shilapi.xcertplay.vendor.SdkSubclassInstrumentation'


def adb(*parts, data=None, timeout=45):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE, timeout=timeout)


def wait():
    adb('wait-for-device')
    for _ in range(40):
        try:
            if adb('shell', 'id', '-u', timeout=5).strip() in (b'0', b'2000'):
                return
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(0.25)
    raise AssertionError('AVD 的 ADB 未恢复')


def identity(root):
    adb('root' if root else 'unroot')
    wait()
    wanted = b'0' if root else b'2000'
    for _ in range(40):
        if adb('shell', 'id', '-u').strip() == wanted:
            return
        time.sleep(0.25)
    raise AssertionError('AVD 的 ADB 身份未切换')


def instrument(suite):
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'suite', suite, runner, timeout=180).decode()
    (output / (suite + '.log')).write_text(result)
    assert 'INSTRUMENTATION_CODE: -1' in result, result
    return result


def scan_enabled():
    values = re.findall(r'wifiConnectivityManagerEnabled=(true|false)', adb('shell', 'dumpsys', 'wifi').decode())
    assert values, 'AVD 未提供扫描管理器状态'
    return values[-1] == 'true'


def settings_ui():
    package = 'com.ecarx.carplay'
    prefs = ('shared_prefs/xcertplay_airplay.xml', 'shared_prefs/diplay.xml')
    keys = ('wifi_high_performance_enabled', 'wifi_scan_pause_enabled')

    def read(path):
        try:
            return adb('exec-out', 'run-as', package, 'cat', path)
        except subprocess.CalledProcessError as error:
            assert b'No such file' in error.stderr
            return None

    def write(path, data):
        if data is None:
            adb('shell', 'run-as', package, 'rm', '-f', path)
        else:
            adb('shell', 'run-as ' + package + ' sh -c ' + shlex.quote('cat > ' + path), data=data)

    def set_value(tree, tag, name, value):
        for old in list(tree):
            if old.get('name') == name:
                tree.remove(old)
        node = ET.SubElement(tree, tag, name=name)
        if tag == 'boolean':
            node.set('value', value)
        else:
            node.text = value

    def launch():
        adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
            package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-connection-wireless')

    def find(label):
        for _ in range(8):
            adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-wireless-performance.xml')
            tree = ET.fromstring(adb('shell', 'cat', '/sdcard/galaxy-wireless-performance.xml'))
            for node in tree.iter('node'):
                if node.get('content-desc') == label and node.get('checkable') == 'true':
                    return node
            adb('shell', 'input', 'swipe', '1090', '1580', '1090', '670', '250')
        raise AssertionError('无线优化开关不可达：' + label)

    def assert_values(expected):
        for _ in range(20):
            tree = ET.fromstring(read(prefs[0]))
            values = {n.get('name'): n.get('value') for n in tree}
            if all(values.get(key) == expected for key in keys):
                return
            time.sleep(0.2)
        raise AssertionError('无线优化开关未持久化')

    adb('shell', 'am', 'force-stop', package)
    original = {path: read(path) for path in prefs}
    try:
        for language, labels in (
            ('zh', ('无线传输性能优化', 'Direct 连接时暂停后台扫描')),
            ('en', ('Optimize wireless performance', 'Pause background scans during Direct')),
        ):
            adb('shell', 'am', 'force-stop', package)
            main = ET.fromstring(original[prefs[0]] or b'<map/>')
            set_value(main, 'string', 'wireless_hotspot_mode', 'WIFI_P2P')
            for key in keys:
                set_value(main, 'boolean', key, 'true')
            general = ET.fromstring(original[prefs[1]] or b'<map/>')
            set_value(general, 'string', 'app_language', language)
            write(prefs[0], ET.tostring(main))
            write(prefs[1], ET.tostring(general))
            for expected in ('true', 'false'):
                launch()
                for index, label in enumerate(labels):
                    node = find(label)
                    assert node.get('checked') == expected, label
                    assert node.get('enabled') == 'true', label
                    (output / f'wireless-settings-{language}-{expected}-{index}.png').write_bytes(
                        adb('exec-out', 'screencap', '-p'))
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                assert_values('false' if expected == 'true' else 'true')
                adb('shell', 'am', 'force-stop', package)
    finally:
        adb('shell', 'am', 'force-stop', package)
        for path, data in original.items():
            write(path, data)


assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1'
assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == b'30'
assert b'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities'), '不能占用已有连接'
assert scan_enabled(), '开始前扫描必须开启，不能覆盖其他测试的暂停状态'
apk = Path(args.apk)
test_apk = Path(args.test_apk)
assert apk.is_file() and test_apk.is_file()
adb('install', '-r', str(apk), timeout=120)
adb('install', '-r', '-t', str(test_apk), timeout=120)
settings_ui()
assert 'WIRELESS_ADB_PREPARED' in instrument('wireless-prepare')
public = adb('exec-out', 'run-as', test_package, 'cat', 'cache/wireless-adb-public-key').rstrip(b'\x00')
assert re.fullmatch(rb'[A-Za-z0-9+/=]+ diplay@headunit', public)
old_uid = adb('shell', 'id', '-u').strip()
old_port = adb('shell', 'getprop', 'service.adb.tcp.port').decode().strip()
auth_enforced = adb('shell', 'getprop', 'ro.adb.secure').strip() == b'1'
# Goldfish 将 adbd 的 TCP 通道接到模拟器管道，不在 guest 内监听；用反向转发接回真实 adbd。
assert not any(line.split()[1:2] == ['tcp:5555'] for line in adb('reverse', '--list').decode().splitlines()), '5555 已有转发，不能覆盖'
bridge = False
old_keys = None
keys_existed = False
keys_captured = False
started = time.monotonic()
try:
    identity(True)
    keys_existed = adb('shell', 'sh', '-c', '"if [ -f /data/misc/adb/adb_keys ]; then echo yes; fi"').strip() == b'yes'
    if keys_existed:
        old_keys = adb('exec-out', 'cat', '/data/misc/adb/adb_keys')
    keys_captured = True
    adb('shell', 'sh', '-c', '"cat > /data/misc/adb/adb_keys"',
        data=(old_keys or b'').rstrip(b'\n') + b'\n' + public + b'\n')
    adb('shell', 'chown', 'system:shell', '/data/misc/adb/adb_keys')
    adb('shell', 'chmod', '640', '/data/misc/adb/adb_keys')
    adb('shell', 'restorecon', '/data/misc/adb/adb_keys')
    identity(False)
    adb('tcpip', '5555')
    wait()
    adb('reverse', 'tcp:5555', 'tcp:' + str(int(args.serial.split('-')[1]) + 1))
    bridge = True
    assert adb('shell', 'id', '-u').strip() == b'2000', 'helper 必须按 shell 身份验收'
    assert 'WIRELESS_PERFORMANCE_ANDROID11_OK' in instrument('wireless-performance')
    assert scan_enabled(), '三轮结束后扫描未恢复'
    summary = {'android_api': 30, 'uid': 2000, 'audio_pipeline_cycles': 3,
               'real_audio_track': True, 'music_navigation_isolation': True, 'audio_thread_priority': True,
               'wifi_lock_release': True, 'local_adb_hotspot_read': True,
               'emulator_adb_bridge': True, 'adb_authentication_enforced': auth_enforced,
               'dual_stack_listener_replacement': True,
               'scan_pause_restore': True, 'elapsed_seconds': round(time.monotonic() - started, 2),
               'settings_ui_zh_en_persistence': True,
               'real_p2p_radio': False,
               'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'real_iphone': False}
    (output / 'result.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False), flush=True)
finally:
    # 仅恢复本 AVD 原有的授权列表与网络 ADB 状态；不触碰用户车机或其他模拟器。
    if bridge:
        adb('reverse', '--remove', 'tcp:5555')
    identity(True)
    if keys_captured and keys_existed:
        adb('shell', 'sh', '-c', '"cat > /data/misc/adb/adb_keys"', data=old_keys)
        adb('shell', 'chown', 'system:shell', '/data/misc/adb/adb_keys')
        adb('shell', 'chmod', '640', '/data/misc/adb/adb_keys')
        adb('shell', 'restorecon', '/data/misc/adb/adb_keys')
    elif keys_captured:
        adb('shell', 'rm', '-f', '/data/misc/adb/adb_keys')
    if old_uid == b'2000':
        identity(False)
    if old_port.isdigit() and int(old_port) > 0:
        adb('tcpip', old_port)
    else:
        adb('usb')
    wait()
