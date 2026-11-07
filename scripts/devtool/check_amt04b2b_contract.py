#!/usr/bin/env python3
"""AMT-04B2B disable-based freeze + durable exact-state restore source safety gate.
Source-only: neither Gradle, ADB, root, Android device nor network is invoked.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES={
    'wire':PKG/'TaskerPackageManageWire.java',
    'editor':PKG/'TaskerPackageManageEditActivity.java',
    'backend':PKG/'TaskerPackageManageBackend.java',
    'ledger':PKG/'TaskerPackageFreezeLedger.java',
    'junit':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'),
    'ledger_test':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageFreezeLedgerTest.java'),
    'device':Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
    'workflow':Path('.github/workflows/devtool-tasker-ci.yml'),
    'manifest':Path('app/src/main/AndroidManifest.xml'),
}

def inspect(root=ROOT):
    errors=[]
    source={}
    for key,p in FILES.items():
        target=root/p
        if not target.is_file():errors.append('missing '+str(p));source[key]=''
        else:source[key]=target.read_text(encoding='utf-8')
    guards={
        'wire':['FREEZE = "freeze"','UNFREEZE = "unfreeze"',
                '|| FREEZE.equals(operation) || UNFREEZE.equals(operation)',
                '%am_restore_recorded', 'KEY_PACKAGE',
                'KEY_OPERATION = "am.pkgmanage.operation"', 'KEY_TOKEN'],
        'editor':['Freeze (remember original state)', 'Unfreeze (restore saved state)',
                  'TaskerPluginWire.LOCALE_PACKAGE.equals(pkg)',
                  'TaskerPackageManageAccess.forTrustedEditor(this, pkg, selected)',
                  'Cannot disrupt the Tasker/Locale automation host.',
                  'Cannot force-stop Tasker itself.'],
        'backend':['executeFreeze(', 'TaskerPackageFreezeLedger(ctx)',
                   'ledger.record(pkg, saved);', 'ledger.forgetMatching(pkg, saved);',
                   'PackageManagerCompat.getApplicationEnabledSetting(pkg, user)',
                   'PackageManagerCompat.setApplicationEnabledSetting(pkg, saved.previousState, 0, user)',
                   'PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER',
                   'restore_record_missing', 'already_disabled_without_snapshot',
                   'freeze_identity_changed', 'freeze_state_conflict',
                   'restore_state_conflict', 'freeze_state_unverified',
                   'restore_state_unverified', 'managed_freeze_pending',
                   'freeze_ledger_unavailable', 'tasker_target_denied',
                   'SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)',
                   'TaskerPluginWire.LOCALE_PACKAGE.equals(pkg)',
                   'identity.firstInstallTime', 'MUTATION_LOCK',
                   'PackageManagerCompat.forceStopPackage(pkg, user)', '"dispatched"'],
        'ledger':['getNoBackupFilesDir()', 'new AtomicFile(',
                  'file.readFully()', 'file.startWrite()', 'file.finishWrite(output)',
                  'file.failWrite(output)', 'MAX_RECORDS = 128', 'MAX_BYTES = 65536',
                  'SCHEMA = 1', 'validPreviousState(',
                  'if (records.has(pkg)) throw new IOException(',
                  'sameAs(Entry other)', 'getJSONObject("records")'],
        'junit':['freezeCannotTargetTaskerHost', 'validOperation("freeze")',
                 'validOperation("unfreeze")',
                 'TaskerPackageManageAccess.valid(app, PKG, "unfreeze", freeze)'],
        'ledger_test':['recordSurvivesNewInstanceAndRestoresExactDefault',
                       'cannotReplaceExistingRecordOrForgetDifferentIdentity',
                       'disabledUserIsNeverSavedAsOwnedFreeze'],
        'device':['testFreezeOwnPackageAlwaysDenied', 'testForceStopOwnPackageAlwaysDenied'],
        'workflow':['default: source', 'options: [source, build, test, lint, instrumentation, full]',
                    'python3 scripts/devtool/check_amt04b2b_contract.py --check',
                    "github.event_name == 'pull_request' && 'full'",
                    "inputs.mode == 'full' || inputs.mode == 'instrumentation'"],
    }
    for key,checks in guards.items():
        for part in checks:
            if part not in source[key]: errors.append(key+': missing '+part)
    if source['backend'].count('PackageManagerCompat.setApplicationEnabledSetting(pkg, saved.previousState, 0, user)') != 1:
        errors.append('single restore dispatch required')
    # The saved prior state must hit durable storage before the first freeze effect.
    freeze_body=source['backend'].split('private static Bundle executeFreeze(',1)[-1].split(
        'public static Bundle execute(',1)[0]
    freeze_write='PackageManagerCompat.setApplicationEnabledSetting(pkg,\n                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER'
    if 'ledger.record(pkg, saved);' not in freeze_body or freeze_write not in freeze_body or (
            freeze_body.index('ledger.record(pkg, saved);') > freeze_body.index(freeze_write)):
        errors.append('freeze must save state before mutation')
    if 'FreezeUtils.freeze(' in source['backend'] or 'FreezeUtils.unfreeze(' in source['backend']:
        errors.append('freeze must not use default suspend/hide semantics')
    if 'Runtime.getRuntime(' in source['backend'] or 'ProcessBuilder(' in source['backend']:
        errors.append('shell execution forbidden')
    if 'Runtime.getRuntime(' in source['ledger'] or 'ProcessBuilder(' in source['ledger']:
        errors.append('ledger shell invocation forbidden')
    # Prevent accidental publication of extra receivers or manifest insertion that moves
    # 121 pinned upstream Android lint positions.
    try:
        xml=ET.fromstring(source['manifest']);ns='{http://schemas.android.com/apk/res/android}'
        fired=[r for r in xml.findall('.//receiver') if any(a.get(ns+'name')=='com.twofortyfouram.locale.intent.action.FIRE_SETTING' for a in r.findall('intent-filter/action'))]
        if len(fired)!=1 or not (fired[0].get(ns+'name') or '').endswith('TaskerActionReceiver'):
            errors.append('single Locale receiver invariant')
        if 'TaskerPackageFreezeLedger' in source['manifest']:
            errors.append('private ledger must not be exported')
    except ET.ParseError: errors.append('invalid manifest XML')
    return {'status':'FAIL' if errors else 'PASS','errors':errors}

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--check',action='store_true');ap.parse_args()
    result=inspect()
    print('AMT04B2B_FREEZE_RESTORE_SOURCE_'+result['status'])
    for e in result['errors']:print('FAIL '+e)
    raise SystemExit(0 if not result['errors'] else 1)
