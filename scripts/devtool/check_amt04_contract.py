#!/usr/bin/env python3
"""AMT-04A source-only contracts; never launches Android, Gradle or network."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
NAMES = ('TaskerPackageQueryWire.java', 'TaskerPackageQueryAccess.java',
         'TaskerPackageQuery.java', 'TaskerPackageQueryEditActivity.java',
         'TaskerPackageQueryReceiver.java')
REQUIRED = tuple(BASE / s for s in NAMES) + (
    Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageQueryTest.java'),
    Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
    Path('app/src/main/AndroidManifest.xml'),
    Path('app/src/main/res/values/strings_automation.xml'),
    BASE / 'TaskerActionReceiver.java',
    Path('.github/workflows/devtool-tasker-ci.yml'),
    Path('docs/AMT04A_PACKAGE_QUERY.md'),
)


def inspect(root: Path = ROOT) -> dict:
    errors = []
    def require(condition: bool, label: str) -> None:
        if not condition: errors.append(label)
    for rel in REQUIRED:
        require((root / rel).is_file(), 'missing: ' + str(rel))
    if errors: return {'status': 'FAIL', 'errors': errors}
    sources = {p.name: (root / p).read_text(encoding='utf8') for p in REQUIRED}
    wire = sources['TaskerPackageQueryWire.java']
    access = sources['TaskerPackageQueryAccess.java']
    query = sources['TaskerPackageQuery.java']
    editor = sources['TaskerPackageQueryEditActivity.java']
    receiver = sources['TaskerPackageQueryReceiver.java']
    old_receiver = sources['TaskerActionReceiver.java']
    test = sources['TaskerJournalInstrumentedTest.java']
    workflow = sources['devtool-tasker-ci.yml']
    for marker in ('KEY_VERSION', 'KEY_TOKEN', 'KEY_PACKAGE', 'KEY_DYNAMIC',
                   'validConfiguration(', 'validPackage(', 'validVariable(',
                   'EXTRA_VARIABLE_REPLACE_KEYS', 'EXTRA_RELEVANT'):
        require(marker in wire, 'query wire missing '+marker)
    for marker in ('getNoBackupFilesDir', 'SecureRandom', 'MessageDigest.isEqual',
                   'tasker_package_query_capability_v1'):
        require(marker in access, 'query capability missing '+marker)
    for marker in ('PackageManager', 'getPackageInfo(', 'NameNotFoundException',
                   'unresolved_tasker_variable', '%am_success', '%am_json',
                   'getInstallSourceInfo(', 'package_query_denied',
                   'Boolean.toString(installed)'):
        require(marker in query, 'read-only query missing '+marker)
    for marker in ('getCallingPackage()', 'trustedEditor(', 'tokenForTrustedEditor(',
                   'validPackage(', 'validVariable(', 'CAPABILITY_VARIABLE_REPLACEMENT'):
        require(marker in editor, 'trusted editor missing '+marker)
    for marker in ('validConfiguration(', 'TaskerPackageQueryAccess.valid(',
                   'TaskerPackageQuery.query(', 'public static Bundle evaluate('):
        require(marker in receiver, 'internal package-query evaluator missing '+marker)
    for marker in ('marker.containsKey(TaskerPackageQueryWire.KEY_VERSION)',
                   'TaskerPackageQueryReceiver.evaluate(app, saved)',
                   'TaskerPluginWire.EXTRA_VARIABLES', 'getSentFromPackage()',
                   'isOrderedBroadcast()', 'goAsync()', 'pending.finish()'):
        require(marker in old_receiver, 'shared receiver query routing missing '+marker)
    require('extends BroadcastReceiver' not in receiver,
            'package-query evaluator must not be an independently registered receiver')
    require('testQueryOwnPackage' in test and 'testQueryUnresolvedVariable' in test,
            'hosted instrumentation must execute read-only package query cases')
    require('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/**' in workflow,
            'instrumentation-only pushes must trigger source CI')
    require('check_amt04_contract.py --check' in workflow,
            'AMT-04 source gate missing from hosted CI')
    require('devtool.android_instrumentation' not in receiver and
            'startForegroundService' not in receiver and 'su ' not in receiver and
            'exec(' not in receiver, 'package query evaluator must not launch privileged operations')

    manifest_path = root / 'app/src/main/AndroidManifest.xml'
    try:
        xml = ET.parse(manifest_path).getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        found = [n for n in xml.findall('.//activity')
                 if (n.get(ns+'name') or '').endswith('TaskerPackageQueryEditActivity')]
        require(len(found) == 1, 'missing/duplicate TaskerPackageQueryEditActivity')
        if len(found) == 1:
            require(found[0].get(ns+'exported') == 'true', 'package-query editor must be exported')
            require([i.get(ns+'name') for i in found[0].findall('.//action')] ==
                    ['com.twofortyfouram.locale.intent.action.EDIT_SETTING'],
                    'package-query editor discovery mismatch')
        action = 'com.twofortyfouram.locale.intent.action.FIRE_SETTING'
        receivers = [n for n in xml.findall('.//receiver') if any(
            x.get(ns+'name') == action for x in n.findall('intent-filter/action'))]
        require(len(receivers) == 1 and (receivers[0].get(ns+'name') or '').endswith('TaskerActionReceiver'),
                'FIRE_SETTING must be delivered only to the shared TaskerActionReceiver')
        require(not any((n.get(ns+'name') or '').endswith('TaskerPackageQueryReceiver')
                        for n in xml.findall('.//receiver')),
                'package-query evaluator must not be registered as a receiver')
        all_permissions = [n.get(ns+'name') for n in xml.findall('uses-permission')]
        require('android.permission.QUERY_ALL_PACKAGES' in all_permissions,
                'App Manager package visibility declaration missing')
    except ET.ParseError as e: errors.append('manifest XML '+str(e))
    return {'phase': 'AMT-04A', 'status': 'PASS' if not errors else 'FAIL',
            'source_only': True, 'errors': errors}


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--check', action='store_true')
    p.add_argument('--json', action='store_true')
    args = p.parse_args()
    result = inspect()
    if args.json: print(json.dumps(result, indent=2))
    else:
        print('AMT04A_SOURCE_CONTRACT_'+result['status'])
        for error in result['errors']: print('- '+error)
    return 0 if result['status'] == 'PASS' else 1

if __name__ == '__main__': raise SystemExit(main())
