#!/usr/bin/env python3
"""在已安装当前 APK 的 L7 AVD 检查分类导航、选择取消和连接取消。"""
import argparse
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True, help='ADB 程序或工作区 ADB 脚本')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path, help='本次截图目录，密度回归使用独立目录保留原证据')
parser.add_argument('--audio-only', action='store_true', help='仅检查声道选择、试听、停止、取消和主题状态；不保存设置、不确认实车听感')
parser.add_argument('--connection-only', action='store_true', help='仅检查 USB 等待页、昼夜/大字、返回保留与原页取消；无真实 USB 会话')
parser.add_argument('--bluetooth-only', action='store_true', help='仅检查蓝牙媒体互斥入口与降级说明；不操作蓝牙或打开系统设置')
parser.add_argument('--theme-only', action='store_true', help='仅检查选择弹窗切换主题后保留待选值和按钮状态')
parser.add_argument('--navigation-only', action='store_true', help='仅检查分类入口、设置层级、返回及图标截图')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('此脚本只允许模拟器，不操作实车')
root = Path(__file__).resolve().parents[2]
output = args.output_dir or root / 'build/previews/flyme-ux'
output.mkdir(parents=True, exist_ok=True)


def adb(*command, binary=False):
    result = subprocess.run([args.adb, '-s', args.serial, *command], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode('utf-8')


def nodes():
    for _ in range(3):
        adb('shell', 'rm', '-f', '/sdcard/l7-ux.xml')
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-ux.xml')
        if 'dumped to' in result:
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-ux.xml')).iter('node'))
        time.sleep(.5)
    raise AssertionError('无法读取当前节点树，未复用旧结果')


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.3)


def tap(text):
    if text == 'USB 有线连接' and any(n.attrib.get('text') == '有线连接' for n in nodes()):
        text = '有线连接'
    if text in ('画面', '设置', '车机', '退出'):
        current = nodes()
        if not any(n.attrib.get('content-desc') == text for n in current):
            handle = next((n for n in current if n.attrib.get('content-desc') == '展开菜单'), None)
            if handle is not None:
                tap_node(handle)
    # 等待页的悬浮菜单会拦截底层操作；先通过画面入口收起，再检查等待卡片。
    if text in ('返回 GalaxyPlay', '取消连接'):
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        if re.search(r'mResumedActivity.*CarPlayHostActivity', state):
            picture = next((n for n in nodes() if n.attrib.get('content-desc') == '画面'), None)
            if picture is not None:
                tap_node(picture)
    # 前一项检查可能已滚到页底，查找也必须覆盖上方入口，不能只向下翻页。
    for start, end in ((1600, 700), (700, 1600)):
        for _ in range(5):
            matches = [n for n in nodes() if n.attrib.get('text') == text or n.attrib.get('content-desc') == text]
            if matches:
                matches.sort(key=lambda n: n.attrib.get('content-desc') != text)
                tap_node(matches[0])
                return
            adb('shell', 'input', 'swipe', '1000', str(start), '1000', str(end), '350')
    raise AssertionError(f'找不到操作：{text}')


def settings_selected():
    assert any(n.attrib.get('content-desc') == '设置' and n.attrib.get('selected') == 'true' for n in nodes()), '配置页离开了设置导航'


