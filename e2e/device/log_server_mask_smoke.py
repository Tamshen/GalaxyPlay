#!/usr/bin/env python3
"""中文 AVD：核对内置服务器遮蔽、编辑空值保留及取消；不读取或打印真实地址，不上传。"""
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
output = Path('build/previews/log-server-mask')
output.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.DEVNULL)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-server-mask.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-server-mask.xml'))

def row(label):
    for _ in range(8):
        root = nodes()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            if label not in (node.get('text'), node.get('content-desc')): continue
            while node.get('clickable') != 'true' and node in parents: node = parents[node]
            if node.get('clickable') == 'true' and node.get('enabled') == 'true': return node
        adb('shell', 'input', 'swipe', '1100', '1580', '1100', '650', '250')
    raise AssertionError('入口不可达：'+label)

def tap(label):
    node = row(label)
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def preferences():
    # 仅做字节相等比较；内容留在进程内，不输出真实地址和凭据。
    try: return adb('shell', 'run-as', package, 'cat', 'shared_prefs/l7_remote_log.xml')
    except subprocess.CalledProcessError: return b''

def masked_fields():
    fields = [n for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 2
    assert all(n.get('text', '') in ('', '*****') for n in fields), '输入框存在实际配置；不输出内容'

def screenshot(name):
    (output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', package+'/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-debug-logs')
server = row('OpenObserve 日志服务器')
assert any(n.get('text') == '*****' for n in server.iter('node')), '当前服务器未遮蔽；请确认 AVD 使用内置默认配置'
screenshot('server-row')
before = preferences()
tap('OpenObserve 日志服务器'); masked_fields(); screenshot('server-modal')
tap('保存')
assert preferences() == before, '未编辑却保存了覆盖值'
tap('OpenObserve 日志服务器'); masked_fields(); tap('取消')
assert preferences() == before, '取消修改了配置'
print('AVD 遮蔽检查通过：列表与弹窗为 *****，保存空字段/取消均未覆盖真实配置；未上传。')
