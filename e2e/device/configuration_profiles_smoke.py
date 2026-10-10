#!/usr/bin/env python3
"""仅在 AVD 验证当前配置快速模板、草稿及固定操作区；不连接手机、不上传日志。"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path)
parser.add_argument('--language', choices=['zh', 'en'])
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许模拟器')
output = args.output_dir or Path(__file__).resolve().parents[2] / 'build/previews/configuration-profiles'
output.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'


def adb(*command, binary=False):
    result = subprocess.run([args.adb, '-s', args.serial, *command], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode('utf-8')


def launch(page='settings-vehicle'):
    adb('shell', 'am', 'start', '-n', f'{package}/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)
    time.sleep(3)


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/configuration-ux.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/configuration-ux.xml'))


def texts():
    return [node.get('text', '') for node in tree().iter('node')]


def click(text, contains=False):
    alternatives = {text}
    for pair in globals().get('labels', {}).values():
        if text in pair:
            alternatives.update(pair)
    for _ in range(8):
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            value = node.get('text', '') or node.get('content-desc', '')
            if (value not in alternatives if not contains else text not in value):
                continue
            while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
                node = parents[node]
            if (node.get('clickable') == 'true' or node.get('checkable') == 'true') and node.get('enabled') == 'true':
                tap(node)
                return
        adb('shell', 'input', 'swipe', '1000', '1600', '1000', '750', '300')
    raise AssertionError(f'未找到可操作项：{text}')


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.25)


def screenshot(name):
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    # 不保存输入节点文本，截图只覆盖本轮模板与普通参数。


def private(path):
    return adb('exec-out', 'run-as', package, 'cat', path)


def active():
    return private('files/configurations/active').strip()




labels = {
    'l7': ('银河 L7', 'Galaxy L7'), 'l6': ('银河 L6', 'Galaxy L6'),
    'cancel': ('取消', 'Cancel'), 'keep': ('继续编辑', 'Keep editing'),
    'discard': ('放弃修改', 'Discard changes'), 'undo': ('撤销修改', 'Undo edits'),
    'save': ('保存', 'Save'), 'video': ('画面', 'Display'), 'connection': ('连接', 'Connection'),
    'bluetooth': ('蓝牙音乐互斥', 'Bluetooth media coordination'),
    'right': ('右舵布局', 'Right-hand drive layout'), 'back': ('返回设置', 'Back to settings'),
    'language': ('应用语言', 'App language'), 'apply': ('应用', 'Apply'),
    'system': ('跟随系统', 'System default'), 'title': ('车型配置', 'Vehicle configuration'),
    'app': ('应用设置', 'App settings'), 'size': ('界面大小', 'Interface size'), 'large': ('大 · 320 DPI', 'Large · 320 DPI'),
}
english = False


def detect_language():
    return 'Vehicle configuration' in texts()


def label(key):
    return labels[key][int(english)]


def language(value):
    global english
    launch('settings-general')
    click(label('language'))
    option = {'zh': '简体中文', 'en': 'English'}[value]
    selected = any(node.get('text') == option and node.get('checked') == 'true' for node in tree().iter('node'))
    click(label('cancel') if selected else option)
    if not selected:
        click(label('apply'))
    launch()
    english = detect_language()


def category(key):
    node = next(node for node in tree().iter('node') if node.get('text') in labels[key] and node.get('class') == 'android.widget.Button')
    tap(node)


def bounds(key):
    node = next(node for node in tree().iter('node') if node.get('text') in labels[key] and node.get('class') == 'android.widget.Button')
    return node.get('bounds')


def read_optional(path):
    try:
        return private(path).encode('utf-8')
    except subprocess.CalledProcessError:
        return None


def app_values(values):
    result = {}
    for space, raw in values.items():
        root = ET.fromstring(raw) if raw else []
        for item in root:
            key = item.get('name')
            allowed = space in {'l7_ui', 'l7_floating_navigation', 'l7_remote_log'} or (
                space == 'diplay' and key == 'app_language') or (
                space == 'xcertplay_airplay' and key in {'debug_logs_enabled', 'carplay_night_mode', 'ambient_delay_seconds', 'ambient_lux_threshold'})
            if allowed:
                result[(space, key)] = ET.tostring(item)
    return result


def current_app_values():
    return app_values({name: read_optional('shared_prefs/' + name + '.xml') for name in application})


def restore_file(path, value):
    if value is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        # 私有配置仅经 stdin 回写到模拟器，不打印、落入截图目录或拼接进命令。
        subprocess.run([args.adb, '-s', args.serial, 'shell', '-T', 'run-as ' + package + " sh -c 'cat > " + path + "'"],
                       input=value, capture_output=True, check=True, timeout=15)


if 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package):
    raise AssertionError('模拟器有活动连接服务')
adb('shell', 'am', 'force-stop', package)
launch()
english = detect_language()
original_id = active()
assert re.fullmatch(r'[a-z0-9_]{1,64}', original_id)
path = f'files/configurations/{original_id}.json'
original = private(path).encode('utf-8')
previous = read_optional(path + '.previous')
application = {name: read_optional('shared_prefs/' + name + '.xml') for name in ['diplay', 'l7_ui', 'l7_floating_navigation', 'l7_remote_log', 'xcertplay_airplay']}
audio = {name: read_optional('files/' + name) for name in ['audio-template.json', 'audio-template-l6.json', 'audio-template-custom.json']}
original_files = set(adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines())
try:
    if args.language:
        language(args.language)
    launch('settings')
    assert label('title') in texts() and label('app') in texts()
    assert '车型设置' not in texts() and 'Vehicle settings' not in texts()
    screenshot('01-settings')
    click(label('app'))
    assert label('language') in texts()
    click(label('size')); click(label('large')); click(label('save'))
    time.sleep(2)
    assert b'value="320"' in private('shared_prefs/l7_ui.xml').encode('utf-8')
    screenshot('02-app-settings')
    launch()
    screenshot('02-configuration')
    independent = current_app_values()
    click(label('l6'))
    assert english == detect_language(), '车型模板改变了语言'
    assert current_app_values() == independent, '车型模板改变了应用偏好'
    assert active() == original_id, '模板另建或切换了文件'
    applied = json.loads(private(path))
    assert applied['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l6'
    screenshot('03-l6-applied')
    # 独立应用偏好不应出现在当前车型文件，也不会被模板或普通参数保存覆盖。
    assert not applied['configuration']['preferences']['diplay'].get('app_language')
    assert not applied['configuration']['preferences']['l7_ui'].get('density')
    before = private(path)
    click(label('bluetooth'))
    assert private(path) == before, '普通参数提前写入文件'
    category('video')
    screenshot('04-display-draft')
    click(label('undo')); click(label('keep'))
    click(label('undo')); click(label('discard'))
    assert private(path) == before
    category('connection')
    fixed = bounds('l7'), bounds('save')
    adb('shell', 'input', 'swipe', '1000', '1450', '1000', '750', '300')
    assert fixed == (bounds('l7'), bounds('save')), '模板或底部操作随参数滚动'
    screenshot('05-connection-scrolled')
    category('video'); click(label('right')); click(label('save'))
    edited = json.loads(private(path))
    assert edited['configuration']['preferences']['xcertplay_airplay']['right_hand_drive']['value'] is True
    assert edited['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l6'
    assert active() == original_id
    assert current_app_values() == independent
    screenshot('06-edited')
    click(label('right'))
    saved = private(path)
    click(label('back')); click(label('keep'))
    assert '有未保存的修改' in texts() or 'Unsaved changes' in texts()
    click(label('back')); click(label('discard'))
    assert private(path) == saved
    launch()
    click(label('l7'))
    assert json.loads(private(path))['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l7'
    assert current_app_values() == independent
    # 旧版其他文件首次读取会升级并保留上一份，不能把迁移备份误判成新配置。
    allowed_backups = {name + '.previous' for name in original_files if name.endswith('.json')}
    assert set(adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines()) - original_files <= allowed_backups, '创建了额外配置文件'
finally:
    # 本轮会覆盖当前设置；在内存保留原件，恢复当前文件、应用偏好及三份兼容音频文件后重启。
    adb('shell', 'am', 'force-stop', package)
    restore_file(path, original)
    for name, value in application.items():
        restore_file('shared_prefs/' + name + '.xml', value)
    for name, value in audio.items():
        restore_file('files/' + name, value)
    launch()
    assert active() == original_id
    assert json.loads(private(path))['configuration'] == json.loads(original)['configuration'], '原配置未恢复'
    restore_file(path + '.previous', previous)
    assert current_app_values() == app_values(application), '应用偏好未恢复'
    for name, value in audio.items():
        assert read_optional('files/' + name) == value, '兼容音频文件未恢复'
print('配置 UX 通过：单一配置入口、L7/L6 一键覆盖同一文件、后续编辑保存、分类直接切换、撤销、固定操作、离开草稿确认、独立应用偏好保留与测试前配置恢复。', flush=True)
