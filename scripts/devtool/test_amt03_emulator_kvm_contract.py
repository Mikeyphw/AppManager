#!/usr/bin/env python3
"""AMT-03 HF03: source-only KVM boot regressions, no Android or local Gradle."""
from __future__ import annotations
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WF = ROOT / '.github/workflows/devtool-tasker-ci.yml'


def job_section() -> str:
    source = WF.read_text(encoding='utf-8')
    marker = '  device-instrumentation:\n'
    assert source.count(marker) == 1, 'missing/duplicate device job'
    return source.split(marker, 1)[1]


class AMT03KvmContract(unittest.TestCase):
    def test_kvm_access_configured_before_emulator(self):
        src = job_section()
        kvm = src.index('      - name: Enable KVM acceleration for AMT-03 instrumentation')
        emu = src.index('      - name: Run journal device instrumentation (no real profiles modified)')
        self.assertLess(kvm, emu)
        for expected in ('/dev/kvm', '99-kvm4all.rules', 'sudo udevadm control --reload-rules',
                         'sudo udevadm trigger --name-match=kvm', 'AMT03_HF03_KVM_READY'):
            self.assertIn(expected, src[kvm:emu])

    def test_emulator_requires_hardware_acceleration(self):
        src = job_section()
        self.assertIn('disable-linux-hw-accel: false', src)
        self.assertIn('emulator-boot-timeout: 900', src)
        self.assertIn('reactivecircus/android-emulator-runner@v2', src)
        self.assertIn('api-level: 35', src)
        self.assertIn('arch: x86_64', src)
        self.assertEqual(src.count('disable-linux-hw-accel: false'), 1)

    def test_bash_wrapper_and_devtool_receipt_remain_strict(self):
        src = job_section()
        self.assertIn("bash -e -o pipefail <<'AMT03_EMULATOR_BASH'", src)
        self.assertIn('python3 -m devtool.android_instrumentation', src)
        self.assertIn('--policy required', src)
        self.assertIn("assert int(r.get('passed',0)) >= 2", src)
        self.assertIn("assert int(r.get('failed',0)) == 0", src)
        self.assertIn('AMT03_DEVICE_INSTRUMENTATION_PASS', src)
        self.assertIn('if-no-files-found: warn', src)

    def test_kvm_step_uses_valid_bash(self):
        src = job_section()
        match = re.search(r'      - name: Enable KVM acceleration for AMT-03 instrumentation\n'
                          r'        shell: bash\n        run: \|\n'
                          r'((?:          .*\n)+)', src)
        self.assertIsNotNone(match)
        script = '\n'.join(line[10:] for line in match.group(1).splitlines()) + '\n'
        result = subprocess.run(['bash', '-n', '-c', script], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_source_check_stays_free_of_local_gradle(self):
        src = WF.read_text(encoding='utf-8')
        self.assertIn('python3 scripts/devtool/check_amt03_contract.py --check', src)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.mode == 'full'", src)
        self.assertIn('devtool --repo "$PWD" --java-home "$JAVA_HOME" invoke --target app ci', src)
        self.assertNotIn('chmod 777 /dev/kvm', src)


if __name__ == '__main__':
    unittest.main()
