#!/usr/bin/env python3
"""HF04 contract tests: pure source/host shell; no Gradle and no device."""
from __future__ import annotations
import os
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path
from amt03_hosted_config import patch

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/devtool-tasker-ci.yml'
RUNNER = ROOT / 'scripts/devtool/amt03_hosted_instrumentation.sh'
CONFIG = ROOT / '.devtool.toml'

class HF04HostedInstrumentationContract(unittest.TestCase):
    def test_ephemeral_instrumentation_config_preserves_original(self):
        raw = CONFIG.read_text()
        self.assertIn('policy = "disabled"', raw)
        staged = patch(raw)
        resolved = tomllib.loads(staged)['targets']['app']['android']
        self.assertEqual('36.1.0', resolved['build_tools']['version'])
        self.assertEqual('27.0.12077973', resolved['ndk_version'])
        self.assertEqual('3.22.1', resolved['cmake_version'])
        self.assertEqual('required', resolved['instrumentation']['policy'])
        self.assertIn('policy = "disabled"', CONFIG.read_text())

    def test_config_must_fail_closed_on_drift(self):
        raw = CONFIG.read_text()
        with self.assertRaises(ValueError):
            patch(raw.replace('policy = "disabled"', 'policy = "required"'))
        with self.assertRaises(ValueError):
            patch(raw.replace('aapt2_provider = "auto"', 'aapt2_provider = "sdk"'))

    def test_one_shell_command_for_emulator_runner(self):
        source = WORKFLOW.read_text()
        start = source.index('      - name: Run journal device instrumentation')
        end = source.index('      - name: Upload instrumentation receipt and diagnostics', start)
        slot = source[start:end]
        self.assertIn('script: bash scripts/devtool/amt03_hosted_instrumentation.sh', slot)
        self.assertNotIn('script: |', slot)
        self.assertNotIn('PYCHECK', slot)
        self.assertIn('ndk;27.0.12077973', source)
        # Validate the action's outer shell compatibility (not the live runner).
        r = subprocess.run(['sh', '-n', '-c', 'bash scripts/devtool/amt03_hosted_instrumentation.sh'],
                           cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_bash_script_strict_receipt_and_restoration(self):
        source = RUNNER.read_text()
        for marker in ('set -Eeuo pipefail', 'trap restore_original EXIT',
                       'resolve_android_environment', '--target app',
                       "assert int(r.get('passed', 0)) >= 2", "assert int(r.get('failed', 0)) == 0",
                       'AMT03_DEVICE_INSTRUMENTATION_PASS', 'PIPESTATUS[0]'):
            self.assertIn(marker, source)
        r = subprocess.run(['bash', '-n', str(RUNNER)], capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_receipt_gate_rejects_zero_tests(self):
        source = RUNNER.read_text()
        self.assertIn("assert int(r.get('executed', 0)) >= 2", source)
        self.assertIn("assert int(r.get('returncode', 1)) == 0", source)

    def test_failure_restores_original_config_and_refuses_unready_sdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / 'repo'
            (repo / 'scripts/devtool').mkdir(parents=True)
            original = CONFIG.read_text()
            (repo / '.devtool.toml').write_text(original)
            (repo / 'scripts/devtool/amt03_hosted_instrumentation.sh').write_text(RUNNER.read_text())
            (repo / 'scripts/devtool/amt03_hosted_config.py').write_text(
                (ROOT / 'scripts/devtool/amt03_hosted_config.py').read_text())
            env = dict(os.environ, RUNNER_TEMP=tmp, JAVA_HOME='/fake-jdk',
                       ANDROID_SDK_ROOT=str(Path(tmp) / 'empty-sdk'),
                       PYTHONDONTWRITEBYTECODE='1')
            result = subprocess.run(['bash', str(repo / 'scripts/devtool/amt03_hosted_instrumentation.sh')],
                                    cwd=repo, env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('AMT03_HF04_SDK_NOT_READY', result.stderr)
            self.assertEqual(original, (repo / '.devtool.toml').read_text())

    def test_devtool_config_installs_no_local_gradle(self):
        source = RUNNER.read_text()
        self.assertNotIn('./gradlew', source)
        self.assertNotIn('sdkmanager --install', source)

if __name__ == '__main__':
    unittest.main()
