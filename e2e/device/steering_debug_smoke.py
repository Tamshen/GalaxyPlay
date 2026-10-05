#!/usr/bin/env python3
"""在已同意协议的 AVD 检查方控页、问题标记、日志与返回；不建立连接或上传。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path, default=Path('build/previews/steering-debug'))
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('只允许 AVD')
args.output_dir.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'


def adb(*parts, binary=False):
    result = subprocess.check_output([args.adb, '-s', args.serial, *parts])
    return result if binary else result.decode()


def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-steering-test.xml')
    assert 'UI hierchary dumped to:' in result, result
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-steering-test.xml'))


def find(label, click=False):
    for direction in (('1600', '600'), ('600', '1600')):
        for _ in range(6):
            root = nodes()
            parents = {child: parent for parent in root.iter() for child in parent}
            for node in root.iter('node'):
                if label not in (node.get('text'), node.get('content-desc')):
                    continue
                label_node = node
                while click and node.get('clickable') != 'true' and node in parents:
                    node = parents[node]
                    if node.get('class') == 'android.widget.ListView':
                        return label_node
                if not click or node.get('clickable') == 'true':
                    return node
            adb('shell', 'input', 'swipe', '1050', direction[0], '1050', direction[1], '200')
    raise AssertionError('入口不可达：' + label)


def tap(label):
    node = find(label, True)
    assert node.get('enabled') == 'true'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)


def screenshot(name):
    (args.output_dir / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


launch('settings-debug')
tap('方控调试')
find('手机会话')
assert any('未连接' in n.get('text', '') for n in nodes().iter('node')), 'AVD 不应存在实际手机会话'
screenshot('steering-day')
tap('标记刚才方控失效')
time.sleep(.5)
log = adb('shell', 'run-as', package, 'cat', 'files/logs/steering.log')
assert 'STEERING_TRACE' in log and 'USER_FAILURE' in log
find('最近输入与处理阶段')
screenshot('steering-marker')
tap('清空本页记录')
find('尚未收到方控或媒体控制事件')
assert 'USER_FAILURE' in adb('shell', 'run-as', package, 'cat', 'files/logs/steering.log')
adb('shell', 'cmd', 'uimode', 'night', 'yes')
launch('settings-debug-steering')
find('手机会话')
screenshot('steering-night')
adb('shell', 'cmd', 'uimode', 'night', 'no')
launch('settings-debug-steering')
adb('shell', 'input', 'keyevent', '4')
find('方控调试')
# 从子页返回调试入口后继续返回设置；一级导航不新增方控按钮。
adb('shell', 'input', 'keyevent', '4')
find('连接设置')
launch('settings-general')
tap('应用语言')
tap('English')
tap('应用')
launch('settings-debug-steering')
find('Phone session')
find('Mark a missed control')
assert not any(re.search(r'[\u3400-\u9fff]', n.get('text', '')) for n in nodes().iter('node')), '英文页面存在中文文案'
screenshot('steering-english')
launch('settings-general')
tap('App language')
tap('简体中文')
tap('Apply')
launch('settings-debug-steering')
print('方控页面、标记落盘、清屏保留日志、中英文、昼夜及两级返回检查通过')
