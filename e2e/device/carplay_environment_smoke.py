#!/usr/bin/env python3
"""仅 AVD：验证环境测试导航、完整采集、USB 拉起隔离和中断留存；不上传。"""
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
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许模拟器'
base = [args.adb, '-s', args.serial]
pkg = 'com.ecarx.carplay'
out = Path('build/previews/164-carplay-environment')
out.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output(base + list(parts), stderr=subprocess.PIPE, timeout=30)

def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', pkg + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/carplay-environment-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/carplay-environment-test.xml'))

def tap(label):
    for _ in range(5):
        root = nodes()
        parents = {c: p for p in root.iter() for c in p}
        for n in root.iter('node'):
            if n.get('text') != label and n.get('content-desc') != label:
                continue
            while n.get('clickable') != 'true' and n in parents:
                n = parents[n]
            if n.get('clickable') != 'true' or n.get('enabled') != 'true':
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        adb('shell', 'input', 'swipe', '1050', '1600', '1050', '600', '200')
    raise AssertionError('找不到按钮：' + label)

def shot(name):
    time.sleep(.6)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

def reports():
    try:
        result = adb('shell', 'run-as', pkg, 'ls', 'files/probe-reports').decode()
    except subprocess.CalledProcessError as error:
        if b'No such file' in error.stderr:
            return []
        raise
    return [json.loads(adb('exec-out', 'run-as', pkg, 'cat', 'files/probe-reports/' + name))
            for name in result.splitlines() if re.fullmatch(r'[a-f0-9-]{36}\.json', name)]

def wait_for(test, timeout=35):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        result = test()
        if result:
            return result
        time.sleep(.25)
    raise AssertionError('等待检查结果超时')

adb('shell', 'am', 'force-stop', pkg)
launch('settings-debug')
tap('环境检测')
tap('CarPlay 环境测试')
shot('zh-intro')
before = {r['runId'] for r in reports()}
tap('开始完整环境测试')
def new_report():
    return next((r for r in reports() if r['runId'] not in before and r['caseId'] == 'CARPLAY_ENVIRONMENT'), None)
wait_for(lambda: (r := new_report()) and any(i['capabilityId'] == 'USB-OBSERVE-0' and i['reason'] != 'NOT_RUN' for i in r['items']))
shot('zh-running')
# 模拟系统 USB 拉起，仅检测宿主门禁，不构造手机或协议响应。
adb('shell', 'am', 'start', '-a', 'android.hardware.usb.action.USB_DEVICE_ATTACHED', '-n', pkg + '/com.shilapi.xcertplay.CarPlayHostActivity')
report = wait_for(lambda: (r := new_report()) and r['executionState'] == 'COMPLETED' and r)
assert len(report['items']) == report['expectedItems']
assert all(i['reason'] != 'NOT_RUN' for i in report['items'])
assert len([i for i in report['items'] if i['capabilityId'].startswith('USB-OBSERVE-')]) == 21
assert report['probeVersion'] == 6 and not report['truncated']
assert all(i['result'] != 'VERIFIED' for i in report['items'])
wait_for(lambda: 'event=end phase=COMPLETED' in adb('exec-out', 'run-as', pkg, 'cat', 'files/logs/probe-latest.log').decode())
log = adb('exec-out', 'run-as', pkg, 'cat', 'files/logs/probe-latest.log').decode()
assert all('entry=' + i['capabilityId'] + ' ' in log for i in report['items'])
assert 'usbRoleSwitch=NOT_ATTEMPTED' in log and 'usbEvents=' in log
(out / 'verification.json').write_text(json.dumps({'items': len(report['items']), 'phase': report['executionState'], 'samples': 21,
    'allItemsInLog': True, 'hostStartSuppressed': True, 'uploaded': False}, ensure_ascii=False, indent=2))
shot('zh-completed')
tap('查看检查结果')
shot('zh-results')
# 结果页重测保留检测类别；后台中断保留部分证据，并在返回时展示终态。
before = {r['runId'] for r in reports()}
tap('重新收集')
wait_for(lambda: (r := new_report()) and any(i['capabilityId'] == 'USB-OBSERVE-0' and i['reason'] != 'NOT_RUN' for i in r['items']))
adb('shell', 'input', 'keyevent', '3')
partial = wait_for(lambda: (r := new_report()) and r['executionState'] == 'CANCELLED' and r)
assert any(i['reason'] == 'NOT_RUN' for i in partial['items'])
assert any(i['reason'] != 'NOT_RUN' for i in partial['items'])
launch('settings-debug-carplay')
shot('zh-cancelled')
print('AVD：完整环境采证、21 个样本、全部日志留存、USB 自动拉起隔离、后台中断部分报告通过；未上传')
