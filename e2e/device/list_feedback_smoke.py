#!/usr/bin/env python3
"""在 AVD 注入真实指针事件，检查设置行反馈边界、消退和触屏弹窗返回。"""
import argparse
from io import BytesIO
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--pointer-jar', type=Path, default=Path('build/e2e/list-feedback/pointer.jar'))
parser.add_argument('--output-dir', type=Path, default=Path('build/previews/list-feedback'))
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('只允许 AVD，不操作实车')
assert args.pointer_jar.is_file(), '先在 Docker 编译 PointerInput.java'
args.output_dir.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
remote = '/data/local/tmp/l7-list-pointer.jar'

def adb(*parts, binary=False):
    data = subprocess.check_output(base + list(parts), stderr=subprocess.DEVNULL)
    return data if binary else data.decode()

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-list-feedback.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-list-feedback.xml'))

def find(label):
    for start, end in (('1600', '650'), ('650', '1600')):
        for _ in range(7):
            root = nodes()
            parents = {child: parent for parent in root.iter() for child in parent}
            node = next((n for n in root.iter('node') if n.get('text') == label), None)
            if node is not None:
                while node.get('clickable') != 'true' and node in parents:
                    node = parents[node]
                return node
            adb('shell', 'input', 'swipe', '1000', start, '1000', end, '250')
    raise AssertionError('未找到条目：' + label)

def box(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))

def point(node):
    left, top, right, bottom = box(node)
    return str((left + right) // 2), str((top + bottom) // 2)

def pointer(action, x, y):
    adb('shell', f'CLASSPATH={remote} app_process /system/bin l7.e2e.PointerInput {action} {x} {y}')

def screenshot(name):
    data = adb('exec-out', 'screencap', '-p', binary=True)
    (args.output_dir / (name + '.png')).write_bytes(data)
    return Image.open(BytesIO(data)).convert('RGB')

def bounded(before, after, bounds):
    changed = ImageChops.difference(before, after).getbbox()
    assert changed is not None, '输入事件没有产生可见反馈'
    left, top, right, bottom = bounds
    assert changed[0] >= left and changed[1] >= top and changed[2] <= right and changed[3] <= bottom, '反馈绘制超出当前条目：' + str(changed)
    return changed

original_night = adb('shell', 'cmd', 'uimode', 'night').strip().split()[-1]
adb('push', str(args.pointer_jar), remote)
try:
    for theme, mode in [('day', 'no'), ('night', 'yes')]:
        adb('shell', 'cmd', 'uimode', 'night', mode)
        adb('shell', 'am', 'start', '-n', 'com.ecarx.carplay/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', 'settings-display')
        row = find('帧率')
        x, y = point(row)
        bounds = box(row)
        # 进入触摸模式，清除上一轮键盘或鼠标焦点；不点击设置值。
        pointer('down', '1100', '80')
        pointer('cancel', '1100', '80')
        time.sleep(.5)
        normal = screenshot(theme + '-normal')
        pointer('hover', x, y)
        time.sleep(.4)
        hovered = screenshot(theme + '-hover')
        changed = bounded(normal, hovered, bounds)
        assert changed[2] - changed[0] > (bounds[2] - bounds[0]) * .9, '悬停未覆盖整行'
        pointer('exit', x, y)
        time.sleep(.5)
        cleared = screenshot(theme + '-exit')
        assert ImageChops.difference(normal.crop(bounds), cleared.crop(bounds)).getbbox() is None, '移出后仍残留悬停高亮'
        pointer('down', x, y)
        time.sleep(.3)
        bounded(normal, screenshot(theme + '-pressed'), bounds)
        pointer('cancel', x, y)
        adb('shell', 'input', 'tap', x, y)
        cancel = find('取消')
        adb('shell', 'input', 'tap', *point(cancel))
        returned = find('帧率')
        assert returned.get('focused') != 'true', '触屏关闭弹窗后条目被强制聚焦'
        time.sleep(.5)
        screenshot(theme + '-modal-return')
    print('列表反馈检查通过：昼夜悬停整行、移出消退、按压不越界、触屏弹窗返回无强制焦点。')
finally:
    for action in ('cancel', 'exit'):
        try:
            pointer(action, '1100', '80')
        except subprocess.CalledProcessError:
            pass  # 没有活动手势时系统可能拒绝取消，不能覆盖原检查结果。
    adb('shell', 'rm', '-f', remote)
    if original_night in ('yes', 'no', 'auto'):
        adb('shell', 'cmd', 'uimode', 'night', original_night)
