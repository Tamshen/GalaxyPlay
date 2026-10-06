#!/usr/bin/env python3
"""在未连接手机的 AVD 检查五项菜单直达连接设置、第三个重连入口及中英文昼夜。"""
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
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)


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


def connection_rows(labels, scene):
    # 输入交付不等于新页面已经绘制；按目标行就绪判断，保留最终失败现场。
    for _ in range(6):
        root = nodes()
        try:
            return root, [entry(label, root) for label in labels]
        except AssertionError:
            pass
    (output / (scene + '-failed.xml')).write_bytes(ET.tostring(root, encoding='utf-8'))
    (output / (scene + '-failed.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    raise AssertionError('连接设置未就绪：' + scene)


original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
english = False
try:
    for english_mode in (False, True):
        if english_mode:
            language(True)
            english = True
        for night in ('no', 'yes'):
            adb('shell', 'cmd', 'uimode', 'night', night)
            launch('home')
            tap('Expand menu' if english_mode else '展开菜单')
            root = nodes()
            menu = [n for n in root.iter('node') if n.get('clickable') == 'true' and n.get('content-desc') in (
                ('Display', 'Connect', 'Settings', 'Vehicle', 'Exit') if english_mode else ('画面', '连接', '设置', '车机', '退出'))]
            expected = ['Display', 'Connect', 'Settings', 'Vehicle', 'Exit'] if english_mode else ['画面', '连接', '设置', '车机', '退出']
            assert [n.get('content-desc') for n in menu] == expected, '连接须位于画面下方'
            for n in menu:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                assert x2 > x1 and y2 > y1, '五个按钮都必须可见'
            (output / (('en' if english_mode else 'zh') + '-' + night + '-menu.png')).write_bytes(
                adb('exec-out', 'screencap', '-p'))
            tap('Connect' if english_mode else '连接')
            labels = ('Wireless connection', 'Wired connection', 'Reconnect current connection') if english_mode else (
                '无线连接', '有线连接', '重新连接当前连接')
            root, rows = connection_rows(labels, ('en' if english_mode else 'zh') + '-' + night)
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
    print('五项菜单、连接位于画面下方、直达连接设置、重连禁用及说明、中英文昼夜与返回通过；未建立手机连接。')
finally:
    if english:
        language(False)
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-connection')
