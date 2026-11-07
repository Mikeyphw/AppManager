#!/usr/bin/env python3
"""AMT-05C fail-closed Tasker APK backup receipts and native service callbacks.
Pure source gate: no Gradle, Android tooling, device, network or real backup I/O.
"""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
SRC = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES = {
    'journal': SRC/'TaskerApkBackupJournal.java',
    'dispatcher': SRC/'TaskerApkBackupDispatcher.java',
    'query': SRC/'TaskerPackageQuery.java',
    'wire': SRC/'TaskerPackageQueryWire.java',
    'service': Path('app/src/main/java/io/github/muntashirakon/AppManager/batchops/BatchOpsService.java'),
    'junit': Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerApkBackupJournalTest.java'),
    'device': Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
    'workflow': Path('.github/workflows/devtool-tasker-ci.yml'),
    'runner': Path('scripts/devtool/amt03_hosted_instrumentation.sh'),
    'manifest': Path('app/src/main/AndroidManifest.xml'),
}
GUARDS = {
    'journal': ['getNoBackupFilesDir()', 'new AtomicFile(isolatedFile)',
                'file.readFully()', 'file.startWrite()', 'file.finishWrite(output)',
                'file.failWrite(output)', 'MAX_BYTES = 65536', 'LIMIT = 64',
                'TaskerPackageQueryWire.validPackage(', 'validName(name)',
                'synchronized (IO_LOCK)', 'records.length() >= LIMIT',
                'if (oldest < 0) throw new IOException(',
                'void enqueue(String pkg, int user, String name) throws IOException',
                'void dispatchUnknown(', 'void started(', 'void finished(',
                '"queued"', '"running"', '"succeeded"', '"failed"',
                '"unverified"', '"unknown"', '"unavailable"',
                'validStatus(', 'if (!pending(current)) return;',
                'BackupUtils.getBackupMetadataFromDbNoLockValidate(request.packageName)',
                'request.userId == backup.userId', 'request.backupName.equals(backup.backupName)',
                'backup.flags == METADATA_FLAGS', 'METADATA_FLAGS = BackupFlags.BACKUP_APK_FILES', 'state = "succeeded";','options.flags.getFlags() != FLAGS',
                'item.getOp() != BatchOpsManager.OP_BACKUP', 'item.getPackages().size() != 1',
                'item.getUsers().size() != 1', 'options.flags.getFlags() != FLAGS',
                'options.override', 'serviceStarted(', 'serviceFinished(',
                '"%am_backup_request_verified"', 'Boolean.toString("succeeded".equals(status))', '"receipt_unavailable"',
                '"none"', '"backup_metadata_unverified"', '"native_backup_failed"'],
    'dispatcher': ['TaskerApkBackupJournal receipts = new TaskerApkBackupJournal(context);',
                   'receipts.enqueue(pkg, user, name);',
                   'ContextCompat.startForegroundService(',
                   'receipts.dispatchUnknown(pkg, user, name);',
                   'backup_receipt_unavailable', 'backup_dispatch_rejected',
                   '"%am_status", "unknown"',
                   'BatchOpsService.getServiceIntent(', '"queued"'],
    'query': ['vars.putAll(TaskerApkBackupJournal.empty());',
              'vars.putAll(TaskerApkBackupJournal.query(context, pkg, UserHandleHidden.myUserId()));',
              'vars.putAll(TaskerBackupInventory.query(pkg));',
              'PackageManager.NameNotFoundException missing'],
    'wire': ['"%am_backup_request_name\\nLatest Tasker backup request name"',
             '"%am_backup_request_status\\nQueued, running, succeeded, failed, unverified or unknown"',
             '"%am_backup_request_verified\\nTrue only after native completion plus matching backup metadata"',
             '"%am_backups_json\\nBounded backup metadata only (no file paths)"'],
    'service': ['TaskerApkBackupJournal.serviceStarted(this, queueItem);',
                'TaskerApkBackupJournal.serviceFinished(this, queueItem,',
                'opResult != null && opResult.isSuccessful()',
                'ACTION_BATCH_OPS_COMPLETED', 'sendResults(Activity.RESULT_OK, item, result);'],
    'junit': ['queueThenNativeResultSurvivesNewProcessObject',
              'failedServiceCannotBecomeVerified',
              'completionRequiresExactPackageUserAndId',
              'duplicateRequestCannotOverwriteHistory',
              'corruptLedgerIsNeverSilentlyReset',
              'unknownDispatchCannotClaimVerifiedOutcome'],
    'device': ['testApkReceiptPersistsAcrossObjectReopenOnDevice',
               'testApkReceiptCorruptionFailsClosedOnDevice',
               'testApkReceiptCannotReclassifyFailureAsSuccessOnDevice',
               'testApkBackupOwnPackageNeverStartsBatchService',
               'testApkBackupHostTargetNeverStartsBatchService'],
    'workflow': ['check_amt05c_contract.py --check',
                 'check_amt05b_contract.py --check',
                 "app/src/main/java/io/github/muntashirakon/AppManager/batchops/BatchOpsService.java'",
                 'default: source',
                 "inputs.mode == 'full' || inputs.mode == 'instrumentation'"],
    'runner': ["assert int(r.get('passed', 0)) >= 14, ('AMT05B_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)",
               "assert int(r.get('passed', 0)) >= 17, ('AMT05C_RECEIPT_DEVICE_COVERAGE_INCOMPLETE', r)",
               "assert int(r.get('failed', 0)) == 0"],
}

