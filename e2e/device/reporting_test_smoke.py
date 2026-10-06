#!/usr/bin/env python3
"""仅在 AVD 验证手动媒体／导航接口测试和日志；不连接手机、不上传，结束恢复原偏好与协议。"""
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
parser.add_argument('--cases', nargs='+', choices=['zh-day', 'zh-night', 'en-day', 'en-night'],
                    default=['zh-day', 'zh-night', 'en-day', 'en-night'])
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '仅允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/reporting-test')
output.mkdir(parents=True, exist_ok=True)


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
    adb('shell', 'uiautomator', 'dump', '/sdcard/reporting-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/reporting-test.xml'))


def visible():
    return [n.get('text', '') for n in nodes().iter('node')]


def row(label):
    root = nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if node.get('text') != label and node.get('content-desc') != label:
            continue
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        return node
    return None


def tap(label):
    for _ in range(6):
        node = row(label)
        if node is not None and node.get('enabled') == 'true' and node.get('clickable') == 'true':
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(.15)
            return
        adb('shell', 'input', 'swipe', '1080', '1700', '1080', '650', '180')
    raise AssertionError('动作不可达：' + label)


# 展开的明细可超过三屏，先回到正文顶部，再查找动作；不把未滚回当作按钮缺失。
def top():
    for _ in range(10):
        adb('shell', 'input', 'swipe', '1080', '450', '1080', '1600', '180')


def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)
    nodes()
    top()
    nodes()


def screenshot(name):
    nodes()
    time.sleep(.2)
    (output / name).write_bytes(adb('exec-out', 'screencap', '-p'))


def log():
    return (read('files/logs/debug.log') or b'').decode(errors='replace')


paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_agreement.xml')
original = {p: read(p) for p in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
labels = {
    'zh': ('媒体上报测试', 'HUD 导航上报测试', '开始上报测试', '结束上报并注销', '取消',
           '切换测试曲目 A／B', '切换播放／暂停', '测试进度增加 15 秒', '切换封面 A／B／无封面',
           '切换测试路名 A／B', '重新检查服务并发布当前路名', '未显示或与样例不符', 'GalaxyPlay 测试曲目 B', '返回调试'),
    'en': ('Media reporting test', 'HUD navigation reporting test', 'Start reporting test', 'End reporting and unregister', 'Cancel',
           'Switch test track A / B', 'Toggle play / pause', 'Advance test progress by 15 seconds', 'Switch artwork A / B / none',
           'Switch test road A / B', 'Recheck service and publish current road', 'No display or a sample mismatch', 'GalaxyPlay test track B', 'Back to Debug'),
}

try:
    for language, names in labels.items():
        if not any(case.startswith(language + '-') for case in args.cases):
            continue
        media, nav, start, end, cancel, track, play, progress, cover, road, refresh, missing, title_b, back = names
        adb('shell', 'am', 'force-stop', package)
        prefs = ET.fromstring(original[paths[0]] or b'<map/>')
        for child in list(prefs):
            if child.get('name') in ('app_language', 'auto_connect'):
                prefs.remove(child)
        ET.SubElement(prefs, 'string', {'name': 'app_language'}).text = language
        ET.SubElement(prefs, 'boolean', {'name': 'auto_connect', 'value': 'false'})
        write(paths[0], ET.tostring(prefs))
        # 仅模拟器夹具；主动阅读／勾选由协议回归覆盖，原同意记录最终恢复。
        digest = hashlib.sha256(Path('galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
        write(paths[1], f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
        for theme, night_value in [('day', 'no'), ('night', 'yes')]:
            if language + '-' + theme not in args.cases:
                continue
            adb('shell', 'cmd', 'uimode', 'night', night_value)
            for kind, label in [('media', media), ('navigation', nav)]:
                launch('settings-debug')
                tap(label)
                assert row(end) is None
                screenshot(f'{language}-{kind}-{theme}-idle.png')
                before = log()
                tap(start); tap(cancel)
                assert log() == before, '取消发生上报写入'
                tap(start); tap(start)
                assert row(end).get('enabled') == 'true'
                assert not any('stage=' in text for text in visible()), '技术字段不能挤占样例'
                if kind == 'media':
                    tap(track)
                    assert title_b in adb('shell', 'dumpsys', 'media_session').decode(errors='replace'), '真实 Android MediaSession 未更新曲目'
                    tap(play); tap(progress); tap(cover)
                    adb('shell', 'input', 'keyevent', '3')
                    assert 'GalaxyPlay 手动上报测试' in adb('shell', 'dumpsys', 'media_session').decode(errors='replace'), '离开页面提前停止测试'
                    launch('settings-debug-media')
                    current = log()
                    assert 'elapsedMs=15000' in current and 'cover=none' in current
                    assert 'artwork' in current and 'result=SAVED' in current
                else:
                    tap(road); tap(refresh)
                    assert 'stage=explicitRefresh' in log()
                assert 'exceptionType=ClassNotFoundException' in log(), 'AVD 缺 SDK 未明确记载'
                top(); tap(missing)
                assert any(('已记录：未符合预期' if language == 'zh' else 'Recorded: did not match expectations') in text for text in visible())
                assert not any('ClassNotFoundException' in text for text in visible()), '技术字段默认应折叠'
                tap('查看技术明细' if language == 'zh' else 'View technical details')
                for _ in range(3):
                    if any('ClassNotFoundException' in text for text in visible()):
                        break
                    adb('shell', 'input', 'swipe', '1080', '1700', '1080', '650', '180')
                assert any('ClassNotFoundException' in text for text in visible()), '展开后应可查看首个 SDK 失败'
                top(); tap('收起技术明细' if language == 'zh' else 'Hide technical details')
                top()
                tap(track if kind == 'media' else road)
                top()
                assert not any('Recorded: did not match expectations' in text or '已记录：未符合预期' in text for text in visible()), '新样例不得继承旧结果'
                tap(missing)
                screenshot(f'{language}-{kind}-{theme}-evidence.png')
                launch('settings-debug-' + kind)
                tap(end)
                top()
                assert row(end) is None
                assert row(start).get('enabled') == 'true'
                current = log()
                assert 'stage=localRelease result=COMPLETED remoteClear=NOT_VERIFIED' in current
                assert 'stage=userObservation result=MISSING_OR_ABNORMAL origin=MANUAL' in current
                if kind == 'media':
                    assert 'GalaxyPlay 手动上报测试' not in adb('shell', 'dumpsys', 'media_session').decode(errors='replace')
                screenshot(f'{language}-{kind}-{theme}-ended.png')
                tap('记录仍有残留或异常' if language == 'zh' else 'Record residual or abnormal information')
                nodes()
                assert 'stage=userCleanupObservation result=RESIDUAL_OR_ABNORMAL origin=MANUAL phase=STOPPED' in log()
                screenshot(f'{language}-{kind}-{theme}-cleanup.png')
                top(); tap(back)
                assert media in visible() or nav in visible()
                print(f'{language} {theme} {kind} 页面、更新与结束通过', flush=True)
    print('AVD 场景 ' + ', '.join(args.cases) + ' 的媒体／HUD 测试、取消、样例与结果绑定、明细、更新及停止通过；未连接手机、未上传')
finally:
    # 异常时也尽力触发显式结束，随后关闭进程，不能把 force-stop 当远端注销通过。
    try:
        for kind in ('media', 'navigation'):
            launch('settings-debug-' + kind)
            for end_label in (labels['en'][3], labels['zh'][3]):
                candidate = row(end_label)
                if candidate is not None and candidate.get('enabled') == 'true':
                    tap(end_label)
                    break
    except Exception:
        pass
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        write(path, data)
    adb('shell', 'cmd', 'uimode', 'night', night)
    assert all(read(path) == data for path, data in original.items())
    print('原语言／偏好、协议记录和昼夜已恢复；测试日志保留在现有队列')
