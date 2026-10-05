#!/usr/bin/env python3
"""仅在 AVD 检查三车型监听、收到输入后标注与落盘；恢复偏好，不连接手机或上传。"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--models', nargs='+', choices=('l7','l6','custom'), default=['l7','l6','custom'])
parser.add_argument('--languages', nargs='+', choices=('zh','en'), default=['zh','en'])
parser.add_argument('--output-dir', type=Path, default=Path('build/previews/vehicle-steering'))
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
output = args.output_dir
output.mkdir(parents=True, exist_ok=True)


def adb(*parts, data=None):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], input=data, stderr=subprocess.PIPE)


def read(path):
    try:
        return adb('exec-out', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        if b'No such file' in error.stderr:
            return None
        raise


def write(path, data):
    assert re.fullmatch(r'(?:files|shared_prefs)/[a-z0-9_.-]+', path)
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {path}'", data=data)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/vehicle-steering.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/vehicle-steering.xml'))


def visible():
    return '\n'.join(n.get('text', '') for n in nodes().iter('node'))


def tap(label):
    for start, end in (('1500', '600'), ('600', '1500')):
        for _ in range(5):
            root = nodes()
            parents = {child: parent for parent in root.iter() for child in parent}
            for node in root.iter('node'):
                if label not in (node.get('text'), node.get('content-desc')):
                    continue
                while node.get('clickable') != 'true' and node.get('class') != 'android.widget.CheckedTextView' and node in parents:
                    node = parents[node]
                if (node.get('clickable') != 'true' and node.get('class') != 'android.widget.CheckedTextView') or node.get('enabled') != 'true':
                    continue
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(.2)
                return
            adb('shell', 'input', 'swipe', '1050', start, '1050', end, '180')
    raise AssertionError('入口不可达：' + label)


def launch():
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', 'settings-debug-steering')


paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_audio_templates.xml', 'shared_prefs/l7_agreement.xml',
         'files/audio-template.json', 'files/audio-template.json.bak',
         'files/audio-template-l6.json', 'files/audio-template-l6.json.bak',
         'files/audio-template-custom.json', 'files/audio-template-custom.json.bak')
original = {path: read(path) for path in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
labels = {
    'zh': ('当前状态', '启用监听日志', '停止监听', '右键', '忽略这组输入', '已收到按键信号', '正在监听，请按一个方控键'),
    'en': ('Current status', 'Start input logging', 'Stop listening', 'Right key', 'Ignore this input group', 'Control input received', 'Listening — press one steering control'),
}
try:
    # 合成协议与车型记录仅供 AVD 界面回归，结束逐字节恢复。
    digest = hashlib.sha256(Path('common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
    write('shared_prefs/l7_agreement.xml', f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
    for language in args.languages:
        status, start, stop, right, skip, received, waiting = labels[language]
        for model in args.models:
            adb('shell', 'am', 'force-stop', package)
            prefs = ET.fromstring(original['shared_prefs/diplay.xml'] or b'<map/>')
            for child in list(prefs):
                if child.get('name') in ('app_language', 'auto_connect'):
                    prefs.remove(child)
            ET.SubElement(prefs, 'string', {'name': 'app_language'}).text = language
            ET.SubElement(prefs, 'boolean', {'name': 'auto_connect', 'value': 'false'})
            write('shared_prefs/diplay.xml', ET.tostring(prefs, encoding='utf-8'))
            write('shared_prefs/l7_audio_templates.xml', f'<map><string name="model">{model}</string></map>'.encode())
            adb('shell', 'cmd', 'uimode', 'night', 'yes' if language == 'en' else 'no')
            launch()
            screen = visible()
            assert status in screen and start in screen
            assert ('L7' in screen if model == 'l7' else 'L6' in screen if model == 'l6' else ('自定义' if language == 'zh' else 'Custom') in screen)
            if language == 'en':
                assert not re.search(r'[\u3400-\u9fff]', screen), '英文页面存在中文'
            tap(start)
            assert waiting in visible() and received not in visible(), '尚无输入不能询问按键'
            adb('shell', 'am', 'broadcast', '-a', 'action_steering_wheel_controller_event', '-p', package, '--ei', 'type', '2')
            time.sleep(.6)
            assert received in visible(), '收到事件后应询问按键'
            (output / f'{language}-{model}-question.png').write_bytes(adb('exec-out', 'screencap', '-p'))
            tap(right)
            assert waiting in visible(), '标注后应该继续监听'
            log = (read('files/logs/steering.log') or b'').decode()
            tail = '\n'.join(log.splitlines()[-30:])
            assert f'model={model}' in tail and 'USER_LABEL' in tail and 'key=RIGHT' in tail
            assert 'vehicle-broadcast' in tail and 'OBSERVE_ONLY' in tail
            # 标注应附在 INPUT 的同一个 trace，而不是先写一个独立预期标记。
            annotated = [line for line in tail.splitlines() if 'stage=USER_LABEL' in line]
            assert annotated
            trace = re.search(r'trace=(\d+)', annotated[-1]).group(1)
            assert any(f'trace={trace} ' in line and 'stage=INPUT' in line for line in tail.splitlines())
            adb('shell', 'input', 'keyevent', '131')
            time.sleep(.6)
            assert received in visible()
            tap(skip)
            tap(stop)
            adb('shell', 'am', 'broadcast', '-a', 'action_steering_wheel_controller_event', '-p', package, '--ei', 'type', '2')
            time.sleep(.6)
            assert received not in visible() and start in visible(), '停止后不能再询问按键'
            (output / f'{language}-{model}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    print('AVD 三车型中英文、昼夜、启用监听后收到输入再标注、同 trace 绑定、忽略／停止与日志落盘通过；没有建立手机连接或上传')
finally:
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        write(path, data)
    adb('shell', 'cmd', 'uimode', 'night', night)
    assert all(read(path) == data for path, data in original.items())
    print('原偏好、车型文件、协议及昼夜设置已恢复')
