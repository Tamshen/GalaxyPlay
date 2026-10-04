#!/usr/bin/env python3
"""显式联调 OpenObserve 请求头；只向当前 AVD 日志流发送合成记录，不读取运行日志。"""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--send', action='store_true')
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
if not args.send or not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许显式 --send 的 AVD 联调')
root = Path(__file__).resolve().parents[2]
config = {}
for line in (root / '.env').read_text().splitlines():
    if line.startswith('L7_LOG_') and '=' in line:
        key, value = line.split('=', 1)
        config[key] = value.strip().strip('\"\'')
prefs = ET.fromstring(subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'run-as',
    'com.ecarx.carplay', 'cat', 'shared_prefs/l7_log_device.xml'], stderr=subprocess.DEVNULL))
device = prefs.find("string[@name='id']").text
assert re.fullmatch(r'[a-z0-9_]+_[a-f0-9]{5}(?:_[a-f0-9]{5}){3}', device)
base, slot, suffix = config['L7_LOG_SERVER_URL'].rsplit('/', 2)
assert suffix == '_json'
target = device[-23:] if slot == '{DeviceID}' else device
if slot == '{HeadUnit}-{DeviceID}':
    target = device[:-24] + '-' + device[-23:]
endpoint = base + '/' + target + '/_json'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *unused):
        return None

opener = urllib.request.build_opener(NoRedirect())
for content_type in ('application/json; charset=utf-8', 'application/json'):
    report = str(uuid.uuid4())
    body = json.dumps([{'report_id': report, 'event_id': report+':0',
        'collected_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'app_version': 'transport-test', 'core_version': 'test', 'line_index': 0,
        'source': 'collection', 'batch_index': 1, 'batch_count': 1,
        'message': 'synthetic OpenObserve request header verification'}]).encode()
    request = urllib.request.Request(endpoint, data=body, method='POST', headers={
        'Content-Type': content_type, 'Authorization': config['L7_LOG_AUTHORIZATION']})
    try:
        with opener.open(request, timeout=15) as response:
            status, result = response.status, response.read(4096).decode(errors='replace')
    except urllib.error.HTTPError as error:
        status, result = error.code, error.read(4096).decode(errors='replace')
    except Exception as error:
        print(json.dumps({'content_type': content_type, 'error': type(error).__name__}))
        continue
    for value in (config['L7_LOG_AUTHORIZATION'], endpoint, base, target, device):
        result = result.replace(value, '[redacted]')
    result = re.sub(r'https?://[^\s\"<>]+|[\w.+-]+@[\w.-]+|\b(?:\d{1,3}\.){3}\d{1,3}\b', '[redacted]', result)
    print(json.dumps({'content_type': content_type, 'http': status, 'response': result[:1000]}, ensure_ascii=False))
