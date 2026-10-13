#!/usr/bin/env python3
"""仅在 AVD 阻止真实连接启动，验证取消按钮和延迟释放时的窗口衔接；结束恢复偏好。"""
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
package = 'com.ecarx.carplay'
base = [args.adb, '-s', args.serial]
output = Path('build/previews/133-cancellation')
output.mkdir(parents=True, exist_ok=True)


def adb(*parts, data=None):
    return subprocess.check_output(base + list(parts), input=data, stderr=subprocess.PIPE, timeout=30)


port = args.frida_address.split(':')[-1]
assert any(line.split()[:2] == [args.serial, 'tcp:' + port]
           for line in adb('forward', '--list').decode().splitlines()), '转发必须属于指定 AVD'
assert 'CarPlayHostActivity' not in adb('shell', 'dumpsys', 'activity', 'activities').decode()
adb('shell', 'am', 'force-stop', package)
original = {}
for path in ('shared_prefs/diplay.xml', 'shared_prefs/l7_agreement.xml'):
    try:
        original[path] = adb('exec-out', 'run-as', package, 'cat', path)
    except subprocess.CalledProcessError as error:
        assert b'No such file' in error.stderr
        original[path] = None

agent = r"""
var host, completion, trace = [], requests = 0;
function ui(task) { return new Promise(function(resolve, reject) {
    Java.perform(function() { Java.scheduleOnMainThread(function() {
        try { resolve(task()); } catch (e) { reject(e); }
    }); });
}); }
function foreground(name) {
    var found;
    Java.choose(name, {onMatch: function(a) {
        if (!a.isDestroyed() && !a.isFinishing() && a.hasWindowFocus()) { found = Java.retain(a); return 'stop'; }
    }, onComplete: function() {}});
    if (!found) throw new Error('未找到前台窗口 ' + name);
    return found;
}
rpc.exports = {
    prepare: function() { return ui(function() {
        // AVD 编译后的继承调用可能已内联，先反优化以监测实际导航顺序。
        Java.deoptimizeEverything();
        var Host = Java.use('com.shilapi.xcertplay.CarPlayHostActivity');
        Host.requestStartupPrerequisites.implementation = function() {};
        Host.maybeStartCarPlay.implementation = function() {};
        var Callbacks = Java.registerClass({name: 'com.shilapi.xcertplay.test.CancelLifecycle',
            implements: [Java.use('android.app.Application$ActivityLifecycleCallbacks')], methods: {
                onActivityCreated: function(a, b) {}, onActivityStarted: function(a) {},
                onActivityPreCreated: function(a, b) {}, onActivityPostCreated: function(a, b) {},
                onActivityPreStarted: function(a) {}, onActivityPostStarted: function(a) {},
                onActivityPreResumed: function(a) {}, onActivityPostResumed: function(a) {},
                onActivityPrePaused: function(a) {}, onActivityPostPaused: function(a) {},
                onActivityPreStopped: function(a) {}, onActivityPostStopped: function(a) {},
                onActivityPreSaveInstanceState: function(a, b) {}, onActivityPostSaveInstanceState: function(a, b) {},
                onActivityPreDestroyed: function(a) {}, onActivityPostDestroyed: function(a) {},
                onActivityResumed: function(a) { trace.push('resumed:' + a.getClass().getName()); },
                onActivityPaused: function(a) {}, onActivityStopped: function(a) {},
                onActivitySaveInstanceState: function(a, b) {},
                onActivityDestroyed: function(a) { trace.push('destroyed:' + a.getClass().getName()); }
            }});
        foreground('com.shilapi.xcertplay.GalaxySettingsActivity').getApplication()
            .registerActivityLifecycleCallbacks(Callbacks.$new());
        var activity = foreground('com.shilapi.xcertplay.GalaxySettingsActivity');
        Java.use('com.shilapi.xcertplay.L7Agreement').INSTANCE.value.accept(activity);
        activity.startActivity(Java.use('android.content.Intent').$new(activity, Host.class));
    }); },
    ready: function() { return ui(function() {
        try { foreground('com.shilapi.xcertplay.CarPlayHostActivity'); return true; }
        catch (e) { return false; }
    }); },
    arm: function(delayed) { return ui(function() {
        host = foreground('com.shilapi.xcertplay.CarPlayHostActivity');
        if (host.controller.value !== null) throw new Error('测试必须没有真实控制器');
        if (delayed) {
            var Background = Java.use('com.shilapi.xcertplay.CarPlayBackgroundSession');
            if (Background.INSTANCE.value.hasSession()) throw new Error('测试必须没有真实会话');
            var Stop = Java.registerClass({name: 'com.shilapi.xcertplay.test.CancelStop',
                implements: [Java.use('kotlin.jvm.functions.Function1')], methods: {invoke: function(done) {
                    requests++;
                    completion = Java.retain(Java.cast(done, Java.use('kotlin.jvm.functions.Function0')));
                    Background.INSTANCE.value.clear(null, false);
                    host.finish();
                    return Java.use('kotlin.Unit').INSTANCE.value;
                }}});
            Background.owner.value = host;
            Background.stopAction.value = Stop.$new();
        }
        trace = [];
    }); },
    cancel: function() { return ui(function() {
        var panel = Java.cast(host.connectionPanel.value, Java.use('com.shilapi.xcertplay.L7ConnectionPanel'));
        if (!panel.cancelButton.value.performClick()) throw new Error('取消按钮未接受点击');
    }); },
    home: function() { return ui(function() {
        var activity = foreground('com.shilapi.xcertplay.GalaxySettingsActivity');
        return activity.getIntent().getStringExtra('page') + '';
    }); },
    release: function() { return ui(function() {
        var before = trace.length;
        if (completion) { completion.invoke(); completion = null; }
        if (trace.length !== before) throw new Error('迟到释放重新跳转');
        return {trace: trace, requests: requests,
            stopping: Java.use('com.shilapi.xcertplay.CarPlayBackgroundSession').INSTANCE.value.isStopping()};
    }); }
};
"""
device = frida.get_device_manager().add_remote_device(args.frida_address)
session = None
try:
    for delayed in (False, True):
        adb('shell', 'am', 'force-stop', package)
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity')
        pid = int(adb('shell', 'pidof', package).decode().strip())
        session = device.attach(pid)
        script = session.create_script(agent)
        script.load()
        api = script.exports_sync
        api.prepare()
        deadline = time.monotonic() + 15
        while not api.ready():
            assert time.monotonic() < deadline, '等待页未在期限内显示'
            time.sleep(.2)
        api.arm(delayed)
        label = 'delayed' if delayed else 'before-controller'
        (output / (label + '-before.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        api.cancel()
        foregrounds = []
        for _ in range(10):
            state = adb('shell', 'dumpsys', 'activity', 'activities').decode()
            resumed = next(line.strip() for line in state.splitlines() if 'mResumedActivity:' in line)
            assert package in resumed, resumed
            foregrounds.append(resumed)
            time.sleep(.15)
        assert api.home() == 'home'
        (output / (label + '-home.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        result = api.release()
        assert 'resumed:com.shilapi.xcertplay.GalaxySettingsActivity' in result['trace'], result
        assert 'destroyed:com.shilapi.xcertplay.CarPlayHostActivity' in result['trace'], result
        assert result['requests'] == int(delayed) and not result['stopping'], result
        result['foregrounds'] = foregrounds
        (output / (label + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
        session.detach()
        session = None
    print('AVD 取消按钮／先回应用首页／延迟释放无迟到跳转检查通过；未建立手机链路。')
finally:
    if session:
        session.detach()
    adb('shell', 'am', 'force-stop', package)
    for path, data in original.items():
        if data is None:
            adb('shell', 'run-as', package, 'rm', '-f', path)
        else:
            adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {path}'", data=data)
    adb('shell', 'am', 'start', '-W', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity')
