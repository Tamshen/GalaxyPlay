#!/usr/bin/env python3
"""从官方标签生成可审查快照；只同步核心路径，Galaxy 与产品私有输入不作覆盖目标。"""
import argparse
import difflib
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / 'tools/upstream-sync'
MAIN = ('shared/src/main/', 'common/src/main/', 'mobile/src/main/')
REFERENCE_TESTS = json.loads((RECIPE / 'test-scope.json').read_text()) if (RECIPE / 'test-scope.json').is_file() else {}
CONFIG = ('shared/build.gradle', 'common/build.gradle.kts', 'mobile/build.gradle.kts',
          'gradle/libs.versions.toml', 'build.gradle.kts', 'settings.gradle.kts')


def run(args, cwd=None, data=None):
    result = subprocess.run(args, cwd=cwd, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    return result.stdout


def destination(path):
    if '/offline-mfi/' in path or path.endswith(('.pk8', '.p7b', '.keystore', '.jks')):
        raise ValueError('上游身份或签名材料不能进入源码快照')
    if path == 'shared/src/main/assets/navigation_test.pcm':
        return 'e2e/shared/runtime/assets/navigation_test.pcm'
    if path in REFERENCE_TESTS:
        return path.replace('common/src/test/', 'e2e/upstream-reference/common/test/', 1)
    if path.endswith('/LocalMfiProbe.kt'):
        return path.replace('shared/src/main/', 'e2e/shared/debug/')
    for module in ('common', 'shared'):
        prefix = module + '/src/test/'
        if path.startswith(prefix):
            return path.replace(prefix, 'e2e/' + module + '/test/', 1)
    if path.startswith(MAIN):
        # 产品仅发布中英文；其他语言不重新引入源集。
        parts = path.split('/')
        if 'res' in parts:
            folder = parts[parts.index('res') + 1]
            if folder.startswith('values-') and folder not in ('values-en', 'values-night'):
                return None
        return path
    return path if path in CONFIG else None


def snapshot(upstream, ref):
    commit = run(['git', '-C', str(upstream), 'rev-parse', ref + '^{commit}']).decode().strip()
    scopes = [x.rstrip('/') for x in MAIN] + ['common/src/test', 'shared/src/test', *CONFIG]
    data = run(['git', '-C', str(upstream), 'archive', commit, *scopes])
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            if member.isdir():
                continue
            if not member.isfile() or '..' in Path(member.name).parts or Path(member.name).is_absolute():
                raise ValueError('上游快照包含非普通文件或非法路径')
            target = destination(member.name)
            if target:
                result[target] = archive.extractfile(member).read()
    return commit, result


def safe_target(path):
    parsed = Path(path)
    if parsed.is_absolute() or '..' in parsed.parts or path.startswith('galaxy/'):
        raise ValueError('补丁目标越过核心目录')
    if not (path.startswith(MAIN + ('e2e/common/test/', 'e2e/shared/test/', 'e2e/shared/debug/', 'e2e/upstream-reference/common/test/')) or path in CONFIG or path == 'e2e/shared/runtime/assets/navigation_test.pcm'):
        raise ValueError('补丁目标不属于核心源集')


def write_snapshot(directory, files):
    for path, data in files.items():
        safe_target(path)
        target = directory / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def check_patches(recipe, directory):
    for patch in sorted(recipe.glob('*.patch')):
        # 限定所有补丁路径，拒绝通过重命名或目录穿越写入产品/私有目录。
        for line in patch.read_text().splitlines():
            if line.startswith(("diff --git", "GIT binary patch", "rename ", "copy ")):
                raise ValueError("仅接受明确目标的文本补丁，不接受重命名或二进制补丁")
            if line.startswith(('--- ', '+++ ')):
                path = line[4:]
                if path != '/dev/null':
                    if not path.startswith(('a/', 'b/')):
                        raise ValueError('补丁路径格式不受支持')
                    safe_target(path[2:])
        run(['git', 'apply', '--check', str(patch)], cwd=directory)
        run(['git', 'apply', str(patch)], cwd=directory)


def galaxy_hashes(root):
    paths = list((root / 'galaxy').rglob('*'))
    if any(p.is_symlink() for p in paths):
        raise ValueError('独立 Galaxy 目录不能包含符号链接')
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in paths if p.is_file()}


def collisions(files, root):
    for path in files:
        if '/src/main/java/' not in path:
            continue
        for candidate in (root / 'galaxy' / path, (root / 'galaxy' / path).with_suffix('.java' if path.endswith('.kt') else '.kt')):
            if candidate.is_file():
                raise ValueError('新增上游类与独立 Galaxy 类冲突：' + path)


def patch_category(path):
    # 通用核心修复和接入补丁分别按职责审查；大型 worker 不搬入 Galaxy 做覆盖。
    if path in CONFIG:
        return '00-build.patch'
    if path.startswith('e2e/'):
        return '50-regressions.patch'
    if '/media/' in path or '/jni/' in path:
        return '20-media-worker.patch'
    if '/orchestration/' in path or '/transport/' in path or '/network/' in path or '/airplay/' in path:
        return '10-connection-protocol.patch'
    if path.endswith('CarPlayHostActivity.kt') or path.endswith('CarPlayVideoActivity.kt'):
        return '30-host-entry.patch'
    return '40-policy-diagnostics.patch'


