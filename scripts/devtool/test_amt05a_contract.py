#!/usr/bin/env python3
"""Source-only mutation tests for AMT-05A. No local Android or Gradle."""
from pathlib import Path
from tempfile import TemporaryDirectory
from shutil import copy2
import unittest
from check_amt05a_contract import ROOT, FILES, PKG, inspect

class AMT05AContract(unittest.TestCase):
    def test_all_source_contracts(self):
        self.assertEqual('PASS',inspect(ROOT)['status'])
    def mutate(self,key,old,new):
        with TemporaryDirectory() as tmp:
            base=Path(tmp)
            for path in FILES.values():
                p=base/path;p.parent.mkdir(parents=True,exist_ok=True)
                copy2(ROOT/path,p)
            target=base/FILES[key]
            src=target.read_text();self.assertIn(old,src)
            target.write_text(src.replace(old,new,1))
            self.assertEqual('FAIL',inspect(base)['status'],(key,old))
    def test_cross_user_guard(self):
        self.mutate('inventory','item.userId != user','item.userId != -1')
    def test_cross_package_guard(self):
        self.mutate('inventory','pkg.equals(item.packageName)','true')
    def test_no_snapshot_path_leak(self):
        self.mutate('inventory','MAX_LABEL_LENGTH = 80','MAX_LABEL_LENGTH = 9999999')
    def test_unavailable_is_not_zero(self):
        self.mutate('inventory','"inventory_unavailable"','"available"')
    def test_bounded_entry_count(self):
        self.mutate('inventory','MAX_ITEMS = 24','MAX_ITEMS = 10000000')
    def test_read_only_guard(self):
        self.mutate('inventory','static Bundle summarize(',
                    'static Bundle summarize( /* setApplicationEnabledSetting( */')
    def test_tasker_variables_declared(self):
        self.mutate('wire','%am_backups_json','%am_undocumented')
    def test_emulator_runs_real_class(self):
        self.mutate('runner','TaskerJournalInstrumentedTest','MockTest')
    def test_emulator_test_count_required(self):
        self.mutate('runner',"assert int(r.get('passed', 0)) >= 12", "assert int(r.get('passed', 0)) >= 2")
    def test_workflow_runs_source_contract(self):
        self.mutate('workflow','check_amt05a_contract.py --check','echo checks skipped')
    def test_ledger_corruption_instrumented(self):
        self.mutate('device','testLedgerCorruptionFailsClosedOnDevice','testLedgerCorruptionIgnored')
    def test_legacy_negative_freeze_test(self):
        self.mutate('device','testFreezeOwnPackageAlwaysDenied','testFreezeOwnPackageAllowed')

if __name__=='__main__':unittest.main()
