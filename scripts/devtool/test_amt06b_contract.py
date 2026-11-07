#!/usr/bin/env python3
"""Adversarial AMT-06B source checks; preserve old source and all native safety gates."""
from pathlib import Path
from tempfile import TemporaryDirectory
from shutil import copy2
import unittest
from check_amt06b_contract import ROOT, FILES, inspect
class Contract(unittest.TestCase):
 def fixture(self,root):
  for p in FILES.values():
   (root/p).parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/p,root/p)
 def mutate(self,key,old,new):
  with TemporaryDirectory() as tmp:
   root=Path(tmp);self.fixture(root);p=root/FILES[key]
   s=p.read_text();self.assertIn(old,s);p.write_text(s.replace(old,new,1))
   self.assertEqual('FAIL',inspect(root)['status'],(key,old))
 def test_integrated_contract(self):self.assertEqual('PASS',inspect(ROOT)['status'])
 def test_no_signature_bypass(self):self.mutate('gate','candidate.flags != REQUIRED_FLAGS','true')
 def test_backup_identity_bound(self):self.mutate('gate','ai.uid != TaskerPackageManageWire.restoreUid(operation)','false')
 def test_install_identity_bound(self):self.mutate('gate','identity.firstInstallTime != TaskerPackageManageWire.restoreInstalledAt(operation)','false')
 def test_user_bound(self):self.mutate('gate','candidate.userId != user','false')
 def test_backup_name_bound(self):self.mutate('gate','!name.equals(candidate.backupName)','false')
 def test_no_restore_over_apk(self):self.mutate('dispatch','new BatchBackupOptions(BackupFlags.BACKUP_INT_DATA,','new BatchBackupOptions(BackupFlags.BACKUP_APK_FILES,')
 def test_exact_user(self):self.mutate('gate','UserHandleHidden.myUserId()','0')
 def test_signing_certificate_unchanged(self):self.mutate('gate','if (!Arrays.asList(installed).contains(signer))','if (false)')
 def test_nonempty_internal_data_required(self):self.mutate('gate','metadata.dataDirs.length == 0','false')
 def test_archive_certificate_checks_required(self):self.mutate('gate','BackupItems.Checksum.getCertChecksums(checks)','new String[0]')
 def test_privilege_remains(self):self.mutate('gate','SelfPermissions.canWriteToDataData()','true')
 def test_service_preflight(self):self.mutate('service','TaskerDataRestoreJournal.authorizeServiceQueue(this, item)','true')
 def test_service_completion(self):self.mutate('service','TaskerDataRestoreJournal.serviceFinished(this, queueItem,','// disabled(')
 def test_write_ahead_journal(self):self.mutate('dispatch','journal.enqueue(backup, requestId);','// removed')
 def test_no_false_verification(self):self.mutate('journal','"%am_restore_request_verified", "false"','"%am_restore_request_verified", "true"')
 def test_user_consent_required(self):self.mutate('editor','restoreConsent.isChecked()','true')
 def test_device_coverage_required(self):self.mutate('runner',"assert int(r.get('passed', 0)) >= 26, ('AMT06B_RESTORE_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",'assert True')
 def test_hosted_source_gate(self):self.mutate('workflow','check_amt06b_contract.py --check','echo bypass')
if __name__=='__main__':unittest.main()
