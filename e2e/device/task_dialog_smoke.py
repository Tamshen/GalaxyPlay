#!/usr/bin/env python3
"""AVD 验证任务弹窗、上传历史与悬浮日志开关；仅上传到本机模拟接口，结束恢复配置及历史。"""
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
parser.add_argument('--failure-only', action='store_true', help='仅复核上传失败重试、历史与悬浮日志开关')
parser.add_argument('--clear-only', action='store_true', help='仅复核清空后空上传、服务端删除中提示与旧重试丢弃')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
prefs = 'shared_prefs/l7_remote_log.xml'
history_prefs = 'shared_prefs/l7_log_history.xml'
output = Path('build/previews/task-dialog')
output.mkdir(parents=True, exist_ok=True)
mode = 'hold'
release = threading.Event()
received = []
accepted = []


def adb(*parts):
    return subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)


class Server(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        rows = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        received.append(rows[0]['report_id'])
        current = mode
        if current == 'success':
            accepted.append((rows[0]['report_id'], len(rows)))
        code = 400 if current == 'deleting' else 503 if current == 'fail' else 200
        body = json.dumps({'code': 200, 'status': [{'name': self.path.split('/')[-2],
                           'successful': len(rows), 'failed': 0}]}).encode()
        if current == 'deleting':
            body = json.dumps({'code': 400, 'message': 'Error# stream [synthetic] is being deleted'}).encode()
        try:
            self.send_response(code)
            padding = 50 if current == 'hold' else 0
            self.send_header('Content-Length', str(len(body)+padding))
            self.end_headers()
            # 分段回送 JSON 前导空白，模拟仍有数据的慢响应，不改变正式应用的 8 秒读取超时。
            for index in range(padding):
                self.wfile.write(b' '); self.wfile.flush()
                if release.wait(1):
                    self.wfile.write(b' ' * (padding-index-1))
                    break
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


server = ThreadingHTTPServer(('127.0.0.1', 0), Server)
server.daemon_threads = True
port = server.server_port
endpoint = f'http://127.0.0.1:{port}/api/test/123/_json'
threading.Thread(target=server.serve_forever, daemon=True).start()


def nodes():
    dumped = adb('shell', 'uiautomator', 'dump', '/sdcard/l7-task-dialog-test.xml')
    assert b'UI hierchary dumped to:' in dumped, '无法读取当前界面'
    return ET.fromstring(adb('shell', 'cat', '/sdcard/l7-task-dialog-test.xml'))


def texts():
    return [n.get('text', '') for n in nodes().iter('node')]


def wait_text(label):
    for _ in range(8):
        if label in texts():
            return
    raise AssertionError('未进入状态：'+label)


def find(label):
    root = nodes()
    parents = {c: p for p in root.iter() for c in p}
    for item in root.iter('node'):
        if label not in (item.get('text'), item.get('content-desc')):
            continue
        while item.get('clickable') != 'true' and item in parents:
            item = parents[item]
        if item.get('clickable') == 'true':
            return item
    raise AssertionError('入口不可达：'+label)


def click(node):
    assert node.get('enabled') == 'true'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def tap(label):
    click(find(label))


def launch(page='settings-debug'):
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n',
        package+'/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', page)


