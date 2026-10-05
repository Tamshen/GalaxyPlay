#!/usr/bin/env python3
"""仅在 AVD 临时禁用 VPN 授权页面，核对有线启动提示与锚点；结束恢复原设置。"""
import argparse
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
parser.add_argument('--output-dir', default='build/previews/wired-vpn')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
vpn_package = 'com.android.vpndialogs'
output = Path(args.output_dir)
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base + list(parts), stderr=subprocess.DEVNULL)


def read(path):
    result = subprocess.run(base + ['exec-out', 'run-as', package, 'cat', path], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def write(path, data):
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
        return
    command = 'run-as ' + package + ' sh -c ' + shlex.quote('cat > ' + path)
    subprocess.run(base + ['shell', command], input=data, capture_output=True, check=True)


def boolean(data, name, value):
    root = ET.fromstring(data) if data else ET.Element('map')
    for node in list(root):
        if node.get('name') == name:
            root.remove(node)
    ET.SubElement(root, 'boolean', name=name, value=str(value).lower())
    return ET.tostring(root)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-wired-vpn.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-wired-vpn.xml'))


def tap(label):
    root = nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    node = next(n for n in root.iter('node') if n.get('text') == label)
    while node.get('clickable') != 'true' and node in parents:
        node = parents[node]
    assert node.get('clickable') == 'true', label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


paths = ['shared_prefs/diplay.xml', 'shared_prefs/xcertplay_airplay.xml',
         'shared_prefs/l7_wired_diagnostics.xml']
adb('shell', 'am', 'force-stop', package)
backups = {path: read(path) for path in paths}
disabled_before = vpn_package in adb('shell', 'pm', 'list', 'packages', '-d').decode()
permissions = ['android.permission.RECORD_AUDIO', 'android.permission.ACCESS_COARSE_LOCATION',
               'android.permission.ACCESS_FINE_LOCATION']
package_state = adb('shell', 'dumpsys', 'package', package).decode()
granted_before = {permission: bool(re.search(re.escape(permission) + r': granted=true', package_state))
                  for permission in permissions}
op = adb('shell', 'cmd', 'appops', 'get', package, 'ACTIVATE_VPN').decode()
match = re.search(r'ACTIVATE_VPN: (allow|ignore|deny|default)', op)
vpn_op_before = match.group(1) if match else 'default'
try:
    write(paths[0], boolean(backups[paths[0]], 'auto_connect', False))
    write(paths[1], boolean(backups[paths[1]], 'wireless_enabled', False))
    write(paths[2], None)
    for permission in permissions:
        adb('shell', 'pm', 'grant', package, permission)
    adb('shell', 'cmd', 'appops', 'set', package, 'ACTIVATE_VPN', 'default')
    adb('shell', 'pm', 'disable-user', '--user', '0', vpn_package)
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.CarPlayHostActivity')
    time.sleep(1)
    labels = {n.get('text', '') for n in nodes().iter('node')}
    assert '有线连接未能开始' in labels, '请先在中文 AVD 同意协议并使用内置认证测试包'
    assert any('无法打开' in text for text in labels), '未显示系统页面不可用原因'
    assert {'重新检查并授权', '返回有线设置', '查看日志'} <= labels
    pid = adb('shell', 'pidof', package).strip()
    assert pid, '授权页面不可用导致应用退出'
    snapshot = read(paths[2])
    record = ET.fromstring(snapshot).find("string[@name='attempts']").text
    attempts = json.loads(record)
    last = attempts[-1]
    assert last['outcome'] == 'FAILED'
    assert any(e['phase'] == 'VPN_LAUNCH' and e['result'] == 'FAILED'
               and 'ActivityNotFoundException' in e.get('exception', '') for e in last['events'])
    output.joinpath('missing-screen.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('重新检查并授权')
    time.sleep(.5)
    assert adb('shell', 'pidof', package).strip() == pid
    record = ET.fromstring(read(paths[2])).find("string[@name='attempts']").text
    assert len(json.loads(record)) == 2, '未保留连续两次失败'
    tap('返回有线设置')
    time.sleep(.5)
    assert '有线连接' in {n.get('text', '') for n in nodes().iter('node')}
    print('AVD：页面不可用提示、进程存活、手动重试、两次记录与返回设置通过；未连接真实手机。')
finally:
    adb('shell', 'am', 'force-stop', package)
    if not disabled_before:
        adb('shell', 'pm', 'enable', '--user', '0', vpn_package)
    adb('shell', 'cmd', 'appops', 'set', package, 'ACTIVATE_VPN', vpn_op_before)
    for path, data in backups.items():
        write(path, data)
    for permission in reversed(permissions):
        if not granted_before[permission]:
            adb('shell', 'pm', 'revoke', package, permission)
    adb('shell', 'rm', '-f', '/sdcard/l7-wired-vpn.xml')
