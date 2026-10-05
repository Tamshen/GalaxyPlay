#!/usr/bin/env python3
"""在已同意协议、未连接手机的 AVD 检查第三个重连入口及中英文昼夜，不建链或上传。"""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = Path('build/previews/reconnect-settings')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.DEVNULL)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-reconnect-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-reconnect-test.xml'))


def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)


def entry(label, root=None):
    root = root if root is not None else nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if label not in (node.get('text'), node.get('content-desc')):
            continue
        while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
            node = parents[node]
        return node
    raise AssertionError('入口不可达：' + label)


def tap(label):
    node = entry(label)
    assert (node.get('clickable') == 'true' or node.get('checkable') == 'true') and node.get('enabled') == 'true', label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def language(english):
    launch('settings-general')
    tap('应用语言' if english else 'App language')
    tap('English' if english else '简体中文')
    tap('应用' if english else 'Apply')


original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
english = False
try:
    for english_mode in (False, True):
        if english_mode:
            language(True)
            english = True
        for night in ('no', 'yes'):
            adb('shell', 'cmd', 'uimode', 'night', night)
            launch('settings-connection')
            root = nodes()
            labels = ('Wireless connection', 'Wired connection', 'Reconnect current connection') if english_mode else (
                '无线连接', '有线连接', '重新连接当前连接')
            rows = [entry(label, root) for label in labels]
            positions = [int(re.findall(r'\d+', row.get('bounds'))[1]) for row in rows]
            assert positions == sorted(positions) and len(set(positions)) == 3, '重连须为第三项'
            assert rows[2].get('enabled') == 'false', '无会话时不得发起重连'
            hint = 'No session is available to reconnect. Choose a connection method first.' if english_mode else (
                '当前没有可重连的会话，请先选择连接方式。')
            assert any(n.get('text') == hint for n in root.iter('node'))
            if english_mode:
                assert not any(re.search(r'[\u3400-\u9fff]', n.get('text', '')) for n in root.iter('node'))
            (output / (('en' if english_mode else 'zh') + '-' + night + '.png')).write_bytes(
                adb('exec-out', 'screencap', '-p'))
    adb('shell', 'input', 'keyevent', '4')
    assert any(n.get('text') == 'CarPlay authentication' for n in nodes().iter('node'))
    print('重连入口顺序、无会话禁用及说明、中英文昼夜与返回检查通过；未建立手机连接。')
finally:
    if english:
        language(False)
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-connection')
