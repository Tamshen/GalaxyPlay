#!/usr/bin/env python3
"""仅在 AVD 验证分步提示、缺失记录、进度恢复与中英文昼夜；不连接手机、不上传。"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--cases', nargs='+', choices=['zh-day','zh-night','en-day','en-night'], default=['zh-day','zh-night','en-day','en-night'])
parser.add_argument('--pages', nargs='+', choices=['steering','voice','media','navigation','codec','scenario'], default=['steering','voice','media','navigation','codec','scenario'])
parser.add_argument('--layout-only', action='store_true')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), '仅允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
out = Path('build/previews/debug-guide')
out.mkdir(parents=True, exist_ok=True)

def adb(*parts, data=None):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE)

def read(path):
    try:
        return adb('exec-out', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        if b'No such file' in error.stderr:
            return None
        raise

def write(path, data):
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        adb('shell', 'run-as', package, 'sh', '-c', "'cat > " + path + "'", data=data)

node_cache = None

def nodes():
    global node_cache
    if node_cache is not None:
        return node_cache
    for _ in range(3):
        response = adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-guide.xml')
        if b'dumped to' not in response:
            continue
        raw = adb('exec-out', 'cat', '/sdcard/galaxy-guide.xml')
        if raw.lstrip().startswith(b'<?xml'):
            node_cache = list(ET.fromstring(raw).iter('node'))
            return node_cache
        time.sleep(.5)
    screenshot('failure.png')
    raise AssertionError('界面未产生稳定的节点树')

def tap(text):
    global node_cache
    import re
    for start, end in [(650, 1200), (1450, 900)]:
        for _ in range(8):
            for n in nodes():
                if n.get('text') == text or n.get('content-desc') == text:
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                    if x2 <= x1 or y2 <= y1:
                        continue
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    node_cache = None
                    time.sleep(.35)
                    return
            adb('shell', 'input', 'swipe', '1100', str(start), '1100', str(end), '420')
            node_cache = None
            time.sleep(.3)
    screenshot('failure.png')
    raise AssertionError('没有找到控件：' + text)

def has(text):
    return any(text in n.get('text', '') for n in nodes())

def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p'))

def launch(page):
    global node_cache
    node_cache = None
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)
    time.sleep(.6)

paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_agreement.xml')
original = {p: read(p) for p in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
try:
    for lang in ['zh', 'en']:
        if not any(case.startswith(lang + '-') for case in args.cases):
            continue
        adb('shell', 'am', 'force-stop', package)
        prefs = ET.fromstring(original[paths[0]] or b'<map/>')
        for item in list(prefs):
            if item.get('name') in ('app_language', 'auto_connect'):
                prefs.remove(item)
        ET.SubElement(prefs, 'string', {'name':'app_language'}).text = lang
        ET.SubElement(prefs, 'boolean', {'name':'auto_connect', 'value':'false'})
        write(paths[0], ET.tostring(prefs))
        digest = hashlib.sha256(Path('galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
        write(paths[1], f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
        begin, next_, locate, missing, hide, back = (('开始分步引导', '已完成，下一步', '去操作／开始观察', '无法完成，记录并跳过', '收起提示', '返回调试') if lang == 'zh' else ('Start guided test', 'Done · next step', 'Locate control / observe', 'Record missing · skip', 'Hide instructions', 'Back to Debug'))
        for mode in ['no', 'yes']:
            if lang + '-' + ('day' if mode == 'no' else 'night') not in args.cases:
                continue
            adb('shell', 'cmd', 'uimode', 'night', mode)
            for page in args.pages:
                launch('settings-debug-' + page)
                assert has(begin)
                tap(begin)
                screenshot(f'{lang}-{mode}-{page}-prepare.png')
                if args.layout_only:
                    tap(hide)
                    adb('shell', 'am', 'force-stop', package)
                    print('布局完成', lang, mode, page, flush=True)
                    continue
                tap(next_)
                assert has(locate)
                screenshot(f'{lang}-{mode}-{page}-step2.png')
                tap(locate)
                screenshot(f'{lang}-{mode}-{page}-focus.png')
                if page == 'scenario':
                    tap('记录操作已执行' if lang == 'zh' else 'Record action performed')
                    tap('未出现／异常' if lang == 'zh' else 'Missing / abnormal')
                    tap(next_)
                else:
                    tap('查看本步提示／定位控件' if lang == 'zh' else 'Show instructions / locate control')
                    tap(missing)
                screenshot(f'{lang}-{mode}-{page}-next.png')
                tap(hide)
                # 返回后只恢复交互进度，不自动开始测试。
                tap(back)
                launch('settings-debug-' + page)
                assert not has(begin), '返回后引导进度丢失'
                screenshot(f'{lang}-{mode}-{page}-restored.png')
                adb('shell', 'am', 'force-stop', package)
                print('完成', lang, mode, page, flush=True)
    trace = (read('files/logs/debug.log') or b'').decode(errors='replace')
    assert 'DEBUG_GUIDE' in trace
    if not args.layout_only:
        assert 'phase=MISSING' in trace
    if 'scenario' in args.pages and not args.layout_only:
        assert 'DEBUG_SCENARIO' in trace and 'MISSING_OR_ABNORMAL' in trace
    print('GUIDE_AVD_OK:', ','.join(args.pages), ','.join(args.cases), '定位、缺失及返回进度')
finally:
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        write(path, data)
    adb('shell', 'cmd', 'uimode', 'night', night)
    adb('shell', 'rm', '-f', '/sdcard/galaxy-guide.xml')
