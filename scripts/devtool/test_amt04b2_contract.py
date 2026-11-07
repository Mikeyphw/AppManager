#!/usr/bin/env python3
"""Source mutation tests for AMT-04B2A and CI modes."""
from pathlib import Path
from shutil import copy2
from tempfile import TemporaryDirectory
import unittest
from check_amt04b2_contract import ROOT, PKG, inspect
class Contract(unittest.TestCase):
    FILES=[PKG/p for p in ('TaskerPackageManageWire.java','TaskerPackageManageEditActivity.java','TaskerPackageManageBackend.java','TaskerPackageManageAccess.java')]+[
        Path('.github/workflows/devtool-tasker-ci.yml'),Path('app/src/main/AndroidManifest.xml'),
        Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'),
        Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java')]
    def test_contract(self):self.assertEqual('PASS', inspect(ROOT)['status'])
    def mutate(self,path,needle,replacement):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            for file in self.FILES:
                destination=root/file;destination.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/file,destination)
            p=root/path;s=p.read_text();self.assertIn(needle,s);p.write_text(s.replace(needle,replacement,1))
            self.assertEqual('FAIL',inspect(root)['status'],path)
    def test_permission(self):self.mutate(PKG/'TaskerPackageManageBackend.java','ManifestCompat.permission.FORCE_STOP_PACKAGES','"android.permission.INTERNET"')
    def test_host_protection(self):self.mutate(PKG/'TaskerPackageManageBackend.java','tasker_target_denied','host_allowed')
    def test_backend_call(self):self.mutate(PKG/'TaskerPackageManageBackend.java','PackageManagerCompat.forceStopPackage(pkg, user)','// removed')
    def test_status_not_verified(self):self.mutate(PKG/'TaskerPackageManageBackend.java','"dispatched"','"success"')
    def test_hmac(self):self.mutate(PKG/'TaskerPackageManageAccess.java','MessageDigest.isEqual(','equalsNotConstantTime(')
    def test_pull_requests_still_full(self):self.mutate(Path('.github/workflows/devtool-tasker-ci.yml'),"github.event_name == 'pull_request' && 'full'","github.event_name == 'pull_request' && 'source'")
    def test_source_default(self):self.mutate(Path('.github/workflows/devtool-tasker-ci.yml'),'default: source','default: full')
    def test_b2_is_in_hosted_source_gate(self):self.mutate(Path('.github/workflows/devtool-tasker-ci.yml'),'python3 scripts/devtool/check_amt04b2_contract.py --check','echo source contract skipped')
    def test_full_retained(self):self.mutate(Path('.github/workflows/devtool-tasker-ci.yml'),"inputs.mode == 'full' || inputs.mode == 'instrumentation'","inputs.mode == 'source'")
if __name__=='__main__':unittest.main()
