#!/usr/bin/env python3
"""AMT-03 HF05 hosted JDK selection regression (source/shell only, never Gradle)."""
from __future__ import annotations
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest
from amt03_hosted_config import patch

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.devtool.toml'
CFG_PATCHER = ROOT / 'scripts/devtool/amt03_hosted_config.py'
RUNNER = ROOT / 'scripts/devtool/amt03_hosted_instrumentation.sh'
WORKFLOW = ROOT / '.github/workflows/devtool-tasker-ci.yml'
SDK_FILES = (
    'build-tools/36.1.0/source.properties', 'build-tools/36.1.0/lib/d8.jar',
    'ndk/27.0.12077973/source.properties',
    'ndk/27.0.12077973/build/cmake/android.toolchain.cmake',
    'ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/clang',
    'cmake/3.22.1/bin/cmake', 'platforms/android-36/android.jar',
)


def fixture_repository(tmp: str, java_version: int) -> tuple[Path, dict[str, str], str]:
    root = Path(tmp) / 'AppManager'
    (root / 'scripts/devtool').mkdir(parents=True)
    for src in (CFG_PATCHER, RUNNER):
        (root / 'scripts/devtool' / src.name).write_bytes(src.read_bytes())
    original = CONFIG.read_text()
    (root / '.devtool.toml').write_text(original)
    sdk = Path(tmp) / 'sdk'
    for rel in SDK_FILES:
        file = sdk / rel
        file.parent.mkdir(parents=True, exist_ok=True)
        file.touch()
    jdk = Path(tmp) / 'jdk'
    (jdk / 'bin').mkdir(parents=True)
    java = jdk / 'bin/java'
    java.write_text(f'#!/bin/sh\necho \'openjdk version "{java_version}.0.1"\' >&2\n')
    java.chmod(0o755)
    javac = jdk / 'bin/javac'
    javac.write_text('#!/bin/sh\nexit 0\n')
    javac.chmod(0o755)
    env = dict(os.environ, RUNNER_TEMP=tmp, JAVA_HOME=str(jdk),
               ANDROID_SDK_ROOT=str(sdk), PYTHONDONTWRITEBYTECODE='1')
    return root, env, original


class AMT03HF05JavaContract(unittest.TestCase):
    def test_source_config_explicit_java_home_is_in_target(self):
        original = CONFIG.read_text()
        home = '/opt/hostedtoolcache/Java_Zulu_jdk/21.0.13-1/x64'
        modified = patch(original, java_home=home)
        android = tomllib.loads(modified)['targets']['app']['android']
        self.assertEqual(home, android['java_home'])
        self.assertEqual('36.1.0', android['build_tools']['version'])
        self.assertEqual('27.0.12077973', android['ndk_version'])
        self.assertEqual('required', android['instrumentation']['policy'])
        self.assertIn('policy = "disabled"', CONFIG.read_text())

    def test_invalid_or_injected_java_home_is_rejected(self):
        original = CONFIG.read_text()
        for value in ('', 'relative/path', '/tmp/jdk\n[hostile]\nvalue=1', '/tmp/jdk\x00bad'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                patch(original, java_home=value)

    def test_config_cli_requires_java_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / '.devtool.toml'
            before = CONFIG.read_bytes()
            config.write_bytes(before)
            result = subprocess.run(['python3', '-B', str(CFG_PATCHER), str(config)],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('--java-home', result.stderr)
            self.assertEqual(before, config.read_bytes())

    def test_hosted_script_passes_jdk21_and_checks_resolved_target(self):
        source = RUNNER.read_text()
        self.assertIn('amt03_hosted_config.py .devtool.toml --java-home "$JAVA_HOME"', source)
        self.assertIn("cfg.runner_options.get('android', {}).get('java_home')", source)
        self.assertIn('AMT03_HF05_DEVTOOL_JDK21_BOUND', source)
        self.assertIn('AMT03_HF05_JDK21_READY', source)
        self.assertIn('AMT03_HF05_REFUSED_JDK_VERSION', source)
        self.assertIn('trap restore_original EXIT', source)
        self.assertIn("assert int(r.get('passed', 0)) >= 2", source)
        self.assertIn('PIPESTATUS[0]', source)
        result = subprocess.run(['bash', '-n', str(RUNNER)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('script: bash scripts/devtool/amt03_hosted_instrumentation.sh', WORKFLOW.read_text())

    def test_java8_is_rejected_before_devtool_and_restored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env, original = fixture_repository(tmp, 8)
            run = subprocess.run(['bash', 'scripts/devtool/amt03_hosted_instrumentation.sh'],
                                 cwd=root, env=env, capture_output=True, text=True)
            self.assertNotEqual(0, run.returncode)
            self.assertIn('AMT03_HF05_REFUSED_JDK_VERSION: required 21, observed 8', run.stderr)
            self.assertNotIn('AMT03_HF05_DEVTOOL_JDK21_BOUND', run.stdout)
            self.assertEqual(original, (root / '.devtool.toml').read_text())

    def test_java21_passes_preflight_but_does_not_fake_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env, original = fixture_repository(tmp, 21)
            run = subprocess.run(['bash', 'scripts/devtool/amt03_hosted_instrumentation.sh'],
                                 cwd=root, env=env, capture_output=True, text=True)
            self.assertIn('AMT03_HF05_JDK21_READY', run.stdout)
            self.assertNotEqual(0, run.returncode)  # Synthetic fixture has no Android SDK packages/Devtool runtime.
            self.assertNotIn('AMT03_DEVICE_INSTRUMENTATION_PASS', run.stdout)
            self.assertEqual(original, (root / '.devtool.toml').read_text())

    def test_java8_source_compatibility_does_not_select_jdk8(self):
        # Regression against run 42383665406. Builder picks the narrowest JDK
        # unless Android target explicitly contains java_home; Java 8 source
        # compatibility must never select the Gradle daemon runtime.
        source = CFG_PATCHER.read_text()
        self.assertIn("'aapt2_provider = \"auto\"\\njava_home = ' + json.dumps(java_home)", source)
        self.assertIn("java_home=args.java_home", source)
        self.assertNotIn('temurin-8-jdk-amd64', CFG_PATCHER.read_text())
        self.assertIn('AMT03_HF05_REFUSED_JDK_VERSION', RUNNER.read_text())


if __name__ == '__main__':
    unittest.main()
