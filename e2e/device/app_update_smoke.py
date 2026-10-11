#!/usr/bin/env python3
"""仅在 AVD 检查正式 HTTPS 更新、取消、下载校验及系统覆盖安装；不建立手机连接或上传。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--install-update', action='store_true')
parser.add_argument('--from-code', type=int)
parser.add_argument('--to-code', type=int)
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许模拟器'
if args.install_update:
    assert args.from_code and args.to_code and args.to_code > args.from_code, '安装验收需要明确起止版本'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
component = package + '/com.shilapi.xcertplay.GalaxySettingsActivity'
output = Path('build/previews/app-update')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base + list(parts), stderr=subprocess.DEVNULL, timeout=30)


def nodes():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-update.xml')
            return ET.fromstring(adb('shell', 'cat', '/sdcard/galaxy-update.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            if attempt == 2:
                raise
            time.sleep(.3)


def texts():
    return '\n'.join(n.get('text', '') for n in nodes().iter('node'))


def tap(*labels):
    root = nodes()
    parents = {c: p for p in root.iter() for c in p}
    for node in root.iter('node'):
        if node.get('text') not in labels and node.get('content-desc') not in labels:
            continue
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        if node.get('enabled') != 'true' or node.get('clickable') != 'true':
            continue
        x, y, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((x + right) // 2), str((y + bottom) // 2))
        return
    raise AssertionError('可操作入口不可达：' + repr(labels))


def wait_for(*labels, timeout=60):
    end = time.monotonic() + timeout
    last = ''
    while time.monotonic() < end:
        last = texts()
        if any(label in last for label in labels):
            return last
        time.sleep(.3)
    (output / 'failed.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    raise AssertionError('等待超时：' + repr(labels) + '\n' + last[:1500])


def screenshot(name):
    (output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def version():
    dump = adb('shell', 'dumpsys', 'package', package).decode()
    return int(re.search(r'versionCode=(\d+)', dump).group(1))


assert 'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities').decode(), '先结束已有投屏'
initial = version()
if args.install_update:
    assert initial == args.from_code, '起始包版本不符'
original_appop = adb('shell', 'cmd', 'appops', 'get', package, 'REQUEST_INSTALL_PACKAGES').decode()
match = re.search(r'REQUEST_INSTALL_PACKAGES:\s*(\w+)', original_appop)
original_mode = match.group(1) if match else 'default'
try:
    adb('shell', 'am', 'start', '-n', component, '--es', 'page', 'settings-about')
    wait_for('检查更新', 'Check for updates')
    tap('检查更新', 'Check for updates')
    found = wait_for('发现新版本', 'is available', '当前已是最新版本', 'You are up to date')
    screenshot('check-result')
    if args.install_update:
        assert '发现新版本' in found or 'is available' in found
        tap('下载更新', 'Download update')
        wait_for('已下载并校验', 'downloaded and verified', timeout=90)
        screenshot('verified')
        tap('安装更新', 'Install update')
        state = wait_for('允许安装更新', 'Allow update installation', '您要安装', 'Do you want', '更新此应用', 'update this')
        if '允许安装更新' in state or 'Allow update installation' in state:
            tap('取消', 'Cancel')
            assert version() == initial
            tap('安装更新', 'Install update')
            wait_for('打开安装权限', 'Open install permission')
            tap('打开安装权限', 'Open install permission')
            wait_for('Allow from this source', '允许来自此来源', '允许安装应用')
            root = nodes()
            switch = next(n for n in root.iter('node') if n.get('class') == 'android.widget.Switch')
            if switch.get('checked') != 'true':
                x, y, right, bottom = map(int, re.findall(r'\d+', switch.get('bounds')))
                adb('shell', 'input', 'tap', str((x + right) // 2), str((y + bottom) // 2))
            adb('shell', 'input', 'keyevent', '4')
            wait_for('安装权限已开启', 'Installation allowed')
            assert version() == initial, '授权返回不应自动安装'
            tap('安装更新', 'Install update')
            wait_for('您要安装', 'Do you want', '更新此应用', 'update this')
        screenshot('system-installer')
        tap('安装', 'Install', '更新', 'Update')
        end = time.monotonic() + 45
        while version() != args.to_code and time.monotonic() < end:
            time.sleep(.5)
        assert version() == args.to_code, '系统安装未完成'
        adb('shell', 'am', 'start', '-n', component, '--es', 'page', 'settings-about')
        wait_for('检查更新', 'Check for updates')
        tap('检查更新', 'Check for updates')
        wait_for('当前已是最新版本', 'You are up to date')
        screenshot('current-after-install')
    else:
        tap('关闭', 'Close')
    (output / 'result.json').write_text(json.dumps({'initialCode': initial, 'finalCode': version(),
        'installedFromUpdater': args.install_update}, ensure_ascii=False, indent=2) + '\n')
    print('AVD 正式 HTTPS 检查、下载校验、授权取消／返回、系统覆盖安装及最新版本复查通过' if args.install_update else 'AVD 正式 HTTPS 检查与关闭通过')
finally:
    adb('shell', 'cmd', 'appops', 'set', package, 'REQUEST_INSTALL_PACKAGES', original_mode)
