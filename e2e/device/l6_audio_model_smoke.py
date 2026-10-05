#!/usr/bin/env python3
"""仅在 AVD 检查两车型音频页与取消契约，结束恢复原偏好／文件／昼夜；不连接手机。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/l6-audio-model')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts, data=None):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE)


def read(path):
    try:
        return adb('exec-out', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        if b'No such file' in error.stderr:
            return None
        raise


def write(path, data):
    assert re.fullmatch(r'(?:files|shared_prefs)/[a-z0-9_.-]+', path)
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {path}'", data=data)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l6-audio-model.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l6-audio-model.xml'))


def visible():
    return [n.get('text', '') for n in nodes().iter('node')]


def tap(label):
    for _ in range(5):
        root = nodes()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            if node.get('text') != label:
                continue
            while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
                node = parents[node]
            if (node.get('clickable') != 'true' and node.get('checkable') != 'true') or node.get('enabled') != 'true':
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1 + x2)//2), str((y1 + y2)//2))
            time.sleep(.2)
            return
        adb('shell', 'input', 'swipe', '1050', '1650', '1050', '650', '180')
    raise AssertionError('入口不可达：' + label)


def launch():
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity',
        '--es', 'page', 'settings-audio')


def screenshot(name):
    (output / name).write_bytes(adb('exec-out', 'screencap', '-p'))


paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_audio_templates.xml',
         'files/audio-template.json', 'files/audio-template.json.bak',
         'files/audio-template-l6.json', 'files/audio-template-l6.json.bak')
original = {path: read(path) for path in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
labels = {
    'zh': ('车型', '银河 L6', '银河 L7', '当前方案', 'L6 配置', 'L7 配置',
           '自定义模板', '保存，下次连接生效', '取消', '编辑配置文件', '自动识别车型'),
    'en': ('Vehicle model', 'Galaxy L6', 'Galaxy L7', 'Current profile', 'L6 profile', 'L7 profile',
           'Custom template', 'Save for next connection', 'Cancel', 'Edit configuration file', 'Detect vehicle model'),
}

try:
    for language, names in labels.items():
        model, l6, l7, profile, l6_profile, l7_profile, custom, save, cancel, edit, detect = names
        adb('shell', 'am', 'force-stop', package)
        root = ET.fromstring(original['shared_prefs/diplay.xml'] or b'<map/>')
        for child in list(root):
            if child.get('name') in ('app_language', 'auto_connect'):
                root.remove(child)
        ET.SubElement(root, 'string', {'name': 'app_language'}).text = language
        ET.SubElement(root, 'boolean', {'name': 'auto_connect', 'value': 'false'})
        write('shared_prefs/diplay.xml', ET.tostring(root, encoding='utf-8', xml_declaration=True))
        write('shared_prefs/l7_audio_templates.xml', b'<map><string name="model">l7</string><string name="mode">l7</string></map>')
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        launch()
        assert l7_profile in visible() and detect in visible()
        before = read('shared_prefs/l7_audio_templates.xml')
        tap(model); tap(l6); tap(cancel)
        assert before == read('shared_prefs/l7_audio_templates.xml')
        tap(model); tap(l6)
        screenshot(language + '-model-confirm.png')
        tap(save)
        assert l6_profile in visible() and edit not in visible()
        screenshot(language + '-l6-day.png')
        tap(profile)
        choices = visible()
        assert l6_profile in choices and custom in choices and l7_profile not in choices
        tap(cancel)
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        adb('shell', 'am', 'force-stop', package)
        launch()
        time.sleep(.5)
        screenshot(language + '-l6-night.png')
        tap(profile); tap(custom); tap(save)
        previous = read('files/audio-template-l6.json')
        tap(edit); tap(cancel)
        assert previous == read('files/audio-template-l6.json')
        tap(model); tap(l7); tap(save)
        assert l7_profile in visible()
        tap(model); tap(l6); tap(save)
        assert custom in visible()
        assert previous == read('files/audio-template-l6.json')
    print('中英文车型确认／取消、L6 昼夜、自定义取消及切换保留通过；未建立手机会话')
except Exception:
    screenshot('failure.png')
    (output / 'failure.xml').write_bytes(adb('shell', 'cat', '/sdcard/l6-audio-model.xml'))
    raise
finally:
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        write(path, data)
    adb('shell', 'cmd', 'uimode', 'night', night)
    assert all(read(path) == data for path, data in original.items())
    print('原偏好、两车型文件及昼夜设置已恢复')
