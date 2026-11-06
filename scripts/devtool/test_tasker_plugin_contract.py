#!/usr/bin/env python3
"""No Android SDK, Gradle or external network required."""
import tempfile
import unittest
from pathlib import Path
from shutil import copy2

from check_tasker_plugin_contract import ROOT, FILES, PKG, inspect


class NativeTaskerContractTests(unittest.TestCase):
    @staticmethod
    def fixture(destination: Path):
        for rel in FILES:
            (destination / rel).parent.mkdir(parents=True, exist_ok=True)
            copy2(ROOT / rel, destination / rel)

    def test_native_plugin_contract(self):
        self.assertEqual(inspect(ROOT)['status'], 'PASS')

    def test_hidden_token_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.fixture(root)
            p=root/PKG/'TaskerActionReceiver.java'
            p.write_text(p.read_text().replace('TaskerAccess.valid(', 'TaskerAccess.bypass('))
            self.assertTrue(any('TaskerAccess.valid(' in e for e in inspect(root)['errors']))

    def test_tasker_edit_must_verify_host(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.fixture(root)
            p=root/PKG/'TaskerActionEditActivity.java'
            p.write_text(p.read_text().replace('getCallingPackage()', 'getPackageName()'))
            self.assertTrue(any('getCallingPackage()' in e for e in inspect(root)['errors']))

    def test_tasker_receiver_must_be_exported_for_discovery(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.fixture(root)
            p=root/'app/src/main/AndroidManifest.xml'
            p.write_text(p.read_text().replace('android:name=".automation.tasker.TaskerActionReceiver"\n            android:exported="true"',
                        'android:name=".automation.tasker.TaskerActionReceiver"\n            android:exported="false"'))
            self.assertTrue(any('not exported' in e for e in inspect(root)['errors']))

    def test_old_approval_screen_is_not_restored(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.fixture(root)
            p=root/'app/src/main/res/xml/preferences_main.xml'
            p.write_text(p.read_text().replace('</PreferenceScreen>', '<!-- automation_tasker_prefs -->\n</PreferenceScreen>'))
            self.assertTrue(any('obsolete authorization' in e for e in inspect(root)['errors']))


if __name__=='__main__': unittest.main()
