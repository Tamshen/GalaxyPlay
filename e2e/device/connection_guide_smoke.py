#!/usr/bin/env python3
"""AVD 验证独立连接引导与强错误提示；临时拒绝热点权限，不开启热点、不连接手机。"""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--english-only', action='store_true', help='仅复核英文引导与热点失败弹窗')
parser.add_argument('--wifi-only', action='store_true', help='仅复核独立 Wi-Fi 入口和读取失败恢复，不启停热点')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/connection-guide')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)


def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-connection-guide.xml')
    assert b'dumped to:' in result
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-connection-guide.xml'))


def texts():
    return [n.get('text', '') for n in nodes().iter('node')]


def launch(page):
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
        package+'/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)


def tap(label):
    for start, end in ((1600, 650), (650, 1600)):
        for _ in range(5):
            root = nodes()
            parents = {child: parent for parent in root.iter() for child in parent}
            for node in root.iter('node'):
                if label not in (node.get('text'), node.get('content-desc')):
                    continue
                while node.get('clickable') != 'true' and node.get('checkable') != 'true' and node in parents:
                    node = parents[node]
                if (node.get('clickable') == 'true' or node.get('checkable') == 'true') and node.get('enabled') == 'true':
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    print('操作：'+label, flush=True)
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    return
            adb('shell', 'input', 'swipe', '1050', str(start), '1050', str(end), '200')
    raise AssertionError('入口不可达：'+label)


def wait(label):
    for _ in range(6):
        if label in texts(): return
    raise AssertionError('未出现提示：'+label)


def screenshot(name):
    (output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def language(english):
    launch('settings-general')
    tap('应用语言' if english else 'App language')
    tap('English' if english else '简体中文')
    tap('应用' if english else 'Apply')


launch('settings-connection')
assert '选择连接方式' in texts(), '请使用中文 AVD，并先同意协议'
permission = adb('shell', 'cmd', 'appops', 'get', package, 'WRITE_SETTINGS').decode()
match = re.search(r'WRITE_SETTINGS: (allow|deny|ignore|default|foreground)', permission)
original_permission = match.group(1) if match else 'default'
original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
english = False
try:
    adb('shell', 'cmd', 'appops', 'set', package, 'WRITE_SETTINGS', 'deny')
    if args.wifi_only:
        launch('settings-connection-wireless')
        tap('原生 Wi-Fi 设置')
        assert 'com.android.settings' in adb('shell', 'dumpsys', 'activity', 'top').decode()
        screenshot('wifi-settings')
        adb('shell', 'input', 'keyevent', '4')
        launch('settings-connection-wireless')
        tap('重新读取热点配置'); wait('未能读取热点配置')
        assert any('读取与设置权限不同' in t for t in texts())
        screenshot('read-failure-wifi')
        tap('原生 Wi-Fi 设置')
        assert 'com.android.settings' in adb('shell', 'dumpsys', 'activity', 'top').decode()
        adb('shell', 'input', 'keyevent', '4')
        launch('settings-connection-wireless')
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        tap('重新读取热点配置'); wait('未能读取热点配置')
        screenshot('read-failure-wifi-night'); tap('关闭')
        print('AVD 通过：独立 Wi-Fi 设置与读取失败恢复入口，昼夜提示；未启停热点、未修改配置。')
        raise SystemExit(0)
    if not args.english_only:
        assert {'无线连接', '有线连接'} <= set(texts())
        assert '一键开启原生热点' not in texts()
        screenshot('methods-day')
        tap('无线连接')
        for _ in range(3):
            if any(n.get('content-desc') == '返回连接方式' for n in nodes().iter('node')): break
        for _ in range(3): adb('shell', 'input', 'swipe', '1050', '600', '1050', '1600', '200')
        wait('无线连接 · 3 步')
        assert '前置确认 · 蓝牙与 iPhone 配对' in texts()
        assert '有线连接' not in texts()
        screenshot('wireless-step1-day')
        tap('一键开启原生热点')
        wait('未能确认热点开启')
        assert any('需要允许本应用修改系统设置' in t for t in texts())
        assert '允许修改系统设置' in texts()
        screenshot('hotspot-failure-day')
        assert '未能确认热点开启' in texts(), '结果提示过早消失'
        tap('关闭')
        tap('重新读取热点配置')
        wait('未能读取热点配置')
        screenshot('read-failure-day')
        tap('关闭')
        tap('生成 CarPlay 热点配置'); tap('取消')
        tap('无线连接')
        wait('前置确认 · 蓝牙与 iPhone 配对')
        assert '正在尝试开启原生热点，请稍候…' not in texts()
        screenshot('bluetooth-prerequisite'); tap('关闭')
        tap('返回连接方式')
        assert '选择连接方式' in texts()
        tap('有线连接')
        assert '有线连接 · 3 步' in texts()
        assert '一键开启原生热点' not in texts() and '蓝牙设置' not in texts()
        screenshot('usb-day')
        tap('有线连接')
        wait('USB 尚未就绪')
        assert any('不支持：' in t or '尚未识别到 Apple USB' in t for t in texts())
        screenshot('usb-not-ready')
        tap('关闭')
        adb('shell', 'input', 'keyevent', '4')
        assert '选择连接方式' in texts()
        adb('shell', 'input', 'keyevent', '4')
        assert 'CarPlay 认证' in texts()
        launch('home'); tap('有线连接')
        wait('有线连接 · 3 步')
        launch('home'); tap('无线连接')
        assert any(n.get('content-desc') == '返回连接方式' for n in nodes().iter('node'))
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        nodes(); screenshot('wireless-night')
        tap('一键开启原生热点'); wait('未能确认热点开启')
        screenshot('hotspot-failure-night'); tap('关闭')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    language(True); english = True
    for page in ('settings-connection', 'settings-connection-wireless', 'settings-connection-usb'):
        launch(page)
        for _ in range(3):
            assert not any(re.search(r'[\u3400-\u9fff]', t) for t in texts()), '英文页面含中文'
            screenshot(page+'-en-night-'+str(_))
            adb('shell', 'input', 'swipe', '1050', '1600', '1050', '650', '200')
    launch('settings-connection-wireless'); tap('Start native hotspot')
    wait('Hotspot activation not confirmed')
    screenshot('hotspot-failure-en-night'); tap('Close')
    print('AVD 通过：'+('英文引导与失败弹窗' if args.english_only else '无线/有线独立入口、三步引导、蓝牙前置、多级返回、热点开启/读取强错误提示、USB 未就绪提示、中英文昼夜')+'；未开启热点或建立连接。')
except Exception:
    screenshot('failure')
    raise
finally:
    if english: language(False)
    adb('shell', 'cmd', 'appops', 'set', package, 'WRITE_SETTINGS', original_permission)
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    launch('settings-connection')
