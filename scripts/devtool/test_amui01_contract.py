#!/usr/bin/env python3
import unittest
from pathlib import Path
import check_amui01_contract as contract

class Amui01SourceContractTests(unittest.TestCase):
    def test_all_source_contracts(self): contract.check()
    def test_authoritative_scan_does_not_depend_on_old_async_cache(self):
        vm=contract.read(contract.JAVA/'main/MainViewModel.java')
        self.assertNotIn('getInstalledOrBackedUpApplicationsFromDb(getApplication(), true, true)',vm)
        self.assertIn('updateApplicationsChecked(getApplication())',vm)
    def test_broadcast_without_uid_uses_package_uri(self):
        receiver=contract.read(contract.JAVA/'types/PackageChangeReceiver.java')
        self.assertIn('getSchemeSpecificPart()',receiver)
        self.assertIn('onPackageChanged(intent, packages == null && uid >= 0 ? uid : null, packages)',receiver)
    def test_all_database_mutations_are_committed_transactionally(self):
        src=contract.read(contract.JAVA/'db/utils/AppDb.java')
        self.assertGreaterEqual(src.count('AppsDb.getInstance().runInTransaction('),2)
    def test_partial_scan_preserves_inaccessible_user(self):
        src=contract.read(contract.JAVA/'db/utils/AppDb.java')
        self.assertIn('oldApps.removeIf(app -> !scannedUsers.contains(app.userId))',src)
        self.assertIn('if (!scannedUsers.contains(backup.userId)) continue;',src)
    def test_package_manager_does_not_silently_skip_bad_parcels(self):
        src=contract.read(contract.JAVA/'compat/PackageManagerCompat.java')
        self.assertIn('Invalid package query for user',src)
        self.assertIn('Inconsistent package identities for user',src)
    def test_generation_owns_publication(self):
        src=contract.read(contract.JAVA/'main/MainViewModel.java')
        self.assertIn('if (epoch != mListEpoch.get())',src)
        self.assertIn('mDeferredCompletion = complete',src)
    def test_never_finish_spinner_while_queued(self):
        src=contract.read(contract.JAVA/'main/MainActivity.java')
        self.assertNotIn('mSwipeRefresh.setRefreshing(false);',src)
    def test_old_tasker_emulator_floor_preserved(self):
        src=contract.read(Path('scripts/devtool/amt03_hosted_instrumentation.sh'))
        self.assertIn("assert int(r.get('passed', 0)) >= 30",src)
        self.assertIn("assert int(r.get('failed', 0)) == 0",src)
    def test_tasker_mode_gate_appears_once(self):
        workflow=contract.read(Path('.github/workflows/devtool-tasker-ci.yml'))
        self.assertEqual(workflow.count("inputs.mode == 'full' || inputs.mode == 'instrumentation'"), 1)
        self.assertEqual(workflow.count("inputs.mode == 'instrumentation' || inputs.mode == 'full'"), 1)
    def test_unique_accelerated_emulator_configs(self):
        workflow=contract.read(Path('.github/workflows/devtool-tasker-ci.yml'))
        self.assertEqual(workflow.count('disable-linux-hw-accel: false'), 1)
        self.assertEqual(workflow.count("disable-linux-hw-accel: 'false'"), 1)
        self.assertIn('api-level: 35',workflow)
        self.assertIn('api-level: 36',workflow)
    def test_handoff_tasks_are_paused(self):
        doc=contract.read(Path('docs/AMUI01_APP_LIST_RELIABILITY.md'))
        self.assertIn('Tasker development remains paused',doc)

if __name__=='__main__': unittest.main()
