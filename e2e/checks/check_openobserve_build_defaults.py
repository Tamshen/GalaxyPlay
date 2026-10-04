#!/usr/bin/env python3
"""用合成配置验证 .env、环境变量优先级及常驻容器清除旧值；不发送网络日志。"""
import base64
import os
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
path = root / '.env'
backup = path.read_bytes() if path.exists() else None
env = dict(os.environ)
for key in ('L7_LOG_SERVER_URL', 'L7_LOG_AUTHORIZATION'): env.pop(key, None)
env['DIPLAY_AUTH_ASSETS_DIR'] = env.get('DIPLAY_AUTH_ASSETS_DIR', str(root / '.private/auth-assets'))
auth = 'Basic ' + base64.b64encode(('test:' + str(uuid.uuid4())).encode()).decode()
url = 'https://logs.example/api/test/{HeadUnit}-{DeviceID}/_json'

def generate(environment):
    result = subprocess.run(['bash', 'scripts/build-android-docker.sh', '--arm64', '--warm', '--',
        ':mobile:generateDebugResValues'], cwd=root, env=environment, capture_output=True)
    if result.returncode:
        # 原始构建输出仅放忽略目录，凭据不打印。
        (root / 'build/host-logs/openobserve-config-check.log').write_bytes(result.stdout + result.stderr)
        raise AssertionError('默认值构建检查失败，查看本地构建日志')
    values = ET.parse(root / 'mobile/build/generated/res/resValues/debug/values/gradleResValues.xml').getroot()
    return {v.get('name'): (v.text or '').strip('"') for v in values}

try:
    path.write_text(f'L7_LOG_SERVER_URL="{url}"\nL7_LOG_AUTHORIZATION="{auth}"\n')
    values = generate(env)
    assert values['l7_log_default_url'] == url
    assert values['l7_log_default_authorization'] == auth
    override = 'https://override.example/api/test/other/_json'
    values = generate(dict(env, L7_LOG_SERVER_URL=override))
    assert values['l7_log_default_url'] == override
    assert values['l7_log_default_authorization'] == auth
    values = generate(env)
    assert values['l7_log_default_url'] == url, '常驻容器残留了上次环境变量'
    print('打包默认值检查通过：.env 字面量、认证值、环境变量优先级与常驻容器恢复。')
finally:
    if backup is None: path.unlink(missing_ok=True)
    else: path.write_bytes(backup)
    generate(env)
