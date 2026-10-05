#!/usr/bin/env python3
"""仅在 AVD 验证调试路由、基础检查、结果筛选、历史和本地导出；不上传。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', type=Path, default=Path('build/previews/debug-probe'))
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('只允许 AVD')
args.output_dir.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'

def adb(*parts, binary=False):
    result = subprocess.check_output(base + list(parts))
    return result if binary else result.decode()

def nodes():
    dumped = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-probe-test.xml')
    assert 'UI hierchary dumped to:' in dumped, '界面转储失败，不能使用旧页面继续点击：' + dumped
    root = ET.fromstring(adb('shell', 'cat', '/sdcard/l7-probe-test.xml'))
    assert package in {n.get('package') for n in root.iter('node')}, '应用不在前台，停止操作并检查启动日志'
    return root

def find(label, clickable=False):
    for start, end in (('1600', '650'), ('650', '1600')):
        for _ in range(6):
            root = nodes()
            parents = {child: parent for parent in root.iter() for child in parent}
            for node in root.iter('node'):
                if label not in (node.get('text'), node.get('content-desc')):
                    continue
                if clickable:
                    label_node = node
                    while node.get('clickable') != 'true' and node in parents:
                        node = parents[node]
                        if node.get('class') == 'android.widget.ListView':
                            return label_node  # ListView 由父级分发选项点击，点击原选项的坐标。
                    if node.get('clickable') != 'true':
                        continue
                return node
            adb('shell', 'input', 'swipe', '1000', start, '1000', end, '200')
    raise AssertionError('入口不可达：' + label)

def tap(label):
    node = find(label, True)
    assert node.get('enabled') == 'true', '入口不可用：' + label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def launch(page):
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', page)

def screenshot(name):
    (args.output_dir / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def collect_label():
    titles = {'收集环境与调试信息', '重新收集环境与调试信息'}
    return next(n.get('text') for n in nodes().iter('node') if n.get('text') in titles)

def reports():
    command = 'ls files/probe-reports/*.json 2>/dev/null || true'
    names = adb('shell', 'run-as', package, 'sh', '-c', "'" + command + "'").splitlines()
    return [json.loads(adb('shell', 'run-as', package, 'cat', name)) for name in names if name.endswith('.json')]

def wait_report(previous):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        values = [r for r in reports() if r['runId'] not in previous and r['executionState'] == 'COMPLETED']
        if values:
            return values[0]
        time.sleep(.3)
    raise AssertionError('没有收到完整检查报告')

def english_table():
    launch('settings-general')
    tap('应用语言')
    tap('English')
    tap('应用')
    launch('settings-debug')
    before = {r['runId'] for r in reports()}
    tap('Collect environment and debug info again')
    wait_report(before)
    tap('View check results')
    for heading in ('Item', 'Status', 'Reason', 'All'):
        find(heading)
    for title in ('Media center reporting', 'HUD navigation reporting', 'System media display'):
        find(title)
    visible = [n.get(key, '') for n in nodes().iter('node') for key in ('text', 'content-desc')]
    assert not any(re.search(r'[\u3400-\u9fff]', text) for text in visible), '英文结果表存在中文界面文案'
    screenshot('debug-table-english-day')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    find('Item')
    screenshot('debug-table-english-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    launch('settings-general')
    tap('App language')
    tap('简体中文')
    tap('Apply')
    launch('settings-debug-results')
    find('项目')

original_night = adb('shell', 'cmd', 'uimode', 'night').strip().split()[-1]
try:
    adb('shell', 'am', 'force-stop', package)
    launch('settings')
    assert not any(n.get('text') == '诊断与日志' for n in nodes().iter('node'))
    tap('关于')
    assert not any(n.get('text') == '调试与日志' for n in nodes().iter('node')), '关于页仍保留重复调试入口'
    tap('返回设置')
    before = {r['runId'] for r in reports()}
    tap('调试')
    find('返回设置')
    find(collect_label())
    assert before == {r['runId'] for r in reports()}, '打开调试页自动开始了扫描'
    screenshot('debug-idle-day')
    tap(collect_label())
    report = wait_report(before)
    permissions = {item['name']: item for item in report['items'] if item['domain'] == 'PERMISSION'}
    catalog = json.loads(Path('common/src/main/assets/l7-permission-catalog.json').read_text())
    assert len(catalog['permissions']) == 163
    assert {item['name'] for item in catalog['permissions']} <= permissions.keys()
    assert report['probeVersion'] == 5
    assert len(report['items']) == report['expectedItems']
    assert permissions['android.permission.CONTROL_VPN']['facts']['declared'] == 'true'
    contract = next(i for i in report['items'] if i['capabilityId'] == 'ENV-SDK-CONTRACT')
    assert contract['result'] == 'UNKNOWN' and contract['reason'] == 'SDK_CONTRACT_CHECKED'
    assert contract['facts']['effectiveCall'] == 'CLASS_SIGNATURE_AND_PACKAGE_QUERY_ONLY'
    assert contract['facts']['mediaProviderQuery'] == 'NOT_VISIBLE_OR_UNINSTALLED'
    assert contract['facts']['mediaServiceQuery'] == 'NOT_VISIBLE_OR_UNINSTALLED'
    assert contract['facts']['mediaServiceAuthorization'] == 'UNTESTED_NO_BINDER_CALL'
    assert contract['facts']['mediaContract'] == 'NOT_VISIBLE_OR_UNINSTALLED'
    assert contract['facts']['navigationContract'] == 'NOT_VISIBLE_OR_UNINSTALLED'
    sessions = next(i for i in report['items'] if i['capabilityId'] == 'ENV-MEDIA-SESSIONS')
    assert sessions['result'] == 'DENIED' and sessions['facts']['exceptionType'] == 'SecurityException'
    assert 'activeSessionCount' not in sessions['facts']
    assert permissions['android.permission.MEDIA_CONTENT_CONTROL']['facts']['declared'] == 'true'
    hotspot = [i for i in report['items'] if i['domain'] == 'HOTSPOT']
    assert [i['capabilityId'] for i in hotspot] == ['HOTSPOT-STATE', 'HOTSPOT-CONFIG', 'HOTSPOT-LEGACY']
    assert all(i['facts']['effectiveCall'] == 'QUERY_ONLY' for i in hotspot)
    assert all('ssid' not in i['facts'] and 'password' not in i['facts'] for i in hotspot)
    for name in ('NETWORK_SETTINGS', 'OVERRIDE_WIFI_CONFIG', 'TETHER_PRIVILEGED', 'NETWORK_STACK'):
        assert permissions['android.permission.' + name]['facts']['declared'] == 'true'
    for name, env in (('SYSTEM_ALERT_WINDOW', 'ENV-OVERLAY'), ('WRITE_SETTINGS', 'ENV-WRITE-SETTINGS')):
        permission = permissions['android.permission.' + name]
        environment = next(item for item in report['items'] if item['capabilityId'] == env)
        allowed = environment['facts']['allowed'] == 'true'
        assert permission['facts']['specialAccess'] == ('ALLOWED' if allowed else 'DENIED')
        if allowed:
            assert permission['reason'] == 'SPECIAL_ACCESS_ALLOWED' and permission['result'] == 'OBSERVED'
    assert permissions['android.permission.PACKAGE_USAGE_STATS']['facts']['specialAccessMethod'] == 'AppOpsManager.OPSTR_GET_USAGE_STATS'
    assert all(i['reason'] != 'NOT_RUN' for i in report['items'])
    assert report['executorContext'] == 'L7_APP'
    assert 'device_id' not in json.dumps(report)
    assert all('sourceDir' not in i['facts'] and 'serial' not in i['facts'] for i in report['items'])
    reporting = report['items'][:3]
    assert [i['capabilityId'] for i in reporting] == ['REPORT-MEDIA', 'REPORT-HUD', 'REPORT-QNX']
    assert all(i['result'] == 'UNKNOWN' and i['facts']['effectiveCall'] == 'NOT_RUN' for i in reporting)
    assert reporting[2]['facts']['qnxProtocol'] == 'UNCONFIRMED_MEDIACENTER_DOWNSTREAM'
    (args.output_dir / 'basic-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    find('检查完成')
    screenshot('debug-completed-day')
    tap('查看检查结果')
    find('返回调试')
    for heading in ('项目', '状态', '原因', '全部'):
        find(heading)
    screenshot('debug-table-all-day')
    for title in ('媒体中心上报', 'HUD 导航上报', '系统媒体显示'):
        find(title)
    tap('系统媒体显示')
    assert any('此项复查 Android 媒体服务入口' in n.get('text', '') for n in nodes().iter('node'))
    screenshot('reporting-system-media-details-day')
    tap('关闭')
    tap('结果筛选')
    tap('无权限')
    screenshot('debug-restricted-day')
    before = {r['runId'] for r in reports()}
    tap('重新收集')
    refreshed = wait_report(before)
    tap('查看检查结果')
    find('全部')
    assert refreshed['runId'] != report['runId']
    batch = refreshed['runId'].replace('-', '')[:12]
    deadline = time.monotonic() + 10
    while True:
        log = adb('shell', 'run-as', package, 'cat', 'files/logs/probe-latest.log')
        if f'batch={batch}' in log and 'event=end phase=COMPLETED' in log:
            break
        assert time.monotonic() < deadline, '重新收集后没有完整写入日志'
        time.sleep(.3)
    assert len(set(re.findall(r'item=(\d+) entry=', log))) == len(refreshed['items'])
    assert 'entry=ENV-RUNTIME' in log and 'entry=ENV-WINDOW' in log
    assert 'entry=ENV-SDK-CONTRACT' in log and 'entry=ENV-MEDIA-SESSIONS' in log
    assert 'mediaContract=NOT_VISIBLE_OR_UNINSTALLED' in log
    assert 'mediaProviderQuery=NOT_VISIBLE_OR_UNINSTALLED' in log
    assert 'mediaServiceQuery=NOT_VISIBLE_OR_UNINSTALLED' in log
    assert 'mediaServiceAuthorization=UNTESTED_NO_BINDER_CALL' in log
    assert 'entry=REPORT-HUD status=PENDING' in log
    assert 'permission.ecarx.openapi.permission.NAVI_SERVICE=declared=' in log
    assert 'qnxProtocol=UNCONFIRMED_MEDIACENTER_DOWNSTREAM' in log
    assert 'device_id' not in log
    previous_log = adb('shell', 'run-as', package, 'cat', 'files/logs/probe-previous.log')
    assert report['runId'].replace('-', '')[:12] in previous_log
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    find('项目')
    screenshot('debug-table-all-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    tap('结果筛选')
    tap('支持 / 已授权')
    tap('系统与运行环境')
    screenshot('debug-evidence-day')
    before = {r['runId'] for r in reports()}
    tap('重新检查此项')
    single = wait_report(before)
    assert [i['capabilityId'] for i in single['items']] == ['ENV-SYSTEM']
    tap('查看检查结果')
    tap('报告操作')
    tap('导出检查报告（JSON）')
    time.sleep(.7)
    labels = [n.get('text', '') for n in nodes().iter('node')]
    assert any('报告已保存' in value for value in labels), '导出没有返回保存成功'
    exported = json.loads(adb('shell', 'cat', '/sdcard/Download/GalaxyPlay/GalaxyPlay-check-' + single['runId'] + '.json'))
    assert exported['runId'] == single['runId']
    screenshot('debug-export-day')
    time.sleep(4.6)  # 保存反馈是自动消退的窗口内提示，系统返回会离开结果页。
    tap('返回调试')
    tap('历史检查报告')
    find('返回调试')
    screenshot('debug-history-day')
    adb('shell', 'input', 'keyevent', '4')
    find('返回设置')
    tap('返回设置')
    tap('日志')
    find('查看当前日志')
    find('OpenObserve 日志服务器')
    screenshot('debug-logs-day')
    tap('返回设置')
    tap('关于')
    find('应用版本')
    adb('shell', 'input', 'keyevent', '4')
    find('显示与性能')
    launch('settings-diagnostics')
    find('返回设置')
    find(collect_label())
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    screenshot('debug-night')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    english_table()
    print('调试检查通过：设置首页入口、关于无重复入口、多级返回、中英文昼夜全量表格、重新收集重置筛选、逐项落盘日志与批次保留、单项复查、历史和 JSON 导出。未上传。')
finally:
    if original_night in ('yes', 'no', 'auto'):
        adb('shell', 'cmd', 'uimode', 'night', original_night)
