#!/usr/bin/env python3
"""仅在 AVD 验证协议闸门、主动确认与撤回；结束时停留未同意页面，不清除连接配置。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--confirmation-only', action='store_true', help='只检查阅读确认、设置图标及撤回，不建立 USB 等待会话')
parser.add_argument('--output-dir', type=Path, help='本轮截图目录，保留旧版本证据')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许模拟器，不代实车用户同意协议')
out = args.output_dir or Path(__file__).resolve().parents[2] / 'build/previews/agreement'
out.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'
activity = 'com.shilapi.xcertplay.'


def adb(*words):
    return subprocess.run([args.adb, '-s', args.serial, *words], check=True, capture_output=True).stdout.decode()


def nodes():
    for _ in range(3):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-agreement.xml')
        if 'dumped to' in result:
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-agreement.xml')).iter('node'))
    raise AssertionError('无法读取协议界面')


def find(label):
    current = nodes()
    matches = [n for n in current if n.attrib.get('text') == label or n.attrib.get('content-desc') == label]
    # 浮动菜单覆盖首页同名按钮，优先可点击的导航描述；卡片则选标题之后的操作行。
    return next((n for n in matches if n.attrib.get('content-desc') == label and n.attrib.get('clickable') == 'true'),
                next((n for n in matches if n.attrib.get('clickable') == 'true'), matches[-1] if matches else None))


def tap_node(node):
    assert node is not None, '没有找到操作入口'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label):
    for _ in range(6):
        node = find(label)
        if node is not None:
            tap_node(node)
            time.sleep(.25)
            return
        # 320 DPI 下设置分类需要滚动；限定正文区域，避开固定菜单。
        adb('shell', 'input', 'swipe', '1000', '1400', '1000', '500', '250')
    raise AssertionError('没有找到操作入口：' + label)


def launch(target='DiPlayActivity', page='home', usb=False):
    command = ['shell', 'am', 'start', '-W', '-f', '0x00020000', '-n', package + '/' + activity + target]
    if usb:
        command += ['-a', 'android.hardware.usb.action.USB_DEVICE_ATTACHED']
    else:
        command += ['--es', 'page', page]
    adb(*command)


def screenshot(name):
    image = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], check=True, capture_output=True)
    (out / (name + '.png')).write_bytes(image.stdout)


def no_services():
    services = adb('shell', 'dumpsys', 'activity', 'services', package)
    assert not re.search(r'ServiceRecord\{[^\n]*(DiPlaySessionService|CarPlayVpnService|L7DebugOverlayService|L7DesktopNavigationService)', services), services


def checkbox():
    return next(n for n in nodes() if n.attrib['class'] == 'android.widget.CheckBox')


def gate():
    assert find('同意并进入') is not None, '没有显示协议闸门'
    assert find('有线连接') is None, '未同意时显示了功能入口'
    no_services()


def accept():
    assert checkbox().attrib['checked'] == 'false', '协议被预先勾选'
    assert find('同意并进入').attrib['enabled'] == 'false'
    for _ in range(35):
        if checkbox().attrib['enabled'] == 'true':
            break
        scroll = next(n for n in nodes() if n.attrib['class'] == 'android.widget.ScrollView')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.attrib['bounds']))
        adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str(y2 - 40),
            str((x1 + x2) // 2), str(y1 + 40), '200')
    assert checkbox().attrib['enabled'] == 'true', '阅读到底并等待后仍无法确认'
    assert find('同意并进入').attrib['enabled'] == 'false', '阅读到底自动同意'
    tap_node(checkbox())
    assert find('同意并进入').attrib['enabled'] == 'true'
    screenshot('read-and-checked')
    tap('同意并进入')
    assert find('有线连接') is not None
    screenshot('home-buttons-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    nodes()
    screenshot('home-buttons-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')


def open_details():
    # 等待会话持有 singleTask 宿主，走真实菜单，避免 am start 的任务恢复语义留在投屏页。
    if find('展开菜单') is not None:
        tap('展开菜单')
    tap('设置')
    screenshot('settings-menu')
    tap('关于')
    screenshot('about-entry')
    tap('使用协议')
    assert find('撤回同意') is not None
    screenshot('agreement-details')


original_night = adb('shell', 'cmd', 'uimode', 'night').strip().split(':')[-1].strip()
try:
    launch(page='settings-about')
    if find('同意并进入') is None:
        open_details()
        tap('撤回同意')
        tap('确认撤回并停止使用')
    gate()
    assert checkbox().attrib['enabled'] == 'false'
    screenshot('first-use-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    nodes()
    screenshot('first-use-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    if not args.confirmation_only:
        tap('不同意并退出')
        time.sleep(.8)
        assert 'mResumedActivity' not in '\n'.join(line for line in adb('shell', 'dumpsys', 'activity', 'activities').splitlines()
                                                  if package in line), '拒绝后仍停留应用'
        launch('CarPlayHostActivity', usb=True)
        gate()
        print('首次进入、拒绝退出、USB 入口拦截通过', flush=True)
    accept()
    print('阅读等待、主动勾选并进入首页通过；精确 5 秒边界由组件测试验证', flush=True)
    if not args.confirmation_only:
        adb('shell', 'am', 'force-stop', package)
        launch()
        assert find('有线连接') is not None, '重新打开丢失同意记录'
        tap('有线连接')
        time.sleep(1)
        assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package), '未建立 USB 等待服务'
        screenshot('usb-buttons-day')
        adb('shell', 'cmd', 'uimode', 'night', 'yes')
        nodes()
        screenshot('usb-buttons-night')
        adb('shell', 'cmd', 'uimode', 'night', 'no')
    open_details()
    if not args.confirmation_only:
        assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package), '查看协议中断了连接'
    tap('撤回同意')
    screenshot('revoke-confirmation')
    tap('取消')
    assert find('撤回同意') is not None
    if not args.confirmation_only:
        assert 'DiPlaySessionService' in adb('shell', 'dumpsys', 'activity', 'services', package), '取消撤回中断了连接'
    tap('撤回同意')
    tap('确认撤回并停止使用')
    gate()
    adb('shell', 'am', 'force-stop', package)
    launch(page='settings')
    gate()
    launch('CarPlayHostActivity', usb=True)
    gate()
    print('设置查看、取消/确认撤回、重启及直达再次拦截通过', flush=True)
    if not args.confirmation_only:
        print('确认重启保持，USB 等待服务在查看/取消时保留、撤回后停止', flush=True)
    screenshot('revoked')
finally:
    adb('shell', 'cmd', 'uimode', 'night', original_night)

print('协议 AVD 检查通过；当前保持未同意，未清除其他配置。', flush=True)
