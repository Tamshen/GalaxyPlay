#!/usr/bin/env python3
"""AVD 检查恢复面板中英文昼夜及操作；注入合成面板，不建立连接或模拟解码成功。"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import frida

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--frida-address', help='已转发到该模拟器的 127.0.0.1:端口')
parser.add_argument('--output-dir', type=Path, default=Path('build/previews/video-recovery'))
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许 AVD'
package = 'com.ecarx.carplay'
args.output_dir.mkdir(parents=True, exist_ok=True)


def adb(*parts, data=None):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], input=data, stderr=subprocess.PIPE)


def read(path):
    try:
        return adb('exec-out', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        if b'No such file' in error.stderr:
            return None
        raise


def write(path, data):
    assert re.fullmatch(r'shared_prefs/[a-z0-9_.-]+', path)
    if data is None:
        adb('shell', 'run-as', package, 'rm', '-f', path)
    else:
        adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {path}'", data=data)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/galaxyplay-video-recovery.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/galaxyplay-video-recovery.xml'))


def tap(label):
    root = nodes()
    node = next(n for n in root.iter('node') if n.get('text') == label)
    assert node.get('clickable') == 'true' and node.get('enabled') == 'true', label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert x2 > x1 and y2 > y1, '动作必须有可触达区域'
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.2)


agent = r"""
var panel, parent, retries = 0, settings = 0, accepted = false;
function ui(task) {
    return new Promise(function(resolve, reject) {
        Java.perform(function() {
            Java.scheduleOnMainThread(function() {
                try { resolve(task()); } catch (e) { reject(e); }
            });
        });
    });
}
rpc.exports = {
    show: function() {
        return ui(function() {
            var activity;
            Java.choose('com.shilapi.xcertplay.DiPlayActivity', {
                onMatch: function(a) {
                    if (!a.isDestroyed() && a.hasWindowFocus()) {
                        activity = Java.retain(a); return 'stop';
                    }
                }, onComplete: function() {}
            });
            if (!activity) throw new Error('No foreground settings Activity');
            var BooleanBox = Java.use('java.lang.Boolean');
            var Unit = Java.use('kotlin.Unit');
            var Function0 = Java.use('kotlin.jvm.functions.Function0');
            var Retry = Java.registerClass({
                name: 'com.shilapi.xcertplay.e2e.VideoRetry', implements: [Function0],
                methods: {invoke: function() { retries++; return BooleanBox.valueOf(accepted); }}
            });
            var Settings = Java.registerClass({
                name: 'com.shilapi.xcertplay.e2e.VideoSettings', implements: [Function0],
                methods: {invoke: function() { settings++; return Unit.INSTANCE.value; }}
            });
            parent = Java.cast(activity.getWindow().getDecorView(), Java.use('android.view.ViewGroup'));
            panel = Java.use('com.shilapi.xcertplay.L7VideoRecoveryPanel').$new(activity, Retry.$new(), Settings.$new());
            parent.addView(panel, Java.use('android.widget.FrameLayout$LayoutParams').$new(-1, -1));
            panel.failed();
            return {retries: retries, settings: settings, visible: panel.getVisibility()};
        });
    },
    accept: function() { accepted = true; },
    state: function() { return ui(function() {
        return {retries: retries, settings: settings, visible: panel.getVisibility()};
    }); },
    recover: function() { return ui(function() { panel.recovered(); }); },
    remove: function() { return ui(function() { if (panel) parent.removeView(panel); }); }
};
"""

paths = ('shared_prefs/diplay.xml', 'shared_prefs/l7_agreement.xml')
adb('shell', 'am', 'force-stop', package)
original = {path: read(path) for path in paths}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
digest = hashlib.sha256(Path('galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').read_bytes()).hexdigest()
if args.frida_address:
    assert re.fullmatch(r'127\.0\.0\.1:\d+', args.frida_address), '只允许本地测试转发'
    port = args.frida_address.split(':')[1]
    assert any(line.split()[:2] == [args.serial, 'tcp:' + port]
               for line in adb('forward', '--list').decode().splitlines()), '转发必须属于指定 AVD'
    device = frida.get_device_manager().add_remote_device(args.frida_address)
else:
    device = frida.get_device(args.serial, timeout=5)
session = script = None
labels = {}
for language, folder in (('zh', 'values'), ('en', 'values-en')):
    resources = ET.parse(f'galaxy/common/src/main/res/{folder}/l7_media_diagnostics.xml').getroot()
    strings = {s.get('name'): s.text for s in resources.findall('string')}
    labels[language] = tuple(strings['l7_video_' + key] for key in
                             ('failed_title', 'retry', 'settings', 'retrying', 'retry_unavailable'))
try:
    for language in ('zh', 'en'):
        for theme in ('no', 'yes'):
            adb('shell', 'am', 'force-stop', package)
            prefs = ET.fromstring(original[paths[0]] or b'<map/>')
            for child in list(prefs):
                if child.get('name') in ('app_language', 'auto_connect'):
                    prefs.remove(child)
            ET.SubElement(prefs, 'string', name='app_language').text = language
            ET.SubElement(prefs, 'boolean', name='auto_connect', value='false')
            write(paths[0], ET.tostring(prefs, encoding='utf-8'))
            write(paths[1], f'<map><string name="accepted_digest">{digest}</string></map>'.encode())
            adb('shell', 'cmd', 'uimode', 'night', theme)
            adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity',
                '--es', 'page', 'settings-connection')
            pid = int(adb('shell', 'pidof', package).decode().strip())
            session = device.attach(pid)
            script = session.create_script(agent)
            script.load()
            assert script.exports_sync.show() == {'retries': 0, 'settings': 0, 'visible': 0}
            title, retry, setting, waiting, unavailable = labels[language]
            root = nodes()
            texts = [n.get('text', '') for n in root.iter('node')]
            assert title in texts and retry in texts and setting in texts
            if language == 'en':
                assert not any(re.search(r'[\u3400-\u9fff]', t) for t in texts), '英文面板存在中文'
            stem = language + '-' + theme
            (args.output_dir / (stem + '-failed.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            tap(retry)
            assert unavailable in [n.get('text') for n in nodes().iter('node')]
            script.exports_sync.accept()
            tap(retry)
            assert waiting in [n.get('text') for n in nodes().iter('node')]
            assert script.exports_sync.state() == {'retries': 2, 'settings': 0, 'visible': 0}
            tap(setting)
            assert script.exports_sync.state() == {'retries': 2, 'settings': 1, 'visible': 0}
            (args.output_dir / (stem + '-retrying.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            script.exports_sync.recover()
            assert script.exports_sync.state()['visible'] == 8
            script.exports_sync.remove()
            session.detach()
            session = script = None
            print(stem + '：失败、拒绝重试、接收重试仍显示、显式设置、恢复隐藏通过。', flush=True)
finally:
    if session:
        try:
            if script:
                script.exports_sync.remove()
        except Exception:
            # 注入进程已退出也要继续恢复偏好；主测试异常仍由外层传播。
            pass
        finally:
            try:
                session.detach()
            except Exception:
                pass
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        write(path, data)
        assert read(path) == data, '偏好恢复失败'
    adb('shell', 'cmd', 'uimode', 'night', night)
    adb('shell', 'rm', '-f', '/sdcard/galaxyplay-video-recovery.xml')
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.DiPlayActivity', '--es', 'page', 'settings-connection')
print('AVD 恢复面板检查通过，原偏好／协议／昼夜恢复；合成回调不代表实车解码恢复。')
