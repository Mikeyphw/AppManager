#!/usr/bin/env python3
"""Source-only mutational regressions for AMT05C, no device or network."""
from pathlib import Path
from shutil import copy2
from tempfile import TemporaryDirectory
import unittest
from check_amt05c_contract import ROOT, FILES, inspect

class AMT05CContracts(unittest.TestCase):
    def fixture(self, root):
        for p in FILES.values():
            dest=root/p;dest.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/p,dest)
    def replace(self,key,needle,change):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root)
            p=root/FILES[key];s=p.read_text(encoding='utf-8')
            self.assertIn(needle,s)
            p.write_text(s.replace(needle,change,1),encoding='utf-8')
            self.assertEqual('FAIL',inspect(root)['status'],(key,needle))
    def test_integrated_contract(self):
        self.assertEqual('PASS',inspect(ROOT)['status'])
    def test_remove_receipt_before_dispatch(self):
        self.replace('dispatcher','receipts.enqueue(pkg, user, name);','// no receipt')
    def test_undurable_journal(self):
        self.replace('journal','file.startWrite()','new FileOutputStream("/sdcard/report")')
    def test_service_callback_must_stay_direct(self):
        self.replace('service','TaskerApkBackupJournal.serviceFinished(this, queueItem,','// removed')
    def test_single_service_start(self):
        self.replace('dispatcher','ContextCompat.startForegroundService(', 'startForegroundService(')
    def test_reject_wrong_user(self):
        self.replace('journal','request.userId == backup.userId', 'true')
    def test_require_backup_name_match(self):
        self.replace('journal','request.backupName.equals(backup.backupName)', 'true')
    def test_require_apk_only_flags(self):
        self.replace('journal','options.flags.getFlags() != FLAGS','false')
    def test_native_metadata_flags_distinct_from_dispatch_flags(self):
        self.replace('journal','backup.flags == METADATA_FLAGS','backup.flags == FLAGS')
    def test_no_false_verification(self):
        self.replace('journal','Boolean.toString("succeeded".equals(status))','Boolean.toString(true)')
    def test_avoid_broadcast_spoofing(self):
        self.replace('journal','serviceStarted(', 'registerReceiver(serviceStarted(')
    def test_hosted_source_contract_mandatory(self):
        self.replace('workflow','check_amt05c_contract.py --check','echo bypass')
    def test_17_device_tests_required(self):
        self.replace('runner',"assert int(r.get('passed', 0)) >= 17, ('AMT05C_RECEIPT_DEVICE_COVERAGE_INCOMPLETE', r)",
                     'assert True')
    def test_no_cross_user_query(self):
        self.replace('query','UserHandleHidden.myUserId()','0')
    def test_receipt_must_not_be_exported(self):
        self.replace('manifest','com.twofortyfouram.locale.intent.action.FIRE_SETTING','some.other.action')

if __name__=='__main__': unittest.main()
