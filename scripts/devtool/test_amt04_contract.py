#!/usr/bin/env python3
"""Read-only Tasker query source and mutational contract tests (no local Gradle)."""
from __future__ import annotations
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_amt04_contract import ROOT, REQUIRED, BASE, inspect


class AMT04AContractTests(unittest.TestCase):
    def fixture(self, root):
        for path in REQUIRED:
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            copy2(ROOT / path, root / path)

    def test_source_contract(self):
        self.assertEqual('PASS', inspect(ROOT)['status'])

    def test_cannot_strip_profile_receiver_coexistence_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            p = root / BASE / 'TaskerActionReceiver.java'
            p.write_text(p.read_text().replace('marker.containsKey(TaskerPackageQueryWire.KEY_VERSION)',
                                                'false'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_missing_query_capability_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            p = root / BASE / 'TaskerPackageQueryAccess.java'
            p.write_text(p.read_text().replace('MessageDigest.isEqual', 'unsafe_equals'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_privileged_receiver_call_is_forbidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            p = root / BASE / 'TaskerPackageQueryReceiver.java'
            p.write_text(p.read_text().replace('TaskerPackageQuery.query(context,',
                                                'startForegroundService(TaskerPackageQuery.query(context,'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_manifest_query_editor_removal_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            p = root / 'app/src/main/AndroidManifest.xml'
            p.write_text(p.read_text().replace('android:name=".automation.tasker.TaskerPackageQueryEditActivity"',
                                                'android:name=".automation.tasker.NotQueryEditor"'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_hosted_instrumentation_push_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            p = root / '.github/workflows/devtool-tasker-ci.yml'
            p.write_text(p.read_text().replace(
                "      - 'app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/**'", ''))
            self.assertEqual('FAIL', inspect(root)['status'])


if __name__ == '__main__': unittest.main()
