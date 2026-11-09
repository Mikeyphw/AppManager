#!/usr/bin/env python3
"""AMUI-01 source checks, mandatory alongside all existing AMT gates. No Gradle."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('app/src/main/java/io/github/muntashirakon/AppManager')

def read(path):
    p=ROOT/path
    if not p.is_file(): raise AssertionError(f'Missing AMUI-01 source: {path}')
    return p.read_text(encoding='utf-8')

def require(path, tokens=(), absent=()):
    source=read(path)
    for token in tokens:
        if token not in source: raise AssertionError(f'{path} missing {token!r}')
    for token in absent:
        if token in source: raise AssertionError(f'{path} retains unsafe {token!r}')

def check():
    require(JAVA/'main/MainViewModel.java', [
        'Executors.newSingleThreadExecutor()', 'mListEpoch.incrementAndGet()',
        'getCachedInstalledOrBackedUpApplicationsFromDb', 'updateApplicationsChecked',
        'mRefreshState.setValue(status.withVisibleCount',
        'mApplicationItemsLiveData.setValue(Collections.unmodifiableList(snapshot))',
        'refreshApplications("resume")', 'refreshApplications("startup")',
        'item.snapshotForList()', 'mListExecutor.execute(() -> {',
        'private void publishVisibleItems',
    ], ['mFilterResult.cancel(true)',
        'getInstalledOrBackedUpApplicationsFromDb(getApplication(), true, true)'])
    require(JAVA/'main/MainActivity.java', [
        'viewModel.refreshApplications("pull")',
        'viewModel.refreshApplications("menu")',
        'getRefreshState().observe', 'mSwipeRefresh.setRefreshing(status.running)',
    ], ['mSwipeRefresh.setRefreshing(false);'])
    require(JAVA/'types/PackageChangeReceiver.java', [
        'final PendingResult pending = goAsync()',
        'RECEIVER_EXECUTOR.execute', 'pending.finish()',
        'getSchemeSpecificPart()', 'Intent.ACTION_PACKAGE_REPLACED',
    ], ['new HandlerThread("PackageChangeReceiver"', 'if (uid != -1) {'])
    require(JAVA/'db/utils/AppDb.java', [
        'public SyncReport updateApplicationsChecked',
        'scannedUsers.add(userId)',
        'oldApps.removeIf(app -> !scannedUsers.contains(app.userId))',
        'AppsDb.getInstance().runInTransaction',
        'if (packageInfoList == null || packageInfoList.isEmpty())',
        'if (!scannedUsers.contains(backup.userId)) continue;',
    ])
    require(JAVA/'compat/PackageManagerCompat.java', [
        'referenceNames.equals(actualNames)',
        'throw new IllegalStateException("Incomplete package scan for user',
        'throw new IllegalStateException("Invalid package query for user',
    ], ['return Collections.emptyList();\n        }\n    }\n\n    @WorkerThread\n    public static List<ApplicationInfo>'])
    require(JAVA/'utils/PackageUtils.java', [
        'getCachedInstalledOrBackedUpApplicationsFromDb',
        'loadInBackground && backgroundRefresh',
    ])
    require(JAVA/'main/MainRecyclerAdapter.java', [
        'Objects.equals(oldItem.label, newItem.label)',
        'java.util.Arrays.equals(oldItem.userIds, newItem.userIds)',
    ])
    require(JAVA/'main/ApplicationItem.java', ['snapshotForList()', 'copy.userIds = userIds.clone()'])
    require(JAVA/'main/AppListSyncStatus.java', ['final boolean running', 'withVisibleCount'])
    require(Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/main/Amui01InstalledInstrumentedTest.java'),
            ['testUidlessPackageUriIsRecognized', 'testAuthoritativeSyncFindsRealInstalledApk',
             'testManualRefreshCompletesAfterVisiblePublication',
             'testSnapshotDoesNotAliasMutableMetadata',
             'Active search filter was ignored'])
    require(Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/main/Amui01RemovedInstrumentedTest.java'),
            ['testUninstallReconcilesWithoutStaleInstalledRows'])
    workflow=read(Path('.github/workflows/devtool-tasker-ci.yml'))
    for needed in ('scripts/devtool/check_amt06b_contract.py --check',
                   'scripts/devtool/check_amt06c_contract.py --check',
                   'scripts/devtool/check_amui01_contract.py --check',
                   'scripts/devtool/amt03_hosted_instrumentation.sh && bash scripts/devtool/amui01_hosted_instrumentation.sh',
                   'amui01-android16:', 'api-level: 36',
                   'amui01-hosted', 'mode == \'instrumentation\''):
        if needed not in workflow:
            if needed == 'amui01-hosted': continue
            raise AssertionError('Workflow missing required gate: '+needed)
    # Guard the unique, unmodified Tasker/Locale instrumentation policy;
    # adding API36 acceptance may not create another copy that masks a
    # mutation of the mandatory API35 gate.
    api35_guard = "inputs.mode == 'full' || inputs.mode == 'instrumentation'"
    api36_guard = "inputs.mode == 'instrumentation' || inputs.mode == 'full'"
    assert workflow.count(api35_guard) == 1, 'AMUI01_CI_REFUSED: duplicate/absent original Tasker mode gate'
    assert workflow.count(api36_guard) == 1, 'AMUI01_CI_REFUSED: API36 mode gate missing/duplicated'
    assert workflow.count('disable-linux-hw-accel: false') == 1, 'AMUI01_CI_REFUSED: original KVM gate duplicated/absent'
    assert workflow.count("disable-linux-hw-accel: 'false'") == 1, 'AMUI01_CI_REFUSED: API36 KVM acceleration not explicitly enabled'
    assert workflow.count('  device-instrumentation:\n') == 1
    assert workflow.count('  amui01-android16:\n') == 1
    api35_job = workflow.split('  device-instrumentation:\n', 1)[1].split('  amt06c-fixture:\n', 1)[0]
    api36_job = workflow.split('  amui01-android16:\n', 1)[1]
    assert api35_guard in api35_job and 'api-level: 35' in api35_job, 'AMUI01_CI_REFUSED: existing Tasker job weakened'
    assert 'disable-linux-hw-accel: false' in api35_job, 'AMUI01_CI_REFUSED: Tasker job KVM policy lost'
    assert api36_guard in api36_job and 'api-level: 36' in api36_job, 'AMUI01_CI_REFUSED: Android16 acceptance not gated'
    assert "disable-linux-hw-accel: 'false'" in api36_job, 'AMUI01_CI_REFUSED: Android16 KVM policy lost'
    assert 'script: bash scripts/devtool/amt03_hosted_instrumentation.sh && bash scripts/devtool/amui01_hosted_instrumentation.sh' in api35_job
    assert 'script: AMUI01_EXPECT_API=36 bash scripts/devtool/amui01_hosted_instrumentation.sh' in api36_job
    from check_amui01_ci_push import validate_workflow
    validate_workflow(workflow)
    script=read(Path('scripts/devtool/amui01_hosted_instrumentation.sh'))
    for needed in ('adb install --user 0', 'adb uninstall "$fixture"',
                   'Amui01InstalledInstrumentedTest', 'Amui01RemovedInstrumentedTest',
                   'AMUI01_LIFECYCLE_HOSTED_PASS'):
        if needed not in script: raise AssertionError('No real hosted test: '+needed)
    print('AMUI01_SOURCE_CONTRACT_PASS')

if __name__=='__main__':
    check()
