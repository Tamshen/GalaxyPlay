#!/usr/bin/env python3
"""仅在 AVD 注入长更新说明，检查固定窗口、滚动正文及昼夜／字号／旋转，不下载或安装。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import frida

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='../tools/scripts/adb.sh')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--frida-address', default='127.0.0.1:28066')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial), '只允许模拟器'
assert re.fullmatch(r'127\.0\.0\.1:\d+', args.frida_address), '只允许本地转发'
base = [args.adb, '-s', args.serial]
package = 'com.ecarx.carplay'
output = Path('build/previews/app-update-layout')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(base + list(parts), stderr=subprocess.PIPE, timeout=30)


port = args.frida_address.split(':')[1]
assert any(line.split()[:2] == [args.serial, 'tcp:' + port]
           for line in adb('forward', '--list').decode().splitlines())
assert 'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities').decode()
settings = {key: adb('shell', 'settings', 'get', 'system', key).decode().strip()
            for key in ('font_scale', 'user_rotation', 'accelerometer_rotation')}
night = adb('shell', 'cmd', 'uimode', 'night').decode().strip().split()[-1]
device = frida.get_device_manager().add_remote_device(args.frida_address)
agent = r"""
var progress, dialog, detail, original, scroll, update;
function ui(task) { return new Promise(function(resolve, reject) {
    Java.perform(function() { Java.scheduleOnMainThread(function() {
        try { resolve(task()); } catch(e) { reject(e); }
    }); });
}); }
function rect(view) {
    var location = Java.array('int', [0, 0]); view.getLocationInWindow(location);
    return [location[0], location[1], view.getWidth(), view.getHeight()];
}
rpc.exports = {
    show: function() { return ui(function() {
        var activity;
        Java.choose('com.shilapi.xcertplay.GalaxySettingsActivity', {
            onMatch: function(a) { if (!a.isDestroyed() && !a.isFinishing()) { activity = Java.retain(a); return 'stop'; } },
            onComplete: function() {}
        });
        if (!activity) throw new Error('设置页面未显示');
        update = Java.cast(activity.appUpdate.value, Java.use('com.shilapi.xcertplay.GalaxyAppUpdate'));
        update.check();
        progress = Java.cast(update.dialog.value, Java.use('com.shilapi.xcertplay.L7TaskProgressDialog'));
        dialog = Java.retain(progress.dialog.value);
        detail = Java.retain(progress.detail.value);
    }); },
    idle: function() { return ui(function() { return update.request.value === null; }); },
    extend: function() { return ui(function() {
        progress.pause(); original = detail.getText().toString();
        detail.setVisibility(0);
        var notes = [];
        for (var i = 1; i <= 120; i++) notes.push('更新说明 ' + i + '：修复连接体验，改善画面显示与操作。');
        detail.setText(Java.use('java.lang.String').$new(notes.join('\n')));
        var parent = detail.getParent();
        while (!Java.use('android.widget.ScrollView').class.isInstance(parent)) parent = parent.getParent();
        scroll = Java.cast(parent, Java.use('android.widget.ScrollView'));
    }); },
    geometry: function() { return ui(function() {
        return {window: rect(dialog.getWindow().getDecorView()), body: rect(scroll),
            contentHeight: scroll.getChildAt(0).getHeight(), scrollY: scroll.getScrollY(),
            install: rect(dialog.getButton(-1)), close: rect(dialog.getButton(-2))};
    }); },
    bottom: function() { return ui(function() { scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); }); },
    close: function() { return ui(function() {
        if (original !== undefined) detail.setText(Java.use('java.lang.String').$new(original));
        dialog.dismiss();
    }); }
};
"""
results = []
session = script = None
try:
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    for rotation, font, theme in ((0, '1.0', 'no'), (0, '1.5', 'yes'), (1, '1.0', 'no'), (1, '1.5', 'yes')):
        adb('shell', 'settings', 'put', 'system', 'font_scale', font)
        adb('shell', 'settings', 'put', 'system', 'user_rotation', str(rotation))
        adb('shell', 'cmd', 'uimode', 'night', theme)
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-about')
        time.sleep(1)
        session = device.attach(int(adb('shell', 'pidof', package).decode().strip()))
        script = session.create_script(agent)
        script.load()
        deadline = time.monotonic() + 10
        while True:
            try:
                script.exports_sync.show(); break
            except frida.core.RPCException as error:
                if '设置页面未显示' not in str(error) or time.monotonic() >= deadline: raise
                time.sleep(.3)
        deadline = time.monotonic() + 40
        while not script.exports_sync.idle() and time.monotonic() < deadline:
            time.sleep(.3)
        assert script.exports_sync.idle(), '更新检查尚未完成'
        time.sleep(.5)
        script.exports_sync.extend(); time.sleep(.5)
        before = script.exports_sync.geometry()
        assert before['contentHeight'] > before['body'][3] > 0, '长说明必须可滚动'
        stem = f'rotation-{rotation}-font-{font}-night-{theme}'
        (output / (stem + '-top.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        script.exports_sync.bottom(); time.sleep(.3)
        after = script.exports_sync.geometry()
        assert after['window'] == before['window'], '滚动不应改变窗口尺寸'
        assert after['install'] == before['install'] and after['close'] == before['close'], repr((before, after))
        assert after['scrollY'] > 0, '正文应滚到后续说明'
        assert after['install'][1] + after['install'][3] <= after['window'][3], '按钮不能溢出窗口'
        (output / (stem + '-bottom.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        results.append({'rotation': rotation, 'fontScale': font, 'night': theme, 'before': before, 'after': after})
        script.exports_sync.close(); session.detach(); session = script = None
    (output / 'result.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    print('AVD 四种横竖屏／字号／昼夜组合长更新说明滚动与固定按钮通过')
finally:
    if script:
        try: script.exports_sync.close()
        except Exception: pass
    if session: session.detach()
    for key, value in settings.items():
        adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))
    adb('shell', 'cmd', 'uimode', 'night', night)
