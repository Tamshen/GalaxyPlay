#!/usr/bin/env python3
"""检查上游与 Galaxy 源集冲突；不允许整文件覆盖或悄悄排除同名核心代码。"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def check(root=ROOT):
    total = 0
    for module in ('shared', 'common'):
        core = root / module / 'src/main/java'
        galaxy = root / 'galaxy' / module / 'src/main/java'
        assert core.is_dir() and galaxy.is_dir(), f'缺少源集：{module}'
        for path in galaxy.rglob('*'):
            if not path.is_file() or path.suffix not in ('.kt', '.java'):
                continue
            relative = path.relative_to(galaxy)
            assert not (core / relative).exists(), f'上游与 Galaxy 同名源码冲突：{module}/{relative}'
            # Java/Kotlin 改写不能通过更换扩展名绕过检查。
            alternate = '.java' if path.suffix == '.kt' else '.kt'
            assert not (core / relative.with_suffix(alternate)).exists(), f'上游与 Galaxy 类冲突：{module}/{relative}'
            total += 1
    for module in ('common', 'mobile'):
        source = root / 'galaxy' / module / 'src/main'
        assert (source / 'AndroidManifest.xml').is_file(), f'缺少产品清单：{module}'
        assert (source / 'res').is_dir(), f'缺少产品资源：{module}'
    assert (root / 'galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md').is_file()
    return total


if __name__ == '__main__':
    print(f'Galaxy 源集检查通过：{check()} 个独立源码文件，无同路径覆盖。')
