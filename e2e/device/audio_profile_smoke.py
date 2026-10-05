#!/usr/bin/env python3
"""AVD 检查内置方案隐藏细节、自定义模板展开及试听取消；结束恢复原方案，不连接手机。"""
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
            while n.get('clickable') != 'true' and n.get('checkable') != 'true' and n in parents:
                n = parents[n]
            if (n.get('clickable') != 'true' and n.get('checkable') != 'true') or n.get('enabled') != 'true':
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
profiles = ('L7 配置', 'L7 BUS', '自定义模板')
initial = next(label for label in profiles if label in texts())


def top():
    for _ in range(3):
        adb('shell', 'input', 'swipe', '1050', '650', '1050', '1600', '180')


def select(label):
    top()
    if label in texts():
        return
    tap('当前方案')
    tap(label)
    tap('保存，下次连接生效')


try:
    for label, screenshot in zip(profiles[:2], ('l7-settings.png', 'l7-bus-settings.png')):
        select(label)
        visible = texts()
        for detail in ('媒体音乐', '导航播报', '语音助手（Siri）', '编辑配置文件', '尝试 L7 BUS 路由', '通话 · usage 2'):
            assert detail not in visible, label + ' 显示了调试细节：' + detail
        (output/screenshot).write_bytes(adb('exec-out', 'screencap', '-p'))
    select('自定义模板')
    assert '编辑配置文件' in texts()
    (output/'custom-settings.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    custom_before = adb('exec-out', 'run-as', package, 'cat', 'files/audio-template.json')
    tap('编辑配置文件')
    assert 'version' in ''.join(texts())
    (output/'custom-editor.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('取消')
    tap('导航播报')
    if '导航 · usage 12' not in texts():
        tap('输出路由')
        tap('导航 · usage 12')
        tap('选择')
    tap('试听此声道（2 秒）')
    for _ in range(8):
        if any('测试音已发送' in t for t in texts()):
            break
    else:
        raise AssertionError('导航试听未完成')
    (output/'navigation-preview.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('取消')
    assert adb('exec-out', 'run-as', package, 'cat', 'files/audio-template.json') == custom_before
    assert preferences() == original, '试听或方案切换修改了旧音频偏好'
    top()
    for label in ('导出配置文件', '导入配置文件'):
        tap(label)
        resumed = adb('shell', 'dumpsys', 'activity', 'activities').decode()
        assert any('documentsui' in line and 'mResumedActivity' in line for line in resumed.splitlines()), '文件选择器未打开：'+label
        adb('shell', 'input', 'keyevent', '4')
        assert adb('exec-out', 'run-as', package, 'cat', 'files/audio-template.json') == custom_before

    print('AVD 通过：L7／L7 BUS 隐藏细节、自定义编辑／文件选择器取消与导航试听，原文件不变；不代表实车发声。')
finally:
    adb('shell', 'input', 'keyevent', '4')
    adb('shell', 'am', 'start', '-W', '-n', package+'/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', 'settings-audio')
    select(initial)
