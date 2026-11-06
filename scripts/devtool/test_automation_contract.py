"""Source-only AMT-01 regression tests (no Gradle)."""
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_automation_contract import ROOT, CORE, TEST, NAMES, ANDROID, ANDROID_CLASSES, IDENTITY_TEST, inspect

def fixture(tmp):
    p = Path(tmp)
    names = [CORE / name for name in NAMES] + [ANDROID / name for name in ANDROID_CLASSES] + [TEST, IDENTITY_TEST, Path('app/src/main/AndroidManifest.xml')]
    for name in names:
        dest = p / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        copy2(ROOT / name, dest)
    return p

class TestAutomationContract(unittest.TestCase):
    def test_source_isolation(self):
        self.assertEqual('PASS', inspect()['status'])

    def test_exposed_endpoint_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            manifest = root / 'app/src/main/AndroidManifest.xml'
            manifest.write_text(manifest.read_text() + '\n<!-- automation.core -->\n')
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_android_uid_verification_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            path = root / ANDROID / 'AndroidCallerVerifier.java'
            path.write_text(path.read_text().replace('Binder.getCallingUid()', '12345'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_shared_uid_rejection_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            path = root / CORE / 'AutomationIdentityPolicy.java'
            path.write_text(path.read_text().replace('uidPackages.length == 1', 'uidPackages.length > 0'))
            self.assertEqual('FAIL', inspect(root)['status'])

    def test_authorization_gate_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            path = root / CORE / 'AutomationDispatcher.java'
            path.write_text(path.read_text().replace('replayGuard.reserve', 'unsafe.reserve'))
            self.assertEqual('FAIL', inspect(root)['status'])

if __name__ == '__main__':
    unittest.main()
