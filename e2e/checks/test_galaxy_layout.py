"""覆盖上游文件不会触及独立适配目录；同名新核心必须明确处理。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('layout', Path(__file__).with_name('check_galaxy_layout.py'))
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)


class GalaxyLayoutTest(unittest.TestCase):
    def test_upstream_replacement_preserves_adapter_and_collision_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in ('shared', 'common'):
                (root / module / 'src/main/java').mkdir(parents=True)
                (root / 'galaxy' / module / 'src/main/java').mkdir(parents=True)
            for module in ('common', 'mobile'):
                source = root / 'galaxy' / module / 'src/main'
                (source / 'res').mkdir(parents=True)
                (source / 'AndroidManifest.xml').write_text('<manifest/>')
            assets = root / 'galaxy/common/src/main/assets'
            assets.mkdir()
            (assets / 'galaxyplay-first-use-agreement.md').write_text('agreement')
            adapter = root / 'galaxy/shared/src/main/java/Adapter.kt'
            adapter.write_text('adapter preserved')
            core = root / 'shared/src/main/java/Core.kt'
            core.write_text('upstream before')
            self.assertEqual(1, layout.check(root))
            core.write_text('upstream after')
            self.assertEqual('adapter preserved', adapter.read_text())
            self.assertEqual(1, layout.check(root))
            (core.parent / 'Adapter.java').write_text('class Adapter {}')
            with self.assertRaisesRegex(AssertionError, '类冲突'):
                layout.check(root)


if __name__ == '__main__':
    unittest.main()
