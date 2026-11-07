#!/usr/bin/env python3
"""AMT-06A source mutations, no local Gradle/Android/network."""
from tempfile import TemporaryDirectory
from shutil import copy2
from pathlib import Path
import unittest
from check_amt06a_contract import ROOT, FILES, inspect
class Contract(unittest.TestCase):
 def test_contract(self):self.assertEqual('PASS', inspect(ROOT)['status'])
 def mutate(self,which,old,new):
  with TemporaryDirectory() as tmp:
   root=Path(tmp)
   for p in FILES.values():
    dest=root/p;dest.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/p,dest)
   f=root/FILES[which];x=f.read_text();self.assertIn(old,x);f.write_text(x.replace(old,new,1))
   self.assertEqual('FAIL',inspect(root)['status'],(which,old))
 def test_no_privilege_bypass(self):self.mutate('backend','SelfPermissions.canWriteToDataData()','true')
 def test_no_arbitrary_package(self):self.mutate('dispatcher','TaskerPackageQueryWire.validPackage(pkg)','true')
 def test_exact_data_flags(self):self.mutate('dispatcher','BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE','BackupFlags.BACKUP_EXT_DATA')
 def test_no_direct_io(self):self.mutate('dispatcher','ContextCompat.startForegroundService(', 'Runtime.getRuntime(')
 def test_no_missing_ledger(self):self.mutate('dispatcher','receipts.enqueue(pkg, user, name, "data");','// bypass')
 def test_prevent_false_success(self):self.mutate('journal','backup.flags == DATA_METADATA_FLAGS','true')
 def test_callback_type_required(self):self.mutate('journal','if (!type.equals(receiptType(rec))) return;','// bypass')
 def test_typed_record_required(self):self.mutate('journal','if ("data".equals(type)) item.put("type", type);','// bypass')
 def test_status_unverified(self):self.mutate('dispatcher','"%am_verified", "false"','"%am_verified", "true"')
 def test_hosted_gate(self):self.mutate('workflow','python3 scripts/devtool/check_amt06a_contract.py --check','echo skipped')
 def test_device_gate(self):self.mutate('runner',"assert int(r.get('passed', 0)) >= 22, ('AMT06A_DATA_BACKUP_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",'assert True')
 def test_no_dynamic_target(self):self.mutate('test','typedDataReceiptPersistsWithoutChangingApkRecords','noTest')
if __name__=='__main__':unittest.main()
