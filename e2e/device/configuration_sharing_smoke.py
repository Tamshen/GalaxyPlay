#!/usr/bin/env python3
"""仅在独立配置验证 AVD 验证预设、IP、改名和文字分享；不连接手机。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--serial', default='emulator-5570')
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial)
a.output_dir.mkdir(parents=True, exist_ok=True)
package = 'com.ecarx.carplay'


def adb(*args, data=None, binary=False):
    r = subprocess.run([a.adb, '-s', a.serial, *args], input=data, capture_output=True, timeout=45, check=True)
    return r.stdout if binary else r.stdout.decode('utf-8')


assert adb('emu', 'avd', 'name').splitlines()[0].strip() in {'configuration147', 'configuration151'}, '只允许本任务独立 AVD'
assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == '30'
adb('install', '-r', str(a.apk.resolve()))
adb('shell', 'am', 'force-stop', package)
with zipfile.ZipFile(a.apk) as z:
    digest = hashlib.sha256(z.read('assets/galaxyplay-first-use-agreement.md')).hexdigest()
# 仅新建的自动化 AVD 使用临时协议夹具，不代表用户实际同意或实车授权。
for name, xml in {
    'l7_agreement': f'<map><string name="accepted_digest">{digest}</string></map>',
    'diplay': '<map><string name="app_language">zh</string><boolean name="auto_connect" value="false"/></map>',
}.items():
    adb('shell', 'run-as', package, 'mkdir', '-p', 'shared_prefs')
    encoded = base64.b64encode(xml.encode()).decode('ascii')
    adb('shell', f"run-as {package} sh -c 'printf %s {encoded} | base64 -d > shared_prefs/{name}.xml'")
adb('shell', 'am', 'start', '-n', package + '/com.shilapi.xcertplay.GalaxySettingsActivity', '--es', 'page', 'settings-vehicle')


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/configuration147-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/configuration147-ui.xml'))


def wait_text(text, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = tree()
        if any(text in (n.get('text', '') + n.get('content-desc', '')) for n in root.iter('node')):
            return root
        time.sleep(.3)
    raise AssertionError('界面等待超时：' + text)


def click(text, contains=False):
    for _ in range(5):
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        for n in sorted(root.iter('node'), key=lambda n: n.get('class') != 'android.widget.Button'):
            label = n.get('text', '') or n.get('content-desc', '')
            if not (text in label if contains else label == text):
                continue
            while n.get('clickable') != 'true' and n.get('checkable') != 'true' and n in parents:
                n = parents[n]
            if (n.get('clickable') == 'true' or n.get('checkable') == 'true') and n.get('enabled') == 'true':
                bounds = list(map(int, re.findall(r'\d+', n.get('bounds'))))
                adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
                time.sleep(.4)
                return
        scroll = next((n for n in root.iter('node') if n.get('scrollable') == 'true' and
                       n.get('class') in ('android.widget.ScrollView', 'android.widget.ListView')), None)
        if scroll is None:
            break
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',scroll.get('bounds')))
        adb('shell','input','swipe',str((x1+x2)//2),str(y2-40),str((x1+x2)//2),str(y1+40),'300')
    raise AssertionError('不可操作：' + text)


def screenshot(name):
    (a.output_dir / (name + '.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))


def current():
    return json.loads(adb('exec-out', 'run-as', package, 'cat', 'files/configurations/current.json'))


def choose(name):
    click('车型 · ', contains=True)
    wait_text(name)
    click(name)
    click('应用模板')


def set_text(value):
    root = tree()
    n = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    adb('shell','input','keyevent','123',*(['67']*45))
    adb('shell','input','text',value)


wait_text('分享')
choose('L7(带头枕)')
with_headrest = current()['configuration']
main = with_headrest['preferences']['xcertplay_airplay']
assert main['display_fps']['value'] == 60 and main['display_scale_percent']['value'] == 80
assert main['audio_focus_enabled']['value'] is False
assert main['galaxy_p2p_address_mode']['value'] == 'IPV4_ONLY'
screenshot('headrest-profile')
choose('L7(无头枕)')
without = current()['configuration']
assert without['preferences']['xcertplay_airplay']['audio_focus_enabled']['value'] is True
adjusted = json.loads(json.dumps(without))
adjusted['preferences']['xcertplay_airplay']['audio_focus_enabled']['value'] = False
assert adjusted == with_headrest
click('连接')
for mode, stored in [('仅 IPv6','IPV6_ONLY'), ('IPv4 + IPv6','DUAL_STACK'), ('仅 IPv4','IPV4_ONLY')]:
    click('Wi-Fi Direct IP 模式')
    wait_text('IPv4 + IPv6')
    screenshot('ip-modes-' + stored)
    click(mode)
    click('确定')
    if stored == 'IPV6_ONLY':
        before_save = current()
        click('保存')
        wait_text('保存配置')
        screenshot('save-name')
        set_text('Cancelled151')
        click('取消')
        assert current() == before_save
    click('保存')
    wait_text('保存配置')
    click('保存')
    assert current()['configuration']['preferences']['xcertplay_airplay']['galaxy_p2p_address_mode']['value'] == stored
click('常用')
click('保存')
wait_text('保存配置')
set_text('Demo147')
click('保存')
assert current()['profile_name'] == 'Demo147'
before_share = current()
click('分享')
set_text('note147')
click('生成分享配置')
wait_text('复制配置文本', 20)
screenshot('share-qr')
click('复制配置文本')
click('关闭')
assert current() == before_share
click('导入')
click('粘贴文本')
root = tree()
code = next(n.get('text','') for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
assert code.startswith('GALAXYPLAY2:')
click('预览配置')
wait_text('导入并应用')
assert any('note147' in n.get('text','') for n in tree().iter('node'))
screenshot('import-preview')
before = current()
click('取消')
assert current() == before
choose('L7(带头枕)')
assert current()['configuration']['preferences']['xcertplay_airplay']['audio_focus_enabled']['value'] is False
click('导入')
click('粘贴文本')
click('预览配置')
wait_text('导入并应用')
click('导入并应用')
imported = current()
assert imported['profile_name'] == 'Demo147'
assert imported['configuration']['preferences']['xcertplay_airplay']['audio_focus_enabled']['value'] is True
assert imported['configuration']['preferences']['l7_audio_templates']['model']['value'] == 'l7'
screenshot('imported-profile')
# 仅清理本脚本在独立 AVD 下载目录创建的合成导出，避免重跑触发覆盖确认。
for name in ['Demo147.galaxyplay.json', 'Demo147.png']:
    adb('shell', 'rm', '-f', '/sdcard/Download/' + name)
for action, name in [('保存配置文件', 'Demo147.galaxyplay.json'), ('保存二维码', 'Demo147.png')]:
    click('分享')
    click('生成分享配置')
    wait_text(action, 20)
    click(action)
    wait_text('SAVE')
    click('SAVE')
    wait_text('分享', 20)
    adb('pull', '/sdcard/Download/' + name, str(a.output_dir / name))
exported = json.loads((a.output_dir / 'Demo147.galaxyplay.json').read_text())
assert exported['name'] == 'Demo147' and exported['format'] == 'galaxyplay-vehicle'
assert (a.output_dir / 'Demo147.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
result = {'presets_only_focus_diff': True, 'ip_modes': 3, 'profile_name': True,
          'qr_displayed': True, 'json_png_export': True, 'copied_code_chars': len(code), 'paste_preview_cancel_apply': True,
          'real_vehicle': False, 'real_phone': False}
(a.output_dir / 'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
