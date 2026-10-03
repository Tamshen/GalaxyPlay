#!/usr/bin/env python3
"""仅在已同意协议的 AVD 检查应用 DPI，最终恢复中档并保留系统密度。"""
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
out = Path(__file__).resolve().parents[2] / 'build/previews/ui-density'
out.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'


def adb(*words):
    return subprocess.run([args.adb, '-s', args.serial, *words], check=True, capture_output=True).stdout.decode()


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-density.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-density.xml')).iter('node'))


def find(label):
    matches = [n for n in nodes() if n.get('text') == label or n.get('content-desc') == label]
    return next((n for n in matches if n.get('content-desc') == label and n.get('clickable') == 'true'),
                next((n for n in matches if n.get('clickable') == 'true'), matches[-1] if matches else None))


def tap_node(node):
    assert node is not None, '找不到操作入口'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.5)


def tap(label):
    tap_node(find(label))


def launch(page):
    adb('shell', 'am', 'start', '-W', '-f', '0x00020000', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)
    time.sleep(.5)
    # 已有 singleTask 投屏任务时，Android 可能恢复任务顶层；沿实际菜单进入目标页。
    if page == 'settings-display' and find('界面大小') is None:
        if find('展开菜单') is not None:
            tap('展开菜单')
        tap('设置')
        if find('显示与性能') is None:
            tap('设置')
        tap('显示与性能')
    if page == 'home' and find('有线连接') is None and find('返回 L7CarPlay') is not None:
        tap('返回 L7CarPlay')


def screenshot(name):
    result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], check=True, capture_output=True)
    (out / (name + '.png')).write_bytes(result.stdout)


def choose(label):
    tap('界面大小')
    tap(label)
    save = find('保存')
    if save.get('enabled') == 'true':
        tap_node(save)
    else:
        tap('取消')
    assert find(label) is not None, '大小档位未保存'


def custom(value):
    tap('自定义 DPI')
    field = next(n for n in nodes() if n.get('class') == 'android.widget.EditText')
    tap_node(field)
    # 清理当前数值，不依赖触摸后仍保留 selectAll；不操作连接或认证数据。
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END', *(['KEYCODE_DEL'] * 12))
    adb('shell', 'input', 'text', str(value))
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('保存')


system_density = adb('shell', 'wm', 'density')
night = adb('shell', 'cmd', 'uimode', 'night').strip().split(':')[-1].strip()
try:
    adb('shell', 'am', 'force-stop', package)
    launch('settings-display')
    assert find('界面大小') is not None, '请先在此 AVD 完成协议确认并进入设置'
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    for label, name, dpi in [('小 · 240 DPI', 'small', 240), ('中（默认） · 280 DPI', 'medium', 280), ('大 · 320 DPI', 'large', 320)]:
        choose(label)
        menu = find('设置')
        x1, _, x2, _ = map(int, re.findall(r'\d+', menu.get('bounds')))
        # 菜单项宽度为 132 - 8 - 8 dp，验证界面确实缩放，而不只改变标签。
        assert abs((x2 - x1) - 116 * dpi / 160) <= 2
        screenshot(name + '-settings')
        launch('home')
        screenshot(name + '-home')
        launch('settings-display')
    custom(300)
    assert find('自定义 · 300 DPI') is not None
    screenshot('custom-300')
    custom(999)
    assert find('自定义 DPI') is not None and find('保存') is not None, '非法值关闭了对话框'
    screenshot('invalid-input')
    tap('取消')
    assert find('自定义 · 300 DPI') is not None, '非法值覆盖了有效值'
    choose('中（默认） · 280 DPI')
    assert adb('shell', 'wm', 'density') == system_density, '修改了系统 DPI'
    adb('shell', 'am', 'force-stop', package)
    launch('settings-display')
    assert find('中（默认） · 280 DPI') is not None, '重启丢失选择'
    print('三档、自定义、非法输入、取消、重启保存和系统 DPI 隔离通过', flush=True)
    launch('home')
    tap('有线连接')
    assert find('返回 L7CarPlay') is not None
    host_before = adb('shell', 'dumpsys', 'activity', 'activities')
    host_id = re.search(r'ActivityRecord\{(\w+)[^\n]*CarPlayHostActivity', host_before).group(1)
    for label, name in [('大 · 320 DPI', 'large'), ('中（默认） · 280 DPI', 'medium')]:
        tap('返回 L7CarPlay')
        launch('settings-display')
        choose(label)
        assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package)
        launch('home')
        tap('查看连接进度')
        assert find('返回 L7CarPlay') is not None
        assert re.search(r'ActivityRecord\{' + host_id + r'[^\n]*CarPlayHostActivity',
                         adb('shell', 'dumpsys', 'activity', 'activities')), '缩放重建了投屏宿主'
        screenshot(name + '-usb')
    tap('取消连接')
    time.sleep(1)
    assert not re.search(r'ServiceRecord[^\n]*(DiPlaySessionService|CarPlayVpnService)',
                         adb('shell', 'dumpsys', 'activity', 'services', package)), '取消后仍有连接服务'
    launch('home')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    nodes()
    screenshot('medium-home-night')
    print('USB 等待期间切换大小保留宿主和服务，取消后释放；未验证真实 iPhone 会话', flush=True)
finally:
    adb('shell', 'cmd', 'uimode', 'night', night)

print('应用 DPI 检查通过，当前中档。', flush=True)
