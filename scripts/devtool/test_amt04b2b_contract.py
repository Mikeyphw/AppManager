#!/usr/bin/env python3
"""AMT-04B2B source mutation regressions; requires no Gradle and no device."""
from pathlib import Path
from shutil import copy2
from tempfile import TemporaryDirectory
import unittest
from check_amt04b2b_contract import ROOT,PKG,FILES,inspect

class FreezeRestoreSafety(unittest.TestCase):
    def test_all_b2b_contracts(self):
        self.assertEqual('PASS',inspect(ROOT)['status'])

    def mutate(self, key, old, new):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            for p in FILES.values():
                dest=root/p;dest.parent.mkdir(parents=True,exist_ok=True);copy2(ROOT/p,dest)
            path=root/FILES[key];src=path.read_text(encoding='utf-8')
            self.assertIn(old,src)
            path.write_text(src.replace(old,new,1),encoding='utf-8')
            self.assertEqual('FAIL',inspect(root)['status'],f'{key}: mutation unexpectedly passed')

    def test_can_never_disable_unowned_snapshot(self):
        self.mutate('backend','already_disabled_without_snapshot','trust_unowned_disabled_state')
    def test_no_restore_without_record(self):
        self.mutate('backend','restore_record_missing','restore_without_saved_record')
    def test_prior_state_must_restore_exactly(self):
        self.mutate('backend','PackageManagerCompat.setApplicationEnabledSetting(pkg, saved.previousState, 0, user)',
                    'PackageManagerCompat.setApplicationEnabledSetting(pkg, 1, 0, user)')
    def test_identity_mismatch_refused(self):
        self.mutate('backend','freeze_identity_changed','ignore_package_identity')
    def test_state_conflict_fails_closed(self):
        self.mutate('backend','restore_state_conflict','overwrite_foreign_state')
    def test_ledger_write_must_precede_side_effect(self):
        self.mutate('backend','ledger.record(pkg, saved);','// remove prior state persistence')
    def test_mutation_before_snapshot_refused(self):
        self.mutate('backend','ledger.record(pkg, saved);',
                    'PackageManagerCompat.setApplicationEnabledSetting(pkg,\n'
                    '                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, user);\n'
                    '                ledger.record(pkg, saved);')
    def test_existing_enable_disable_cannot_bypass_ledger(self):
        self.mutate('backend','managed_freeze_pending','enabled_while_snapshot_open')
    def test_atomic_rollback_is_required(self):
        self.mutate('ledger','file.failWrite(output)','// incomplete write accepted')
    def test_no_backup(self):
        self.mutate('ledger','getNoBackupFilesDir()','getFilesDir()')
    def test_tasker_host_protection(self):
        self.mutate('backend','TaskerPluginWire.LOCALE_PACKAGE.equals(pkg)','"none".equals(pkg)')
    def test_editor_host_protection(self):
        self.mutate('editor','Cannot disrupt the Tasker/Locale automation host.','Host mutation allowed')
    def test_legacy_force_stop_editor_message_remains_protected(self):
        self.mutate('editor', 'Cannot force-stop Tasker itself.', 'Allow force stop of Tasker')

    def test_legacy_b2a_contract_retained(self):
        from check_amt04b2_contract import inspect as old_inspect
        self.assertEqual('PASS', old_inspect(ROOT)['status'])

    def test_one_shared_receiver(self):
        self.mutate('manifest','android:name=".automation.tasker.TaskerActionReceiver"',
                    'android:name=".automation.tasker.FakeReceiver"')
    def test_hosted_source_check_registered(self):
        self.mutate('workflow','python3 scripts/devtool/check_amt04b2b_contract.py --check',
                    'echo source gate omitted')
    def test_hosted_full_preserved(self):
        self.mutate('workflow',"inputs.mode == 'full' || inputs.mode == 'instrumentation'",
                    "inputs.mode == 'source'")
    def test_hmac_scope_retained(self):
        self.mutate('wire','KEY_OPERATION = "am.pkgmanage.operation"',
                    'KEY_OPERATION = "am.pkgmanage.anything"')

if __name__=='__main__':unittest.main()
