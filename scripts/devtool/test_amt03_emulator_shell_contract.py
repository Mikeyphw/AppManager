#!/usr/bin/env python3
"""AMT-03 HF02 hosted emulator shell regression. Pure Python/shell, no Gradle."""
from __future__ import annotations
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/devtool-tasker-ci.yml'
JOB = '      - name: Run journal device instrumentation (no real profiles modified)'
NEXT = '      - name: Upload instrumentation receipt and diagnostics'
WRAPPER = "bash -e -o pipefail <<'AMT03_EMULATOR_BASH'"


def extract_script() -> str:
    content = WORKFLOW.read_text(encoding='utf-8')
    if content.count(JOB) != 1 or content.count(NEXT) != 1:
        raise AssertionError('expected one instrumentation job and artifact upload')
    start = content.index(JOB)
    end = content.index(NEXT, start)
    section = content[start:end]
    anchor = '          script: |\n'
    if section.count(anchor) != 1:
        raise AssertionError('emulator job script missing/duplicated')
    return textwrap.dedent(section.split(anchor, 1)[1]).strip() + '\n'


def under_sh(script: str, temp: str) -> subprocess.CompletedProcess[str]:
    shell = shutil.which('sh')
    if shell is None:
        raise RuntimeError('sh is required for the CI shell contract')
    env = dict(os.environ, JAVA_HOME='/fake-jdk', RUNNER_TEMP=temp,
               PYTHONDONTWRITEBYTECODE='1')
    return subprocess.run([shell, '-c', script], cwd=ROOT, env=env,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


class HF02EmulatorShellContract(unittest.TestCase):
    def test_bash_wrapper_and_nested_python_heredoc(self):
        script = extract_script()
        self.assertTrue(script.startswith(WRAPPER + '\n'))
        self.assertTrue(script.endswith('AMT03_EMULATOR_BASH\n'))
        self.assertIn("python3 - <<'PYCHECK'", script)
        self.assertIn('python3 -m devtool.android_instrumentation', script)
        self.assertIn('--policy required', script)
        self.assertIn('TaskerJournalInstrumentedTest', script)
        for shell in ('sh', 'bash'):
            resolved = shutil.which(shell)
            self.assertIsNotNone(resolved, shell)
            syntax = subprocess.run([resolved, '-n'], input=script,
                                    text=True, capture_output=True)
            self.assertEqual(0, syntax.returncode, syntax.stderr)

    def _script_with_fake_instrumentation(self, *, exit_code: int) -> str:
        script = extract_script()
        if exit_code == 0:
            fake = ("python3 -c 'import json,os,pathlib; "
                    "pathlib.Path(os.environ[\"RUNNER_TEMP\"], "
                    "\"amt03-instrumentation.json\").write_text(" 
                    "json.dumps({\"status\":\"passed\",\"passed\":2,\"failed\":0}))' "
                    '2>&1 | tee "$RUNNER_TEMP/amt03-instrumentation.log"')
        else:
            fake = (f"python3 -c 'import sys;sys.exit({exit_code})' 2>&1 "
                    '| tee "$RUNNER_TEMP/amt03-instrumentation.log"')
        script, count = re.subn(r'(?m)^python3 -m devtool\.android_instrumentation[^\n]+$',
                                lambda _: fake, script)
        self.assertEqual(1, count)
        return script

    def test_outer_dash_runs_successful_bash_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = under_sh(self._script_with_fake_instrumentation(exit_code=0), tmp)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn('AMT03_DEVICE_INSTRUMENTATION_PASS', result.stdout)

    def test_pipefail_propagates_instrumentation_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = under_sh(self._script_with_fake_instrumentation(exit_code=13), tmp)
            self.assertEqual(13, result.returncode, result.stdout)
            self.assertNotIn('AMT03_DEVICE_INSTRUMENTATION_PASS', result.stdout)


if __name__ == '__main__':
    unittest.main()
