#!/usr/bin/env python3
"""核对固定上游测试的去向与活动回归执行结果，不把参考用例计为产品通过。"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def audit(upstream, require_results=False):
    spec = importlib.util.spec_from_file_location('sync', ROOT / 'scripts/sync-upstream-core.py')
    sync = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sync)
    baseline = json.loads((ROOT / 'tools/upstream-sync/baseline.json').read_text())
    ref = baseline['tag']
    commit = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', ref + '^{commit}']).decode().strip()
    if commit != baseline['commit']:
        raise ValueError('测试来源未固定到当前核心配方')
    paths = subprocess.check_output(['git', '-C', str(upstream), 'ls-tree', '-r', '--name-only', ref,
        'common/src/test', 'shared/src/test']).decode().splitlines()
    active = reference = 0
    for source in paths:
        target = ROOT / sync.destination(source)
        if not target.is_file():
            raise ValueError('上游回归没有映射文件：' + source)
        if source in sync.REFERENCE_TESTS:
            original = subprocess.check_output(['git', '-C', str(upstream), 'show', ref + ':' + source])
            if target.read_bytes() != original:
                raise ValueError('参考测试不是固定上游原文：' + source)
            reference += 1
        else:
            active += 1
    suites = {}
    classes = set()
    for module in ('shared', 'common'):
        for path in (ROOT / 'e2e' / module / 'test').rglob('*'):
            if path.suffix not in ('.kt', '.java'):
                continue
            text = path.read_text()
            package = re.search(r'^package\s+([\w.]+)', text, re.M)
            if not package:
                continue
            # 文件名可能与类名不同，同一文件也可能包含多个顶层测试类。
            declarations = list(re.finditer(
                r'^(?:(?:public|private|internal|protected|abstract|open|final)\s+)*class\s+(\w+)\b', text, re.M))
            for index, declaration in enumerate(declarations):
                name = package[1] + '.' + declaration[1]
                if name in classes:
                    raise ValueError('活动类重复：' + name)
                classes.add(name)
                end = declarations[index + 1].start() if index + 1 < len(declarations) else len(text)
                if not re.search(r'@Test\b', text[declaration.end():end]):
                    continue
                result = ROOT / module / 'build/test-results/testDebugUnitTest' / ('TEST-' + name + '.xml')
                if require_results:
                    if not result.is_file():
                        raise ValueError('活动回归没有执行结果：' + name)
                    attrs = ET.parse(result).getroot().attrib
                    if int(attrs['tests']) == 0 or any(int(attrs.get(k, 0)) for k in ('failures', 'errors', 'skipped')):
                        raise ValueError('活动回归没有完整通过：' + name)
                    suites[name] = int(attrs['tests'])
        if require_results:
            for result in (ROOT / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
                if result.stem[5:] not in suites:
                    raise ValueError('执行结果没有对应活动测试类：' + result.stem[5:])
    print(f'固定上游测试映射：{commit[:12]}，{active} 个活动文件、{reference} 个原文参考文件；活动类无重复。')
    if require_results:
        print(f'实际执行：{len(suites)} 个测试类、{sum(suites.values())} 项，无遗漏、失败、错误或跳过。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--require-results', action='store_true')
    args = parser.parse_args()
    audit(args.upstream, args.require_results)
