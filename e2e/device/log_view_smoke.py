#!/usr/bin/env python3
"""AVD 检查日志单列操作、搜索复制和清空取消；不上传、不删除已有数据。"""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = Path('build/previews/log-view')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts])


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-log-view-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-log-view-test.xml'))


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def find(label, root=None):
    root = root if root is not None else nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    for original in root.iter('node'):
        if label not in (original.get('text'), original.get('content-desc')):
            continue
        node = original
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        if node.get('clickable') == 'true':
            return node
    raise AssertionError('入口不可达：' + label)


def tap(label):
    node = find(label)
    assert node.get('enabled') == 'true', '操作未启用：' + label
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def screenshot(name):
    (output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def launch():
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
        package+'/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-debug')


def search(value):
    node = next(n for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText')
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * (len(node.get('text', '')) + 1)))
    if value:
        adb('shell', 'input', 'text', value)
    # 收起输入法，复制和刷新保持可见。
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    updated = next(n for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText')
    assert updated.get('text') == value or not value and updated.get('text') == updated.get('content-desc'), '搜索词未正确替换'


original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
try:
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    launch()
    root = nodes()
    actions = ['查看日志', '上传日志', '清空日志', '清空报告']
    geometry = [bounds(find(label, root)) for label in actions]
    assert len({(b[0], b[2]) for b in geometry}) == 1, '按钮未等宽对齐'
    assert all(geometry[i][3] < geometry[i+1][1] for i in range(3)), '按钮仍并排或重叠'
    assert all(b[2]-b[0] > 600 for b in geometry), '按钮未铺满右侧内容区'
    assert all(b[3] <= 1920 for b in geometry), '快捷按钮未在首屏完整显示'
    screenshot('actions-day')
    tap('查看日志')
    search('upload_summary')
    root = nodes()
    assert any('upload_summary retained=' in n.get('text', '') for n in root.iter('node')), '搜索未显示整理摘要'
    tap('复制筛选结果')
    tap('刷新')
    assert any(n.get('text') == 'upload_summary' for n in nodes().iter('node')), '刷新丢失搜索词'
    screenshot('search-day')
    search('no_match_l7_synthetic_439')
    assert find('复制筛选结果').get('enabled') == 'false', '无匹配仍允许复制'
    screenshot('search-empty')
    search('')
    assert find('复制筛选结果').get('enabled') == 'true', '清空搜索后未恢复全部日志'
    tap('复制全部日志')
    # 数据过大时仅关闭分段选择器，不逐段覆盖剪贴板。
    if any(n.get('text') == '分段复制日志' for n in nodes().iter('node')):
        tap('关闭')
    tap('关闭')
    for action in ('清空日志', '清空报告'):
        tap(action)
        assert any(n.get('text') == action for n in nodes().iter('node'))
        screenshot('confirm-logs' if action == '清空日志' else 'confirm-reports')
        tap('取消')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    launch(); screenshot('actions-night')
    tap('查看日志'); search('upload_summary'); screenshot('search-night'); tap('关闭')
    print('AVD 检查通过：四个按钮等宽单列，日志搜索/刷新/复制，清空确认取消，昼夜截图；未上传或清空已有数据。')
finally:
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch()
