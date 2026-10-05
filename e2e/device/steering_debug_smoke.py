#!/usr/bin/env python3
"""兼容旧测试入口，执行当前监听后标注流程的 AVD 检查。"""
from pathlib import Path
import runpy

runpy.run_path(str(Path(__file__).with_name('vehicle_steering_smoke.py')), run_name='__main__')
