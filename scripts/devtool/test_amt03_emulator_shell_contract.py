#!/usr/bin/env python3
"""HF02/HF04: emulator action must invoke one Bash file, preserving fail-closed behavior."""
from __future__ import annotations
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/devtool-tasker-ci.yml'
SCRIPT = ROOT / 'scripts/devtool/amt03_hosted_instrumentation.sh'

class HF02EmulatorShellContract(unittest.TestCase):
    def test_emulator_action_invokes_single_bash_command(self):
        s = WORKFLOW.read_text()
        start = s.index('      - name: Run journal device instrumentation')
        end = s.index('      - name: Upload instrumentation receipt and diagnostics', start)
        slot = s[start:end]
        self.assertIn('script: bash scripts/devtool/amt03_hosted_instrumentation.sh', slot)
        self.assertNotIn('script: |', slot)
        syntax = subprocess.run(['sh', '-n', '-c', 'bash scripts/devtool/amt03_hosted_instrumentation.sh'],
                                capture_output=True, text=True)
        self.assertEqual(0, syntax.returncode, syntax.stderr)

    def test_bash_runs_nested_python_heredoc(self):
        s = SCRIPT.read_text()
        self.assertIn("python3 -B - <<'PY'", s)
        self.assertIn("<<'PYCHECK'", s)
        self.assertIn('set -Eeuo pipefail', s)
        result = subprocess.run(['bash', '-n', str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_pipefail_propagates_instrumentation_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            expr = 'set -e -o pipefail; bash -c "exit 13" | tee ' + repr(str(Path(tmp) / 'out'))
            result = subprocess.run(['sh', '-c', 'bash -c "$1"', 'sh', expr],
                                    capture_output=True, text=True)
            self.assertEqual(13, result.returncode, result.stdout + result.stderr)

    def test_script_removes_stale_receipt_and_checks_results(self):
        s = SCRIPT.read_text()
        self.assertIn('rm -f -- "$receipt" "$log"', s)
        self.assertIn('PIPESTATUS[0]', s)
        self.assertIn('AMT03_DEVICE_INSTRUMENTATION_PASS', s)
        self.assertIn("assert int(r.get('executed', 0)) >= 2", s)
        self.assertIn("assert int(r.get('failed', 0)) == 0", s)

if __name__ == '__main__':
    unittest.main()