def launch(page='home'):
    adb('shell', 'am', 'start', '-n', 'com.ecarx.carplay/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)
    time.sleep(1)


def ensure_chinese():
    # 场景按中文资源定位；AVD 若跟随英文系统，先经应用真实设置选择中文。
    if any(n.attrib.get('text') == 'Display and performance' for n in nodes()):
        launch('settings-general')
        tap('App language')
        tap('简体中文')
        tap('Apply')
        launch('settings')


def screenshot(name):
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def checked_option():
    return next(n.attrib['text'] for n in nodes() if n.attrib.get('class') == 'android.widget.CheckedTextView' and n.attrib.get('checked') == 'true')


def require_labels(expected):
    remaining = set(expected)
    for _ in range(5):
        remaining -= {n.attrib.get(key, '') for n in nodes() for key in ('text', 'content-desc')}
        if not remaining:
            return
        adb('shell', 'input', 'swipe', '1000', '1600', '1000', '700', '350')
    raise AssertionError(f'设置入口不可达：{sorted(remaining)}')


def assert_stacked_long_value(label):
    # 在真实 Android 文字排版中检查长值；Robolectric 的 ARM64 路径没有原生字体测量。
    for _ in range(8):
        for group in nodes():
            children = list(group)
            title = next((n for n in children if n.get('text') == label), None)
            if title is None:
                continue
            values = [n for n in children if n.get('text') and n is not title]
            if not values:
                continue
            value = values[0]
            title_box = list(map(int, re.findall(r'\d+', title.get('bounds'))))
            value_box = list(map(int, re.findall(r'\d+', value.get('bounds'))))
            if value_box[3] >= 1750:
                continue
            assert len(value.get('text')) >= 14, '此布局场景需要已配置的长路由名称'
            assert value_box[0] == title_box[0], '长值没有与名称左对齐'
            assert value_box[1] >= title_box[3], '长值挤占名称行'
            assert value_box[3] > value_box[1], '长值没有可见高度'
            return
        adb('shell', 'input', 'swipe', '1000', '1500', '1000', '1100', '300')
    raise AssertionError('找不到完整的长值条目')


def navigation_smoke():
    current = nodes()
    assert not any(n.attrib.get('text') == '进入' for n in current), '分类尾部仍显示多余的进入文字'
    assert not any(n.get(key) in ('返回设置', '返回画面') for n in current
                   for key in ('text', 'content-desc')), '设置首页不应显示返回按钮'
    screenshot('hierarchy-categories-day')
    tap('车型设置')
    require_labels(['返回设置', '车型', '自动识别车型'])
    screenshot('hierarchy-vehicle-day')
    tap('返回设置')
    tap('显示与性能')
    settings_selected()
    assert not any(n.attrib.get('text') in ('GalaxyPlay', 'GalaxyPlay') for n in nodes()), '子页重复显示应用标题栏'
    require_labels(['返回设置', 'CarPlay 尺寸', '分辨率', '帧率', '高效视频', '全屏显示'])
    screenshot('hierarchy-display-day')
    # 重新点击当前侧栏分类应返回首页，不能困在同一个子页。
    tap('设置')
    tap('音频路由')
    require_labels(['音乐缓冲', '音频焦点', '高级音频通道映射', '媒体音乐', '语音助手（Siri）', '导航播报'])
    screenshot('hierarchy-audio-day')
    tap('返回设置')
    tap('通用设置')
    require_labels(['打开 GalaxyPlay 时连接', '车机启动后打开', '向 iPhone 报告位置', '应用语言'])
    tap('画面')
    tap('设置')
    require_labels(['返回设置', '应用语言'])
    screenshot('hierarchy-general-day')
    tap('返回设置')
    tap('CarPlay 认证')
    screenshot('hierarchy-auth-day')
    tap('选择认证来源')
    require_labels(['应用内置（默认）', '文字导入', 'USB 认证（CH341）', '远程 MFi'])
    tap('取消')
    tap('返回设置')
    for category, labels, name in (
        ('连接设置', ['无线连接', '有线连接', '选择连接方式'], 'connection'),
        ('关于', ['应用版本', '使用协议'], 'about'),
        ('调试', ['返回设置', '环境与权限', '调试模块'], 'debug'),
        ('日志', ['返回设置', '查看日志', '上传日志'], 'logs'),
    ):
        tap(category)
        settings_selected()
        screenshot('hierarchy-' + name + '-day')
        require_labels(labels)
        screenshot('hierarchy-' + name + '-bottom-day')
        if name == 'about':
            assert not any(n.attrib.get('text') == '调试与日志' for n in nodes()), '关于页仍保留重复调试入口'
        if name == 'logs':
            require_labels(['查看当前日志', '保存诊断报告', '选择保存位置', '管理悬浮窗权限'])
        tap('返回设置')
    tap('权限与连接帮助')
    require_labels(['返回设置', '应用权限', '蓝牙设置', '无线连接帮助'])
    screenshot('hierarchy-permissions-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    screenshot('hierarchy-permissions-night')
    tap('返回设置')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    launch('settings')
    screenshot('hierarchy-large-night')
    tap('显示与性能')
    require_labels(['返回设置', '全屏显示'])
    screenshot('hierarchy-child-large-night')
    tap('设置')
    tap('音频路由')
    assert_stacked_long_value('语音助手（Siri）')
    screenshot('hierarchy-long-value-large-night')
    tap('设置')
    print('设置层级检查通过：分类进入、侧栏返回、文字返回、子页恢复和全部 L7 常用参数可达；未保存参数或认证。', flush=True)


def audio_smoke():
    tap('音频路由')
    tap('媒体音乐')
    route_names = {item.text for item in ET.parse(root / 'galaxy/common/src/main/res/values/l7_media_diagnostics.xml').getroot().find('string-array').findall('item')}
    current = next(n.attrib['text'] for n in nodes() if n.attrib.get('text') in route_names)
    stop = next(n for n in nodes() if n.attrib.get('text') == '停止试听')
    tap('试听此声道（2 秒）')
    # 使用试听前的按钮坐标立即停止，不能等待 UI 稳定至声音自行播放完再冒充停止检查。
    tap_node(stop)
    assert any(n.attrib.get('text') == '试听已停止，设置未保存。' for n in nodes())
    tap('高级输出策略')
    alternate = '语音助手策略（ASSISTANT）' if current != '语音助手策略（ASSISTANT）' else '导航提示策略（NAVIGATION）'
    tap(alternate)
    tap('选择')
    assert any(n.attrib.get('text') == '保存，下次连接生效' and n.attrib.get('enabled') == 'true' for n in nodes())
    tap('试听此声道（2 秒）')
    time.sleep(3)
    assert any(n.attrib.get('text', '').startswith('测试音已发送，请确认是否听到。') for n in nodes()), '试听没有返回真实播放调用结果'
    screenshot('audio-preview-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    assert any(n.attrib.get('text') == '保存，下次连接生效' and n.attrib.get('enabled') == 'true' for n in nodes())
    screenshot('audio-preview-night')
    tap('取消')
    tap('媒体音乐')
    assert any(n.attrib.get('text') == current for n in nodes()), '选择或试听绕过保存改变了设置'
    tap('取消')
    tap('语音助手（Siri）')
    tap('试听此声道（2 秒）')
    time.sleep(3)
    assert any(n.attrib.get('text', '').startswith('测试音已发送，请确认是否听到。') for n in nodes())
    screenshot('assistant-preview-night')
    tap('取消')
    tap('导航播报')
    tap('试听此声道（2 秒）')
    time.sleep(3)
    assert any(n.attrib.get('text', '').startswith('测试音已发送，请确认是否听到。') for n in nodes())
    screenshot('navigation-preview-night')
    tap('取消')
    print('三用途交互检查通过：媒体/助手/导航播放调用、用途策略选择、立即停止、主题状态保留、取消恢复；未保存参数，未验证实车出声。', flush=True)


def bluetooth_smoke():
    tap('音频路由')
    settings_selected()
    require_labels(['蓝牙音乐互斥', '蓝牙音乐冲突排查'])
    tap('蓝牙音乐冲突排查')
    current = nodes()
    message = next(n.attrib['text'] for n in current if '最近检查：' in n.attrib.get('text', ''))
    assert '尚未检查' in message or '会话已结束' in message, 'AVD 没有真实 CarPlay，不能声明已处理蓝牙'
    assert '保留“通话音频”' in message and 'USB 未识别' in message
    require_labels(['打开蓝牙设置', '关闭'])
    screenshot('bluetooth-media-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(.8)
    require_labels(['打开蓝牙设置', '关闭'])
    screenshot('bluetooth-media-night')
    tap('关闭')
    settings_selected()
    print('蓝牙媒体入口检查通过：状态不冒充已处理、手动降级说明、昼夜弹窗和关闭返回；未操作蓝牙。')



def enter_usb_waiting():
    launch('settings-connection-usb')
    tap('有线连接')
    current = nodes()
    if any(n.get('text') == '开始等待 USB 连接' for n in current):
        tap('开始等待 USB 连接')
        return True
    assert any(n.get('text') == 'USB 尚未就绪' for n in current), '缺少 USB 未就绪提示'
    screenshot('usb-unavailable'); tap('关闭')
    print('当前 AVD 未声明 USB Host，已验证强提示；未执行 USB 等待场景。', flush=True)
    return False


def connection_smoke():
    if not enter_usb_waiting():
        return
    current = nodes()
    if any(n.attrib.get('text') == 'Only this time' for n in current):
        tap('Only this time')
    require_labels(['返回 GalaxyPlay', '取消连接'])
    screenshot('usb-wait-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(1)
    require_labels(['返回 GalaxyPlay', '取消连接'])
    screenshot('usb-wait-night')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    time.sleep(1)
    require_labels(['返回 GalaxyPlay', '取消连接'])
    screenshot('usb-wait-large-night')
    tap('返回 GalaxyPlay')
    services = adb('shell', 'dumpsys', 'activity', 'services', 'com.ecarx.carplay')
    assert 'DiPlaySessionService' in services, '返回结束了等待会话'
    tap('查看连接进度')
    require_labels(['取消连接'])
    tap('取消连接')
    time.sleep(1)
    services = adb('shell', 'dumpsys', 'activity', 'services', 'com.ecarx.carplay')
    assert not re.search(r'ServiceRecord.*(?:DiPlaySessionService|L7DebugOverlayService)', services), '原页取消后会话服务仍在运行'
    screenshot('usb-cancelled-home')
    print('USB 等待页检查通过：昼夜/大字操作可达、返回保留会话、原页取消释放服务；未验证真实 USB 连接。', flush=True)

original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
original_night = adb('shell', 'cmd', 'uimode', 'night').strip().split()[-1]
frame_changed = False
try:
    assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == '30'
    assert '1440x1920' in adb('shell', 'wm', 'size')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    if args.connection_only:
        adb('shell', 'am', 'force-stop', 'com.ecarx.carplay')
    launch('settings')
    ensure_chinese()
    nodes()
    screenshot('settings-day')
    if args.connection_only:
        connection_smoke()
        raise SystemExit(0)
    if args.audio_only:
        audio_smoke()
        raise SystemExit(0)
    if args.bluetooth_only:
        bluetooth_smoke()
        raise SystemExit(0)
    if args.navigation_only:
        navigation_smoke()
        raise SystemExit(0)
    tap('显示与性能')
    settings_selected()
    tap('帧率')
    original_choice = checked_option()
    options = [n for n in nodes() if n.attrib.get('class') == 'android.widget.CheckedTextView']
    pending = next(n for n in options if n.attrib['text'] != original_choice)
    tap_node(pending)
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(.5)
    assert checked_option() == pending.attrib['text'], '主题切换丢失待选值'
    assert any(n.attrib.get('text') == '保存' and n.attrib.get('enabled') == 'true' for n in nodes()), '主题切换错误地禁用了确认按钮'
    # 等主题切换和模拟器合成完成，再采集用于人工检阅的配色截图。
    time.sleep(2)
    screenshot('selection-theme-switch')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    tap('取消')
    tap('帧率')
    assert checked_option() == original_choice, '取消后选择发生变化'
    assert any(n.attrib.get('text') == '保存' and n.attrib.get('enabled') == 'false' for n in nodes())
    screenshot('selection-day')
    tap('取消')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    assert any(n.attrib.get('text') == 'CarPlay 认证' for n in nodes()), '返回没有回到设置首页'
    if args.theme_only:
        print('弹窗主题检查通过：待选值和确认状态保留，取消后恢复原值。', flush=True)
        raise SystemExit(0)
    tap('连接设置')
    tap('无线连接')
    settings_selected()
    tap('车机热点详情')
    screenshot('hotspot-modal-day')
    tap('取消')
    settings_selected()
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    launch('settings')
    tap('CarPlay 认证')
    settings_selected()
    tap('选择认证来源')
    original_auth = checked_option()
    screenshot('authentication-modal-day')
    tap('USB 认证（CH341）')
    tap('取消')
    tap('选择认证来源')
    assert checked_option() == original_auth, '取消后认证来源发生变化'
    tap('关闭')
    tap('文字快捷导入')
    inputs = [n for n in nodes() if n.attrib.get('class') == 'android.widget.EditText']
    tap_node(inputs[0])
    time.sleep(.5)
    screenshot('import-keyboard-day')
    assert any(n.attrib.get('text') == '取消' and n.attrib.get('enabled') == 'true' for n in nodes()), '键盘遮挡了取消操作'
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('导入并启用')
    time.sleep(1)
    assert any(n.attrib.get('text', '').startswith('未能启用认证') for n in nodes()), '空输入没有显示校验提示'
    screenshot('import-error-day')
    tap('取消')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    print('设置路由、热点弹窗、认证取消与输入错误检查通过。', flush=True)
    tap('音频路由')
    screenshot('audio-day')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('通用设置')
    assert any(n.attrib.get('text') == '应用语言' for n in nodes()), '通用设置缺少语言入口'
    settings_selected()
    tap('画面')
    tap('设置')
    assert any(n.attrib.get('text') == '应用语言' for n in nodes()), '切换导航后没有回到原设置子页'
    tap('应用语言')
    screenshot('language-modal-day')
    tap('取消')
    screenshot('general-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(.5)
    screenshot('general-night')
    tap('应用语言')
    screenshot('language-modal-night')
    tap('取消')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    launch('settings')
    screenshot('settings-large-night')
    launch('settings-display')
    tap('帧率')
    screenshot('selection-large-night')
    tap('取消')
    adb('shell', 'settings', 'delete', 'system', 'font_scale')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    if not enter_usb_waiting():
        print('其余设置检查通过；USB 等待场景需支持 USB Host 的设备。', flush=True)
        raise SystemExit(0)
    current = nodes()
    # 模拟器如出现录音授权，只授予本次使用，避免预先绕过权限流程。
    if any(n.attrib.get('text') == 'Only this time' for n in current):
        tap('Only this time')
    time.sleep(1)
    screenshot('usb-wait-day')
    tap('返回 GalaxyPlay')
    launch('settings-display')
    tap('帧率')
    current = nodes()
    alternate = next(n for n in current if n.attrib.get('class') == 'android.widget.CheckedTextView' and n.attrib['text'] != original_choice)
    tap_node(alternate)
    frame_changed = True
    tap('保存，下次连接生效')
    settings_selected()
    services = adb('shell', 'dumpsys', 'activity', 'services', 'com.ecarx.carplay')
    assert 'DiPlaySessionService' in services, '保存设置结束了等待会话'
    screenshot('saved-setting-with-session')
    tap('帧率')
    tap(original_choice)
    tap('保存，下次连接生效')
    frame_changed = False
    tap('画面')
    tap('取消连接')
    time.sleep(1)
    services = adb('shell', 'dumpsys', 'activity', 'services', 'com.ecarx.carplay')
    assert not re.search(r'ServiceRecord.*(?:DiPlaySessionService|L7DebugOverlayService)', services), '取消后会话服务仍在运行'
    screenshot('cancelled-home-day')
    print('AVD 检查通过：设置归属/恢复、热点/认证/语言弹窗、输入错误、选择取消、昼夜与大字截图、USB 等待取消。')
    print(f'截图：{output}')
finally:
    try:
        if frame_changed:
            # 只在 AVD 保存测试值，并通过界面恢复原值；不读取应用的私有认证配置。
            adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            launch('settings-display')
            tap('帧率')
            if checked_option() != original_choice:
                tap(original_choice)
                captions = [n for n in nodes() if n.attrib.get('class') == 'android.widget.Button' and n.attrib.get('text', '').startswith('保存')]
                tap_node(captions[0])
            else:
                tap('取消')
    finally:
        if original_font == 'null':
            adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            adb('shell', 'settings', 'put', 'system', 'font_scale', original_font)
        if original_night in ('yes', 'no', 'auto'):
            adb('shell', 'cmd', 'uimode', 'night', original_night)
