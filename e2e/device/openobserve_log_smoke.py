#!/usr/bin/env python3
"""仅在 AVD 检查 OpenObserve 配置与按钮；不点击上传、不连接真实服务。"""
import argparse
import base64
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial): parser.error('只允许 AVD')
root = Path(__file__).resolve().parents[2]
out = root/'build/previews/openobserve'
out.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
prefs = 'shared_prefs/l7_remote_log.xml'
url = 'https://logs.example/api/test/galaxyplay/_json'
authorization = 'Basic ' + base64.b64encode(('test:' + str(uuid.uuid4())).encode()).decode()

def adb(*parts, binary=False):
    data = subprocess.check_output(base+list(parts), stderr=subprocess.DEVNULL)
    return data if binary else data.decode()

def launch():
    adb('shell', 'am', 'start', '-n', 'com.ecarx.carplay/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-debug-logs')
    time.sleep(.7)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/l7-openobserve.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/l7-openobserve.xml')).iter('node'))

def click(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.3)

def find(label):
    for start,end in (('1600','650'),('650','1600')):
        for _ in range(5):
            found=next((n for n in nodes() if n.get('text')==label),None)
            if found is not None:return found
            adb('shell','input','swipe','1000',start,'1000',end,'250')
    raise AssertionError('未找到入口：'+label)

def tap(label):click(find(label))
def screenshot(name):(out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))

def fill():
    fields=[n for n in nodes() if n.get('class')=='android.widget.EditText']
    assert len(fields)==2
    assert fields[0].get('text') in ('','OpenObserve 写入 URL'), '请使用未配置默认地址的测试 APK'
    click(fields[0]);adb('shell','input','text',url);adb('shell','input','keyevent','4')
    # 键盘出现会改变弹窗位置；每次输入都重新读取节点，不能复用旧坐标。
    fields=[n for n in nodes() if n.get('class')=='android.widget.EditText']
    click(fields[1]);adb('shell','input','text',authorization.replace(' ','%s'));adb('shell','input','keyevent','4')
    fields=[n for n in nodes() if n.get('class')=='android.widget.EditText']
    assert fields[1].get('password')=='true' and 'Basic ' not in fields[1].get('text',''), '认证输入未隐藏'

backup=subprocess.run(base+['exec-out','run-as','com.ecarx.carplay','cat',prefs],capture_output=True)
# exec-out 不保证透传远端 cat 的退出码；不能把缺少文件的错误文字还原成配置。
backup_data=None
if backup.stdout.strip():
    if backup.stdout.startswith(b'cat:') and b'No such file or directory' in backup.stdout:
        pass
    else:
        assert ET.fromstring(backup.stdout).tag=='map', '原配置格式异常，停止测试以免覆盖'
        backup_data=backup.stdout
try:
    adb('shell','am','force-stop','com.ecarx.carplay')
    adb('shell','run-as','com.ecarx.carplay','rm','-f',prefs)
    launch()
    find('上传状态')
    summary = next(n.get('text') for n in nodes() if n.get('text', '').startswith(('未上传', '已上传\n')))
    tap('OpenObserve 日志服务器');fill();screenshot('configuration');tap('取消')
    current=subprocess.run(base+['exec-out','run-as','com.ecarx.carplay','cat',prefs],capture_output=True).stdout.decode()
    assert url not in current, '取消却保存配置'
    tap('OpenObserve 日志服务器');fill();tap('保存')
    saved=ET.fromstring(adb('exec-out','run-as','com.ecarx.carplay','cat',prefs))
    values={n.get('name'):n.text for n in saved}
    assert values.get('endpoint')==url and values.get('authorization')==authorization
    find('上传日志');screenshot('configured')
    assert any(n.get('text') == summary for n in nodes()), '保存配置改变了上传历史'
    tap('OpenObserve 日志服务器');tap('恢复打包默认值')
    assert '请先填写 OpenObserve 写入地址和认证信息' in [n.get('text') for n in nodes()]
    screenshot('defaults')
    print('AVD 配置检查通过：双输入框、取消不保存、保存不上传、恢复默认值；未发送日志。')
finally:
    adb('shell','am','force-stop','com.ecarx.carplay')
    if backup_data is not None:
        command='run-as com.ecarx.carplay sh -c '+shlex.quote('cat > '+prefs)
        subprocess.run(base+['shell',command],input=backup_data,check=True,capture_output=True)
    else:adb('shell','run-as','com.ecarx.carplay','rm','-f',prefs)
    launch()
