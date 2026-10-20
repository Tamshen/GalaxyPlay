#!/usr/bin/env python3
"""仅限 API 30 AVD：验证真实扫描服务停扫、拒绝新扫描与恢复，不代表实车射频验收。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='../tools/scripts/adb.sh')
    parser.add_argument('--serial', default='emulator-5556')
    parser.add_argument('--apk', default='mobile/build/outputs/apk/debug/mobile-debug.apk')
    parser.add_argument('--output', default='build/previews/wifi-scan-guard/result.json')
    parser.add_argument('--observe-seconds', type=int, default=35)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), '仅允许 AVD'
    assert 5 <= args.observe_seconds <= 60

    def adb(*parts):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], check=True,
                                capture_output=True, text=True, timeout=25)
        return result.stdout.strip()

    assert adb('shell', 'getprop ro.kernel.qemu') == '1'
    assert adb('shell', 'getprop ro.build.version.sdk') == '30'
    assert 'uid=0(' in adb('shell', 'su 0 id')
    remote = '/data/local/tmp/galaxy-scanguard-smoke.apk'
    adb('push', args.apk, remote)
    helper = 'com.shilapi.xcertplay.network.GalaxyScanServiceMain'

    def command(action, root=True):
        prefix = 'su 0 ' if root else ''
        output = adb('shell', prefix + 'env CLASSPATH=' + shlex.quote(remote) +
                     ' app_process /system/bin ' + helper + ' ' + action)
        values = re.findall(r'^GALAXY_SCAN_SERVICE_V1\|(\w+)$', output, re.M)
        assert len(values) == 1, '助手未返回有效协议结果'
        return values[0]

    def wifi():
        return {'enabled': 'Wifi is enabled' in adb('shell', 'cmd wifi status'),
                'ipv4': re.findall(r'inet ([\d.]+/\d+)', adb('shell', 'ip -o -4 addr show wlan0'))}

    result = {'before': command('INSPECT'), 'wifi_before': wifi()}
    assert result['before'] == 'ENABLED', '原状态未开启，不接管其他所有者的关闭状态'
    try:
        result['unprivileged'] = command('DISABLE', root=False)
        assert result['unprivileged'] == 'DENIED'
        assert command('INSPECT') == 'ENABLED'
        result['disabled'] = command('DISABLE')
        assert result['disabled'] == 'DISABLED'
        result['state_checks'] = []
        until = time.monotonic() + args.observe_seconds
        while time.monotonic() < until:
            adb('shell', 'cmd wifi start-scan')
            time.sleep(min(5, max(0, until - time.monotonic())))
            state = command('INSPECT')
            result['state_checks'].append(state)
            assert state == 'DISABLED'
        result['observation_seconds'] = args.observe_seconds
        result['after_scan_request'] = command('INSPECT')
        result['wifi_disabled'] = wifi()
        assert result['after_scan_request'] == 'DISABLED'
        assert result['wifi_disabled'] == result['wifi_before'], '停扫不应关闭 Wi-Fi 或清除原连接地址'
    finally:
        result['restored'] = command('ENABLE')
        result['wifi_restored'] = wifi()
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        assert result['restored'] == 'ENABLED', '扫描未恢复，需立即检查模拟器状态'
        adb('shell', 'rm ' + shlex.quote(remote))
    print('AVD 扫描服务停扫、权限拒绝、Wi-Fi 保持与恢复检查通过；实车 P2P / 音乐待验收。')


if __name__ == '__main__':
    main()