def inspect(root=ROOT):
    issues=[]; src={}
    for key,path in FILES.items():
        p=root/path
        if not p.is_file(): issues.append('missing '+str(path)); src[key]=''
        else: src[key]=p.read_text(encoding='utf-8')
    for key,needles in GUARDS.items():
        for needle in needles:
            if needle not in src[key]: issues.append(key+': missing '+needle)
    j=src['journal'];d=src['dispatcher'];s=src['service'];q=src['query']
    for illegal in ('Runtime.getRuntime(', 'ProcessBuilder(',
                    'BackupManager(', '.backup(', '.restore(',
                    'OP_RESTORE_BACKUP', 'OP_DELETE_BACKUP',
                    'BACKUP_EXT_DATA', 'BACKUP_ADB_DATA',
                    'BACKUP_NO_SIGNATURE_CHECK', 'getItem().getBackupPath('):
        if illegal in j or illegal in d:
            issues.append('forbidden write escalation: '+illegal)
    if d.count('ContextCompat.startForegroundService(')!=1:
        issues.append('dispatcher must start exactly one native service')
    if ('receipts.enqueue(pkg, user, name);' not in d
            or 'ContextCompat.startForegroundService(' not in d
            or d.index('receipts.enqueue(pkg, user, name);') > d.index('ContextCompat.startForegroundService(')):
        issues.append('receipt not persisted before service dispatch')
    if s.count('TaskerApkBackupJournal.serviceStarted(this, queueItem);')!=1 or s.count('TaskerApkBackupJournal.serviceFinished(this, queueItem,')!=1:
        issues.append('single native start/completion callback required')
    if q.count('vars.putAll(TaskerApkBackupJournal.query(context, pkg, UserHandleHidden.myUserId()));')!=1:
        issues.append('status queried once and scoped to the current user')
    if 'sendBroadcast(' in j or 'registerReceiver(' in j:
        issues.append('broadcast-derived callback forbidden')
    try:
        manifest=ET.fromstring(src['manifest']); ns='{http://schemas.android.com/apk/res/android}'
        fires=[r for r in manifest.findall('.//receiver') if any(
            a.get(ns+'name')=='com.twofortyfouram.locale.intent.action.FIRE_SETTING'
            for a in r.findall('intent-filter/action'))]
        if len(fires)!=1 or not (fires[0].get(ns+'name') or '').endswith('TaskerActionReceiver'):
            issues.append('one shared Locale FIRE_SETTING receiver required')
        if 'TaskerApkBackupJournal' in src['manifest']:
            issues.append('receipt ledger cannot be exported')
    except ET.ParseError: issues.append('malformed Android manifest')
    return {'status': 'FAIL' if issues else 'PASS', 'issues': issues}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.parse_args()
    result=inspect();print('AMT05C_DURABLE_BACKUP_RECEIPT_'+result['status'])
    for issue in result['issues']: print('FAIL '+issue)
    raise SystemExit(0 if result['status']=='PASS' else 1)
