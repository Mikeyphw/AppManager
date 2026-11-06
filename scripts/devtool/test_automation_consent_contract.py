#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_automation_consent_contract import ROOT, SOURCE, inspect

FILES = (
    "app/src/main/res/xml/preferences_main.xml",
    "app/src/main/res/values/strings_automation.xml",
    "app/src/main/AndroidManifest.xml",
    str(SOURCE / "automation/ui/AutomationPreferences.java"),
    str(SOURCE / "automation/android/AndroidCallerVerifier.java"),
    str(SOURCE / "automation/android/AndroidGrantRepository.java"),
)

class TestConsentContract(unittest.TestCase):
    def test_current_source_passes(self):
        self.assertEqual(inspect(ROOT)['status'], 'PASS')

    def fixture(self, temp):
        dest = Path(temp)
        for name in FILES:
            p=dest/name
            p.parent.mkdir(parents=True,exist_ok=True)
            copy2(ROOT/name,p)
        return dest

    def test_removed_settings_entry_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=self.fixture(tmp)
            path=root/'app/src/main/res/xml/preferences_main.xml'
            text=path.read_text()
            path.write_text(text.replace('io.github.muntashirakon.AppManager.automation.ui.AutomationPreferences',
                                         'io.github.muntashirakon.AppManager.settings.PrivacyPreferences'))
            self.assertIn('consent settings navigation missing',inspect(root)['errors'])

    def test_missing_signature_identity_check_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=self.fixture(tmp)
            path=root/SOURCE/'automation/android/AndroidCallerVerifier.java'
            path.write_text(path.read_text().replace('currentSigningCertificates(packageName)',
                                                      'null /* signature verification absent */'))
            self.assertTrue(any('installed caller verification' in e for e in inspect(root)['errors']))

    def test_missing_revoke_path_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=self.fixture(tmp)
            path=root/SOURCE/'automation/ui/AutomationPreferences.java'
            path.write_text(path.read_text().replace('grantRepository.revokeGrant(',
                                                     'grantRepository.fakeRevoke('))
            self.assertTrue(any('explicit consent safeguard' in e for e in inspect(root)['errors']))

if __name__=='__main__':unittest.main()
