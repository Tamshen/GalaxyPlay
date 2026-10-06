#!/usr/bin/env python3
"""仅在 AVD 检查首页车型入口、独立车型页、L7／L6／自定义车型音频页与取消契约，结束恢复原偏好／文件／昼夜；不连接手机。"""
import argparse
import hashlib
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
            if node.get('text') != label and node.get('content-desc') != label:
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


def launch(page="settings-vehicle"):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity',
        '--es', 'page', page)


def screenshot(name):
    nodes()
    time.sleep(.2)
    (output / name).write_bytes(adb('exec-out', 'screencap', '-p'))


paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_audio_templates.xml',
         'files/audio-template.json', 'files/audio-template.json.bak',
         'files/audio-template-l6.json', 'files/audio-template-l6.json.bak',
         'files/audio-template-custom.json', 'files/audio-template-custom.json.bak',
         'shared_prefs/l7_agreement.xml')
original = {path: read(path) for path in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
labels = {
    'zh': ('车型', '银河 L6', '银河 L7', '当前方案', 'L6 配置', 'L7 配置',
           '自定义模板', '保存，下次连接生效', '取消', '编辑配置文件', '自动识别车型', '车型设置', 'CarPlay 认证', '返回设置'),
    'en': ('Vehicle model', 'Galaxy L6', 'Galaxy L7', 'Current profile', 'L6 profile', 'L7 profile',
           'Custom template', 'Save for next connection', 'Cancel', 'Edit configuration file', 'Detect vehicle model', 'Vehicle settings', 'CarPlay authentication', 'Back to settings'),
}

try:
    # 仅用于 AVD 页面回归；原协议记录结束恢复，主动阅读／勾选另由协议组件回归覆盖。
    digest = hashlib.sha256(Path('galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
    write('shared_prefs/l7_agreement.xml', f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
    for language, names in labels.items():
        model, l6, l7, profile, l6_profile, l7_profile, custom, save, cancel, edit, detect, category, auth, back = names
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
        launch("settings")
        entries = {n.get('text'): n for n in nodes().iter('node') if n.get('text') in (category, auth)}
        assert len(entries) == 2, '首页首项或认证入口不可见'
        assert int(re.findall(r'\d+', entries[category].get('bounds'))[1]) < int(re.findall(r'\d+', entries[auth].get('bounds'))[1]), '车型设置不是首项'
        screenshot(language + '-settings-day.png')
        tap(category)
        assert model in visible() and detect in visible() and profile not in visible()
        screenshot(language + '-vehicle-day.png')
        tap(back)
        assert category in visible()
        tap(category)
        before = read('shared_prefs/l7_audio_templates.xml')
        tap(model); tap(l6); tap(cancel)
        assert before == read('shared_prefs/l7_audio_templates.xml')
        tap(model); tap(l6)
        screenshot(language + '-model-confirm.png')
        tap(save)
        assert l6 in visible() and profile not in visible()
        launch("settings-audio")
        assert l6_profile in visible() and edit not in visible()
        assert model not in visible() and detect not in visible()
        screenshot(language + '-l6-day.png')
        tap(profile)
        choices = visible()
        assert l6_profile in choices and custom in choices and l7_profile not in choices
        tap(cancel)
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        adb('shell', 'am', 'force-stop', package)
        launch()
        time.sleep(.5)
        screenshot(language + '-vehicle-night.png')
        tap(back)
        screenshot(language + '-settings-night.png')
        launch("settings-audio")
        screenshot(language + '-l6-night.png')
        tap(profile); tap(custom); tap(save)
        previous = read('files/audio-template-l6.json')
        tap(edit); tap(cancel)
        assert previous == read('files/audio-template-l6.json')
        launch()
        tap(model); tap(l7); tap(save)
        launch("settings-audio")
        assert l7_profile in visible()
        launch()
        tap(model); tap(l6); tap(save)
        launch("settings-audio")
        assert l6_profile in visible() and edit not in visible()
        assert previous == read('files/audio-template-l6.json')
        tap(profile); tap(custom); tap(save)
        assert previous == read('files/audio-template-l6.json')
        launch()
        custom_model = '自定义' if language == 'zh' else 'Custom'
        before = read('shared_prefs/l7_audio_templates.xml')
        old_custom = read('files/audio-template-custom.json')
        tap(model)
        options = visible()
        assert l7 in options and l6 in options and custom_model in options
        screenshot(language + '-three-models.png')
        tap(custom_model); tap(cancel)
        assert before == read('shared_prefs/l7_audio_templates.xml')
        assert old_custom == read('files/audio-template-custom.json')
        tap(model); tap(custom_model); tap(save)
        assert custom_model in visible()
        launch('settings-audio')
        assert custom in visible() and edit in visible()
        generic = read('files/audio-template-custom.json')
        assert generic and previous == read('files/audio-template-l6.json')
        screenshot(language + '-custom-night.png')
        tap(edit); tap(cancel)
        assert generic == read('files/audio-template-custom.json')
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        adb('shell', 'am', 'force-stop', package)
        launch('settings-audio')
        screenshot(language + '-custom-day.png')
        tap('恢复用途路由' if language == 'zh' else 'Restore usage routing'); tap(save)
        assert ('系统默认' if language == 'zh' else 'System default') in visible()
        assert generic == read('files/audio-template-custom.json')
    print('中英文设置首页首项、独立车型页与返回、三车型确认／取消、L7／L6 自动适配、昼夜、自定义独立文件及恢复保留通过；未建立手机会话')
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
    print('原偏好、三项车型文件、协议记录及昼夜设置已恢复')
