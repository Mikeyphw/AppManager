#!/usr/bin/env python3
"""AMT-04B1: fail-closed source contract for fixed-scope enable/disable actions."""
from __future__ import annotations
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
FILES=tuple(PKG/p for p in ('TaskerPackageManageWire.java','TaskerPackageManageAccess.java',
            'TaskerPackageManageBackend.java','TaskerPackageManageEditActivity.java',
            'TaskerPackageManageReceiver.java','TaskerActionReceiver.java'))+(
            Path('app/src/main/AndroidManifest.xml'),
            Path('.github/workflows/devtool-tasker-ci.yml'),
            Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
            Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'),
            Path('docs/AMT04B1_PACKAGE_MANAGE.md'))

def inspect(root=ROOT):
    errors=[]
    for p in FILES:
        if not (root/p).is_file(): errors.append('missing '+str(p))
    if errors:return {'status':'FAIL','errors':errors}
    d={p.name:(root/p).read_text(encoding='utf-8') for p in FILES}
    guards={
        'TaskerPackageManageWire.java':['KEY_PACKAGE','KEY_OPERATION','KEY_TOKEN','validConfiguration(',
            'validOperation(', 'ENABLE', 'DISABLE', 'input.size() != 4'],
        'TaskerPackageManageAccess.java':['HmacSHA256','getNoBackupFilesDir()',
            'MessageDigest.isEqual(', 'forTrustedEditor(', 'pkg + "\\u0000" + operation',
            'if (!f.getBaseFile().exists()) return false'],
        'TaskerPackageManageBackend.java':['SelfPermissions.checkSelfOrRemotePermission(',
            'Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE',
            'PackageManagerCompat.setApplicationEnabledSetting(',
            'PackageManagerCompat.getApplicationEnabledSetting(',
            'state_unverified', 'self_target_denied', 'system_package_denied', 'backend_permission_required',
            'FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP', 'MUTATION_LOCK',
            'ManifestCompat.permission.FORCE_STOP_PACKAGES', 'tasker_target_denied',
            'PackageManagerCompat.forceStopPackage(', 'dispatched', '%am_verified'],
        'TaskerPackageManageEditActivity.java':['trustedEditor(getCallingPackage())',
            'forTrustedEditor(this, pkg, selected)', 'TaskerPackageManageWire.configuration(',
            'System apps cannot be modified'],
        'TaskerPackageManageReceiver.java':['TaskerPackageManageAccess.valid(',
            'TaskerPackageManageBackend.execute(', 'public static Bundle evaluate(',
            'TaskerPackageManageWire.KEY_PACKAGE'],
        'TaskerActionReceiver.java':['marker.containsKey(TaskerPackageManageWire.KEY_VERSION)',
            'marker.containsKey(TaskerPackageQueryWire.KEY_VERSION)',
            'TaskerPackageManageReceiver.evaluate(app, saved)',
            'TaskerPackageQueryReceiver.evaluate(app, saved)',
            'getSentFromPackage()', 'if (!isOrderedBroadcast()) return;', 'goAsync()',
            'pending.finish()', 'ambiguous_plugin_configuration'],
        'TaskerJournalInstrumentedTest.java':['testManageOwnPackageAlwaysDenied',
            'testManageUnknownOperationAlwaysDenied'],
        'devtool-tasker-ci.yml':['check_amt04b_contract.py --check',
            'app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/**'],
    }
    for file, markers in guards.items():
        for marker in markers:
            if marker not in d[file]: errors.append(file+': missing '+marker)
    for forbidden in ['FreezeUtils.freeze(', 'Runtime.getRuntime()',
                      'ProcessBuilder(', ' su ', 'startForegroundService(']:
        if forbidden in d['TaskerPackageManageReceiver.java'] or forbidden in d['TaskerPackageManageBackend.java']:
            errors.append('forbidden privileged operation: '+forbidden)
    xml=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
    ns='{http://schemas.android.com/apk/res/android}'
    editor=[x for x in xml.findall('.//activity') if (x.get(ns+'name') or '').endswith('TaskerPackageManageEditActivity')]
    if len(editor)!=1 or editor[0].get(ns+'exported')!='true':
        errors.append('missing/duplicate package-manage editor')
    elif not any(x.get(ns+'name')=='com.twofortyfouram.locale.intent.action.EDIT_SETTING' for x in editor[0].findall('.//action')):
        errors.append('package-manage editor Locale discovery mismatch')
    action='com.twofortyfouram.locale.intent.action.FIRE_SETTING'
    hits=[node for node in xml.findall('.//receiver') if any(
        x.get(ns+'name')==action for x in node.findall('intent-filter/action'))]
    if len(hits)!=1 or not (hits[0].get(ns+'name') or '').endswith('TaskerActionReceiver'):
        errors.append('FIRE_SETTING must be exclusively owned by shared TaskerActionReceiver')
    if any((x.get(ns+'name') or '').endswith('TaskerPackageManageReceiver') for x in xml.findall('.//receiver')):
        errors.append('package-manage internal evaluator must not be exported')
    if 'extends BroadcastReceiver' in d['TaskerPackageManageReceiver.java']:
        errors.append('package-manage evaluator must not extend BroadcastReceiver')
    return {'status':'FAIL' if errors else 'PASS','phase':'AMT-04B1','source_only':True,'errors':errors}

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--check',action='store_true');a=ap.parse_args()
    result=inspect();print('AMT04B1_SOURCE_CONTRACT_'+result['status'])
    for item in result['errors']:print('FAIL '+item)
    raise SystemExit(0 if result['status']=='PASS' else 1)
