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
    # 模拟器偶发拒绝滑动注入，仅对可重复的滑动重试；点击不能重试以免重复提交。
    attempts = 3 if command[:3] == ('shell', 'input', 'swipe') else 1
    for attempt in range(attempts):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, timeout=30)
        if result.returncode == 0:
            break
        if attempt == attempts - 1:
            result.check_returncode()
        time.sleep(.5)
    return result.stdout if binary else result.stdout.decode('utf-8')


def launch(page='settings-vehicle'):
    adb('shell', 'am', 'start', '-n', f'{package}/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)
    time.sleep(3)


def tree():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/configuration-ux.xml')
            return ET.fromstring(adb('shell', 'cat', '/sdcard/configuration-ux.xml'))
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
            time.sleep(1)


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
    time.sleep(.4)


def screenshot(name):
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    # 不保存输入节点文本，截图只覆盖本轮模板与普通参数。


def private(path):
    return adb('exec-out', 'run-as', package, 'cat', path)


def active():
    return private('files/configurations/active').strip()




labels = {
    'l7': ('银河 L7', 'Galaxy L7'), 'l6': ('银河 L6', 'Galaxy L6'),
    'custom_vehicle': ('自定义', 'Custom'), 'reset': ('重置当前配置', 'Reset current configuration'),
    'reset_confirm': ('确认重置', 'Reset configuration'),
    'smoothness': ('画面流畅度', 'Picture smoothness'), 'fps60': ('更流畅 · 60 fps', 'Smoother · 60 fps'),
    'vehicle': ('车型 · ', 'Vehicle · '), 'template_apply': ('应用模板', 'Apply template'),
    'more': ('上报', 'Reporting'), 'common': ('常用', 'Common'),
    'hotspot': ('热点名称与密码', 'Hotspot name and password'), 'audio': ('音频', 'Audio'),
    'controls': ('方控', 'Controls'), 'steering': ('方向盘按键控制', 'Steering wheel controls'),
    'media_report': ('原车音乐与媒体上报', 'Report music and media to the car'),
    'navigation_report': ('原车导航上报', 'Report navigation to the car'),
    'authentication': ('CarPlay 认证', 'CarPlay authentication'),
    'quality': ('画面清晰度', 'Picture quality'), 'custom': ('自定义百分比…', 'Custom percentage…'),
    'confirm': ('确定', 'Confirm'),
    'cancel': ('取消', 'Cancel'), 'keep': ('继续编辑', 'Keep editing'),
    'discard': ('放弃修改', 'Discard changes'), 'undo': ('撤销修改', 'Undo edits'),
    'save': ('保存', 'Save'), 'video': ('画面', 'Display'), 'connection': ('连接', 'Connection'),
    'bluetooth': ('暂停手机蓝牙音乐', 'Pause phone Bluetooth music'),
    'right': ('右舵布局', 'Right-hand drive layout'), 'back': ('返回设置', 'Back to settings'),
    'language': ('应用语言', 'App language'), 'apply': ('应用', 'Apply'),
    'system': ('跟随系统', 'System default'), 'title': ('车型配置', 'Vehicle configuration'),
    'app': ('应用设置', 'App settings'), 'size': ('界面大小', 'Interface size'), 'large': ('大 · 320 DPI', 'Large · 320 DPI'),
    'icon': ('CarPlay 图标大小', 'CarPlay icon size'), 'icon_large': ('大 · 125%', 'Large · 125%'),
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
    for _ in range(5):
        root = tree()
        node = next((node for node in root.iter('node') if node.get('text') in labels[key]
                     and node.get('class') == 'android.widget.Button' and node.get('clickable') == 'true'), None)
        if node is not None:
            tap(node)
            assert not any(value in texts() for value in ['高级设置', 'Advanced settings', '收起高级设置', 'Hide advanced settings']), '配置仍有折叠入口'
            assert any(node.get('text') in labels[key] and node.get('selected') == 'true'
                       for node in tree().iter('node')), 'TAB 点选未切换：' + key
            return
        bar = next(node for node in root.iter('node') if node.get('class') == 'android.widget.HorizontalScrollView')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', bar.get('bounds')))
        start, end = (x1 + 30, x2 - 30) if key == 'common' else (x2 - 30, x1 + 30)
        adb('shell', 'input', 'swipe', str(start), str((y1 + y2) // 2), str(end), str((y1 + y2) // 2), '300')
    raise AssertionError('横向 TAB 不可达：' + key)


def swipe_tabs():
    root = tree()
    bar = next(node for node in root.iter('node') if node.get('class') == 'android.widget.HorizontalScrollView')
    old = [(node.get('text'), node.get('bounds')) for node in bar.iter('node') if node.get('class') == 'android.widget.Button']
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', bar.get('bounds')))
    adb('shell', 'input', 'swipe', str(x2 - 30), str((y1 + y2) // 2), str(x1 + 30), str((y1 + y2) // 2), '300')
    time.sleep(.3)
    bar = next(node for node in tree().iter('node') if node.get('class') == 'android.widget.HorizontalScrollView')
    new = [(node.get('text'), node.get('bounds')) for node in bar.iter('node') if node.get('class') == 'android.widget.Button']
    assert old != new, 'TAB 横向手势没有滚动'


def template(key, apply=True):
    click(label('vehicle'), contains=True)
    click(label(key))
    assert any(node.get('text') in labels[key] and node.get('checked') == 'true'
               for node in tree().iter('node')), '车型候选没有选中：' + key
    click(label('template_apply') if apply else label('cancel'))


def bounds(key):
    node = next(node for node in tree().iter('node') if (node.get('text') in labels[key] or (key == 'vehicle' and node.get('text', '').startswith(label('vehicle')))) and node.get('class') == 'android.widget.Button')
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
            allowed = space in {'l7_ui', 'l7_floating_navigation', 'l7_remote_log', 'l7_authentication', 'carplay_picture'} or (
                space == 'diplay' and key in {'app_language', 'auto_connect'}) or (
                space == 'xcertplay_airplay' and key in {'ui_scale_percent', 'debug_logs_enabled', 'carplay_night_mode', 'ambient_delay_seconds', 'ambient_lux_threshold', 'manual_hotspot_ssid', 'manual_hotspot_passphrase', 'manual_hotspot_security', 'manual_hotspot_band', 'manual_hotspot_channel', 'wireless_enabled', 'wireless_hotspot_mode', 'auto_start_on_boot', 'mfi_target', 'mfi_i2c_path', 'remote_mfi_server', 'remote_mfi_token', 'existing_wifi_ssid', 'existing_wifi_passphrase', 'wifi_p2p_preferred_channel', 'manufacturer', 'model', 'oem_label'})
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
application = {name: read_optional('shared_prefs/' + name + '.xml') for name in ['diplay', 'l7_ui', 'l7_floating_navigation', 'l7_remote_log', 'l7_authentication', 'xcertplay_airplay', 'carplay_picture']}
audio = {name: read_optional('files/' + name) for name in ['audio-template.json', 'audio-template-l6.json', 'audio-template-custom.json']}
original_files = set(adb('shell', 'run-as', package, 'ls', 'files/configurations').splitlines())
try:
    # 用合成热点凭据验证模板与重置保留，不显示原用户名称或操作系统热点。
    saved_main = application['xcertplay_airplay']
    preferences = ET.fromstring(saved_main) if saved_main else ET.Element('map')
    for key, value in {'manual_hotspot_ssid': 'UX-test-hotspot', 'manual_hotspot_passphrase': 'UX-test-password',
                       'manual_hotspot_security': 'WPA2', 'manual_hotspot_band': 'AUTO'}.items():
        item = next((n for n in preferences if n.get('name') == key), None)
        if item is None:
            item = ET.SubElement(preferences, 'string', {'name': key})
        item.text = value
    adb('shell', 'am', 'force-stop', package)
    restore_file('shared_prefs/xcertplay_airplay.xml', ET.tostring(preferences, encoding='utf-8'))
    launch()
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
    click(label('icon')); click(label('icon_large')); click(label('save'))
    assert b'name="ui_scale_percent" value="125"' in private('shared_prefs/xcertplay_airplay.xml').encode('utf-8')
    screenshot('02-app-settings')
    launch()
    screenshot('02-configuration')
    independent = current_app_values()
    click(label('vehicle'), contains=True)
    screenshot('03-vehicle-picker')
    click(label('l6'))
    assert any(node.get('text') in labels['l6'] and node.get('checked') == 'true'
               for node in tree().iter('node')), '车型候选没有选中：l6'
    screenshot('03-l6-selected')
    click(label('template_apply'))
    assert english == detect_language(), '车型模板改变了语言'
    assert current_app_values() == independent, '车型模板改变了应用偏好'
    assert active() == original_id, '模板另建或切换了文件'
    applied = json.loads(private(path))
    assert applied['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l6'
    screenshot('03-l6-applied')
    template('custom_vehicle')
    assert json.loads(private(path))['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'custom'
    assert active() == original_id and current_app_values() == independent
    screenshot('03-custom-applied')
    category('common')
    click(label('smoothness')); click(label('fps60')); click(label('confirm')); click(label('save'))
    custom_saved = private(path)
    click(label('bluetooth'))
    click(label('reset'))
    screenshot('03-reset-confirmation')
    click(label('cancel'))
    assert private(path) == custom_saved, '取消重置改变了文件'
    assert '有未保存的修改' in texts() or 'Unsaved changes' in texts(), '取消重置丢失了草稿'
    click(label('reset')); click(label('reset_confirm'))
    reset = json.loads(private(path))['configuration']['preferences']
    assert reset['l7_audio_templates']['model']['value'] == 'custom', '重置切换了车型'
    assert reset['xcertplay_airplay']['display_fps']['value'] == 30
    assert reset['xcertplay_airplay']['bluetooth_media_exclusive']['value'] is True
    assert active() == original_id and current_app_values() == independent
    assert '有未保存的修改' not in texts() and 'Unsaved changes' not in texts()
    screenshot('03-custom-reset')
    template('l6')
    # 独立应用偏好不应出现在当前车型文件，也不会被模板或普通参数保存覆盖。
    assert not applied['configuration']['preferences']['diplay'].get('app_language')
    assert not applied['configuration']['preferences']['l7_ui'].get('density')
    vehicle_main = applied['configuration']['preferences']['xcertplay_airplay']
    assert not any(key in vehicle_main for key in ['manual_hotspot_ssid', 'manual_hotspot_passphrase',
        'wireless_enabled', 'auto_start_on_boot', 'mfi_target', 'remote_mfi_token', 'ui_scale_percent']), '车型文件含独立连接或认证设置'
    assert not applied['configuration']['preferences']['diplay'].get('auto_connect')
    before = private(path)
    category('common')
    click(label('quality')); click(label('custom')); click(label('confirm'))
    editor = tree()
    inputs = [n for n in editor.iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(inputs) == 1, '精确百分比不是单项输入'
    screenshot('04-custom-percent')
    click(label('cancel'))
    assert private(path) == before, '取消精确输入修改文件'
    assert '有未保存的修改' not in texts() and 'Unsaved changes' not in texts(), '取消精确输入修改草稿'
    root = tree()
    switch = next(n for n in root.iter('node') if n.get('class') == 'android.widget.Switch' and n.get('content-desc') in labels['bluetooth'])
    title = next(n for n in root.iter('node') if n.get('class') == 'android.widget.TextView' and n.get('text') in labels['bluetooth'])
    control_bounds = list(map(int, re.findall(r'\d+', switch.get('bounds'))))
    title_bounds = list(map(int, re.findall(r'\d+', title.get('bounds'))))
    assert control_bounds[2] <= title_bounds[0], 'Flyme 开关没有位于文字左侧'
    assert control_bounds[2] - control_bounds[0] <= 144, '320 DPI 开关视觉比例过大'
    click(label('bluetooth'))
    assert private(path) == before, '普通参数提前写入文件'
    template('l7', apply=False)
    assert private(path) == before, '取消车型选择写入了文件'
    assert '有未保存的修改' in texts() or 'Unsaved changes' in texts(), '取消车型选择丢失草稿'
    swipe_tabs()
    assert private(path) == before, 'TAB 滑动写入了草稿'
    category('more')
    screenshot('04-tabs-more')
    category('common')
    category('video')
    screenshot('04-display-draft')
    click(label('undo')); click(label('keep'))
    click(label('undo')); click(label('discard'))
    assert private(path) == before
    category('controls')
    assert label('steering') in texts()
    assert label('hotspot') not in texts() and label('authentication') not in texts()
    screenshot('05-controls-direct')
    category('more')
    assert label('media_report') in texts() and label('navigation_report') in texts()
    screenshot('05-reporting-direct')
    launch('settings-connection')
    assert label('authentication') in texts(), '独立连接页缺少认证入口'
    screenshot('05-external-connection')
    launch('settings-connection-wireless')
    click(label('hotspot'))
    assert len([n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']) == 2, '热点不是两项输入'
    screenshot('05-hotspot-modal')
    click(label('cancel'))
    assert not any(value in texts() for value in ['热点频段', '热点通道', '热点安全方式', 'Hotspot band', 'Hotspot channel', 'Hotspot security'])
    assert current_app_values() == independent, '取消热点输入修改独立设置'
    launch()
    category('audio')
    screenshot('05-audio-direct')
    category('video')
    for _ in range(8):
        if any(value in texts() for value in ['HEVC 软件解码', 'HEVC software decoding']):
            break
        adb('shell', 'input', 'swipe', '1000', '1450', '1000', '750', '300')
    else:
        raise AssertionError('专业参数未直接显示')
    screenshot('05-display-details')
    fixed = bounds('vehicle'), bounds('save')
    adb('shell', 'input', 'swipe', '1000', '1450', '1000', '750', '300')
    assert fixed == (bounds('vehicle'), bounds('save')), '模板或底部操作随参数滚动'
    screenshot('05-display-scrolled')
    # 固定车型与重置按钮会改变正文起点，手势必须从实际滚动区内部开始。
    for _ in range(8):
        root = tree()
        if any(node.get('text') in labels['right'] for node in root.iter('node')):
            break
        areas = [list(map(int, re.findall(r'\d+', node.get('bounds'))))
                 for node in root.iter('node') if node.get('class') == 'android.widget.ScrollView']
        x1, y1, x2, y2 = max(areas, key=lambda bounds: bounds[2] - bounds[0])
        adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str(y1 + (y2 - y1) // 5),
            str((x1 + x2) // 2), str(y2 - (y2 - y1) // 5), '300')
    assert not any(value in texts() for value in ['高级设置', 'Advanced settings', '收起高级设置', 'Hide advanced settings'])
    screenshot('05-display-direct')
    click(label('right')); click(label('save'))
    edited = json.loads(private(path))
    assert edited['template_id'] == 'custom', '保存改动未切换为自定义配置'
    assert any(value.startswith(label('vehicle')) and label('custom_vehicle') in value for value in texts()), '保存后车型仍显示模板'
    assert edited['configuration']['preferences']['xcertplay_airplay']['right_hand_drive']['value'] is True
    assert edited['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l6'
    assert active() == original_id
    assert current_app_values() == independent
    click(label('vehicle'), contains=True)
    assert any(node.get('text') in labels['custom_vehicle'] and node.get('checked') == 'true' for node in tree().iter('node')), '保存后车型候选未选中自定义'
    click(label('cancel'))
    screenshot('06-edited')
    click(label('right'))
    saved = private(path)
    click(label('back')); click(label('keep'))
    assert '有未保存的修改' in texts() or 'Unsaved changes' in texts()
    click(label('back')); click(label('discard'))
    assert private(path) == saved
    launch()
    template('l7')
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
print('配置 UX 通过：单一配置入口、车型模态选择与取消保留草稿、L7/L6/自定义确认覆盖同一文件、重置当前配置确认与取消、修改保存自动显示并选中自定义且保留实际车型适配、后续编辑保存、横向 TAB 滚动切换、方控及上报开关直接显示、各模块参数直接显示、自定义百分比取消、独立连接页认证入口和热点双输入弹窗取消、撤销、固定操作、离开草稿确认、独立应用／热点／连接／认证设置保留与测试前配置恢复。', flush=True)
