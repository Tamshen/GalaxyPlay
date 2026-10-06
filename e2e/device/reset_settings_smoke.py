#!/usr/bin/env python3
"""AVD 验证关于页全量重置；私有数据仅在内存备份，结束后恢复数据与授权，不上传。"""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--reset-and-restore', action='store_true', required=True)
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
component = package+'/com.shilapi.xcertplay.GalaxySettingsActivity'
output = Path('build/previews/reset-settings')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)


def shell_ok(command):
    return subprocess.run(base+['shell', command], capture_output=True).returncode == 0


def private_write(path, data):
    command = 'run-as '+package+' sh -c '+shlex.quote('cat > '+shlex.quote(path))
    subprocess.run(base+['shell', command], input=data, check=True, capture_output=True)


def launch():
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', component, '--es', 'page', 'settings-about')


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-reset-settings.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-reset-settings.xml'))


def tap(label):
    for _ in range(8):
        root = nodes()
        parents = {c: p for p in root.iter() for c in p}
        for node in root.iter('node'):
            if node.get('text') != label:
                continue
            while node.get('clickable') != 'true' and node in parents:
                node = parents[node]
            if node.get('clickable') != 'true' or node.get('enabled') != 'true':
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        adb('shell', 'input', 'swipe', '1100', '1650', '1100', '650', '200')
    raise AssertionError('入口不可达：'+label)


launch()
assert '关于' in {n.get('text') for n in nodes().iter('node')}, '需要已同意协议的中文 AVD'
adb('shell', 'am', 'force-stop', package)
directories = [name for name in ('shared_prefs', 'files', 'no_backup', 'databases')
               if shell_ok(f'run-as {package} test -d {name}')]
assert 'shared_prefs' in directories
backup = adb('exec-out', 'run-as', package, 'tar', '-cf', '-', *directories)
external = '/sdcard/Android/data/'+package
external_backup = adb('exec-out', 'tar', '-cf', '-', '-C', external, '.') if shell_ok('test -d '+external) else None
state = adb('shell', 'dumpsys', 'package', package).decode()
runtime = state.split('runtime permissions:')[-1]
grants = re.findall(r'(android\.permission\.\w+): granted=true', runtime)
operations = dict(re.findall(r'^\s*(\w+): (allow|ignore|deny|default|foreground|errored)\b',
                            adb('shell', 'cmd', 'appops', 'get', package).decode(), re.M))
fixtures = ['shared_prefs/l7_reset_fixture.xml', 'files/logs/l7-reset-fixture.txt',
            'files/probe-reports/l7-reset-fixture.txt', 'no_backup/l7-reset-fixture.txt']
try:
    adb('shell', 'run-as', package, 'mkdir', '-p', 'shared_prefs', 'files/logs', 'files/probe-reports', 'no_backup')
    for path in fixtures:
        private_write(path, b'<map><string name="reset_test">fixture</string></map>' if path.endswith('.xml') else b'reset-test')
    launch()
    tap('清空所有配置')
    (output/'confirmation.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('取消')
    assert all(shell_ok(f'run-as {package} test -f {p}') for p in fixtures)
    tap('清空所有配置')
    tap('全部清空并退出')
    for _ in range(60):
        result = subprocess.run(base+['shell', 'pidof', package], capture_output=True)
        if not result.stdout.strip():
            break
        time.sleep(.2)
    else:
        raise AssertionError('系统清理未结束应用进程')
    assert all(not shell_ok(f'run-as {package} test -e {p}') for p in fixtures)
    assert not shell_ok(f'run-as {package} test -e shared_prefs/l7_agreement.xml')
    assert not shell_ok(f'run-as {package} test -e no_backup/offline-mfi')
    launch()
    assert 'com.shilapi.xcertplay.L7AgreementActivity' in adb('shell', 'dumpsys', 'activity', 'activities').decode()
    (output/'first-use.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    print('AVD 通过：取消保留数据；确认后进程退出、偏好与私有文件清除，重新打开进入首次使用协议。')
finally:
    adb('shell', 'am', 'force-stop', package)
    # 仅回滚本次测试应用的私有目录；备份不落盘，也不打印内容。
    adb('shell', 'run-as', package, 'rm', '-rf', 'shared_prefs', 'files', 'no_backup', 'databases')
    subprocess.run(base+['exec-in', 'run-as', package, 'tar', '-xf', '-'], input=backup, check=True, capture_output=True)
    if external_backup is not None:
        adb('shell', 'mkdir', '-p', external)
        subprocess.run(base+['exec-in', 'tar', '-xf', '-', '-C', external], input=external_backup, check=True, capture_output=True)
    for permission in grants:
        adb('shell', 'pm', 'grant', package, permission)
    for operation, mode in operations.items():
        adb('shell', 'cmd', 'appops', 'set', package, operation, mode)
    launch()
