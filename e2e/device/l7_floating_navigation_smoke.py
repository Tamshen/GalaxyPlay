#!/usr/bin/env python3
"""仅在 AVD 检查浮动侧栏、拖动、透明度与完整退出；不模拟真实手机连接。"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path, help='本轮截图目录，保留旧版本证据')
parser.add_argument('--home-only', action='store_true', help='只检查首页居中和悬浮菜单，不建立等待会话')
parser.add_argument('--settings-only', action='store_true', help='只检查设置双栏、固定 Header、滚动及昼夜切换')
parser.add_argument('--projection-only', action='store_true', help='跳过已验证的首页交互，仅继续投屏菜单与设置往返检查')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许模拟器，退出检查不操作实车')
root = Path(__file__).resolve().parents[2]
out = args.output_dir or root / 'build/previews/floating-navigation'
out.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'

def adb(*words, check=True):
    return subprocess.run([args.adb, '-s', args.serial, *words], check=check, capture_output=True).stdout.decode()

def nodes():
    for _ in range(3):
        adb('shell', 'rm', '-f', '/sdcard/l7-floating.xml')
        if 'dumped to' in adb('shell', 'uiautomator', 'dump', '/sdcard/l7-floating.xml'):
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-floating.xml')).iter('node'))
        time.sleep(.4)
    raise AssertionError('无法读取界面')

def bounds(node):
    return list(map(int, re.findall(r'\d+', node.attrib['bounds'])))

def find(label):
    current = nodes()
    # 菜单在正文之上，优先匹配可点击入口，避免点到正文中的同名标题。
    return next((n for n in current if n.attrib.get('content-desc') == label and n.attrib.get('clickable') == 'true'),
                next((n for n in current if label in (n.attrib.get('text'), n.attrib.get('content-desc'))), None))

def tap(label):
    for start, end in ((1500, 600), (600, 1500)):
        for _ in range(4):
            node = find(label)
            if node is not None:
                x1, y1, x2, y2 = bounds(node)
                if label in ('画面', '连接', '断开连接', '设置', '车机', '退出'):
                    print(f'点击导航：{label}，入口={node.attrib.get("content-desc") or node.attrib.get("text")}，坐标={node.attrib["bounds"]}', flush=True)
                adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(.3)
                return
            adb('shell', 'input', 'swipe', '1000', str(start), '1000', str(end), '300')
    raise AssertionError('未找到：' + label)

def screenshot(name):
    result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], check=True, capture_output=True)
    (out / (name + '.png')).write_bytes(result.stdout)

def menu_bounds():
    return [(n.attrib['content-desc'], bounds(n)) for n in nodes()
            if n.attrib.get('clickable') == 'true' and n.attrib.get('content-desc') in ('画面', '连接', '断开连接', '设置', '车机', '退出')]

def menu_labels():
    expected = ['画面', '连接', '设置', '车机', '退出']
    # 返回键交给 Activity 后再等布局完成，不重复发键或把动画中的空树当最终结果。
    actual = []
    for _ in range(4):
        actual = [n.get('content-desc') for n in nodes()
                  if n.get('clickable') == 'true' and n.get('content-desc')]
        if actual == expected:
            return actual
        time.sleep(.5)
    return actual

def require_menu(expected, message):
    # Activity 切换时允许等待窗口完成交接，最终仍逐项比较完整坐标，不放宽位置误差。
    for _ in range(3):
        actual = menu_bounds()
        if actual == expected:
            return
        time.sleep(.4)
    raise AssertionError(f'{message}；预期={expected}，实际={actual}')

def launch(page='home'):
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)
    time.sleep(.5)

def host():
    return bool(re.search(r'mResumedActivity.*com\.shilapi\.xcertplay\.CarPlayHostActivity',
                          adb('shell', 'dumpsys', 'activity', 'activities')))

def preferences():
    text = adb('shell', 'run-as', package, 'cat', 'shared_prefs/l7_floating_navigation.xml', check=False)
    return {e.attrib['name']: e.attrib.get('value', e.text) for e in ET.fromstring(text)} if text.strip() else {}

def set_transparency(value):
    if host():
        if find('展开菜单') is not None:
            tap('展开菜单')
        tap('设置')
        tap('显示与性能')
    else:
        launch('settings-display')
    tap('悬浮图标透明度')
    tap(str(value) + '%')
    tap('保存')

def start_waiting():
    launch()
    for _ in range(3):
        if host() and find('画面') is not None:
            return
        if not host():
            screenshot('home')
            tap('有线连接')
            if find('有线连接 · 3 步') is not None:
                tap('有线连接')
            if find('USB 尚未就绪') is not None:
                assert find('开始等待 USB 连接') is not None, '本场景需 USB Host 支持；能力不足提示应使用 connection_guide_smoke.py 验证'
                tap('开始等待 USB 连接')
        time.sleep(.8)
        if find('While using the app') is not None:
            tap('While using the app')
        elif find('Only this time') is not None:
            tap('Only this time')
    if not host() or find('画面') is None:
        screenshot('waiting-failure')
        raise AssertionError('未进入投屏等待页')

def require_no_connection_footer():
    labels = {n.attrib.get('text') for n in nodes()}
    assert not labels.intersection({'iPhone 未连接', '正在等待 iPhone · 画面尚未显示',
                                    'iPhone 会话已连接 · 当前为应用状态页'}), '仍显示底部连接状态'

def check_home():
    launch()
    assert not host(), '首页检查意外进入投屏页'
    require_no_connection_footer()
    assert find('展开菜单') is not None and find('画面') is None, '首页没有默认收起菜单'
    title = find('Apple CarPlay')
    connect = find('无线连接')
    assert title is not None and connect is not None
    buttons = [n.attrib.get('text') for n in nodes() if n.attrib.get('class') == 'android.widget.Button']
    assert buttons in (['无线连接', '有线连接', '设置'], ['立即连接', '无线连接', '有线连接', '设置']), '首页入口顺序不正确：' + str(buttons)
    window = bounds(nodes()[0])
    for node in (title, connect):
        x1, _, x2, _ = bounds(node)
        assert abs(x1 + x2 - window[0] - window[2]) <= 4, '首页内容未在完整窗口水平居中'
    screenshot('home')
    adb('shell', 'input', 'keyevent', '4')
    menu = menu_labels()
    assert menu == ['画面', '连接', '设置', '车机', '退出'], '首页返回没有展开五项菜单：' + str(menu)
    screenshot('home-menu')
    fixed_menu = menu_bounds()
    assert all(b[2] < window[2] // 2 for _, b in fixed_menu), '菜单没有固定在窗口左侧'
    assert 0 < fixed_menu[0][1][1] - window[1] < 80, '菜单没有固定在窗口顶部并保留边距'
    tap('画面')
    original_handle = bounds(find('展开菜单'))
    try:
        for index, (x, y) in enumerate(((1180, 1450), (250, 500))):
            x1, y1, x2, y2 = bounds(find('展开菜单'))
            adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2), str(x), str(y), '650')
            tap('展开菜单')
            assert menu_bounds() == fixed_menu, '拖动浮动图标后菜单位置发生变化'
            screenshot('home-menu-drag-' + str(index))
            tap('画面')
    finally:
        if find('展开菜单') is None:
            tap('画面')
        x1, y1, x2, y2 = bounds(find('展开菜单'))
        ox1, oy1, ox2, oy2 = original_handle
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2), str((ox1+ox2)//2), str((oy1+oy2)//2), '650')
    tap('展开菜单')
    tap('设置')
    assert find('车型设置') is not None, '首页菜单无法进入设置'
    assert menu_bounds() == fixed_menu, '进入设置后菜单位置变化或重复建立'
    assert find('设置').attrib.get('selected') == 'true', '设置菜单没有选中当前分类'
    require_no_connection_footer()
    screenshot('settings-menu')
    tap('显示与性能')
    assert find('返回设置') is not None, '设置菜单遮挡了分类操作'
    assert menu_bounds() == fixed_menu, '进入设置子页后菜单位置变化'
    adb('shell', 'input', 'swipe', '1000', '1500', '1000', '600', '350')
    assert menu_bounds() == fixed_menu, '设置内容滚动带动了菜单'
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    assert menu_bounds() == fixed_menu, '切换夜间主题后菜单位置变化'
    assert find('设置').attrib.get('selected') == 'true', '夜间主题丢失设置选中态'
    require_no_connection_footer()
    screenshot('settings-child-menu-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    tap('设置')
    assert find('车型设置') is not None, '重新点击设置没有返回分类首页'
    adb('shell', 'input', 'keyevent', '4')
    assert find('无线连接') is not None and find('展开菜单') is not None, '设置返回没有恢复首页'
    tap('展开菜单')
    tap('画面')
    assert find('展开菜单') is not None and find('画面') is None
    tap('设置')
    assert find('车型设置') is not None, '首页设置按钮没有进入统一分类页'
    require_menu(fixed_menu, '首页设置按钮进入后菜单位置变化')
    adb('shell', 'input', 'keyevent', '4')
    tap('无线连接')
    assert find('返回连接方式') is not None and find('无线连接 · 3 步') is not None, '无线入口没有进入配置与连接页'
    tap('设置')
    adb('shell', 'input', 'keyevent', '4')
    screenshot('home-restored')
    print('导航检查通过：首页居中、默认收起、返回展开、拖动后菜单固定，设置/子页复用同一坐标、内容可滚动、昼夜选中态、设置往返与收起。', flush=True)
    return fixed_menu

def check_settings():
    launch('settings')
    assert find('车型设置') is not None and find('返回设置') is None, '设置首页入口或 Header 不符合约定'
    fixed_menu = menu_bounds()
    assert len(fixed_menu) == 4
    screenshot('settings-day')
    tap('显示与性能')
    for _ in range(2):
        adb('shell', 'input', 'swipe', '1000', '650', '1000', '1500', '350')
    header = bounds(find('返回设置'))
    first_row = bounds(find('界面大小'))
    screenshot('settings-display-day')
    for _ in range(3):
        adb('shell', 'input', 'swipe', '1000', '1500', '1000', '650', '350')
    require_menu(fixed_menu, '正文滚动带动了左栏')
    assert bounds(find('返回设置')) == header, '正文滚动带动了 Header'
    after_scroll = find('界面大小')
    assert after_scroll is None or bounds(after_scroll) != first_row, '正文没有滚动'
    screenshot('settings-scrolled-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    require_menu(fixed_menu, '夜间模式改变了导航坐标')
    assert find('设置').attrib.get('selected') == 'true'
    assert bounds(find('返回设置')) == header
    screenshot('settings-scrolled-night')
    tap('返回设置')
    assert find('车型设置') is not None and find('返回设置') is None
    screenshot('settings-night')
    tap('画面')
    assert find('无线连接') is not None and find('展开菜单') is not None
    screenshot('home-handle-restored')
    tap('展开菜单')
    require_menu(fixed_menu, '回到首页后浮动菜单按钮位置变化')
    screenshot('home-menu-restored')
    tap('设置')
    require_menu(fixed_menu, '再次进入设置时按钮位置变化')
    print('设置双栏检查通过：正文独立滚动、左栏与 Header 固定、昼夜选中态、两级返回和首页菜单恢复。', flush=True)

def check_idle_exit():
    # 无 USB Host 的模拟器仍执行真实退出交互；不把空闲退出当等待会话释放。
    launch()
    tap('展开菜单')
    before = adb('shell', 'pidof', package).strip()
    tap('退出')
    tap('取消')
    assert before == adb('shell', 'pidof', package).strip()
    tap('退出')
    screenshot('idle-exit-confirmation')
    tap('退出应用')
    deadline = time.monotonic() + 9
    while adb('shell', 'pidof', package, check=False).strip() and time.monotonic() < deadline:
        time.sleep(.3)
    assert not adb('shell', 'pidof', package, check=False).strip(), '退出后主进程仍在'
    services = adb('shell', 'dumpsys', 'activity', 'services', package)
    assert not re.search(r'ServiceRecord.*(?:DiPlaySessionService|L7DebugOverlayService|CarPlayVpnService)', services)
    print('空闲退出通过：取消保留进程、确认关闭进程及服务；未建立等待会话。', flush=True)

original_transparency = int(preferences().get('transparency', 50))
original_night = adb('shell', 'settings', 'get', 'secure', 'ui_night_mode').strip()
adb('shell', 'am', 'force-stop', package)
try:
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    if args.settings_only:
        check_settings()
        raise SystemExit(0)
    if args.projection_only:
        launch()
        adb('shell', 'input', 'keyevent', '4')
        expected_menu = menu_bounds()
        assert len(expected_menu) == 5, '首页五项菜单不可见'
        tap('画面')
    else:
        expected_menu = check_home()
    if args.home_only:
        raise SystemExit(0)
    if 'android.hardware.usb.host' not in adb('shell', 'pm', 'list', 'features'):
        check_idle_exit()
        print('跳过 USB 等待／投屏态检查：当前 AVD 未声明 USB Host；首页与空闲退出已验证。', flush=True)
        raise SystemExit(0)
    start_waiting()
    assert find('画面') is not None
    require_menu(expected_menu, '投屏等待页与首页菜单位置不一致')
    print('已进入投屏等待页，开始浮层交互检查。', flush=True)
    before = adb('shell', 'pidof', package).strip()
    tap('画面')
    first = find('展开菜单')
    assert first is not None
    first_bounds = bounds(first)
    screenshot('floating-day')
    adb('shell', 'input', 'keyevent', '4')
    assert find('画面') is not None and host(), '返回键离开了投屏页'
    menu = [n.attrib.get('content-desc') for n in nodes() if n.attrib.get('clickable') == 'true' and n.attrib.get('content-desc')]
    assert menu == ['画面', '连接', '设置', '车机', '退出'], '浮动菜单包含多余入口：' + str(menu)
    screenshot('rail-day')
    tap('画面')
    x1, y1, x2, y2 = first_bounds
    target = ('250', '500') if (x1+x2)//2 > 700 else ('1180', '1450')
    adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2), *target, '650')
    moved = find('展开菜单')
    assert moved is not None and bounds(moved) != first_bounds, '拖动未生效或误展开'
    assert before == adb('shell', 'pidof', package).strip(), '侧栏操作重启了应用'
    tap('展开菜单')
    assert find('画面') is not None
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    screenshot('rail-night')
    tap('车机')
    assert not host(), '车机入口没有离开投屏页'
    assert before == adb('shell', 'pidof', package).strip(), '返回车机结束了应用'
    services = adb('shell', 'dumpsys', 'activity', 'services', package)
    assert 'DiPlaySessionService' in services, '返回车机结束了等待会话'
    launch()
    assert host(), '从车机返回后未恢复投屏页'
    tap('展开菜单')
    tap('设置')
    require_menu(expected_menu, '从投屏进入设置时菜单位置变化')
    print('跨页面坐标检查通过：首页、投屏等待页与设置的五个按钮坐标完全一致。', flush=True)
    for label in ('关于', '调试', '日志', '连接设置'):
        tap(label)
        assert find('返回设置') is not None, '详细功能未归入设置：' + label
        if label == '日志':
            required = {'日志与报告', '悬浮日志', '查看当前日志', '保存诊断报告', '管理悬浮窗权限'}
            labels = set()
            for _ in range(6):
                labels.update(n.attrib.get('text') for n in nodes())
                if required <= labels:
                    break
                adb('shell', 'input', 'swipe', '1000', '1500', '1000', '600', '300')
            assert required <= labels
            screenshot('diagnostics-night')
            tap('查看当前日志')
            tap('刷新')
            tap('关闭')
        adb('shell', 'input', 'keyevent', '4')
    labels = {n.attrib.get('text') for n in nodes()}
    assert '悬浮日志' not in labels and '诊断' not in labels, '设置中仍有重复诊断入口'
    screenshot('settings-categories')
    changed = 75 if original_transparency != 75 else 25
    set_transparency(changed)
    assert int(preferences()['transparency']) == changed
    tap('画面')
    assert host() and find('展开菜单') is not None
    screenshot('floating-night')
    print('浮动导航检查通过：五项菜单、返回车机保留服务、诊断日志整合、返回展开、拖动、主题与透明度保存。', flush=True)
    tap('展开菜单')
    tap('退出')
    tap('取消')
    assert before == adb('shell', 'pidof', package).strip()
    tap('退出')
    screenshot('exit-confirmation')
    tap('退出应用')
    deadline = time.monotonic() + 9
    while adb('shell', 'pidof', package, check=False).strip() and time.monotonic() < deadline:
        time.sleep(.3)
    assert not adb('shell', 'pidof', package, check=False).strip(), '退出后主进程仍在'
    time.sleep(1)
    processes = adb('shell', 'ps', '-A', '-o', 'NAME')
    assert not any(n == package or n.startswith(package + ':') for n in processes.splitlines()), '退出后还有应用进程'
    services = adb('shell', 'dumpsys', 'activity', 'services', package)
    assert not re.search(r'ServiceRecord.*(?:DiPlaySessionService|L7DebugOverlayService|CarPlayVpnService)', services)
    print('完整退出检查通过：取消保留进程，确认后应用进程及三个服务均未运行。', flush=True)
except Exception:
    try:
        screenshot('failure')
        (out / 'failure.xml').write_text(adb('shell', 'cat', '/sdcard/l7-floating.xml'))
    except Exception as capture_error:
        print(f'补充取证失败：{type(capture_error).__name__}；保留原失败', flush=True)
    raise
finally:
    if int(preferences().get('transparency', 50)) != original_transparency:
        set_transparency(original_transparency)
    adb('shell', 'cmd', 'uimode', 'night', 'yes' if original_night == '2' else 'no')

start_waiting()
tap('画面')
assert find('展开菜单') is not None
screenshot('final-preview')
print('已恢复透明度并重新打开浮动入口预览；AVD 未验证真实连接后的自动收起。', flush=True)
