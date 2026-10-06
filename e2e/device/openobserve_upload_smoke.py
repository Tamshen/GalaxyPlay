#!/usr/bin/env python3
"""显式授权后从 AVD 点击上传，并只查询本次合成测试记录；不在实车运行。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--send', action='store_true', help='实际点击上传，须先获得用户授权')
parser.add_argument('--verify-query', action='store_true', help='另行查询本次测试记录，需要查询权限')
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
if not args.send or not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许显式 --send 的 AVD 联调')
root = Path(__file__).resolve().parents[2]
out = root / 'build/previews/openobserve'
out.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
config = {}
for line in (root / '.env').read_text().splitlines():
    if line.startswith('L7_LOG_') and '=' in line:
        key, value = line.split('=', 1)
        config[key] = value.strip().strip('\"\'')
endpoint = config['L7_LOG_SERVER_URL']
authorization = config['L7_LOG_AUTHORIZATION']
url = urllib.parse.urlsplit(endpoint)
prefix, organization, example_stream, suffix = url.path.rsplit('/', 3)
assert suffix == '_json' and prefix.endswith('/api')
search_url = urllib.parse.urlunsplit((url.scheme, url.netloc, f'{prefix}/{organization}/_search', '', ''))
marker = 'L7_AVD_SMOKE_' + uuid.uuid4().hex
started = time.time()

def adb(*parts, binary=False):
    data = subprocess.check_output(base + list(parts), stderr=subprocess.DEVNULL)
    return data if binary else data.decode()

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-openobserve-upload.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-openobserve-upload.xml')).iter('node'))

def find(label):
    for start, end in (('1600', '650'), ('650', '1600')):
        for _ in range(6):
            match = next((n for n in nodes() if n.get('text') == label), None)
            if match is not None:
                return match
            adb('shell', 'input', 'swipe', '1000', start, '1000', end, '250')
    raise AssertionError('找不到入口：' + label)

adb('shell', 'am', 'start', '-n', 'com.ecarx.carplay/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-debug-logs')
find('OpenObserve 日志服务器')
find('日志名称')
stream = next(n.get('text') for n in nodes() if re.fullmatch(r'(?:[a-z0-9]+(?:_[a-z0-9]+)*_)?[a-f0-9]{5}(?:_[a-f0-9]{5}){3}', n.get('text', '')))
actual_endpoint = urllib.parse.urlunsplit((url.scheme, url.netloc, f'{prefix}/{organization}/{stream}/_json', '', ''))
if example_stream == '{HeadUnit}-{DeviceID}':
    actual_endpoint = endpoint.replace('{HeadUnit}', stream[:-24]).replace('{DeviceID}', stream[-23:])
elif example_stream == '{DeviceID}':
    actual_endpoint = endpoint.replace('{DeviceID}', stream)
assert actual_endpoint in [n.get('text') for n in nodes()], '应用实际上传地址与 .env 的组织及本机日志名称不一致，未上传'
lines = '\n'.join(f'{marker} sample={index} level=info AVD synthetic upload verification' for index in range(1, 4)) + '\n'
command = 'run-as com.ecarx.carplay sh -c ' + shlex.quote('mkdir -p files/logs && cat >> files/logs/diplay.log')
subprocess.run(base + ['shell', command], input=lines.encode(), check=True, capture_output=True)
node = find('上传日志')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
success = ''
for _ in range(12):
    values = [n.get('text', '') for n in nodes()]
    success = next((text for text in values if text.startswith('上传成功')), '')
    failure = next((text for text in values if text.startswith(('上传失败', '上传结果未确认', 'OpenObserve 未全部写入'))), '')
    if success or failure:
        break
    time.sleep(.5)
assert success and stream in success, '应用未确认完整上传成功：' + failure
(out / 'uploaded.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
result = {'stream': stream, 'report_prefix': success.split('报告 ')[-1],
          'marker': marker, 'ingestion_confirmed': True, 'query_status': 'not_requested'}
(out / 'upload-result.json').write_text(json.dumps(result, indent=2) + '\n')
print('AVD 上传成功，已收到服务端全部写入确认。')
print(json.dumps(result))
if not args.verify_query:
    raise SystemExit(0)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *unused):
        return None

opener = urllib.request.build_opener(NoRedirect())
sql = f'SELECT * FROM "{stream}" WHERE message LIKE \'%{marker}%\' ORDER BY line_index'
hits = []
for attempt in range(4):
    query = {'query': {'sql': sql, 'start_time': int((started - 300) * 1e6),
             'end_time': int((time.time() + 300) * 1e6), 'from': 0, 'size': 10}, 'search_type': 'ui'}
    request = urllib.request.Request(search_url, data=json.dumps(query).encode(), headers={
        'Content-Type': 'application/json', 'Authorization': authorization}, method='POST')
    try:
        with opener.open(request, timeout=15) as response:
            hits = json.load(response).get('hits', [])
    except urllib.error.HTTPError as error:
        result['query_status'] = error.code
        (out / 'upload-result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(f'查询验证返回 HTTP {error.code}；上传已确认，未输出凭据或响应正文。')
        raise SystemExit(2) from None
    if len(hits) == 3:
        break
    time.sleep(2)
assert len(hits) == 3 and all('device_id' not in hit and marker in hit['message'] for hit in hits)
assert len({hit['report_id'] for hit in hits}) == 1
result.update(report_id=hits[0]['report_id'], verified_samples=len(hits), query_status=200)
(out / 'upload-result.json').write_text(json.dumps(result, indent=2) + '\n')
print('AVD 点击上传与服务端查询通过；本机日志流、报告编号一致，确认 3 条不含 device_id 的合成记录。')
print(json.dumps(result))
