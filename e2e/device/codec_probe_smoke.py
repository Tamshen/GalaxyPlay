#!/usr/bin/env python3
"""仅在 AVD 检查真实 MediaCodec／JNI 样例调用；软件结果不代表车机硬件通过。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--cases', nargs='+', choices=['zh-day','zh-night','en-day','en-night'], default=['zh-day','zh-night','en-day','en-night'])
parser.add_argument('--output-dir', type=Path, default=Path('build/e2e/avd-0.1.76/codec-probe'))
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
args.output_dir.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]

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
    assert re.fullmatch(r'shared_prefs/[a-z0-9_.-]+', path)
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {path}'", data=data)

def nodes():
    adb('shell', 'rm', '-f', '/sdcard/codec-probe.xml')
    adb('shell', 'uiautomator', 'dump', '/sdcard/codec-probe.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/codec-probe.xml'))

def row(label, root=None):
    root = root if root is not None else nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if label not in (node.get('text'), node.get('content-desc')):
            continue
        while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
            node = parents[node]
        if node.get('clickable') == 'true' or node.get('checkable') == 'true':
            return node
    return None

def tap_node(node):
    assert node is not None and node.get('enabled') == 'true', '动作未启用'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def find(label):
    for start, end in ((1550, 1050), (650, 1150)):
        for _ in range(10):
            node = row(label)
            if node is not None:
                return node
            adb('shell', 'input', 'swipe', '1080', str(start), '1080', str(end), '250')
    raise AssertionError('找不到：' + label)

def tap(label):
    tap_node(find(label)); time.sleep(.15)

def launch(page='settings-debug-codec'):
    adb('shell', 'am', 'start', '-W', '-n', package+'/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)
    time.sleep(.4)

def logs():
    data = b'\n'.join(read('files/logs/'+name) or b'' for name in ('debug-previous-2.log','debug-previous.log','debug.log'))
    return '\n'.join(line for line in data.decode(errors='replace').splitlines() if 'CODEC_PROBE ' in line)

def fresh(text, before):
    previous = set(re.findall(r'\brun=(\d+)', before))
    return '\n'.join(line for line in text.splitlines()
                     if (match := re.search(r'\brun=(\d+)', line)) and match.group(1) not in previous)

def results(text):
    return [line for line in text.splitlines() if 'processExited=' in line and 'hardwarePassed=' in line]

def wait_results(count, before, timeout=35):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        current = results(fresh(logs(), before))
        if len(current) >= count:
            return current
        time.sleep(.3)
    raise AssertionError('未收到全部结果：'+fresh(logs(), before))

def capture(name):
    (args.output_dir/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_agreement.xml')
original = {p: read(p) for p in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
report = []
try:
    for language in ('zh', 'en'):
        if not any(case.startswith(language+'-') for case in args.cases):
            continue
        adb('shell', 'am', 'force-stop', package)
        prefs = ET.fromstring(original[paths[0]] or b'<map/>')
        for child in list(prefs):
            if child.get('name') in ('app_language', 'auto_connect'):
                prefs.remove(child)
        ET.SubElement(prefs, 'string', {'name':'app_language'}).text=language
        ET.SubElement(prefs, 'boolean', {'name':'auto_connect','value':'false'})
        write(paths[0], ET.tostring(prefs))
        digest = hashlib.sha256(Path('galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
        write(paths[1], f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
        names = ('硬件解码测试','允许软件对照','开始所选方式','依次测试全部方式','视频格式','应用','我看到了动态画面','返回调试','结束测试') if language=='zh' else ('Hardware decoder test','Allow software comparison','Start selected path','Test all paths in sequence','Video format','Apply','I saw a moving picture','Back to Debug','End test')
        title, software, start, all_paths, sample, apply, observe, back, stop = names
        for theme, value in (('day','no'), ('night','yes')):
            if language+'-'+theme not in args.cases:
                continue
            adb('shell','am','force-stop',package)
            adb('shell','cmd','uimode','night',value); launch('settings-debug'); tap(title)
            baseline = logs(); assert not row(stop) or row(stop).get('enabled')=='false'
            assert 'c2.android' not in ' '.join(n.get('text','') for n in nodes().iter('node')), '默认暴露了软件候选'
            capture(language+'-'+theme+'-hardware-default')
            assert not fresh(logs(),baseline), '打开页面自动启动了解码'
            tap(software)
            # 四种语言／主题均真实执行五种 API，显式的软件对照不作为硬件验收。
            before=logs(); tap(all_paths)
            if language == "zh" and theme == "day":
                time.sleep(1); capture("zh-day-frame-a")
                time.sleep(.6); capture("zh-day-frame-b")
            lines=wait_results(5,before)
            for method in ('JAVA_TYPE','JAVA_NAME','JAVA_ASYNC','NDK','JAVA_TUNED'):
                line=next(line for line in lines if 'method='+method+' ' in line)
                assert 'outputs=60 ' in line and 'eos=true ' in line and 'released=true ' in line, line
                assert 'hardwarePassed=false' in line, line
                assert 'software=true ' in line, line
            report.extend(lines)
            assert 'GalaxyCodecProbeService' not in adb('shell','dumpsys','activity','services',package).decode(), '测试服务仍绑定'
            tap(observe); time.sleep(.4)
            assert 'observation=VISIBLE_MOTION origin=USER' in fresh(logs(), before)
            capture(language+'-'+theme+'-results')
            tap(back)
            assert row(title) is not None, '未返回调试入口'
        # HEVC 也必须真实经过 Java 与 NDK，而非只读取能力列表。
        adb('shell','am','force-stop',package)
        launch(); tap(software); tap(sample); tap('HEVC'); tap(apply)
        before=logs(); tap(all_paths); lines=wait_results(5,before)
        for line in lines:
            assert 'video=HEVC ' in line and 'hardwarePassed=false' in line, line
            assert 'released=true ' in line or 'processExited=true' in line or 'workerStarted=false ' in line, line
            assert 'software=true ' in line or 'workerStarted=false ' in line, line
            assert 'outputs=60 ' in line or re.search(r'reason=(ProbeTimeout|NDK_STATUS|TIMEOUT) ',line), line
        report.extend(lines)
        # 主动后台立即停止，不等待 codec 的正常完成，也不自动续跑。
        before=logs(); tap(all_paths)
        deadline=time.monotonic()+8
        while 'stage=FEED' not in fresh(logs(),before) and time.monotonic()<deadline:
            time.sleep(.05)
        assert 'stage=FEED' in fresh(logs(),before), '后台测试必须先进入真实解码'
        adb('shell','input','keyevent','3')
        line=wait_results(1,before)[0]
        assert 'reason=BACKGROUND ' in line or 'reason=SURFACE_LOST ' in line, line
        time.sleep(1); assert len(results(fresh(logs(), before)))==1, '后台仍继续执行其他方法'
        assert package+':codec_probe' not in adb('shell','ps','-A','-o','NAME').decode()
        report.append(line); launch()
    (args.output_dir/'summary.json').write_text(json.dumps({'scope':'AVD software comparison; no vehicle hardware pass','results':report},ensure_ascii=False,indent=2))
    print('通过：'+', '.join(args.cases)+' 的 H.264 五路径、所选语言 HEVC 五路径、手动标注与后台取消；没有硬件通过结论。')
except Exception:
    capture('failure')
    (args.output_dir/'failure.xml').write_text(ET.tostring(nodes(), encoding='unicode'))
    (args.output_dir/'probe.log').write_text(logs())
    raise
finally:
    adb('shell','am','force-stop',package)
    for path,data in original.items(): write(path,data)
    adb('shell','cmd','uimode','night',night)
    launch('settings-debug')
