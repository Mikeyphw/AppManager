#!/usr/bin/env python3
"""AMT-05D fail-closed source mutation suite; no device, Gradle or network."""
from pathlib import Path
from tempfile import TemporaryDirectory
from shutil import copy2
import unittest
from check_amt05d_contract import FILES, ROOT, inspect
class RecoveryContract(unittest.TestCase):
 def test_full_contract(self):self.assertEqual(inspect(ROOT)['status'],'PASS')
 def mutate(self,key,old,new):
  with TemporaryDirectory() as directory:
   root=Path(directory)
   for p in FILES.values():
    target=root/p;target.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/p,target)
   f=root/FILES[key];s=f.read_text();self.assertIn(old,s)
   f.write_text(s.replace(old,new,1));self.assertEqual(inspect(root)['status'],'FAIL',(key,old))
 def test_no_expiry_gate(self):self.mutate('journal','expireStalePending(records, recoveryNow);','// no recovery')
 def test_no_invented_success(self):self.mutate('journal','record.put("status", "unknown")','record.put("status", "succeeded")')
 def test_time_rollback_guard(self):self.mutate('journal','if (pending(status) && now >= updated && now - updated > STALE_MS)','if (pending(status) && now - updated > STALE_MS)')
 def test_capacity_preserved(self):self.mutate('journal','if (oldest < 0) throw new IOException(','if (oldest < -1) throw new IOException(')
 def test_no_terminal_rewrite(self):self.mutate('journal','if (!pending(current)) return;','if (false) return;')
 def test_current_user_scope(self):self.mutate('journal','if (!pkg.equals(rec.getString("package")) || user != rec.getInt("user"))\n                        continue;','if (false)\n                        continue;')
 def test_history_bounded(self):self.mutate('journal','history.length() < HISTORY_LIMIT','true')
 def test_history_variables_cleared(self):self.mutate('journal','out.putString("%am_backup_request_history_json", "[]")','out.putString("%am_backup_request_history_json", "stale")')
 def test_hosted_source_gate(self):self.mutate('workflow','python3 scripts/devtool/check_amt05d_contract.py --check','echo skipped')
 def test_tie_order_preserved(self):self.mutate('journal','latest == null || rec.getLong("created_ms") > latest.getLong("created_ms")','latest == null || rec.getLong("created_ms") >= latest.getLong("created_ms")')
 def test_expiry_reason_visible(self):self.mutate('journal','observedError = "backup_completion_timeout";','observedError = "";')
 def test_device_coverage_gate(self):self.mutate('runner',"assert int(r.get('passed', 0)) >= 19, ('AMT05D_RECEIPT_RECOVERY_DEVICE_COVERAGE_INCOMPLETE', r)",'assert True')
if __name__=='__main__':unittest.main()
