#!/usr/bin/env python3
"""仅在 AVD 验证桌面悬浮菜单；测试授权悬浮权限，最终退出应用。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许模拟器')
package = 'com.ecarx.carplay'
out = Path(__file__).resolve().parents[2] / 'build/previews/desktop-menu'
out.mkdir(parents=True, exist_ok=True)


def adb(*words):
    return subprocess.run([args.adb, '-s', args.serial, *words], check=True, capture_output=True).stdout.decode()


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-desktop.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-desktop.xml')).iter('node'))


def find(label):
    matches = [n for n in nodes() if n.get('text') == label or n.get('content-desc') == label]
    return next((n for n in matches if n.get('content-desc') == label and n.get('clickable') == 'true'),
                next((n for n in matches if n.get('clickable') == 'true'), matches[-1] if matches else None))


def tap(label):
    node = find(label)
    assert node is not None, '找不到：' + label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.6)


def screenshot(name):
    image = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], check=True, capture_output=True)
    (out / (name + '.png')).write_bytes(image.stdout)


def launch(page='home'):
    adb('shell', 'am', 'start', '-W', '-f', '0x00020000', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)
    time.sleep(.6)


def overlay_bounds():
    # Android 11 的 uiautomator dump 只返回焦点窗口；悬浮菜单不抢焦点，从系统窗口读取真实边界。
    windows = adb('shell', 'dumpsys', 'window', 'windows')
    for window in re.split(r'(?=  Window #\d+ Window\{)', windows):
        if 'GalaxyPlay 桌面菜单}:\n' in window and 'isVisible=true' in window:
            bounds = re.search(r'mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', window)
            assert bounds, '悬浮窗口没有可见边界'
            return tuple(map(int, bounds.groups()))
    return None


def tap_overlay(label):
    bounds = overlay_bounds()
    assert bounds is not None, '桌面悬浮窗口未显示'
    x1, y1, x2, y2 = bounds
    if label == '展开桌面菜单':
        assert x2 - x1 == y2 - y1, '当前不是收起入口'
        y = (y1 + y2) // 2
    else:
        assert y2 - y1 > 3 * (x2 - x1), '当前不是四项菜单'
        # 四项等高按钮；视觉状态另由留存截图复核，页面内的选中态仍读取无障碍节点。
        index = ['画面', '设置', '车机', '退出'].index(label)
        padding = (x2 - x1) * 10 / 132
        y = round(y1 + padding + (y2 - y1 - 2 * padding) * (index + .5) / 4)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str(y))
    time.sleep(.7)


def desktop():
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    assert overlay_bounds() is not None


def host_id():
    return re.search(r'ActivityRecord\{(\w+)[^\n]*CarPlayHostActivity',
                     adb('shell', 'dumpsys', 'activity', 'activities')).group(1)


adb('shell', 'am', 'force-stop', package)
# 仅修改模拟器中本应用的悬浮权限，实车用户需自己在系统设置授权。
adb('shell', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', 'allow')
launch()
assert find('有线连接') is not None, '请先在 AVD 完成协议确认'
assert overlay_bounds() is None, '应用内重复显示桌面入口'
desktop()
screenshot('collapsed')
before_bounds = overlay_bounds()
x1, y1, x2, y2 = before_bounds
adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str((y1 + y2) // 2), '1200', '700', '500')
assert overlay_bounds() != before_bounds, '悬浮入口未移动'
tap_overlay('展开桌面菜单')
assert overlay_bounds()[0] < 100 and overlay_bounds()[1] < 150, '展开菜单没有固定在左上角'
screenshot('expanded')
tap_overlay('画面')
assert find('有线连接') is not None and overlay_bounds() is None
print('后台悬浮入口、拖动、固定四项菜单、返回首页与应用内隐藏通过', flush=True)
tap('有线连接')
assert find('返回 GalaxyPlay') is not None
before = host_id()
desktop()
tap_overlay('展开桌面菜单')
tap_overlay('画面')
assert find('返回 GalaxyPlay') is not None and host_id() == before
assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package)
screenshot('returned-to-session')
desktop()
tap_overlay('展开桌面菜单')
tap_overlay('设置')
assert find('显示与性能') is not None
assert find('设置').get('selected') == 'true'
tap('显示与性能')
assert find('设置').get('selected') == 'true'
tap('桌面悬浮菜单')
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(1)
assert overlay_bounds() is None, '关闭后仍有桌面入口'
launch('settings-display')
tap('桌面悬浮菜单')
desktop()
tap_overlay('展开桌面菜单')
tap_overlay('车机')
assert overlay_bounds() is not None
tap_overlay('展开桌面菜单')
tap_overlay('退出')
assert find('退出应用') is not None
screenshot('exit-confirmation')
tap('取消')
assert overlay_bounds() is not None
assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package)
tap_overlay('展开桌面菜单')
tap_overlay('退出')
tap('退出应用')
time.sleep(2)
result = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', package], capture_output=True)
assert not result.stdout.strip(), '退出后进程仍在'
assert overlay_bounds() is None
assert not re.search(r'ServiceRecord[^\n]*(DiPlaySessionService|L7DesktopNavigationService|L7DebugOverlayService)',
                     adb('shell', 'dumpsys', 'activity', 'services', package))
print('桌面返回同一等待宿主、设置、开关、车机、退出取消及确认清理通过；没有真实 iPhone 会话', flush=True)
