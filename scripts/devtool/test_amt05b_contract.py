#!/usr/bin/env python3
"""Consolidated source gate + adversarial mutations for AMT05B."""
from pathlib import Path
from shutil import copy2
from tempfile import TemporaryDirectory
import unittest
from check_amt05b_contract import FILE, ROOT, PKG, inspect
class AMT05BContracts(unittest.TestCase):
 def test_source_gate(self):self.assertEqual('PASS',inspect(ROOT)['status'])
 def mutation(self,key,original,new):
  with TemporaryDirectory() as d:
   root=Path(d)
   for path in FILE.values():
    target=root/path;target.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/path,target)
   p=root/FILE[key];text=p.read_text();self.assertIn(original,text)
   p.write_text(text.replace(original,new,1))
   self.assertEqual('FAIL',inspect(root)['status'],f'mutation {key} {original}')
 def test_not_full_data_backup(self):self.mutation('dispatcher','BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE','BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE')
 def test_no_overwrite(self):self.mutation('dispatcher','BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE','BackupFlags.BACKUP_APK_FILES')
 def test_only_one_pkg(self):self.mutation('dispatcher','BatchQueueItem.getBatchOpQueue(','BatchQueueItem.getOneClickQueue(')
 def test_no_restore(self):self.mutation('dispatcher','BatchOpsManager.OP_BACKUP','BatchOpsManager.OP_RESTORE_BACKUP')
 def test_no_hidden_success(self):self.mutation('backend','"queued"','"success"')
 def test_service_launch_retained(self):self.mutation('dispatcher','ContextCompat.startForegroundService(','context.startService(')
 def test_package_hmac_binding(self):self.mutation('manage_test','TaskerPackageManageAccess.forTrustedEditor(app, PKG, "backup_apk")','TaskerPackageManageAccess.forTrustedEditor(app, PKG, "enable")')
 def test_device_denial_coverage(self):self.mutation('device','testApkBackupHostTargetNeverStartsBatchService','missing_host_test')
 def test_emulator_receipt(self):self.mutation('runner',"assert int(r.get('passed', 0)) >= 14, ('AMT05B_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)", "assert int(r.get('passed', 0)) >= 12, r")
 def test_ci_contract_registered(self):self.mutation('workflow','check_amt05b_contract.py --check','echo skipped')
 def test_host_scope_retained(self):self.mutation('backend','tasker_target_denied','tasker_permitted')
 def test_negative_tests_required(self):self.mutation('test','backupQueueTargetsOnePackageCurrentUserOnly','missing_queue_guard')
if __name__=='__main__':unittest.main()
