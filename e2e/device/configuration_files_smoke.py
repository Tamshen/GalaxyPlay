#!/usr/bin/env python3
"""仅在专用空白 AVD 验证配置文件新建、覆盖、复制和切换，不连接手机或上传日志。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--serial', default='emulator-5592')
p.add_argument('--language', choices=['zh', 'en'], default='zh')
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial)
a.output_dir.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'


def adb(*args, binary=False):
    result = subprocess.run([a.adb, '-s', a.serial, *args], capture_output=True, timeout=40, check=True)
    return result.stdout if binary else result.stdout.decode('utf-8')


assert adb('emu', 'avd', 'name').splitlines()[0].strip() == 'configuration_files', '只允许专用空白验证 AVD'
assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == '30'
adb('install', '-r', str(a.apk.resolve()))
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'am', 'force-stop', package)
with zipfile.ZipFile(a.apk) as z:
    digest = hashlib.sha256(z.read('assets/galaxyplay-first-use-agreement.md')).hexdigest()
# 仅测试 AVD 使用协议夹具，不代表用户同意，也不操作主 AVD 的数据。
for name, xml in {
    'l7_agreement': f'<map><string name="accepted_digest">{digest}</string></map>',
    'diplay': f'<map><string name="app_language">{a.language}</string><boolean name="auto_connect" value="false"/></map>',
}.items():
    encoded = base64.b64encode(xml.encode()).decode('ascii')
    adb('shell', 'run-as', package, 'mkdir', '-p', 'shared_prefs')
    adb('shell', f"run-as {package} sh -c 'printf %s {encoded} | base64 -d > shared_prefs/{name}.xml'")

english = a.language == 'en'

def label(zh, en):
    return en if english else zh


def tree():
    path = '/sdcard/configuration-files-ui.xml'
    adb('shell', 'rm', '-f', path)
    output = adb('shell', 'uiautomator', 'dump', path)
    assert 'dumped' in output, '未取得本次界面树'
    return ET.fromstring(adb('shell', 'cat', path))


def click(text, contains=False):
    for _ in range(7):
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in sorted(root.iter('node'), key=lambda n: n.get('class') != 'android.widget.Button'):
            value = node.get('text', '') or node.get('content-desc', '')
            if not (text in value if contains else text == value):
                continue
            while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
                node = parents[node]
            if node.get('enabled') != 'true' or not (node.get('clickable') == 'true' or node.get('checkable') == 'true'):
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        scroll = next((n for n in root.iter('node') if n.get('scrollable') == 'true' and
                       n.get('class') in {'android.widget.ListView', 'android.widget.ScrollView'}), None)
        if scroll is None:
            break
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.get('bounds')))
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y2-35), str((x1+x2)//2), str(y1+35), '300')
    raise AssertionError('没有找到可操作项：' + text)


def launch():
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-vehicle')


def read(path):
    return adb('exec-out', 'run-as', package, 'cat', path)


def active():
    key = read('files/configurations/active').strip()
    assert re.fullmatch(r'[a-z0-9_]{1,64}', key)
    return json.loads(read('files/configurations/' + key + '.json'))


def files():
    return {name for name in adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines() if name.endswith('.json')}


def screenshot(name):
    (a.output_dir / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def input_text(value):
    node = next(n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    adb('shell', 'input', 'keyevent', '123', *(['67'] * 45))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '4')


def picker():
    click(label('配置文件 · ', 'File · '), contains=True)


def save():
    click(label('保存', 'Save'))
    name = next(n.get('text') for n in tree().iter('node') if n.get('class') == 'android.widget.EditText')
    click(label('保存', 'Save'))
    return name


try:
    launch()
    baseline = active()
    before_files = files()
    picker()
    screenshot('01-configuration-files')
    click(label('内置 · L7(带头枕)', 'Built-in · L7 (with headrests)'))
    click(label('基于此配置新建', 'Start a new file'))
    assert active() == baseline and files() == before_files
    click(label('暂停手机蓝牙音乐', 'Pause phone Bluetooth music'))
    click(label('新建配置文件', 'Create configuration file'))
    screenshot('02-create-name')
    click(label('取消', 'Cancel'))
    assert active() == baseline and files() == before_files
    click(label('新建配置文件', 'Create configuration file'))
    own = 'Home-' + a.language + '-' + str(int(time.time()))
    input_text(own)
    click(label('新建配置文件', 'Create configuration file'))
    created = active()
    assert created['profile_id'] != baseline['profile_id'] and created['profile_name'] == own
    assert created['configuration']['preferences']['xcertplay_airplay']['bluetooth_media_exclusive']['value'] is False
    assert len(files()) == len(before_files) + 1
    for _ in range(2):
        assert save() == own
        assert active() == created and len(files()) == len(before_files) + 1
    click(label('暂停手机蓝牙音乐', 'Pause phone Bluetooth music'))
    assert save() == own
    updated = active()
    assert updated['profile_id'] == created['profile_id'] and updated['profile_name'] == own
    assert updated['revision'] == created['revision'] + 1
    screenshot('03-user-file-saved')
    picker()
    click(label('复制为新文件', 'Copy to a new file'))
    assert active() == updated
    click(label('新建配置文件', 'Create configuration file'))
    copy = 'Copy-' + a.language + '-' + str(int(time.time()))
    input_text(copy)
    click(label('新建配置文件', 'Create configuration file'))
    assert active()['profile_name'] == copy and len(files()) == len(before_files) + 2
    picker()
    click(label('用户 · ', 'User · ') + own)
    click(label('使用此配置', 'Use this configuration'))
    assert active() == updated
    picker()
    screenshot('04-user-files-list')
    click(label('内置 · L7(带头枕)', 'Built-in · L7 (with headrests)'))
    click(label('基于此配置新建', 'Start a new file'))
    assert active() == updated and len(files()) == len(before_files) + 2
    screenshot('05-built-in-kept')
    preserved = json.loads(read('files/configurations/' + baseline['profile_id'] + '.json'))
    assert preserved == baseline
    result = dict(language=a.language, builtInUnchanged=True, cancelKeepsFiles=True, createOnce=True,
                  repeatedSaveKeepsNameAndFile=True, noOpKeepsRevision=True, explicitCopy=True,
                  switchUserFile=True, originalFilePreserved=True, realVehicle=False, realPhone=False)
    (a.output_dir / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
except Exception:
    screenshot('failed')
    raise
