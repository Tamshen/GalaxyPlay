#!/usr/bin/env python3
"""仅在 AVD 上传合成完整日志到本机接口，验证实际 SQLite、分批、失败续传与临时文件清理。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--output-dir', default='build/previews/full-log-upload')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许模拟器'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path(args.output_dir)
output.mkdir(parents=True, exist_ok=True)
received, accepted, accepted_rows, received_streams = [], [], [], []
remark = '18:00_update_WiFiDirect_failed'
received_lock = threading.Lock()


def adb(*parts, data=None):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE, timeout=30)


def private(command, data=None):
    return adb('shell', 'run-as ' + package + ' sh -c ' + shlex.quote(command), data=data)


def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/galaxy-full-upload.xml')
    assert b'dumped to:' in result, '未读取当前界面，不使用旧转储'
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/galaxy-full-upload.xml'))


def texts():
    return '\n'.join(n.get('text', '') for n in nodes().iter('node'))


def tap(label):
    root = nodes()
    parents = {c: p for p in root.iter() for c in p}
    item = next(n for n in root.iter('node') if n.get('text') == label)
    while item.get('clickable') != 'true' and item in parents:
        item = parents[item]
    assert item.get('enabled') == 'true' and item.get('clickable') == 'true'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', item.get('bounds')))
    assert x2 > x1 and y2 > y1
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


class Server(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        assert len(body) <= 256 * 1024
        rows = json.loads(body)
        assert len(rows) <= 1000
        index = rows[0]['batch_index']
        with received_lock:
            received.append((index, body))
            received_streams.append(self.path.split('/')[-2])
            code = 503 if index == 3 and sum(i == 3 for i, _ in received) == 1 else 200
        if code == 200:
            accepted_rows.extend(rows)
            for row in rows:
                match = re.search(r'full_avd_line=(\d+)', row['message'])
                if match:
                    accepted.append(int(match[1]))
        response = json.dumps({'code': 200, 'status': [{'name': self.path.split('/')[-2],
            'successful': len(rows), 'failed': 0}]}).encode()
        self.send_response(code)
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', '只允许真实 AVD'
assert 'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities').decode()
adb('shell', 'am', 'force-stop', package)
backups = {}
for path in ('shared_prefs/l7_remote_log.xml', 'shared_prefs/l7_log_history.xml'):
    result = subprocess.run(base + ['exec-out', 'run-as', package, 'cat', path], capture_output=True)
    if b'No such file' in result.stdout + result.stderr:
        backups[path] = None
    else:
        assert result.returncode == 0 and ET.fromstring(result.stdout).tag == 'map'
        backups[path] = result.stdout
assert private('if [ -e files/logs-full-upload-backup ]; then echo occupied; fi') == b'', '已有测试备份，停止'
had_logs = private('if [ -d files/logs ]; then echo present; fi').strip() == b'present'
server = ThreadingHTTPServer(('127.0.0.1', 0), Server)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_port
endpoint = f'http://127.0.0.1:{port}/api/test/123/_json'
logs_moved = False
try:
    adb('reverse', f'tcp:{port}', f'tcp:{port}')
    if had_logs:
        private('mv files/logs files/logs-full-upload-backup')
    logs_moved = True
    private('mkdir -p files/logs shared_prefs')
    private('cat > files/logs/previous-7.log', data=('\n'.join(
        f'full_avd_line={i} codec=H264 ' + 'counter ' * 15 for i in range(6200)) + '\n').encode())
    private('cat > shared_prefs/l7_remote_log.xml', data=(
        f'<map><string name="endpoint">{endpoint}</string><string name="authorization">Basic dGVzdDpwYXNz</string></map>').encode())
    private('rm -f shared_prefs/l7_log_history.xml')
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
        package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-debug-logs')
    visible = texts()
    for _ in range(5):
        if f'http://127.0.0.1:{port}/api/test/' in visible:
            break
        adb('shell', 'input', 'swipe', '1050', '1600', '1050', '700', '200')
        visible = texts()
    assert f'http://127.0.0.1:{port}/api/test/' in visible, '未确认本机地址，禁止上传'
    tap('上传日志')
    assert '开始上传' in texts(), '必须先填写备注并确认'
    assert not received, '打开备注弹窗不能发送请求'
    (output / 'remark.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('取消')
    assert not received, '取消备注不能发送请求'
    tap('上传日志')
    edit = next(n for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', edit.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    adb('shell', 'input', 'text', remark)
    adb('shell', 'input', 'keyevent', '4')
    tap('开始上传')
    deadline = time.monotonic() + 60
    while 'HTTP 503' not in texts():
        assert time.monotonic() < deadline, '未到失败重试状态'
    first_attempt = list(received)
    assert any(i == 3 for i, _ in first_attempt)
    (output / 'failed-batch.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    private('cat > files/logs/previous-7.log', data=b'newer unrelated session\n')
    tap('重试上传')
    deadline = time.monotonic() + 60
    while '上传成功' not in texts():
        assert time.monotonic() < deadline, '未完成完整上传'
    failed_bodies = [body for index, body in received if index == 3]
    assert len(failed_bodies) == 2 and failed_bodies[0] == failed_bodies[1], '重试必须使用原批次正文和事件编号'
    confirmed = {index for index, _ in first_attempt if index != 3}
    assert confirmed.isdisjoint(index for index, _ in received[len(first_attempt):]), '不能重发已确认批次'
    assert sorted(accepted) == list(range(6200)), '上下文记录必须全部收到且没有已确认批次重复'
    ids = {row['report_id'] for row in accepted_rows}
    assert len(ids) == 1 and re.fullmatch(r'[a-f0-9]{32}', next(iter(ids)))
    report_id = next(iter(ids))
    assert all(row['upload_remark'] == remark for row in accepted_rows)
    boundaries = [row for row in accepted_rows if row['source'] == 'upload']
    assert len(boundaries) == 2
    begin = next(row for row in boundaries if row['message'].startswith('UPLOAD_BEGIN '))
    end = next(row for row in boundaries if row['message'].startswith('UPLOAD_END '))
    assert begin['line_index'] == min(row['line_index'] for row in accepted_rows)
    assert end['line_index'] == max(row['line_index'] for row in accepted_rows)
    assert end['message'].endswith(f'records={len(accepted_rows)}')
    assert all(report_id in row['message'] and remark in row['message'] for row in boundaries)
    assert sum(len(body) for _, body in received) > 1024 * 1024
    assert private('ls -A cache/log-upload-snapshots').strip() == b'', '成功后仍有快照残留'
    (output / 'complete.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    history = ET.fromstring(private('cat shared_prefs/l7_log_history.xml'))
    saved = {node.get('name'): node.text for node in history if node.tag == 'string'}
    assert saved['report_id'] == report_id and saved['upload_remark'] == remark
    assert set(received_streams) == {saved['stream']}, '回执必须保存实际写入流名'
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity',
        '--es', 'page', 'settings-debug-logs')
    visible = texts()
    for _ in range(8):
        if any(n.get('text') == '上传状态' for n in nodes().iter('node')):
            break
        adb('shell', 'input', 'swipe', '1050', '1600', '1050', '700', '200')
        visible = texts()
    assert report_id in visible and remark in visible, '重启后应保留完整回执'
    tap('上传状态')
    (output / 'receipt.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    (output / 'result.json').write_text(json.dumps({'retained_fixture_lines': len(accepted),
        'requests': len(received), 'total_request_bytes': sum(len(b) for _, b in received),
        'batch_order': [i for i, _ in received], 'report_id': report_id, 'upload_remark': remark,
        'total_records': len(accepted_rows), 'boundaries': boundaries,
        'persisted_receipt': saved}, indent=2))
    print('AVD 备注确认／取消、首尾编号、完整6200条、原快照续传及重启回执通过，仅发本机接口。')
finally:
    adb('shell', 'am', 'force-stop', package)
    if logs_moved:
        private('rm -rf files/logs')
        if had_logs:
            private('mv files/logs-full-upload-backup files/logs')
    for path, data in backups.items():
        if data is None:
            private('rm -f ' + path)
        else:
            private('cat > ' + path, data=data)
    adb('reverse', '--remove', f'tcp:{port}')
    server.shutdown()
    server.server_close()
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity')