def refresh(root, recipe, commit, ref, files):
    current = set(files)
    tracked = run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).decode().split('\0')
    current.update(p for p in tracked if p.startswith(MAIN) and destination(p) == p)
    chunks = {}
    for path in sorted(current):
        safe_target(path)
        local = root / path
        before = files.get(path)
        if local.is_symlink():
            raise ValueError("核心源码不能是符号链接：" + path)
        after = local.read_bytes() if local.is_file() else None
        if before == after:
            continue
        try:
            old = before.decode() if before is not None else ''
            new = after.decode() if after is not None else ''
        except UnicodeDecodeError:
            raise ValueError('二进制核心差异需先单独审查：' + path)
        if old and not old.endswith('\n') or new and not new.endswith('\n'):
            raise ValueError('核心文本需保留末尾换行：' + path)
        category = patch_category(path)
        chunks.setdefault(category, []).extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
            'a/' + path if before is not None else '/dev/null',
            'b/' + path if after is not None else '/dev/null', n=3))
    recipe.mkdir(parents=True, exist_ok=True)
    if any(recipe.glob('*.patch')):
        raise ValueError('已有补丁，请在独立空目录生成并审查后替换')
    for category, lines in chunks.items():
        (recipe / category).write_text(''.join(lines))
    hashes = {path: hashlib.sha256((root / path).read_bytes()).hexdigest() if (root / path).is_file() else None
              for path in sorted(current)}
    (recipe / 'baseline.json').write_text(json.dumps({'tag': ref, 'commit': commit,
        'managed': sorted(current), 'resultSha256': hashes}, ensure_ascii=False, indent=2) + '\n')


def verify_local(root, recipe):
    baseline = json.loads((recipe / 'baseline.json').read_text())
    mismatch = []
    for path, expected in baseline['resultSha256'].items():
        safe_target(path)
        target = root / path
        if target.is_symlink():
            raise ValueError('核心源码不能是符号链接：' + path)
        actual = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
        if actual != expected:
            mismatch.append(path)
    if mismatch:
        raise ValueError('核心源码发生漂移，请先审查并更新同步配方：\n' + '\n'.join(mismatch))
    return baseline['commit'], len(baseline['managed'])


def prepare(root, recipe, upstream, ref, output, verify=False):
    baseline = json.loads((recipe / 'baseline.json').read_text())
    commit, files = snapshot(upstream, ref or baseline['tag'])
    if verify and commit != baseline['commit']:
        raise ValueError('验证只能使用已固定的官方基线')
    collisions(files, root)
    hashes = galaxy_hashes(root)
    with tempfile.TemporaryDirectory(prefix='galaxy-core-') as temporary:
        staging = Path(temporary)
        # --check 不读取私有输入；候选工程也只复制 Git 跟踪的公开文件。
        if not verify:
            public = run(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
            for path in filter(None, public):
                if '.private' in Path(path).parts or Path(path).suffix.lower() in ('.pk8', '.p7b', '.key', '.pem', '.keystore', '.jks', '.apk', '.aab'):
                    raise ValueError('候选工程包含禁止的私有输入或分发产物')
                origin = root / path
                if origin.is_symlink():
                    raise ValueError('公开工程包含符号链接，请明确审查')
                if origin.is_file():
                    target = staging / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(origin, target)
        for path in baseline['managed']:
            safe_target(path)
            (staging / path).unlink(missing_ok=True)
        write_snapshot(staging, files)
        check_patches(recipe, staging)
        collisions({str(p.relative_to(staging)): None for module in ('shared', 'common')
            for p in (staging / module / 'src/main/java').rglob('*') if p.is_file()}, root)
        if verify:
            mismatch = [p for p in sorted(set(baseline['managed']) | set(files))
                if ((staging / p).read_bytes() if (staging / p).is_file() else None) !=
                   ((root / p).read_bytes() if (root / p).is_file() else None)]
            if mismatch:
                raise ValueError('源码与已审查快照/补丁不一致：\n' + '\n'.join(mismatch))
        else:
            if output.exists():
                raise ValueError('输出目录已存在，不覆盖任何现有工作')
            if galaxy_hashes(staging) != hashes:
                raise ValueError('候选工程未完整保留独立 Galaxy 代码')
            shutil.copytree(staging, output)
    if galaxy_hashes(root) != hashes:
        raise ValueError('原工作区的 Galaxy 代码发生变化')
    return commit, len(files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'check', 'refresh', 'verify'))
    parser.add_argument('--upstream', type=Path)
    parser.add_argument('--ref')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--recipe', type=Path, default=RECIPE)
    args = parser.parse_args()
    if args.mode == 'verify':
        commit, count = verify_local(ROOT, args.recipe)
        print(f'本地核心配方核对通过：{commit[:12]}，{count} 个管理目标。')
        return
    if args.upstream is None:
        parser.error('快照操作必须指定 --upstream；离线核对使用 verify')
    if args.mode == 'refresh':
        if not args.ref or not args.output:
            parser.error('refresh 必须指定 --ref 与新的空 --output 配方目录')
        commit, files = snapshot(args.upstream, args.ref)
        collisions(files, ROOT)
        refresh(ROOT, args.output, commit, args.ref, files)
        print('新配方已生成，请审查差异并执行 check；未修改源码或版本号。')
    else:
        if args.mode == 'prepare' and not args.output:
            parser.error('prepare 必须指定新的 --output 候选工程目录')
        commit, count = prepare(ROOT, args.recipe, args.upstream, args.ref, args.output, args.mode == 'check')
        print(f'核心快照检查通过：{commit[:12]}，{count} 个输入文件；Galaxy 保持不变。')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError) as error:
        raise SystemExit(str(error))
