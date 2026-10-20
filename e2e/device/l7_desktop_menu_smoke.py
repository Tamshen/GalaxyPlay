#!/usr/bin/env python3
"""仅在 AVD 验证程序外悬浮图标的拖动、单击返回和前台隐藏，最终停止应用。"""
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
out = Path(__file__).resolve().parents[2] / 'build/previews/desktop-shortcut'
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
    for _ in range(5):
        if node is not None: break
        adb('shell', 'input', 'swipe', '1100', '1600', '1100', '650', '350')
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
    # Android 11 的 uiautomator dump 只返回焦点窗口；悬浮图标不抢焦点，从系统窗口读取真实边界。
    windows = adb('shell', 'dumpsys', 'window', 'windows')
    for window in re.split(r'(?=  Window #\d+ Window\{)', windows):
        if '车机桌面悬浮图标' in window and 'isVisible=true' in window:
            bounds = re.search(r'mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', window)
            assert bounds, '悬浮窗口没有可见边界'
            return tuple(map(int, bounds.groups()))
    return None


def tap_overlay():
    bounds = overlay_bounds()
    assert bounds is not None, '桌面悬浮窗口未显示'
    x1, y1, x2, y2 = bounds
    assert x2 - x1 == y2 - y1, '后台窗口应始终只有图标大小'
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    # 等前台 Activity 转场稳定，不能把点击后立即移除旧图标当作返回完成。
    nodes()
    deadline = time.monotonic() + 5
    while overlay_bounds() is not None and time.monotonic() < deadline:
        time.sleep(.2)


def desktop():
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    assert overlay_bounds() is not None


adb('shell', 'am', 'force-stop', package)
# 仅修改模拟器中本应用的悬浮权限，实车用户需自行在系统设置授权。
adb('shell', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', 'allow')
launch()
assert find('有线连接') is not None, '请先在 AVD 完成协议确认'
assert overlay_bounds() is None, '应用内重复显示桌面入口'
desktop()
screenshot('outside-shortcut')
before_bounds = overlay_bounds()
x1, y1, x2, y2 = before_bounds
adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str((y1 + y2) // 2), '500' if x1 > 800 else '1200', '700', '500')
time.sleep(.5)
after_bounds = overlay_bounds()
assert after_bounds is not None and after_bounds != before_bounds, '拖动应只移动图标，不打开画面'
screenshot('dragged-shortcut')
tap_overlay()
assert find('有线连接') is not None and overlay_bounds() is None, '单击应直接返回首页并隐藏图标'
assert 'DiPlaySessionService' not in adb('shell', 'dumpsys', 'activity', 'services', package), '无会话返回不得主动连接'
screenshot('single-tap-home')
desktop()
assert overlay_bounds() == after_bounds, '返回后台后未保留拖动位置'
tap_overlay()
assert overlay_bounds() is None
launch('settings-general')
tap('车机桌面悬浮图标')
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');time.sleep(1)
assert overlay_bounds() is None, '关闭开关后仍有悬浮入口'
launch('settings-general');tap('车机桌面悬浮图标')
desktop();tap_overlay()
assert overlay_bounds() is None
adb('shell', 'am', 'force-stop', package)
print('AVD 拖动不跳转、位置保留、单击直返首页、前台隐藏和独立开关通过；没有真实 CarPlay 会话', flush=True)
