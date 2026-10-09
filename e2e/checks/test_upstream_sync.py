"""使用临时 Git 快照验证覆盖、补丁冲突、漂移和目录隔离；不读取私有输入。"""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('sync', Path(__file__).parents[2] / 'scripts/sync-upstream-core.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE)


class UpstreamSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.upstream = self.base / 'upstream'
        self.product = self.base / 'product'
        self.recipe = self.base / 'recipe'
        for root in (self.upstream, self.product):
            root.mkdir()
            git(root, 'init', '-q')
            git(root, 'config', 'user.name', '测试')
            git(root, 'config', 'user.email', 'test@example.invalid')
        files = {x: '// upstream\n' for x in sync.CONFIG}
        files.update({
            'shared/src/main/java/Core.kt': 'class Core {\n    fun hook() = 1\n}\n',
            'common/src/main/java/Host.kt': 'class Host\n',
            'mobile/src/main/AndroidManifest.xml': '<manifest/>\n',
            'shared/src/test/java/CoreTest.kt': 'class CoreTest\n',
            'common/src/test/java/HostTest.kt': 'class HostTest\n',
        })
        for path, contents in files.items():
            target = self.upstream / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(contents)
        self.commit_upstream('v1')
        self.commit, self.files = sync.snapshot(self.upstream, 'v1')
        sync.write_snapshot(self.product, self.files)
        adapter = self.product / 'galaxy/shared/src/main/java/Adapter.kt'
        adapter.parent.mkdir(parents=True)
        adapter.write_text('class Adapter\n')
        self.adapter = adapter
        self.core = self.product / 'shared/src/main/java/Core.kt'
        self.core.write_text(self.core.read_text().replace('= 1', '= Adapter()'))
        git(self.product, 'add', '.')
        git(self.product, 'commit', '-qm', '产品适配')
        sync.refresh(self.product, self.recipe, self.commit, 'v1', self.files)

    def commit_upstream(self, tag):
        git(self.upstream, 'add', '.')
        git(self.upstream, 'commit', '-qm', tag)
        git(self.upstream, 'tag', tag)

    def test_prepare_preserves_adapter_and_relocates_tests(self):
        output = self.base / 'candidate'
        sync.prepare(self.product, self.recipe, self.upstream, None, output)
        self.assertEqual(self.adapter.read_bytes(), (output / self.adapter.relative_to(self.product)).read_bytes())
        self.assertEqual(self.core.read_bytes(), (output / self.core.relative_to(self.product)).read_bytes())
        self.assertTrue((output / 'e2e/shared/test/java/CoreTest.kt').is_file())
        self.assertFalse((output / 'shared/src/test').exists())
        self.assertFalse((output / '.git').exists())
        sync.prepare(self.product, self.recipe, self.upstream, None, None, True)

    def test_shared_reference_tests_remain_outside_active_source_sets(self):
        path = 'shared/src/test/java/DeferredTest.kt'
        original = sync.REFERENCE_TESTS
        try:
            sync.REFERENCE_TESTS = {path: 'Deferred feature'}
            self.assertEqual('e2e/upstream-reference/shared/test/java/DeferredTest.kt', sync.destination(path))
            sync.safe_target(sync.destination(path))
        finally:
            sync.REFERENCE_TESTS = original

    def test_official_missing_final_newline_is_preserved_in_patch_context(self):
        source = self.upstream / 'shared/src/main/java/Core.kt'
        source.write_text('class Core {\n    fun hook() = 2\n}')
        self.commit_upstream('v2')
        commit, files = sync.snapshot(self.upstream, 'v2')
        recipe = self.base / 'recipe-v2'
        sync.refresh(self.product, recipe, commit, 'v2', files)
        sync.prepare(self.product, recipe, self.upstream, 'v2', None, True)
        self.assertIn('No newline at end of file', (recipe / '40-policy-diagnostics.patch').read_text())

    def test_new_upstream_file_is_included(self):
        (self.upstream / 'shared/src/main/java/New.kt').write_text('class New\n')
        self.commit_upstream('v2')
        output = self.base / 'candidate'
        sync.prepare(self.product, self.recipe, self.upstream, 'v2', output)
        self.assertTrue((output / 'shared/src/main/java/New.kt').is_file())

    def test_changed_entry_rejects_patch_without_mutating_product(self):
        (self.upstream / 'shared/src/main/java/Core.kt').write_text('class Core { fun hook() = 2 }\n')
        self.commit_upstream('v2')
        before = self.core.read_bytes()
        with self.assertRaises(RuntimeError):
            sync.prepare(self.product, self.recipe, self.upstream, 'v2', self.base / 'candidate')
        self.assertEqual(before, self.core.read_bytes())
        self.assertFalse((self.base / 'candidate').exists())

    def test_new_class_collision_is_explicit(self):
        (self.upstream / 'shared/src/main/java/Adapter.java').write_text('class Adapter {}\n')
        self.commit_upstream('v2')
        with self.assertRaisesRegex(ValueError, '冲突'):
            sync.prepare(self.product, self.recipe, self.upstream, 'v2', self.base / 'candidate')

    def test_drift_and_wrong_baseline_rejected(self):
        self.core.write_text('changed\n')
        with self.assertRaisesRegex(ValueError, '不一致'):
            sync.prepare(self.product, self.recipe, self.upstream, None, None, True)
        (self.upstream / 'shared/src/main/java/New.kt').write_text('class New\n')
        self.commit_upstream('v2')
        with self.assertRaisesRegex(ValueError, '固定'):
            sync.prepare(self.product, self.recipe, self.upstream, 'v2', None, True)

    def test_patch_cannot_target_galaxy_or_traverse(self):
        for path in ('galaxy/shared/Adapter.kt', '../outside.kt', '/absolute.kt'):
            with self.subTest(path=path):
                (self.recipe / 'core-adaptation.patch').write_text(f'--- /dev/null\n+++ b/{path}\n@@ -0,0 +1 @@\n+bad\n')
                with self.assertRaisesRegex(ValueError, '目录'):
                    sync.prepare(self.product, self.recipe, self.upstream, None, self.base / 'candidate')

    def test_offline_build_check_rejects_unreviewed_core_drift(self):
        sync.verify_local(self.product, self.recipe)
        self.core.write_text('unreviewed\n')
        with self.assertRaisesRegex(ValueError, '漂移'):
            sync.verify_local(self.product, self.recipe)

    def test_existing_output_is_not_overwritten(self):
        output = self.base / 'candidate'
        output.mkdir()
        (output / 'user.txt').write_text('preserve')
        with self.assertRaisesRegex(ValueError, '已存在'):
            sync.prepare(self.product, self.recipe, self.upstream, None, output)
        self.assertEqual('preserve', (output / 'user.txt').read_text())


if __name__ == '__main__':
    unittest.main()
