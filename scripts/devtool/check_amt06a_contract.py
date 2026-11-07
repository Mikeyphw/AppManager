#!/usr/bin/env python3
"""AMT-06A: fixed-package privileged APK+internal-data backup, no restore, source-only."""
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES={
 'wire':PKG/'TaskerPackageManageWire.java',
 'editor':PKG/'TaskerPackageManageEditActivity.java',
 'backend':PKG/'TaskerPackageManageBackend.java',
 'dispatcher':PKG/'TaskerDataBackupDispatcher.java',
 'journal':PKG/'TaskerApkBackupJournal.java',
 'querywire':PKG/'TaskerPackageQueryWire.java',
 'test':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerDataBackupTest.java'),
 'device':Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
 'legacy':Path('scripts/devtool/check_amt05c_contract.py'),
 'workflow':Path('.github/workflows/devtool-tasker-ci.yml'),
 'runner':Path('scripts/devtool/amt03_hosted_instrumentation.sh'),
 'manifest':Path('app/src/main/AndroidManifest.xml')}
GUARDS={
 'wire':['BACKUP_APK = "backup_apk"','BACKUP_DATA = "backup_data"',
         '|| BACKUP_APK.equals(operation) || BACKUP_DATA.equals(operation)', 'input.size() != 4'],
 'editor':['Backup APK (new named backup; does not overwrite)',
           'Backup APK + internal app data (requires privileged access)',
           'TaskerPackageManageWire.BACKUP_DATA.equals(savedOperation) ? 6',
           'TaskerPackageManageWire.BACKUP_DATA :',
           'check storage encryption settings',
           'TaskerPackageManageAccess.forTrustedEditor(this, pkg, selected)',
           'Cannot force-stop Tasker itself.', 'Cannot disrupt the Tasker/Locale automation host.'],
 'backend':['TaskerPackageManageWire.BACKUP_DATA.equals(operation)',
            'SelfPermissions.canWriteToDataData()',
            'backend_data_permission_required','TaskerDataBackupDispatcher.dispatch(ctx, pkg, user);',
            'FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP',
            'tasker_target_denied','self_target_denied',
            'TaskerApkBackupDispatcher.dispatch(ctx, pkg, user);',
            '&& !TaskerPackageManageWire.BACKUP_DATA.equals(operation)'],
 'dispatcher':['TaskerPackageQueryWire.validPackage(pkg)',
               'BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE',
               'new String[] {name}, null','BatchQueueItem.getBatchOpQueue(BatchOpsManager.OP_BACKUP,',
               'receipts.enqueue(pkg, user, name, "data");',
               'ContextCompat.startForegroundService(',
               'receipts.dispatchUnknown(pkg, user, name, "data");',
               'BatchOpsService.getServiceIntent(',
               '"%am_verified", "false"', '"queued"'],
 'journal':['METADATA_FLAGS = BackupFlags.BACKUP_APK_FILES',
            'DATA_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE',
            'DATA_METADATA_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA',
            'if ("data".equals(type)) item.put("type", type);',
            'if (!type.equals(receiptType(rec))) return;',
            'options.flags.getFlags() != FLAGS && options.flags.getFlags() != DATA_FLAGS',
            'backup.flags == METADATA_FLAGS','backup.flags == DATA_METADATA_FLAGS',
            'request.flags.getFlags() == DATA_FLAGS ? "data" : "apk"',
            'data ? "data" : "apk", state, error',
            'out.putString("%am_backup_request_type", "")',
            'item.put("type", receiptType(rec));',
            'record.put("status", "unknown")',
            'if (!pending(current)) return;'],
 'querywire':['%am_backup_request_type\\nLatest backup request type: apk or data',
              '%am_backup_request_history_json'],
 'test':['oneCurrentUserNativeBackupWithExactFlagsAndNoOverwrite',
         'typedDataReceiptPersistsWithoutChangingApkRecords',
         'operationBoundProofCannotAuthorizeApkOrDisable'],
 'device':['testDataBackupOwnPackageDeniedBeforeService',
           'testDataBackupTaskerHostDeniedBeforeService',
           'testDataBackupReceiptPersistsTypeWithoutTouchingApps'],
 'legacy':["'BACKUP_EXT_DATA', 'BACKUP_ADB_DATA'"],
 'workflow':['python3 scripts/devtool/check_amt06a_contract.py --check',
             'python3 scripts/devtool/check_amt05d_contract.py --check'],
 'runner':["assert int(r.get('passed', 0)) >= 22, ('AMT06A_DATA_BACKUP_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",
           "assert int(r.get('failed', 0)) == 0"]}
def inspect(root=ROOT):
 issues=[]; s={}
 for key,path in FILES.items():
  f=root/path;s[key]=f.read_text(encoding='utf8') if f.is_file() else ''
  if not f.is_file():issues.append('missing '+str(path))
 for key,needles in GUARDS.items():
  for needle in needles:
   if needle not in s[key]:issues.append(key+' missing '+needle)
 for bad in ('OP_RESTORE_BACKUP','OP_DELETE_BACKUP', 'BACKUP_EXT_DATA',
             'BACKUP_ADB_DATA','BACKUP_NO_SIGNATURE_CHECK','BACKUP_CUSTOM_USERS',
             'Runtime.getRuntime(', 'ProcessBuilder(', '.restore(', 'getItem().getBackupPath('):
  if bad in s['dispatcher']:issues.append('unsafe data dispatch '+bad)
 if s['dispatcher'].count('ContextCompat.startForegroundService(')!=1:
  issues.append('single native service dispatch required')
 if s['backend'].count('TaskerDataBackupDispatcher.dispatch(ctx, pkg, user);')!=1:
  issues.append('single data dispatch in backend required')
 if ('receipts.enqueue(pkg, user, name, "data");' in s['dispatcher']
     and 'ContextCompat.startForegroundService(' in s['dispatcher']
     and s['dispatcher'].index('receipts.enqueue(pkg, user, name, "data");') > s['dispatcher'].index('ContextCompat.startForegroundService(')):
  issues.append('ledger must precede service dispatch')
 if 'TaskerDataBackupDispatcher' in s['manifest']:
  issues.append('dispatcher must not be exported')
 if s['manifest'].count('com.twofortyfouram.locale.intent.action.FIRE_SETTING')!=1:
  issues.append('one FIRE_SETTING receiver only')
 if 'SelfPermissions.canWriteToDataData()' in s['backend'] and 'TaskerDataBackupDispatcher.dispatch(ctx, pkg, user);' in s['backend']:
  if s['backend'].index('SelfPermissions.canWriteToDataData()') > s['backend'].index('TaskerDataBackupDispatcher.dispatch(ctx, pkg, user);'):
   issues.append('privilege gate must precede dispatch')
 return {'status':'FAIL' if issues else 'PASS','issues':issues}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.parse_args()
 out=inspect();print('AMT06A_NATIVE_DATA_BACKUP_SOURCE_'+out['status'])
 for x in out['issues']: print('FAIL '+x)
 raise SystemExit(0 if not out['issues'] else 1)
