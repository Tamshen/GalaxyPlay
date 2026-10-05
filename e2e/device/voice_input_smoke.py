#!/usr/bin/env python3
"""AVD 检查语音页权限、手动电平、停止／后台／限时和中英文昼夜；不连接手机或上传。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = Path('build/previews/voice-input')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.DEVNULL)


def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-voice-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-voice-test.xml'))


def entry(label, root=None):
    root = root if root is not None else nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if label not in (node.get('text'), node.get('content-desc')):
            continue
        while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
            node = parents[node]
        return node
    raise AssertionError('入口不可达：' + label)


def tap(label):
    node = entry(label)
    assert node.get('enabled') == 'true' and (node.get('clickable') == 'true' or node.get('checkable') == 'true'), label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def has(text):
    return any(n.get('text') == text for n in nodes().iter('node'))


def voice_log():
    return adb('shell', 'run-as', package, 'cat', 'files/logs/voice-input.log').decode()


def wait_log(phase, baseline):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        lines = voice_log()[baseline:]
        if 'phase=' + phase in lines:
            return lines
        time.sleep(.3)
    raise AssertionError('未观察到语音终态 ' + phase)


def language(english):
    launch('settings-general')
    tap('应用语言' if english else 'App language')
    tap('English' if english else '简体中文')
    tap('应用' if english else 'Apply')


original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
original_permission = bool(re.search(r'android.permission.RECORD_AUDIO: granted=true',
    adb('shell', 'dumpsys', 'package', package).decode()))
english = False
try:
    adb('shell', 'pm', 'revoke', package, 'android.permission.RECORD_AUDIO')
    launch('settings-debug-voice')
    assert entry('开始测试（10 秒）').get('enabled') == 'false'
    assert has('未授权')
    adb('shell', 'pm', 'grant', package, 'android.permission.RECORD_AUDIO')
    launch('settings-debug-voice')
    assert has('未开始'), '授权不可自动开始采集'
    assert entry('开始测试（10 秒）').get('enabled') == 'true'
    for english_mode in (False, True):
        if english_mode:
            language(True); english = True
        for night in ('no', 'yes'):
            adb('shell', 'cmd', 'uimode', 'night', night)
            launch('settings-debug-voice')
            root = nodes()
            assert has('Voice input test' if english_mode else '语音输入测试')
            assert entry('Start test (10 seconds)' if english_mode else '开始测试（10 秒）', root).get('enabled') == 'true'
            if english_mode:
                assert not any(re.search(r'[\u3400-\u9fff]', n.get('text', '')) for n in root.iter('node'))
            (output / (('en' if english_mode else 'zh') + '-' + night + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    language(False); english = False
    launch('settings-debug-voice')
    try:
        baseline = len(voice_log())
    except subprocess.CalledProcessError:
        baseline = 0
    start = entry('开始测试（10 秒）')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', start.get('bounds')))
    tap('开始测试（10 秒）')
    time.sleep(.5)
    # 采集中电平持续刷新，直接点击已知按钮位置，避免无障碍等待空闲。
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    stopped = wait_log('STOPPED', baseline)
    assert 'phase=START' in stopped
    baseline = len(voice_log())
    tap('开始测试（10 秒）')
    adb('shell', 'input', 'keyevent', '3')
    wait_log('STOPPED', baseline)
    launch('settings-debug-voice')
    assert has('未开始'), '回到前台不可自动续录'
    baseline = len(voice_log())
    tap('开始测试（10 秒）')
    completed = wait_log('COMPLETE', baseline)
    assert any(int(n) > 0 for n in re.findall(r'bytes=(\d+)', completed)), 'AVD 未读取到 PCM 样本'
    assert has('已完成 10 秒测试')
    (output / 'zh-complete.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    adb('shell', 'input', 'keyevent', '4')
    assert has('方控调试') and has('语音输入测试'), '返回须进入调试首页'
    print('AVD 语音页权限、手动采集、停止／后台／限时、中英文昼夜及返回通过；未连接手机或上传。')
finally:
    if english:
        language(False)
    if not original_permission:
        adb('shell', 'pm', 'revoke', package, 'android.permission.RECORD_AUDIO')
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-debug-voice')
