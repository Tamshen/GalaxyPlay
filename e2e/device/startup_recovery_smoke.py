#!/usr/bin/env python3
"""仅在 AVD 模拟连续进程崩溃，检查自动连接熔断与 USB 直达拦截；结束恢复原偏好。"""
import argparse
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
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
prefs_path = 'shared_prefs/diplay.xml'
activity = package+'/com.shilapi.xcertplay.DiPlayActivity'
output = Path('build/previews/startup-recovery')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)


def read_prefs():
    return adb('exec-out', 'run-as', package, 'cat', prefs_path)


def values(data):
    return {n.get('name'): (n.tag, n.get('value') if n.get('value') is not None else n.text)
            for n in ET.fromstring(data)}


def write_prefs(data):
    command = 'run-as '+package+' sh -c '+shlex.quote('cat > '+prefs_path)
    subprocess.run(base+['shell', command], input=data, check=True, capture_output=True)


def launch(page=None):
    extra = ['--es', 'page', page] if page else []
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', activity, *extra)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-startup-recovery.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-startup-recovery.xml'))


def texts():
    return {n.get('text', '') for n in nodes().iter('node')}


def tap(label):
    root = nodes()
    parents = {c: p for p in root.iter() for c in p}
    for n in root.iter('node'):
        if n.get('text') != label:
            continue
        while n.get('clickable') != 'true' and n in parents:
            n = parents[n]
        assert n.get('clickable') == 'true'
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        return
    raise AssertionError('入口不可达：'+label)


launch('settings-general')
assert '通用设置' in texts(), '请先在中文 AVD 完成协议确认'
adb('shell', 'am', 'force-stop', package)
backup = read_prefs()
try:
    root = ET.fromstring(backup)
    for n in list(root):
        if n.get('name', '').startswith('recovery_') or n.get('name') == 'auto_connect':
            root.remove(n)
    ET.SubElement(root, 'boolean', name='auto_connect', value='true')
    write_prefs(ET.tostring(root))
    for attempt in range(3):
        # 显式设置路由跳过实际连接；Application 仍须覆盖构造期与进程级异常。
        launch('settings-general')
        state = values(read_prefs())
        assert state['recovery_failures'][1] == str(attempt)
        assert state['auto_connect'][1] == 'true'
        pid = adb('shell', 'pidof', package).decode().strip()
        adb('shell', 'am', 'crash', pid)
        for poll in range(40):
            result = subprocess.run(base+['shell', 'pidof', package], capture_output=True)
            if result.stdout.decode().strip() != pid:
                break
            # AVD 的系统崩溃确认框可能阻塞 Android 原处理器；落盘后只结束故障进程。
            if poll == 10 and values(read_prefs()).get('recovery_crashed', (None, None))[1] == 'true':
                adb('shell', 'am', 'force-stop', package)
            time.sleep(0.2)
        else:
            raise AssertionError('模拟崩溃未结束进程')
        print(f'已模拟第 {attempt+1} 次异常退出', flush=True)
    launch()
    state = values(read_prefs())
    assert state['recovery_failures'][1] == '3'
    assert state['auto_connect'][1] == 'false'
    assert state['recovery_blocked'][1] == 'true'
    assert '已暂停自动连接' in texts()
    (output/'recovery-notice.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('关闭')
    for k, value in values(backup).items():
        if k != 'auto_connect' and not k.startswith('recovery_'):
            assert state[k] == value, '非保护配置被修改'
    launch('settings-general')
    assert '通用设置' in texts()
    (output/'settings-accessible.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    # 模拟系统直接拉起 USB 宿主；不得绕开保护进入连接等待或再次崩溃。
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-W', '-a', 'android.hardware.usb.action.USB_DEVICE_ATTACHED',
        '-n', package+'/com.shilapi.xcertplay.CarPlayHostActivity')
    resumed = adb('shell', 'dumpsys', 'activity', 'activities').decode()
    assert re.search(r'mResumedActivity:.*com.shilapi.xcertplay.DiPlayActivity', resumed)
    assert values(read_prefs())['auto_connect'][1] == 'false'
    print('AVD 通过：第三次崩溃后关闭自动连接、设置可访问、USB 直达受保护、原配置保留。')
finally:
    adb('shell', 'am', 'force-stop', package)
    write_prefs(backup)
    launch('settings-general')
