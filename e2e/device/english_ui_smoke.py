#!/usr/bin/env python3
"""仅在 AVD 检查英文页面、设置弹窗、协议和离线许可；结束后保留英文。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--dialogs-only', action='store_true', help='仅复核弹窗、协议和 USB 取消，跳过已验证的十个页面')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('只允许模拟器，不操作实车')
output = Path(__file__).resolve().parents[2] / 'build/previews/english-ui'
output.mkdir(parents=True, exist_ok=True)
han = re.compile(r'[\u3400-\u9fff]')


def adb(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, *command], stderr=subprocess.DEVNULL)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-english.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-english.xml')).iter('node'))


def launch(page):
    adb('shell', 'am', 'start', '-n', 'com.ecarx.carplay/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)
    time.sleep(.3)


def swipe():
    adb('shell', 'input', 'swipe', '1000', '1580', '1000', '650', '300')


def tap(*labels):
    for _ in range(6):
        current = nodes()
        parents = {child: parent for parent in current for child in parent}
        def actionable(node):
            while node is not None:
                if node.get('enabled') == 'false':
                    return False
                if node.get('clickable') == 'true' or node.get('checkable') == 'true':
                    return True
                node = parents.get(node)
            return False
        for node in current:
            if node.get('text') in labels or node.get('content-desc') in labels:
                # 同名分组标题不是入口，只点击可操作的行、按钮或选项。
                if not actionable(node):
                    continue
                x, y, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
                adb('shell', 'input', 'tap', str((x + right) // 2), str((y + bottom) // 2))
                time.sleep(.3)
                return
        swipe()
    raise AssertionError(f'入口不可达：{labels}')


def english(name, allow_native_language_name=False):
    current = nodes()
    visible = [n.get(key, '') for n in current for key in ('text', 'content-desc')]
    unexpected = [v for v in visible if han.search(v) and not (allow_native_language_name and v == '简体中文')]
    assert not unexpected, f'{name} 含中文界面文案：{unexpected[:3]}'
    return current


def screenshot(name):
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))


launch('settings-general')
tap('应用语言', 'App language')
if any(n.get('text') == 'English' and n.get('checked') == 'true' for n in nodes()):
    # 当前已经是英文时确认按钮按设计禁用，直接关闭选择器。
    adb('shell', 'input', 'keyevent', '4')
else:
    tap('English')
    tap('应用', 'Apply')

pages = ('home', 'settings', 'settings-auth', 'settings-connection', 'settings-display',
         'settings-audio', 'settings-general', 'settings-permissions', 'settings-diagnostics', 'settings-about')
for page in (() if args.dialogs_only else pages):
    launch(page)
    english(page)
    screenshot(page)
    if page not in ('home', 'settings'):
        previous = None
        for _ in range(5):
            current = english(page)
            state = [(n.get('text'), n.get('bounds')) for n in current]
            if state == previous:
                break
            previous = state
            swipe()

launch('settings-about')
tap('Third-party licenses')
current = english('第三方许可弹窗')
all_text = '\n'.join(n.get('text', '') for n in current)
assert 'GNU GENERAL PUBLIC LICENSE' in all_text
assert 'Copyright AndyShaman' in all_text
screenshot('licenses-modal')
swipe()
english('许可弹窗滚动')
tap('Close')
assert any(n.get('text') == 'About' for n in nodes())

tap('User agreement')
current = english('英文协议')
assert any('First-use Notice and Risk Acknowledgment' in n.get('text', '') for n in current)
screenshot('agreement')
tap('Back to About')

launch('settings-general')
tap('App language')
current = english('语言选择器', allow_native_language_name=True)
assert {n.get('text') for n in current if n.get('class') == 'android.widget.CheckedTextView'} == {
    'System default', 'English', '简体中文'}
adb('shell', 'input', 'keyevent', '4')

launch('settings-auth')
tap('Authentication source')
english('认证来源选择器')
screenshot('authentication-picker')
adb('shell', 'input', 'keyevent', '4')

launch('settings-display')
tap('Interface size')
english('界面大小选择器')
screenshot('density-picker')
adb('shell', 'input', 'keyevent', '4')

launch('settings-audio')
tap('Media music')
english('声道路由弹窗')
screenshot('audio-modal')
tap('Advanced output policy')
english('音频流选项')
screenshot('audio-picker')
adb('shell', 'input', 'keyevent', '4')
adb('shell', 'input', 'keyevent', '4')

launch('home')
tap('Wired connection')
english('USB 等待页面')
screenshot('usb-waiting')
tap('Cancel connection')
launch('home')
english('返回首页')
screenshot('home-final')
print(('英文弹窗检查通过：' if args.dialogs_only else '英文检查通过：10 个页面、') +
      '许可弹窗/滚动/关闭、协议、语言/认证/字号/音频选择器和 USB 取消；未改变认证或音频配置。')
