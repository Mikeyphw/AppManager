#!/usr/bin/env python3
"""AMT-06B: exact snapshot, explicitly bound, internal-data-only restore, source-only.
Does NOT run Android, Gradle, adb, invoke backup I/O, or modify user data.
"""
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES={
 'wire':PKG/'TaskerPackageManageWire.java',
 'editor':PKG/'TaskerPackageManageEditActivity.java',
 'backend':PKG/'TaskerPackageManageBackend.java',
 'gate':PKG/'TaskerDataRestoreGate.java',
 'dispatch':PKG/'TaskerDataRestoreDispatcher.java',
 'journal':PKG/'TaskerDataRestoreJournal.java',
 'service':Path('app/src/main/java/io/github/muntashirakon/AppManager/batchops/BatchOpsService.java'),
 'query':PKG/'TaskerPackageQuery.java',
 'querywire':PKG/'TaskerPackageQueryWire.java',
 'junit':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerDataRestoreTest.java'),
 'manage_test':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'),
 'device':Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
 'workflow':Path('.github/workflows/devtool-tasker-ci.yml'),
 'runner':Path('scripts/devtool/amt03_hosted_instrumentation.sh'),
 'manifest':Path('app/src/main/AndroidManifest.xml'),
}
GUARDS={
'wire':['RESTORE_PREFIX = "restore_data:"', 'restoreOperation(', 'isDataRestore(',
        'validRestoreName(', 'restoreUid(', 'restoreInstalledAt(', '|| isDataRestore(operation);',
        'input.size() != 4'],
'editor':['"Restore INTERNAL data from fixed Tasker backup (destructive)"',
          'restoreConsent.setChecked(false)', 'restoreConsent.isChecked()',
          'validRestoreName(name)', 'identity.firstInstallTime',
          'TaskerDataRestoreGate.select(this, pkg, UserHandleHidden.myUserId(), selected)',
          'forTrustedEditor(this, pkg, selected)', 'Cannot force-stop Tasker itself.',
          'Cannot disrupt the Tasker/Locale automation host.'],
'backend':['TaskerPackageManageWire.isDataRestore(operation)',
           'TaskerDataRestoreDispatcher.dispatch(ctx, pkg, user, operation)',
           'SelfPermissions.canWriteToDataData()',
           '&& !TaskerPackageManageWire.isDataRestore(operation)',
           'self_target_denied', 'tasker_target_denied', 'system_package_denied'],
'gate':['UserHandleHidden.myUserId()', 'SelfPermissions.canWriteToDataData()',
        'identity.firstInstallTime != TaskerPackageManageWire.restoreInstalledAt(operation)',
        'ai.uid != TaskerPackageManageWire.restoreUid(operation)',
        'candidate.userId != user', '!name.equals(candidate.backupName)',
        'candidate.flags != REQUIRED_FLAGS', 'candidate.versionCode != version',
        'candidate.hasKeyStore', 'candidate.getItem().isFrozen()',
        'ensureSigningAndRestorableData(context, pkg, selected);',
        'BackupItems.Checksum.getCertChecksums(checks)',
        'PackageUtils.getSigningCertChecksums(',
        'if (!Arrays.asList(installed).contains(signer))',
        'metadata.dataDirs.length == 0',
        'item.setCrypto(archiveInfo.getCrypto())',
        'REQUIRED_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA',
        'getBackupMetadataFromDbNoLockValidate(pkg)', 'if (selected != null) throw new IOException('],
'dispatch':['BatchOpsManager.OP_RESTORE_BACKUP',
            'new BatchBackupOptions(BackupFlags.BACKUP_INT_DATA,',
            'TaskerDataRestoreJournal journal = new TaskerDataRestoreJournal(context)',
            'journal.enqueue(backup, requestId);', 'ContextCompat.startForegroundService(',
            'journal.dispatchUnknown(requestId);', '"%am_verified", "false"',
            '"%am_status", "queued"'],
'journal':['new AtomicFile(testFile)', 'getNoBackupFilesDir()',
           'file.readFully()', 'file.startWrite()', 'file.finishWrite(out)',
           'file.failWrite(out)', 'MAX_BYTES = 8192',
           'validRequestId(', 'if (!pending(rec.optString("status"))) return;',
           'authorizeServiceQueue(', 'TaskerDataRestoreGate.select(',
           'options.flags.getFlags() != BackupFlags.BACKUP_INT_DATA',
           'checked.relativeDir.equals(options.relativeDir)',
           '"native_completed"', '"%am_restore_request_verified", "false"',
           'return false;', 'return true;', 'restore_completion_timeout'],
'service':['TaskerDataRestoreJournal.authorizeServiceQueue(this, item)',
           'TaskerDataRestoreJournal.serviceFinished(this, queueItem,',
           'sendResults(Activity.RESULT_CANCELED, item, null);',
           'TaskerApkBackupJournal.serviceFinished(this, queueItem,'],
'query':['vars.putAll(TaskerDataRestoreJournal.empty());',
         'vars.putAll(TaskerDataRestoreJournal.query(context, pkg, UserHandleHidden.myUserId()));',
         'TaskerBackupInventory.query(pkg)', 'TaskerApkBackupJournal.query(context, pkg, UserHandleHidden.myUserId())'],
'querywire':['%am_restore_request_status\\nNone, queued, running, native_completed, failed, unknown or unavailable',
             '%am_restore_request_verified\\nAlways false'],
'junit':['identityAndBackupNameAreImmutableWithinHmacOperation',
         'maliciousOrDynamicRestoreStringsNeverAuthorize',
         'onlyCurrentUserInternalDataIsInNativeQueue',
         'nativeServiceReceiptIsDurableAndNeverClaimsVerifiedRestore',
         'corruptedJournalCannotAuthorizeNewRestore',
         'secondRestoreCannotBypassAnActiveRequest'],
'manage_test':['TaskerPackageManageWire.restoreOperation(',
               'validOperation("restore_data")'],
'device':['testRestoreOwnPackageDeniedBeforeNativeService',
          'testRestoreProtectedAutomationHostDeniedBeforeNativeService',
          'testRestoreReceiptSurvivesObjectReopenWithUnverifiedStatus',
          'testRestoreOperationNeverAcceptsDynamicPackageOrBackup'],
'workflow':['check_amt06a_contract.py --check', 'check_amt06b_contract.py --check',
            "app/src/main/java/io/github/muntashirakon/AppManager/batchops/BatchOpsService.java'"],
'runner':["assert int(r.get('passed', 0)) >= 26, ('AMT06B_RESTORE_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",
          "assert int(r.get('failed', 0)) == 0"],
}

