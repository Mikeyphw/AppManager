#!/usr/bin/env python3
"""Mutational source-only acceptance: no Android tools, Gradle, network, device."""
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_amt04b_contract import ROOT,PKG,FILES,inspect

class AMT04B1ContractTests(unittest.TestCase):
    def fixture(self,root):
        for path in FILES:
            p=root/path;p.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/path,p)
    def assert_detects(self,path,from_text,to_text):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root);p=root/path
            s=p.read_text();self.assertIn(from_text,s);p.write_text(s.replace(from_text,to_text))
            self.assertEqual('FAIL',inspect(root)['status'])
    def test_full_contract(self):self.assertEqual('PASS',inspect(ROOT)['status'])
    def test_cannot_disable_scoped_hmac(self):self.assert_detects(PKG/'TaskerPackageManageAccess.java','MessageDigest.isEqual','String.equals')
    def test_cannot_remove_permission_check(self):self.assert_detects(PKG/'TaskerPackageManageBackend.java','SelfPermissions.checkSelfOrRemotePermission(', 'true || permissionCheck(')
    def test_cannot_remove_system_guard(self):self.assert_detects(PKG/'TaskerPackageManageBackend.java','system_package_denied','no_guard')
    def test_cannot_remove_result_verification(self):self.assert_detects(PKG/'TaskerPackageManageBackend.java','state_unverified','always_success')
    def test_cannot_enable_dynamic_package(self):self.assert_detects(PKG/'TaskerPackageManageWire.java','input.size() != 4','config.size() > 6')
    def test_cannot_remove_ordered_broadcast_requirement(self):self.assert_detects(PKG/'TaskerActionReceiver.java','if (!isOrderedBroadcast()) return;', 'if (true) return;')
    def test_cannot_remove_self_protection(self):self.assert_detects(PKG/'TaskerPackageManageBackend.java','self_target_denied','self_allow')
    def test_cannot_change_package_manage_editor(self):self.assert_detects(Path('app/src/main/AndroidManifest.xml'),
          'android:name=".automation.tasker.TaskerPackageManageEditActivity"','android:name=".automation.tasker.FakeManageEditor"')

if __name__=='__main__':unittest.main()
