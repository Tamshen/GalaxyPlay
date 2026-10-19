"""验证认证目录字面量读取、Docker 挂载和源码包隔离，不使用真实凭据。"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('auth_path', ROOT / 'scripts/resolve-auth-assets.py')
auth_path = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auth_path)
KEY = auth_path.KEY


class AuthenticationPathTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def configure(self, text):
        (self.root / '.env').write_text(text)

    def test_missing_and_empty_values_use_default(self):
        expected = self.root / '.private/auth-assets'
        self.assertEqual(expected, auth_path.resolve(self.root, {}))
        self.configure(f'{KEY}=\n')
        self.assertEqual(expected, auth_path.resolve(self.root, {}))

    def test_quoted_relative_path_preserves_spaces_unicode_and_equals(self):
        self.configure(f'# 注释\n{KEY} = "本地 材料=a"\n')
        self.assertEqual(self.root / '本地 材料=a', auth_path.resolve(self.root, {}))

    def test_absolute_path(self):
        self.configure(f"{KEY}='{self.root}/external'\n")
        self.assertEqual(self.root / 'external', auth_path.resolve(self.root, {}))

    def test_environment_overrides_file_and_empty_environment_uses_default(self):
        self.configure(f'{KEY}=from-file\n')
        self.assertEqual(self.root / 'from-env', auth_path.resolve(self.root, {KEY: 'from-env'}))
        self.assertEqual(self.root / '.private/auth-assets', auth_path.resolve(self.root, {KEY: ''}))

    def test_shell_expressions_are_literal_and_unrelated_values_are_ignored(self):
        marker = self.root / 'must-not-exist'
        literal = f'$(touch {marker})'
        self.configure(f'GALAXY_PLAY_LOG_AUTHORIZATION=ignored\n{KEY}="{literal}"\n')
        self.assertEqual(self.root / literal, auth_path.resolve(self.root, {}))
        self.assertFalse(marker.exists())

    def test_last_assignment_wins(self):
        self.configure(f'{KEY}=old\n{KEY}=new\n')
        self.assertEqual(self.root / 'new', auth_path.resolve(self.root, {}))

    def test_invalid_quotes_and_control_characters_are_rejected(self):
        self.configure(f'{KEY}="unfinished\n')
        with self.assertRaises(ValueError):
            auth_path.resolve(self.root, {})
        with self.assertRaises(ValueError):
            auth_path.resolve(self.root, {KEY: 'bad\npath'})


class DockerAuthenticationWiringTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in ('scripts', 'e2e/checks', 'bin', 'docker'):
            (self.root / name).mkdir(parents=True)
        (self.root / 'docker/Dockerfile').write_text('FROM synthetic-image\n')
        for name in ('build-android-docker.sh', 'resolve-auth-assets.py'):
            shutil.copyfile(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        for name in ('scripts/sync-upstream-core.py', 'e2e/checks/check_galaxy_layout.py'):
            (self.root / name).write_text('')
        docker = self.root / 'bin/docker'
        docker.write_text(f'#!{sys.executable}\n' + '''import json, os, sys
from pathlib import Path
if sys.argv[1] == 'run':
    Path(os.environ['DOCKER_CAPTURE']).write_text(json.dumps(sys.argv[2:]))
    sys.exit(42)
print('synthetic-image')
''')
        docker.chmod(0o755)
        self.capture = self.root / 'docker.json'
        self.environment = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'],
                                DOCKER_CAPTURE=str(self.capture))
        self.environment.pop(KEY, None)

    def assets(self, name):
        directory = self.root / name
        (directory / 'offline-mfi').mkdir(parents=True)
        for filename in ('identity.pk8', 'certificate.p7b'):
            (directory / 'offline-mfi' / filename).write_text('synthetic-fixture')
        return directory.resolve()

    def run_build(self, *arguments, environment=None):
        result = subprocess.run(['bash', str(self.root / 'scripts/build-android-docker.sh'), *arguments],
                                cwd='/', env=environment or self.environment, capture_output=True)
        return result.returncode

    def test_dotenv_directory_is_mounted_readonly_and_environment_takes_priority(self):
        configured = self.assets('证书 目录')
        override = self.assets('override')
        (self.root / '.env').write_text(f'{KEY}="证书 目录"\n')
        for environment, expected in [(self.environment, configured),
                                      (dict(self.environment, **{KEY: str(override)}), override)]:
            with self.subTest(directory=expected.name):
                self.assertEqual(42, self.run_build(environment=environment))
                args = json.loads(self.capture.read_text())
                self.assertIn(f'type=bind,source={expected},target=/run/galaxyplay-auth,readonly', args)
                self.assertIn(f'{KEY}=/run/galaxyplay-auth', args)

    def test_source_only_does_not_read_or_mount_authentication(self):
        (self.root / '.env').write_text(f'{KEY}=missing\n')
        self.assertEqual(42, self.run_build('--source-only'))
        self.assertNotIn('/run/galaxyplay-auth', self.capture.read_text())

    def test_missing_material_fails_before_docker(self):
        (self.root / '.env').write_text(f'{KEY}=missing\n')
        self.assertEqual(2, self.run_build())
        self.assertFalse(self.capture.exists())
        directory = self.assets('missing')
        (directory / 'offline-mfi/certificate.p7b').write_text('')
        self.assertEqual(2, self.run_build())
        self.assertFalse(self.capture.exists())

    def test_branded_log_environment_is_forwarded_by_name(self):
        environment = dict(self.environment, GALAXY_PLAY_LOG_SERVER_URL='https://example.invalid',
                           GALAXY_PLAY_LOG_AUTHORIZATION='synthetic')
        self.assertEqual(42, self.run_build('--source-only', environment=environment))
        args = json.loads(self.capture.read_text())
        for key in ('GALAXY_PLAY_LOG_SERVER_URL', 'GALAXY_PLAY_LOG_AUTHORIZATION'):
            self.assertIn(key, args)
            self.assertNotIn(environment[key], args)


if __name__ == '__main__':
    unittest.main()
