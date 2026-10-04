#!/usr/bin/env python3
"""AVD 验证原生热点引导、生成弹窗与日志快捷入口；不改热点、不授权、不上传。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--gate-only', action='store_true', help='中文 AVD：仅临时拒绝修改设置权限以检查无线预检，结束恢复原值')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = Path('build/previews/native-hotspot-logs')
output.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts])

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-hotspot-log-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-hotspot-log-test.xml'))

def texts():
    return [n.get('text', '') for n in nodes().iter('node')]

def tap(*labels):
    for _ in range(7):
        root = nodes()
        parents = {child: parent for parent in root.iter() for child in parent}
        for original in root.iter('node'):
            if not any(label in (original.get('text'), original.get('content-desc')) for label in labels): continue
            node = original
            while node.get('clickable') != 'true' and node in parents:
                node = parents[node]
                if node.get('class') == 'android.widget.ListView':
                    node = original
                    break
            if node.get('class') == 'android.widget.CheckedTextView':
                x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
            if node.get('clickable') != 'true' or node.get('enabled') != 'true': continue
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        adb('shell', 'input', 'swipe', '1050', '1570', '1050', '650', '200')
    raise AssertionError('入口不可达：'+str(labels))

def launch(page):
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', package+'/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)

def screenshot(name):
    (output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

def english():
    assert not any(re.search(r'[\u3400-\u9fff]', t) for t in texts()), '英文界面含中文'

def check_gate():
    access = adb('shell', 'cmd', 'appops', 'get', package, 'WRITE_SETTINGS').decode()
    match = re.search(r'WRITE_SETTINGS: (allow|deny|ignore|default|foreground)', access)
    mode = match.group(1) if match else 'default'
    try:
        adb('shell', 'cmd', 'appops', 'set', package, 'WRITE_SETTINGS', 'deny')
        launch('settings-connection'); tap('无线连接')
        assert any('需要允许本应用修改系统设置' in t for t in texts()), '无线连接未经过原生热点预检'
        screenshot('wireless-permission-fallback')
        adb('shell', 'input', 'keyevent', '4')
    finally:
        adb('shell', 'cmd', 'appops', 'set', package, 'WRITE_SETTINGS', mode)

if args.gate_only:
    check_gate()
    launch('settings-debug')
    print('无线连接预检通过：权限不足提示原生设置授权，未开启热点，原 AppOps 已恢复。')
    raise SystemExit

original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
try:
    adb('shell', 'am', 'force-stop', package)
    launch('settings-general')
    tap('应用语言', 'App language')
    if any(n.get('text') == 'English' and n.get('checked') == 'true' for n in nodes().iter('node')):
        adb('shell', 'input', 'keyevent', '4')
    else:
        tap('English'); tap('应用', 'Apply')
    for lang in ('en', 'zh'):
        if lang == 'zh':
            launch('settings-general'); tap('App language'); tap('简体中文'); tap('Apply')
        launch('settings-debug')
        view, upload = ('View logs','Upload logs') if lang=='en' else ('查看日志','上传日志')
        visible=texts()
        assert view in visible and upload in visible, '日志操作未在首屏显示'
        summary = next(t for t in visible if t.startswith(('Not uploaded', 'Uploaded\n', '未上传', '已上传\n')))
        if lang=='en': english()
        screenshot('quick-logs-'+lang)
        tap(view)
        tap('Refresh' if lang=='en' else '刷新')
        tap('Close' if lang=='en' else '关闭')
        assert view in texts()
        assert summary in texts(), '查看日志改变了上传历史'
        launch('settings-connection')
        visible=texts()
        assert any(('different service' in t if lang=='en' else '不是同一个服务' in t) for t in visible)
        if lang=='en': english()
        screenshot('native-hotspot-'+lang)
        tap('Generate CarPlay hotspot configuration' if lang=='en' else '生成 CarPlay 热点配置')
        visible=texts()
        assert any(re.fullmatch(r'CarPlay_[0-9A-F]{4}', t) for t in visible), '生成名称不符合规则'
        assert not any(re.fullmatch(r'[A-Za-z0-9]{16}', t) for t in visible), '默认显示了完整密码'
        if lang=='en': english()
        screenshot('generated-dialog-'+lang)
        tap('Cancel' if lang=='en' else '取消')
        tap('Open Android native hotspot settings' if lang=='en' else '打开 Android 原生热点设置')
        assert 'com.android.settings' in {n.get('package') for n in nodes().iter('node')}, '未进入 Android 原生设置'
        launch('settings-connection')
    check_gate()
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    launch('settings-debug'); screenshot('quick-logs-zh-night')
    print('AVD 检查通过：中英文日志首屏操作、查看刷新、热点提示、生成弹窗和原生设置入口；未上传或改热点。')
finally:
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-debug')
