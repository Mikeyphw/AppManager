#!/usr/bin/env python3
"""AMT-05A: read-only backup inventory and live Android-ledger emulator coverage.
No Gradle/ADB during source validation. Fail closed on deleted safety gates.
"""
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES={
 'inventory':PKG/'TaskerBackupInventory.java',
 'query':PKG/'TaskerPackageQuery.java',
 'wire':PKG/'TaskerPackageQueryWire.java',
 'ledger':PKG/'TaskerPackageFreezeLedger.java',
 'junit':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerBackupInventoryTest.java'),
 'device':Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
 'workflow':Path('.github/workflows/devtool-tasker-ci.yml'),
 'runner':Path('scripts/devtool/amt03_hosted_instrumentation.sh'),
}
GUARDS={
 'inventory':['BackupUtils.getBackupMetadataFromDbNoLockValidate(pkg)',
              'UserHandleHidden.myUserId()', 'pkg.equals(item.packageName)', 'item.userId != user',
              'MAX_ITEMS = 24', 'MAX_LABEL_LENGTH = 80',
              'count > entries.length()', '"%am_backup_status"', '"inventory_unavailable"',
              '"inventory_serialization_failed"', '"%am_backups_json"',
              'return unavailable("inventory_capacity_exceeded")',
              'static Bundle summarize('],
 'query':['vars.putAll(TaskerBackupInventory.query(pkg));',
          'vars.putString("%am_backup_status", "not_queried")',
          'vars.putString("%am_backup_count", "")',
          'vars.putString("%am_backups_json", "{}")',
          'PackageManager.NameNotFoundException missing'],
 'wire':['"%am_backup_status\\nBackup inventory availability"',
         '"%am_backup_count\\nBackups for the current Android user"',
         '"%am_backups_json\\nBounded backup metadata only (no file paths)"'],
 'ledger':['TaskerPackageFreezeLedger(File isolatedFile)', 'new AtomicFile(isolatedFile)',
           'file.readFully()', 'getNoBackupFilesDir()'],
 'junit':['filtersOtherPackagesAndOtherUsers', 'capsPayloadWithoutLosingExactCount',
          'unavailableIsNeverLabeledAsNoBackups', 'emptyInventoryIsARealZero'],
 'device':['testBackupInventoryDeviceScopedReadOnly',
           'testLedgerSurvivesReopenAndRemovesOnlyMatchingSnapshot',
           'testLedgerCorruptionFailsClosedOnDevice',
           'testLedgerNeverOverwritesPriorSnapshotOnDevice',
           'TaskerPackageFreezeLedger(store)', 'testFreezeOwnPackageAlwaysDenied'],
 'workflow':['python3 scripts/devtool/check_amt05a_contract.py --check',
             'default: source',
             "inputs.mode == 'full' || inputs.mode == 'instrumentation'"],
 'runner':["assert int(r.get('passed', 0)) >= 2, r",
           "assert int(r.get('passed', 0)) >= 12, ('AMT05A_INSTRUMENTATION_COVERAGE_INCOMPLETE', r)",
           "assert int(r.get('failed', 0)) == 0", 'AMT03_DEVICE_INSTRUMENTATION_PASS']
}

def inspect(root=ROOT):
    errors=[];data={}
    for k,path in FILES.items():
        p=root/path
        if not p.is_file():errors.append('missing '+str(path));data[k]=''
        else:data[k]=p.read_text(encoding='utf-8')
    for k,markers in GUARDS.items():
        for marker in markers:
            if marker not in data[k]:errors.append(k+' missing '+marker)
    inventory=data['inventory']
    for forbidden in ('new BackupManager(', '.backup(', '.restore(', 'setApplicationEnabledSetting(',
                      'forceStopPackage(', 'Runtime.getRuntime(', 'ProcessBuilder(',
                      'FileOutputStream(', 'delete(', 'relativeDir', 'getItem().getBackupPath('):
        if forbidden in inventory:errors.append('read-only inventory violation: '+forbidden)
    if inventory.count('BackupUtils.getBackupMetadataFromDbNoLockValidate(pkg)')!=1:
        errors.append('inventory may query backup DB only once')
    q=data['query']
    if q.count('vars.putAll(TaskerBackupInventory.query(pkg));')!=1:
        errors.append('inventory must integrate exactly once')
    if q.find('vars.putAll(TaskerBackupInventory.query(pkg));')<q.find('PackageInfo info ='):
        errors.append('inventory may not query before valid installed-package metadata')
    runner=data['runner']
    if "--class io.github.muntashirakon.AppManager.automation.tasker.TaskerJournalInstrumentedTest" not in runner:
        errors.append('emulator runner must target instrumented class')
    if '  device-instrumentation:' not in data['workflow']:
        errors.append('hosted emulator job removed')
    if '  hosted-android:' not in data['workflow']:
        errors.append('hosted Gradle job removed')
    return {'status':'FAIL' if errors else 'PASS','errors':errors}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.parse_args()
    r=inspect();print('AMT05A_BACKUP_INVENTORY_RUNTIME_SOURCE_'+r['status'])
    for error in r['errors']:print('FAIL '+error)
    raise SystemExit(0 if not r['errors'] else 1)
