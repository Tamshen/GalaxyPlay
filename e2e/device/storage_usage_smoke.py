#!/usr/bin/env python3
"""仅在 AVD 备份日志后注入超额文件，验证启动自动清理与应用设置占用展示；结束恢复日志目录。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许模拟器'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/storage-134')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts, data=None):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE, timeout=30)


def private(command, data=None):
    return adb('shell', 'run-as ' + package + ' sh -c ' + shlex.quote(command), data=data)


def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-storage.xml')
    assert b'dumped to:' in result
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/galaxy-storage.xml'))


assert 'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities').decode()
adb('shell', 'am', 'force-stop', package)
assert private('if [ -e files/logs-storage-backup ]; then echo occupied; fi') == b'', '已有备份，停止'
had_logs = private('if [ -d files/logs ]; then echo present; fi').strip() == b'present'
moved = False
try:
    if had_logs:
        private('mv files/logs files/logs-storage-backup')
    moved = True
    private('mkdir -p files/logs cache/log-upload-snapshots/upload-storage-test cache/app-updates')
    for i, name in enumerate(['previous-7.log', 'previous-6.log', 'previous-5.log', 'previous-4.log',
                             'previous-3.log', 'previous-2.log', 'previous.log', 'diplay.log', 'debug.log',
                             'steering-previous-2.log', 'steering-previous.log', 'steering.log']):
        private('cat > files/logs/' + name, data=(f'storage_fixture={i}\n' + 'counter evidence\n' * 31_000).encode())
    private('cat > cache/log-upload-snapshots/upload-storage-test/batches.bin', data=b'orphan snapshot')
    private('cat > cache/app-updates/download-storage-test.partial', data=b'orphan partial')
    private('cat > cache/app-updates/download-storage-test.apk', data=b'orphan apk')
    private('touch -t 202001010000 cache/app-updates/download-storage-test.partial cache/app-updates/download-storage-test.apk')
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity',
        '--es', 'page', 'settings-general')
    deadline = time.monotonic() + 20
    while True:
        sizes = private(r'find files/logs -type f -name "*.log" -exec stat -c %s {} \;').decode().split()
        total = sum(map(int, sizes))
        orphans = private('if [ -e cache/log-upload-snapshots/upload-storage-test ]; then echo snapshot; fi; '
                          'if [ -e cache/app-updates/download-storage-test.partial ]; then echo partial; fi; '
                          'if [ -e cache/app-updates/download-storage-test.apk ]; then echo apk; fi')
        if total <= 5_000_000 and not orphans:
            break
        assert time.monotonic() < deadline, '启动自动清理未完成'
        time.sleep(.3)
    assert private('if [ -s files/logs/diplay.log ] && [ -s files/logs/debug.log ]; then echo kept; fi').strip() == b'kept'
    tree = nodes()
    for _ in range(10):
        if any(n.get('text') == '存储与内存占用' for n in tree.iter('node')):
            break
        adb('shell', 'input', 'swipe', '1100', '1650', '1100', '500', '250')
        tree = nodes()
    labels = ('应用数据占用', '日志占用（上限 5 MB）', '缓存占用', '配置与其他数据', '当前运行内存')
    deadline = time.monotonic() + 15
    while any(n.get('text') == '正在统计…' for n in tree.iter('node')):
        assert time.monotonic() < deadline
        time.sleep(.3); tree = nodes()
    parents = {c: p for p in tree.iter() for c in p}
    values = {}
    for label in labels:
        node = next(n for n in tree.iter('node') if n.get('text') == label)
        texts = [n.get('text', '') for n in parents[node].iter('node')]
        value = next(t for t in texts if re.search(r'\d', t) and t != label)
        assert value not in ('暂时无法统计', '正在统计…')
        values[label] = value
    assert re.search(r'[1-9]', values['当前运行内存']), '真实 AVD 的进程内存不应显示零'
    (output / 'usage.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    (output / 'result.json').write_text(json.dumps({'retained_log_bytes': total, 'displayed': values}, ensure_ascii=False, indent=2))
    print('AVD 日志总量≤5 MB、启动临时文件清理、数据／日志／缓存／其他／运行内存展示通过。')
finally:
    adb('shell', 'am', 'force-stop', package)
    if moved:
        private('rm -rf files/logs')
        if had_logs:
            private('mv files/logs-storage-backup files/logs')
    private('rm -rf cache/log-upload-snapshots/upload-storage-test')
    private('rm -f cache/app-updates/download-storage-test.partial cache/app-updates/download-storage-test.apk')
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity')