def screenshot(name):
    (output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def write_prefs(data, path=prefs):
    command = 'run-as '+package+' sh -c '+shlex.quote('mkdir -p shared_prefs; cat > '+path)
    subprocess.run(base+['shell', command], input=data, check=True, capture_output=True)


def read_prefs(path):
    result = subprocess.run(base+['exec-out', 'run-as', package, 'cat', path], capture_output=True)
    # exec-out 可能返回成功退出码，但把文件不存在写在 stdout。
    if result.stdout.startswith(b'cat:') and b'No such file' in result.stdout:
        return None
    if result.returncode:
        assert b'No such file' in result.stdout+result.stderr, '无法备份配置，停止测试'
        return None
    assert ET.fromstring(result.stdout).tag == 'map', '配置异常，停止测试'
    return result.stdout


def uploaded_summary():
    return next(t for t in texts() if t.startswith('已上传\n上次成功：'))


adb('shell', 'am', 'force-stop', package)
backups = {path: read_prefs(path) for path in (prefs, history_prefs)}
original_night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
permission = adb('shell', 'cmd', 'appops', 'get', package, 'SYSTEM_ALERT_WINDOW').decode()
match = re.search(r'SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)', permission)
original_permission = match.group(1) if match else 'default'
try:
    adb('reverse', f'tcp:{port}', f'tcp:{port}')
    write_prefs(f'<map><string name="endpoint">{endpoint}</string><string name="authorization">Basic dGVzdDpwYXNz</string></map>'.encode())
    adb('shell', 'run-as', package, 'rm', '-f', history_prefs)
    launch('settings-debug-logs')
    # 先确认应用实际使用本机地址，再允许点击上传；不读取或发送真实服务器凭据。
    for _ in range(5):
        if any(t.startswith(f'http://127.0.0.1:{port}/api/test/') and t.endswith('/_json') for t in texts()):
            break
        adb('shell', 'input', 'swipe', '1050', '1600', '1050', '700', '200')
    assert any(t.startswith(f'http://127.0.0.1:{port}/api/test/') and t.endswith('/_json') for t in texts()), '未确认本机模拟地址，禁止上传'
    launch()
    assert not {'停止本轮', '重试上传', '取消上传'} & set(texts())
    assert '未上传' in texts()
    if args.clear_only:
        for label in ('清空日志', '清空报告'):
            tap(label); tap('清空')
            assert '部分内容未能清空，请重试。' not in texts()
        before = len(received)
        tap('上传日志'); wait_text('暂无可上传日志')
        assert len(received) == before
        assert '重试上传' not in texts()
        screenshot('empty-after-clear'); tap('关闭')
        command = 'run-as '+package+' sh -c '+shlex.quote('mkdir -p files/logs; cat >> files/logs/diplay.log')
        subprocess.run(base+['shell', command], input=b'synthetic clear-upload verification\n', check=True, capture_output=True)
        mode = 'deleting'
        tap('上传日志'); wait_text('上传失败')
        assert any('日志流正在删除中' in t for t in texts())
        screenshot('stream-deleting'); tap('关闭')
        old = received[-1]
        tap('清空报告'); tap('清空'); texts()
        tap('上传日志'); wait_text('上传失败')
        assert received[-1] != old, '清空后仍使用旧重试快照'
        tap('关闭')
        tap('清空日志'); tap('清空'); texts()
        before = len(received)
        tap('上传日志'); wait_text('暂无可上传日志')
        assert len(received) == before
        tap('关闭')
        print('AVD 通过：清空后无内容不请求、删除中 HTTP 400 明确提示、清空报告丢弃旧重试；仅访问本机模拟服务。')
        raise SystemExit(0)
    if not args.failure_only:
        collect = next(t for t in texts() if t in ('收集环境与调试信息', '重新收集环境与调试信息'))
        tap(collect)
        for _ in range(8):
            if '检查完成' in texts():
                break
        assert '检查完成' in texts()
        screenshot('collection-complete')
        tap('查看检查结果')
        assert {'项目', '状态', '原因'} <= set(texts())
        launch(); tap('上传日志')
        assert '停止上传' in texts() and received
        screenshot('upload-progress')
        adb('shell', 'input', 'keyevent', '4')
        assert '终止当前任务？' in texts()
        screenshot('upload-exit-confirm')
        tap('继续等待')
        assert '停止上传' in texts()
        tap('停止上传')
        assert any('已停止上传' in t for t in texts())
        release.set(); tap('关闭')
        assert '重试上传' not in texts()
    mode = 'fail'
    tap('上传日志')
    wait_text('上传失败')
    assert '重试上传' in texts()
    failed_id = received[-1]
    count = len(received)
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    nodes()  # 等待主题刷新后再截图。
    screenshot('upload-failed-night')
    tap('关闭')
    assert '重试上传' not in texts() and '未上传' in texts()
    tap('上传日志')
    assert '重试上传' in texts() and len(received) == count, '打开失败提示自动发送了请求'
    mode = 'success'
    tap('重试上传')
    wait_text('上传完成')
    assert all(value == failed_id for value in received[count:])
    screenshot('upload-complete-night')
    tap('关闭')
    summary = uploaded_summary()
    line_count = sum(count for report, count in accepted if report == failed_id)
    assert f'共 {line_count} 条日志' in summary and line_count > 0
    screenshot('upload-history')
    persisted = read_prefs(history_prefs)
    assert persisted is not None
    # 杀进程再打开，验证时间和条数来自持久记录；后续失败或取消不能覆盖它。
    adb('shell', 'am', 'force-stop', package)
    launch()
    assert uploaded_summary() == summary
    mode = 'fail'
    tap('上传日志'); wait_text('上传失败'); tap('关闭')
    assert uploaded_summary() == summary and read_prefs(history_prefs) == persisted
    mode = 'hold'
    release.clear()
    tap('上传日志'); tap('重试上传'); wait_text('停止上传')
    tap('停止上传')
    assert any('已停止上传' in t for t in texts())
    release.set(); tap('关闭')
    assert uploaded_summary() == summary and read_prefs(history_prefs) == persisted
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    adb('shell', 'cmd', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', 'deny')
    launch('settings-debug-logs')
    switches = [n for n in nodes().iter('node') if n.get('class') == 'android.widget.Switch']
    assert len(switches) == 1 and switches[0].get('checked') == 'false'
    assert not {'显示悬浮日志', '关闭悬浮日志'} & set(texts())
    screenshot('overlay-switch-off')
    click(switches[0])
    assert any('系统设置中' in t for t in texts())
    tap('取消')
    switch = next(n for n in nodes().iter('node') if n.get('class') == 'android.widget.Switch')
    assert switch.get('checked') == 'false'
    print('AVD 通过：'+('失败重试与开关' if args.failure_only else '收集结果、上传进度/停止/返回确认/失败重试/成功、开关')+'；上传历史的时间/条数、重启保留、失败/取消不覆盖；未访问真实日志服务。')
except Exception:
    screenshot('failure')
    (output/'failure-state.json').write_text(json.dumps(texts(), ensure_ascii=False))
    raise
finally:
    adb('shell', 'am', 'force-stop', package)
    release.set()
    for path, backup in backups.items():
        if backup is None:
            adb('shell', 'run-as', package, 'rm', '-f', path)
        else:
            write_prefs(backup, path)
    adb('shell', 'cmd', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', original_permission)
    adb('shell', 'cmd', 'uimode', 'night', original_night)
    adb('reverse', '--remove', f'tcp:{port}')
    server.shutdown(); server.server_close()
    launch()
