#!/usr/bin/env python3
"""应用 L7 AVD 的逻辑密度预览档，实车系统密度需单独采集校准。"""
import argparse
import json
import math
import subprocess
from pathlib import Path

WIDTH, HEIGHT, DIAGONAL_INCHES = 1440, 1920, 13.2
# 物理 PPI 不能推导 Android 的 UI 缩放；此值是可读性预览档，不是实车规格。
DEFAULT_PREVIEW_DENSITY = 320
ROOT = Path(__file__).resolve().parents[1]


def density_value(value):
    try:
        density = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError('密度必须为整数')
    if not 80 <= density <= 640:
        raise argparse.ArgumentTypeError('AVD 密度应在 80～640 之间')
    return density


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(ROOT.parent / 'tools/scripts/adb.sh'))
    parser.add_argument('--density', type=density_value, help='用户指定逻辑密度；默认 320 为预览档，非实车实测值')
    parser.add_argument('--check', action='store_true', help='只读核对当前配置，不修改设备')
    parser.add_argument('--report', type=Path, help='保存配置检查 JSON，不读取应用私有数据')
    args = parser.parse_args()
    density = args.density if args.density is not None else DEFAULT_PREVIEW_DENSITY

    def adb(*command):
        return subprocess.run([args.adb, '-s', 'emulator-5556', *command],
                              check=True, capture_output=True, text=True, timeout=15).stdout.strip()

    # 固定序列号仍可能被其他实例占用，修改前同时检查 AVD 名称及系统版本。
    name = adb('emu', 'avd', 'name').splitlines()[0]
    if name != 'carplay_unpack' or adb('shell', 'getprop', 'ro.build.version.sdk') != '30':
        raise RuntimeError('目标不是 Android 11 的 carplay_unpack，停止配置')
    if not args.check:
        for command in (
            ('wm', 'size', f'{WIDTH}x{HEIGHT}'),
            ('wm', 'density', str(density)),
            ('settings', 'put', 'system', 'font_scale', '1.0'),
            ('settings', 'put', 'system', 'accelerometer_rotation', '0'),
            ('settings', 'put', 'system', 'user_rotation', '0'),
        ):
            adb('shell', *command)

    size_output = adb('shell', 'wm', 'size')
    density_output = adb('shell', 'wm', 'density')
    # 有覆盖值时使用覆盖值；不将 Physical density 字段当成实车屏幕测量。
    size = size_output.splitlines()[-1].split(':', 1)[-1].strip()
    actual_density = int(density_output.splitlines()[-1].split(':', 1)[-1].strip())
    state = {
        'avd': name, 'serial': 'emulator-5556', 'android_api': 30,
        'screen_pixels': size, 'screen_diagonal_inches': DIAGONAL_INCHES,
        'physical_ppi_estimate': math.hypot(WIDTH, HEIGHT) / DIAGONAL_INCHES,
        'density_source': '预览调试基线，待实车系统密度校准' if args.density is None else '用户指定，来源需另行核实',
        'requested_density': density, 'effective_density': actual_density,
        'wm_size': size_output, 'wm_density': density_output,
        'full_screen_dp_estimate': [round(WIDTH * 160 / actual_density, 2), round(HEIGHT * 160 / actual_density, 2)],
        'font_scale': adb('shell', 'settings', 'get', 'system', 'font_scale'),
        'accelerometer_rotation': adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation'),
        'user_rotation': adb('shell', 'settings', 'get', 'system', 'user_rotation'),
    }
    print(json.dumps(state, ensure_ascii=False, indent=2))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if (size != f'{WIDTH}x{HEIGHT}' or actual_density != density
            or state['font_scale'] != '1.0' or state['accelerometer_rotation'] != '0'
            or state['user_rotation'] != '0'):
        raise RuntimeError('AVD 屏幕配置未达到请求状态，请检查输出')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(f'错误：{error}')