def inspect(root=ROOT):
 errors=[];contents={}
 for key,path in FILES.items():
  f=root/path
  if not f.is_file():errors.append('missing '+str(path));contents[key]=''
  else:contents[key]=f.read_text(encoding='utf-8')
 for key,parts in GUARDS.items():
  for part in parts:
   if part not in contents[key]:errors.append(key+' missing '+part)
 for key in ('dispatch','gate','journal'):
  for forbidden in ('BACKUP_EXT_DATA', 'BACKUP_ADB_DATA', 'BACKUP_NO_SIGNATURE_CHECK',
                    'BACKUP_CUSTOM_USERS', 'Runtime.getRuntime(', 'ProcessBuilder(',
                    'OP_DELETE_BACKUP', 'OP_BACKUP_APK', 'PackageInstaller',
                    'getItem().getBackupPath(', 'sendBroadcast('):
   if forbidden in contents[key]:errors.append('unsafe restore '+key+': '+forbidden)
 if contents['dispatch'].count('ContextCompat.startForegroundService(')!=1:
  errors.append('restore must dispatch exactly once')
 a=contents['dispatch']
 if 'journal.enqueue(backup, requestId);' not in a or 'ContextCompat.startForegroundService(' not in a or (
   a.index('journal.enqueue(backup, requestId);') > a.index('ContextCompat.startForegroundService(')):
  errors.append('persist restore receipt before any native side effect')
 s=contents['service']
 if ('TaskerDataRestoreJournal.authorizeServiceQueue(this, item)' in s
       and 'batchOpsManager.performOp(' in s and
       s.index('TaskerDataRestoreJournal.authorizeServiceQueue(this, item)') > s.index('batchOpsManager.performOp(')):
  errors.append('native restore must be authorized BEFORE backup manager work')
 if contents['journal'].count('putString("%am_restore_request_verified", "false")') != 2:
  errors.append('restore verification must remain false in both query branches')
 if 'result == Activity.RESULT_OK && opResult != null && opResult.isSuccessful()' not in s:
  errors.append('native completion cannot be inferred from service dispatch')
 if 'TaskerDataRestoreJournal' in contents['manifest'] or 'TaskerDataRestoreDispatcher' in contents['manifest']:
  errors.append('restore components must remain private Java types, never exported')
 if contents['manifest'].count('com.twofortyfouram.locale.intent.action.FIRE_SETTING')!=1:
  errors.append('preserve single Locale Tasker receiver')
 return {'status':'FAIL' if errors else 'PASS','errors':errors}
if __name__=='__main__':
 argparse.ArgumentParser().add_argument('--check',action='store_true')
 out=inspect();print('AMT06B_GUARDED_RESTORE_SOURCE_'+out['status'])
 for e in out['errors']:print('FAIL '+e)
 raise SystemExit(0 if not out['errors'] else 1)
