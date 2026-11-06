#!/usr/bin/env python3
"""Source-level regressions requiring no Android SDK or Gradle."""
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_amt03_contract import FILES, PKG, ROOT, inspect

class TaskerR3ContractTests(unittest.TestCase):
    def fixture(self, root):
        for rel in FILES:
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            copy2(ROOT / rel, root / rel)

    def test_full_contract(self):
        self.assertEqual('PASS', inspect(ROOT)['status'])

    def test_removal_of_atomic_cancel_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerExecutionJournal.java'
            path.write_text(path.read_text().replace('public synchronized Record cancelQueued(',
                                                     'public Record unsafeCancelQueued('))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_uncaptured_dispatch_failure_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerActionReceiver.java'
            src = path.read_text()
            src = src.replace('if (!journal.enqueue(id, profileId, state, callbackUri))',
                              'if (!journal.enqueue_after_profile_load(id, profileId, state, callbackUri))')
            path.write_text(src)
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_background_fallback_cannot_bypass_security_denial(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerActionReceiver.java'
            path.write_text(path.read_text().replace(
                'foregroundRejected instanceof android.app.ForegroundServiceStartNotAllowedException',
                'foregroundRejected instanceof RuntimeException'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_retention_timestamp_only_regression_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerExecutionJournal.java'
            path.write_text(path.read_text().replace('ORDER BY terminal_seq DESC',
                                                     'ORDER BY finished DESC'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_process_recovery_state_sync_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerExecutionJournal.java'
            path.write_text(path.read_text().replace('TaskerProfileEvents.recoveredInterrupted(',
                                                     'TaskerProfileEvents.recoveryNoop('))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_cancel_must_preserve_unrelated_running_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerProfileEvents.java'
            path.write_text(path.read_text().replace('if (!pref.getBoolean("running", false))',
                                                     'if (true)'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_per_profile_active_state_guard_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerProfileEvents.java'
            src = path.read_text()
            self.assertIn('if (!pref.getBoolean(p + "running", false))', src)
            path.write_text(src.replace('if (!pref.getBoolean(p + "running", false))', 'if (true)'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_queued_state_requires_current_journal_status(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerProfileEvents.java'
            path.write_text(path.read_text().replace('!"queued".equals(current.status)',
                                                     '"queued".equals(current.status)'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_job_permission_breach_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / 'app/src/main/AndroidManifest.xml'
            path.write_text(path.read_text().replace(
                'android:name=".automation.tasker.TaskerDispatchJobService"\n            android:permission="android.permission.BIND_JOB_SERVICE"\n            android:exported="true"',
                'android:name=".automation.tasker.TaskerDispatchJobService"\n            android:permission="android.permission.INTERNET"\n            android:exported="true"'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_guard_against_unsafe_retry_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            path = root / PKG / 'TaskerDispatchJobService.java'
            path.write_text(path.read_text().replace('jobFinished(params, false)',
                                                     'jobFinished(params, true)'))
            self.assertEqual('FAIL', inspect(root)['status'])

if __name__ == '__main__': unittest.main()
