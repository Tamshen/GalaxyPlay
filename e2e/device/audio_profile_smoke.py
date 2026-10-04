#!/usr/bin/env python3
"""AVD 检查博越默认音频配置、导航 14 号流与试听；不保存新选择、不连接手机。"""
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
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/audio-profile')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-audio-profile.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-audio-profile.xml'))


def texts():
    return [n.get('text', '') for n in nodes().iter('node')]


def tap(label):
    for _ in range(7):
        root = nodes()
        parents = {c: p for p in root.iter() for c in p}
        for n in root.iter('node'):
            if n.get('text') != label:
                continue
            while n.get('clickable') != 'true' and n in parents:
                n = parents[n]
            if n.get('clickable') != 'true' or n.get('enabled') != 'true':
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        adb('shell', 'input', 'swipe', '1050', '1600', '1050', '650', '200')
    raise AssertionError('入口不可达：'+label)


def preferences():
    return {n.get('name'): n.get('value') or n.text for n in ET.fromstring(adb(
        'exec-out', 'run-as', package, 'cat', 'shared_prefs/xcertplay_airplay.xml'))}


adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
    package+'/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', 'settings-audio')
original = preferences()
assert original['navigation_audio_channel'] == '14'
assert original['audio_focus_enabled'] == 'true'
assert original['advanced_audio_channel_mapping'] == 'true'
try:
    tap('导航播报')
    assert '14 · 导航（博越默认）' in texts()
    (output/'navigation-profile.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('试听此声道（2 秒）')
    for _ in range(8):
        if any('测试音已发送' in t for t in texts()):
            break
    else:
        raise AssertionError('导航试听未完成')
    (output/'navigation-preview.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('取消')
    assert preferences() == original, '试听或取消修改了音频配置'
    print('AVD 通过：博越默认焦点/高级映射、导航流 14、试听完成且取消不保存；不代表实车发声。')
finally:
    adb('shell', 'input', 'keyevent', '4')
