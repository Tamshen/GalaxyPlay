#!/usr/bin/env python3
"""AVD 完整调试交互检查；只记录缺失，不伪造实车确认、不上传或清空日志。"""
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
output = Path('build/previews/full-debug')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], stderr=subprocess.DEVNULL)


def launch():
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity',
        '--es', 'page', 'settings-debug')


def snapshot():
    adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-full-debug.xml')
    value = adb('shell', 'cat', '/sdcard/galaxy-full-debug.xml')
    return ET.fromstring(value)


def find(root, text):
    return next((node for node in root.iter('node') if node.get('text') == text), None)


def tap(node):
    assert node is not None and node.get('enabled') == 'true', '控件不可用'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert y2 > y1 and x2 > x1, '控件位于屏幕外'
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def save(name, root):
    ET.ElementTree(root).write(output / (name + '.xml'), encoding='utf-8')
    (output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def wait_for(text, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = snapshot()
        if find(root, text) is not None:
            return root
        time.sleep(0.2)
    raise AssertionError('等待超时：' + text)


launch()
home = wait_for('开始完整调试')
assert find(home, '单项与高级调试') is not None
assert find(home, '语音输入测试') is None, '旧单项入口应默认折叠'
save('home', home)
tap(find(home, '开始完整调试'))
deadline = time.monotonic() + 300
retried = False
questions = 0
finished = None
while time.monotonic() < deadline:
    root = snapshot()
    if find(root, '检查完成，日志已记录') is not None:
        finished = root
        break
    assert find(root, '检查已停止') is None, '流程意外停止'
    missing = find(root, '没有或异常')
    if missing is None:
        time.sleep(0.2)
        continue
    confirm = find(root, '确认正常')
    retry = find(root, '再试一次')
    assert confirm is not None and retry is not None, '人工判断须有三种操作'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', missing.get('bounds')))
    assert y2 - y1 >= 64, '按钮触控区太小'
    unavailable = any('没有报告可用的硬件视频解码器' in n.get('text', '') for n in root.iter('node'))
    if unavailable:
        assert confirm.get('enabled') == 'false', '没有硬件证据不能确认正常'
    if unavailable and not retried:
        save('hardware-question', root)
        tap(retry)
        retried = True
    else:
        questions += 1
        tap(missing)
    time.sleep(0.35)
assert finished is not None, '完整流程未结束'
save('finished', finished)

# 只核对本次合成 AVD 流程的阶段，不输出其他设备信息或原日志内容。
lines = []
for name in ('debug-previous-2.log', 'debug-previous.log', 'debug.log'):
    try:
        lines.extend(adb('shell', 'run-as', package, 'cat', 'files/logs/' + name).decode().splitlines())
    except subprocess.CalledProcessError:
        pass
events = [line[line.index('DEBUG_SUITE '):] for line in lines if 'DEBUG_SUITE ' in line]
done = next(line for line in reversed(events) if 'stage=FINISHED ' in line)
run = re.search(r'run=(\d+)', done).group(1)
events = [line for line in events if 'run=' + run + ' ' in line]
assert any('stage=AUTOMATIC_COMPLETED ' in line and 'check=ENVIRONMENT ' in line for line in events)
assert not any('stage=USER_CONFIRMED ' in line for line in events), '脚本不能制造人工通过'
if retried:
    assert any('check=CODEC_UNAVAILABLE ' in line and 'stage=USER_RETRY ' in line for line in events)
    assert sum('check=ENVIRONMENT ' in line and 'stage=CHECK_START ' in line for line in events) == 1
assert any('check=STEERING_' in line for line in events) and any('check=SIRI ' in line for line in events)
(output / 'suite-events.txt').write_text('\n'.join(events) + '\n')

# 完成后重复开始与主动停止必须可用，不依赖已隐藏的预览 Surface。
tap(find(finished, '开始完整调试'))
running = snapshot()
# 快速环境检查可能已进入提问，模态框覆盖正文；返回也必须停止整轮。
if find(running, '没有或异常') is not None:
    adb('shell', 'input', 'keyevent', '4')
else:
    tap(find(wait_for('停止调试'), '停止调试'))
stopped = wait_for('检查已停止')
assert find(stopped, '开始完整调试').get('enabled') == 'true'
save('stopped', stopped)
launch()
print('完整调试 AVD 检查通过；人工缺失记录 %d 次，重试 %s，完成后重启与停止通过。' % (questions, retried))
