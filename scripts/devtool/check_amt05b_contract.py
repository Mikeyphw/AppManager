#!/usr/bin/env python3
"""AMT-05B: fixed-package, APK-only Tasker backup enqueue contract (source-only)."""
from __future__ import annotations
import argparse
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILE={
 'wire': PKG/'TaskerPackageManageWire.java',
 'editor': PKG/'TaskerPackageManageEditActivity.java',
 'backend': PKG/'TaskerPackageManageBackend.java',
 'dispatcher': PKG/'TaskerApkBackupDispatcher.java',
 'receiver': PKG/'TaskerActionReceiver.java',
 'test': Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerApkBackupRequestTest.java'),
 'manage_test': Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'),
 'device': Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
 'workflow': Path('.github/workflows/devtool-tasker-ci.yml'),
 'runner': Path('scripts/devtool/amt03_hosted_instrumentation.sh'),
 'manifest': Path('app/src/main/AndroidManifest.xml'),
}
GUARDS={
 'wire': ['BACKUP_APK = "backup_apk"','|| BACKUP_APK.equals(operation)',
          '%am_backup_name\\nUnique backup name; completion not confirmed',
          'input.size() != 4'],
 'editor':['Backup APK (new named backup; does not overwrite)',
           'Each request may use storage; completion is not confirmed by Tasker.',
           'TaskerPackageManageWire.BACKUP_APK.equals(savedOperation) ? 5',
           'TaskerPackageManageWire.BACKUP_APK :',
           'Cannot force-stop Tasker itself.',
           'Cannot disrupt the Tasker/Locale automation host.'],
 'backend':['TaskerPackageManageWire.BACKUP_APK.equals(operation)',
            'TaskerApkBackupDispatcher.dispatch(ctx, pkg, user);',
            '"queued"', '"%am_verified"','"%am_backup_name"',
            '"tasker_target_denied"','"self_target_denied"',
            'FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP',
            '"managed_freeze_pending"' if False else 'managed_freeze_pending'],
 'dispatcher':['BatchOpsManager.OP_BACKUP',
               'BatchBackupOptions(BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE,',
               'BatchQueueItem.getBatchOpQueue(',
               'ContextCompat.startForegroundService(',
               'BatchOpsService.getServiceIntent(',
               'UserHandleHidden.myUserId()' if False else 'buildQueue(',
               'UUID.randomUUID()',
               'backup_dispatch_rejected',
               '"%am_backup_name"',
               '"queued"'],
 'receiver':['TaskerPackageManageReceiver.evaluate(app, saved)',
             'marker.containsKey(TaskerPackageManageWire.KEY_VERSION)',
             'if (!isOrderedBroadcast()) return;'],
 'test':['backupQueueTargetsOnePackageCurrentUserOnly',
          'queuedResultDoesNotClaimBackupCompleted',
          'apkBackupProofCannotAuthorizeForceStop',
          'backupOperationRefusesDynamicPackage'],
 'manage_test':['validOperation("backup_apk")',
                'TaskerPackageManageAccess.forTrustedEditor(app, PKG, "backup_apk")'],
 'device':['testApkBackupOwnPackageNeverStartsBatchService',
           'testApkBackupHostTargetNeverStartsBatchService'],
 'workflow':['check_amt05b_contract.py --check',
             'default: source',"inputs.mode == 'full' || inputs.mode == 'instrumentation'"],
 'runner':["assert int(r.get('passed', 0)) >= 12, ('AMT05A_INSTRUMENTATION_COVERAGE_INCOMPLETE', r)",
           "assert int(r.get('passed', 0)) >= 14, ('AMT05B_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",
           "assert int(r.get('failed', 0)) == 0"],
}
def inspect(root=ROOT):
 errors=[];src={}
 for k,p in FILE.items():
  q=root/p
  if not q.is_file():errors.append('missing '+str(p));src[k]=''
  else:src[k]=q.read_text(encoding='utf-8')
 for k,guard in GUARDS.items():
  for marker in guard:
   if marker not in src[k]:errors.append(k+' missing '+marker)
 for forbid in ('BackupManager(','.restore(', 'OP_RESTORE_BACKUP', 'OP_DELETE_BACKUP',
                'BACKUP_INT_DATA', 'BACKUP_EXT_DATA', 'BACKUP_ADB_DATA',
                'BACKUP_NO_SIGNATURE_CHECK', 'Runtime.getRuntime(', 'ProcessBuilder(',
                'getApplicationEnabledSetting(', 'setApplicationEnabledSetting(',
                'forceStopPackage(', 'getItem().getBackupPath('):
  if forbid in src['dispatcher']:errors.append('unsafe backup dispatcher: '+forbid)
 if src['dispatcher'].count('ContextCompat.startForegroundService(')!=1:errors.append('more than one dispatch call')
 if src['backend'].count('TaskerApkBackupDispatcher.dispatch(ctx, pkg, user);')!=1:errors.append('backup may dispatch multiple times')
 if 'getSentFromPackage()' not in src['receiver']:errors.append('receiver host validation removed')
 if '.automation.tasker.TaskerApkBackupDispatcher' in src['manifest']:
  errors.append('APK dispatcher must not be exported in manifest')
 if src['manifest'].count('com.twofortyfouram.locale.intent.action.FIRE_SETTING')!=1:
  errors.append('single Locale FIRE_SETTING receiver invariant')
 return {'status':'FAIL' if errors else 'PASS','errors':errors}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.parse_args()
 r=inspect();print('AMT05B_APK_BACKUP_SOURCE_'+r['status']);[print('FAIL '+v) for v in r['errors']]
 raise SystemExit(0 if not r['errors'] else 1)
