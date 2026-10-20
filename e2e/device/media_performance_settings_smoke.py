#!/usr/bin/env python3
"""仅 AVD：三项独立选择、取消、重启持久化与回退；结束恢复原偏好，不连接手机。"""
import argparse
import base64
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path, default=Path('build/e2e/media-performance'))
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '仅允许 AVD'
package = 'com.ecarx.carplay'
args.output_dir.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.PIPE, timeout=40)


def read_optional(path):
    try:
        return adb('shell', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        assert b'No such file' in error.stderr, error.stderr.decode()
        return None


def write(path, data):
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        encoded = base64.b64encode(data).decode()
        adb('shell', f"run-as {package} sh -c 'printf %s {encoded} | base64 -d > {path}'")


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-media-performance.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/galaxy-media-performance.xml'))


def click(label):
    for _ in range(9):
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            if label not in (node.get('text'), node.get('content-desc')):
                continue
            while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
                node = parents[node]
            if node.get('enabled') != 'true' or not (node.get('clickable') == 'true' or node.get('checkable') == 'true'):
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            if x2 > x1 and y2 > y1:
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
        scroll = next((n for n in root.iter('node') if n.get('scrollable') == 'true'
                       and n.get('class') == 'android.widget.ScrollView'), None)
        if scroll is None:
            break
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.get('bounds')))
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y2-40), str((x1+x2)//2), str(y1+40), '300')
    raise AssertionError('找不到设置：' + label)


def launch(page='settings-vehicle'):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity',
        '--es', 'page', page)


def saved():
    data = read_optional(performance_path) or b'<map/>'
    return {item.get('name'): item.text for item in ET.fromstring(data)}


performance_path = 'shared_prefs/galaxy_media_performance.xml'
language_path = 'shared_prefs/diplay.xml'
adb('shell', 'am', 'force-stop', package)
original = read_optional(performance_path)
language = read_optional(language_path)
assert language is not None
try:
    write(performance_path, None)
    # 只设置本次 AVD 的展示语言，完整原值在 finally 恢复。
    values = ET.fromstring(language)
    node = next((n for n in values if n.get('name') == 'app_language'), None)
    if node is None:
        node = ET.SubElement(values, 'string', name='app_language')
    node.text = 'zh'
    write(language_path, ET.tostring(values))
    for english in (False, True):
        if english:
            launch('settings-general')
            click('应用语言')
            click('English')
            click('应用')
        rows = ['Music playback strategy', 'Video buffer strategy', 'Hardware low latency strategy'] if english else [
            '音乐播放策略', '视频缓冲策略', '硬解低延迟策略']
        default = 'Software default' if english else '软件默认'
        oem = 'OEM approach' if english else '原厂方案'
        confirm = 'Save for next connection' if english else '保存，下次连接生效'
        cancel = 'Cancel' if english else '取消'
        keys = ['galaxy_performance_music', 'galaxy_performance_video_buffer', 'galaxy_performance_low_latency']
        for index, row in enumerate(rows):
            launch()
            click('Common' if english else '常用')
            click(row)
            assert any(n.get('text') == default and n.get('checked') == 'true' for n in tree().iter('node'))
            before = saved()
            click(oem)
            click(cancel)
            assert saved() == before
            click(row)
            click(oem)
            click(confirm)
            expected = {**before, keys[index]: 'OEM'}
            assert saved() == expected
            adb('shell', 'am', 'force-stop', package)
            launch()
            click(row)
            root = tree()
            assert any(n.get('text') == oem and n.get('checked') == 'true' for n in root.iter('node'))
            if index == 2:
                prefix = 'en' if english else 'zh'
                (args.output_dir / (prefix + '-selector.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
                (args.output_dir / (prefix + '-selector.xml')).write_bytes(ET.tostring(root))
            click(default)
            click(confirm)
            assert saved() == {**before, keys[index]: 'DEFAULT'}
        (args.output_dir / (('en' if english else 'zh') + '-settings.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    print('三项默认、取消、独立保存、重启保留、回退及中英文界面通过；未建立手机连接。')
finally:
    adb('shell', 'am', 'force-stop', package)
    write(performance_path, original)
    write(language_path, language)
    launch()
