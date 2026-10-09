#!/usr/bin/env python3
"""仅在 AVD 验证配置模态框、取消、新建及切换；不连接手机、不上传日志。"""
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
            value = node.get('text', '')
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


def name(value):
    node = next(node for node in tree().iter('node') if node.get('class') == 'android.widget.EditText')
    tap(node)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'keyevent', '--longpress', 'KEYCODE_DEL')
    # 一次发送删除键序列，避免依赖输入法的全选菜单。
    adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * 40))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(.25)


def screenshot(name):
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    # 不保存输入节点文本，截图仅包含人工构造的测试名称或隐藏值。


def private(path):
    return adb('exec-out', 'run-as', package, 'cat', path)


def active():
    return private('files/configurations/active').strip()


if 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package):
    raise AssertionError('模拟器有活动连接服务，请结束后再检查配置 UI')
adb('shell', 'am', 'force-stop', package)
launch()
english = 'Vehicle settings' in texts()
labels = {
    'edit': ('编辑配置', 'Edit configuration'), 'cancel': ('取消', 'Cancel'),
    'keep': ('继续编辑', 'Keep editing'), 'discard': ('放弃修改', 'Discard changes'),
    'new': ('新建配置', 'New configuration'), 'l6': ('L6 默认', 'L6 default'),
    'next': ('下一步', 'Next'), 'save': ('保存', 'Save'), 'files': ('本地配置文件', 'Local configuration files'),
    'use': ('使用此配置', 'Use this configuration'), 'category': ('参数分类', 'Settings category'),
    'projection': ('投屏参数', 'Projection'), 'view': ('查看分类', 'View category'),
    'language': ('应用语言', 'App language'), 'apply': ('应用', 'Apply'),
    'system': ('跟随系统', 'System default'),
}


def label(key):
    return labels[key][int(english)]


def language(value):
    global english
    launch('settings-general')
    click(label('language'))
    option = {'zh': '简体中文', 'en': 'English', 'system': label('system')}[value]
    selected = any(node.get('text') == option and node.get('checked') == 'true' for node in tree().iter('node'))
    if selected:
        click(label('cancel'))
    else:
        click(option)
        click(label('apply'))
    time.sleep(1)
    launch()
    english = 'Vehicle settings' in texts()


original_id = active()
original = json.loads(private(f'files/configurations/{original_id}.json'))
original_language = original['configuration']['preferences']['diplay']['app_language']['value']
original_files = set(adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines())
test_name = 'CONFIG_UX_L6_' + str(int(time.time()))[-6:]
try:
    if args.language:
        language(args.language)
    screenshot('01-files')
    before = private(f'files/configurations/{original_id}.json')
    click(label('edit')); name('CONFIG_UX_DRAFT')
    screenshot('02-editor')
    click(label('cancel')); click(label('keep'))
    assert 'CONFIG_UX_DRAFT' in texts()
    click(label('cancel')); click(label('discard'))
    assert private(f'files/configurations/{original_id}.json') == before, '取消修改了文件'
    click(label('new')); click(label('l6')); click(label('next'))
    name(test_name); click(label('save'))
    assert active() == original_id, '新建自动切换了配置'
    files = set(adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines())
    new_file = next(value for value in files - original_files if value.endswith('.json'))
    created = json.loads(private(f'files/configurations/{new_file}'))
    assert created['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l6'
    click(label('files')); click(test_name, contains=True)
    screenshot('03-switch-confirm')
    click(label('use'))
    time.sleep(1)
    english = 'Vehicle settings' in texts()
    assert active() == created['profile_id']
    screenshot('04-l6-selected')
    click(label('edit')); click(label('category'))
    screenshot('05-categories')
    click(label('cancel')); click(label('cancel'))
    print('配置 UX 通过：取消保留文件、新建不自动使用、L6 切换确认、编辑分类。语言：' + ('en' if english else 'zh'))
finally:
    # 通过同一真实界面恢复原配置，保留测试创建文件供复核，不删除既有资料。
    adb('shell', 'am', 'force-stop', package)
    launch()
    english = 'Vehicle settings' in texts()
    click(label('files')); click(original['profile_name'], contains=True); click(label('use'))
    assert active() == original_id, '未恢复原配置'
    if args.language:
        time.sleep(1)
        english = 'Vehicle settings' in texts()
        language(original_language)
