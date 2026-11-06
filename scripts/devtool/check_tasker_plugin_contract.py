#!/usr/bin/env python3
"""AMT-02 native Tasker protocol source gate. No Gradle/Android actions."""
from __future__ import annotations
import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PKG = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
MANIFEST = Path('app/src/main/AndroidManifest.xml')
PREFERENCES = Path('app/src/main/res/xml/preferences_main.xml')
STRINGS = Path('app/src/main/res/values/strings_automation.xml')
SERVICE = Path('app/src/main/java/io/github/muntashirakon/AppManager/profiles/ProfileApplierService.java')
DOC = Path('docs/TASKER_NATIVE.md')
NAMES = ('TaskerAccess.java', 'TaskerPluginWire.java', 'TaskerActionEditActivity.java',
         'TaskerActionReceiver.java', 'TaskerProfileEvents.java', 'TaskerEventEditActivity.java',
         'TaskerStateEditActivity.java', 'TaskerConditionEditActivity.java',
         'TaskerConditionReceiver.java', 'TaskerCompletion.java')
FILES = tuple(PKG / name for name in NAMES) + (MANIFEST, PREFERENCES, STRINGS, SERVICE, DOC)
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'


def inspect(root: Path = ROOT) -> dict:
    errors: list[str] = []
    for rel in FILES:
        if not (root / rel).is_file():
            errors.append('missing: '+str(rel))
    if errors:
        return {'status':'FAIL','errors':errors}
    try:
        m = ET.parse(root / MANIFEST).getroot()
        ET.parse(root / PREFERENCES)
        strings = ET.parse(root / STRINGS).getroot()
    except ET.ParseError as exc:
        return {'status':'FAIL','errors':['invalid manifest/preferences/strings XML: '+str(exc)]}
    required = (
        ('activity', 'TaskerActionEditActivity', 'com.twofortyfouram.locale.intent.action.EDIT_SETTING'),
        ('receiver', 'TaskerActionReceiver', 'com.twofortyfouram.locale.intent.action.FIRE_SETTING'),
        ('activity', 'TaskerEventEditActivity', 'net.dinglisch.android.tasker.ACTION_EDIT_EVENT'),
        ('activity', 'TaskerStateEditActivity', 'com.twofortyfouram.locale.intent.action.EDIT_CONDITION'),
        ('receiver', 'TaskerConditionReceiver', 'com.twofortyfouram.locale.intent.action.QUERY_CONDITION'),
    )
    for tag, cls, action in required:
        nodes = [node for node in m.findall('.//'+tag)
                 if (node.get(ANDROID_NS+'name') or '').endswith('.automation.tasker.'+cls)]
        if len(nodes) != 1:
            errors.append('missing/duplicate Tasker component: '+cls)
            continue
        node = nodes[0]
        if node.get(ANDROID_NS+'exported') != 'true':
            errors.append('native Tasker component not exported: '+cls)
        found = [a.get(ANDROID_NS+'name') for a in node.findall('intent-filter/action')]
        if found != [action]:
            errors.append('wrong native Tasker discovery action: '+cls)
    for action in ('com.twofortyfouram.locale.intent.action.FIRE_SETTING',
                   'com.twofortyfouram.locale.intent.action.QUERY_CONDITION'):
        hits=[a for a in m.findall('.//receiver/intent-filter/action') if a.get(ANDROID_NS+'name') == action]
        if len(hits) != 1: errors.append('Tasker action/condition receiver collision: '+action)
    pref = (root / PREFERENCES).read_text()
    if 'automation_tasker_prefs' in pref or 'AutomationPreferences' in pref:
        errors.append('obsolete authorization settings UI not removed')
    if (root / PKG.parent / 'ui/AutomationPreferences.java').exists():
        errors.append('obsolete authorization fragment not removed')
    if (root / 'scripts/devtool/check_automation_consent_contract.py').exists():
        errors.append('obsolete consent gate not removed')
    if (root / 'scripts/devtool/test_automation_consent_contract.py').exists():
        errors.append('obsolete consent test not removed')
    names=[e.get('name') for e in strings.findall('string')]
    if len(names)!=len(set(names)) or not {'tasker_action_plugin','tasker_event_plugin','tasker_state_plugin'}.issubset(names):
        errors.append('Tasker labels missing/duplicate')
    sources = {name:(root / PKG / name).read_text() for name in NAMES}
    signatures = {
        'TaskerAccess.java':('getNoBackupFilesDir(', 'new SecureRandom()', 'MessageDigest.isEqual(', 'tokenForTrustedEditor(', 'valid('),
        'TaskerPluginWire.java':('ACTION_EDIT_EVENT', 'EDIT_ACTION', 'FIRE_ACTION', 'QUERY_CONDITION',
                                'validConfiguration(', '%am_status', 'TaskerPluginWire', 'getInt(KEY_VERSION',
                                'KEY_WAIT', 'RESULT_PENDING', 'EXTRA_TIMEOUT', 'CAPABILITY_SYNCHRONOUS'),
        'TaskerActionEditActivity.java':('getCallingPackage()', 'trustedEditor(', 'tokenForTrustedEditor(',
                                         'ProfileManager.getProfiles()', 'TaskerPluginWire.editResult(', 'isFinishing()',
                                         'waitForCompletion', 'canWait', 'KEY_WAIT'),
        'TaskerActionReceiver.java':('getSentFromPackage()', 'TaskerAccess.valid(', 'goAsync()',
                                     'ProfileQueueItem.fromProfiledApplierInfo(', 'ContextCompat.startForegroundService(',
                                     'TaskerProfileEvents.queued(', 'TaskerProfileEvents.dispatchFailed(',
                                     '"queued"', '"failed"', 'RESULT_PENDING', 'TaskerCompletion.acceptedCallback('),
        'TaskerProfileEvents.java':('REQUEST_QUERY', 'EXTRA_PASS_THROUGH', 'EXTRA_MESSAGE_ID',
                                    'EVENT_TTL', 'started(', 'finished(', 'matchingEvent('),
        'TaskerConditionReceiver.java':('QUERY_CONDITION', 'TaskerAccess.valid(',
                                        'matchingEvent(', 'stateMatches(', 'CONDITION_SATISFIED',
                                        'CONDITION_UNSATISFIED', 'isOrderedBroadcast()'),
        'TaskerCompletion.java':('acceptedCallback(', 'validUri(', 'Intent.parseUri(',
                                 'TaskerPluginWire.TASKER_PACKAGE', 'EXTRA_CALL_SERVICE_PACKAGE',
                                 'EXTRA_RESULT_CODE', 'EXTRA_VARIABLES', 'startForegroundService(',
                                 'sendBroadcast(', 'return false;'),
        'TaskerConditionEditActivity.java':('getCallingPackage()', 'TaskerAccess.tokenForTrustedEditor(',
                                           'TaskerPluginWire.EDIT_EVENT', 'TaskerPluginWire.EDIT_STATE'),
    }
    for name, markers in signatures.items():
        for marker in markers:
            if marker not in sources[name]: errors.append('native Tasker safety contract missing: '+name+' / '+marker)
    service = (root / SERVICE).read_text()
    for marker in ('TaskerProfileEvents.started(this, item, taskerRequestId)',
                   'TaskerProfileEvents.finished(this, item, taskerRequestId, taskerSucceeded)',
                   'TaskerCompletion.finish(this, intent, taskerRequestId, item, taskerSucceeded)',
                   'ProfileQueueItem item = getQueueItem(intent)',
                   'profileManager.applyProfile(item.getState(), mProgressHandler)'):
        if marker not in service: errors.append('profile-service observation hook missing: '+marker)
    return {'status':'PASS' if not errors else 'FAIL', 'errors':errors, 'phase':'AMT-02-R2C', 'source_only':True}


def main() -> int:
    p=argparse.ArgumentParser()
    p.add_argument('--check',action='store_true')
    p.add_argument('--json',action='store_true')
    args=p.parse_args()
    result=inspect()
    if args.json: print(json.dumps(result,indent=2))
    else:
        print('AMT02_NATIVE_TASKER_CONTRACT_'+result['status'])
        for error in result['errors']:print('- '+error)
    return 0 if result['status']=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
