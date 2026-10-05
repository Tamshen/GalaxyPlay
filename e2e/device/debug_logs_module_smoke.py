#!/usr/bin/env python3
"""AVD 检查独立调试与日志分类、返回和默认事实留存；不上传或清除既有报告。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--evidence-only', action='store_true', help='只复验显式采集及日志落盘')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = Path('build/previews/debug-logs-module')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.DEVNULL)


def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-debug-module.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-debug-module.xml'))


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


def language(english):
    launch('settings-general')
    tap('应用语言' if english else 'App language')
    tap('English' if english else '简体中文')
    tap('应用' if english else 'Apply')


def reports():
    import json
    try:
        names = adb('shell', 'run-as', package, 'ls', 'files/probe-reports').decode().splitlines()
    except subprocess.CalledProcessError:
        return []
    return [json.loads(adb('shell', 'run-as', package, 'cat', 'files/probe-reports/' + name))
            for name in names if name.endswith('.json')]


original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
english = False
try:
    adb('shell', 'am', 'force-stop', package)
    for mode in (() if args.evidence_only else (False, True)):
        if mode:
            language(True); english = True
        debug, logs = ('Debug', 'Logs') if mode else ('调试', '日志')
        back = 'Back to settings' if mode else '返回设置'
        for night in ('no', 'yes'):
            adb('shell', 'cmd', 'uimode', 'night', night)
            launch('settings')
            assert has(debug) and has(logs)
            (output / (('en' if mode else 'zh') + '-' + night + '-settings.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            tap(debug)
            root = nodes()
            texts = [n.get('text') for n in root.iter('node')]
            assert ('Voice input test' if mode else '语音输入测试') in texts
            assert ('Steering controls' if mode else '方控调试') in texts
            assert ('Upload logs' if mode else '上传日志') not in texts
            (output / (('en' if mode else 'zh') + '-' + night + '-debug.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            tap(back)
            tap(logs)
            assert has('Upload logs' if mode else '上传日志')
            assert not has('Voice input test' if mode else '语音输入测试')
            (output / (('en' if mode else 'zh') + '-' + night + '-logs.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            adb('shell', 'input', 'keyevent', '4')
            assert has(debug) and has(logs)
        launch('settings-debug-logs')
        assert has('Upload logs' if mode else '上传日志'), '旧日志路由不可达'
        launch('settings-debug-voice')
        adb('shell', 'input', 'keyevent', '4')
        assert has('Voice input test' if mode else '语音输入测试'), '调试子页返回错误'
    if english:
        language(False); english = False
    launch('settings-debug')
    before = {r['runId'] for r in reports()}
    time.sleep(.3)
    assert before == {r['runId'] for r in reports()}, '进入调试页面触发自动收集'
    title = next(n.get('text') for n in nodes().iter('node') if n.get('text') in
                 ('收集环境与调试信息', '重新收集环境与调试信息'))
    deadline = time.monotonic() + 5
    while entry(title).get('enabled') != 'true' and time.monotonic() < deadline:
        time.sleep(.2)
    tap(title)
    deadline = time.monotonic() + 25
    new = []
    while time.monotonic() < deadline:
        new = [r for r in reports() if r['runId'] not in before and r['executionState']=='COMPLETED']
        if new: break
        time.sleep(.3)
    assert new, '未生成完成报告'
    report = new[0]; batch = report['runId'].replace('-', '')[:12]
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        fact_log = adb('shell', 'run-as', package, 'cat', 'files/logs/probe-latest.log').decode()
        if batch in fact_log and 'event=end phase=COMPLETED' in fact_log: break
        time.sleep(.2)
    assert batch in fact_log and 'event=end phase=COMPLETED' in fact_log
    assert 'event=truncated' not in fact_log, '正常批次的逐项明细被截断'
    assert len(set(re.findall(r'item=(\d+) entry=', fact_log))) == len(report['items'])
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        debug_log = adb('shell', 'run-as', package, 'cat', 'files/logs/debug.log').decode()
        if batch in debug_log: break
        time.sleep(.2)
    assert batch in debug_log, '逐项事实未进入默认持久日志'
    scope = '逐项事实默认落盘' if args.evidence_only else '独立调试／日志、中英文昼夜、父级返回、旧路由与逐项事实默认落盘'
    print('AVD ' + scope + '通过；未上传。')
finally:
    if has('关闭'):
        tap('关闭')
    if english: language(False)
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-debug')
