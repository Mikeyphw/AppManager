#!/usr/bin/env python3
"""AMT-03 source-only contract checks; suitable for Termux and hosted CI."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PKG = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
SERVICE = Path('app/src/main/java/io/github/muntashirakon/AppManager/profiles/ProfileApplierService.java')
MANIFEST = Path('app/src/main/AndroidManifest.xml')
WORKFLOW = Path('.github/workflows/devtool-tasker-ci.yml')
FILES = (
    PKG / 'TaskerExecutionJournal.java', PKG / 'TaskerExecutionGate.java',
    PKG / 'TaskerWorkScheduler.java', PKG / 'TaskerDispatchJobService.java',
    PKG / 'TaskerActionReceiver.java', PKG / 'TaskerActionEditActivity.java',
    PKG / 'TaskerPluginWire.java', PKG / 'TaskerCompletion.java',
    PKG / 'TaskerProfileEvents.java', SERVICE, MANIFEST, WORKFLOW,
    Path('docs/TASKER_AMT03.md'),
    Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerExecutionJournalTest.java'),
    Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
)
CHECKS = {
    'TaskerExecutionJournal.java': ('SQLiteOpenHelper', 'id TEXT PRIMARY KEY', 'CONFLICT_IGNORE',
        'status IN (\'queued\',\'scheduled\')', 'status=\'running\'',
        'process_died_outcome_unknown', 'recoveredInterrupted(', 'MAX_TERMINAL', 'terminal_seq', 'nextTerminalSequence()', 'public synchronized Record cancelQueued(',
        'public synchronized boolean claim(', 'public synchronized boolean complete(', 'public synchronized Bundle recent('),
    'TaskerDispatchJobService.java': ('extends JobService', 'TaskerExecutionGate.LOCK',
        'journal.claim(id)', 'journal.complete(id, success, error)', 'ProfileManager(',
        'TaskerCompletion.finishWithStatus(', 'jobFinished(params, false)', 'return false;'),
    'TaskerWorkScheduler.java': ('JobScheduler', 'getAllPendingJobs()', 'JobScheduler.RESULT_SUCCESS',
        'setPersisted(false)', 'TaskerDispatchJobService.class', 'js.cancel('),
    'TaskerActionReceiver.java': ('TaskerExecutionJournal.get(context)',
        'TaskerPluginWire.MODE_CANCEL', 'TaskerPluginWire.MODE_HISTORY',
        'journal.enqueue(', 'journal.cancelQueued(', 'TaskerWorkScheduler.schedule(',
        'journal.failBeforeStart(', 'ContextCompat.startForegroundService(',
        'foregroundRejected instanceof android.app.ForegroundServiceStartNotAllowedException'),
    'TaskerPluginWire.java': ('MODE_CANCEL', 'MODE_HISTORY', 'KEY_REQUEST',
        'validRequestId(', 'validConditionConfiguration(', 'EXTRA_VARIABLE_REPLACE_KEYS, KEY_REQUEST',
        'output.putExtra(EXTRA_VARIABLE_REPLACE_KEYS, KEY_PROFILE)'),
    'TaskerActionEditActivity.java': ('Recent execution history', 'Cancel queued request',
        'TaskerPluginWire.KEY_REQUEST', 'TaskerPluginWire.MODE_CANCEL', 'TaskerPluginWire.MODE_HISTORY'),
    'TaskerCompletion.java': ('finishWithStatus(', 'validUri(', 'TaskerPluginWire.TASKER_PACKAGE'),
    'TaskerProfileEvents.java': ('public static void cancelled(', 'recoveredInterrupted(', 'requestStateUpdate(context)',
        'synchronized (journal)', '!"queued".equals(current.status)',
        'if (!pref.getBoolean(p + "running", false))',
        'if (!pref.getBoolean("running", false))'),
    'ProfileApplierService.java': ('TaskerExecutionGate.LOCK', 'journal.claim(request)',
        'journal.complete(taskerRequestId', 'TaskerProfileEvents.finished(this, item, taskerRequestId, taskerSucceeded)',
        'TaskerCompletion.finish(this, intent, taskerRequestId, item, taskerSucceeded)'),
    'TaskerExecutionJournalTest.java': ('testCancelledRequestNeverStarts', 'testClaimExactlyOnce',
        'testFinishedCannotBeCancelled', 'testHistoryBoundedResult'),
    'TaskerJournalInstrumentedTest.java': ('testCancelQueuedRequest', 'testClaimIsExclusive'),
    'devtool-tasker-ci.yml': ('device-instrumentation:', 'android-emulator-runner@v2',
        'bash scripts/devtool/amt03_hosted_instrumentation.sh'),
}


def inspect(root: Path = ROOT) -> dict:
    errors = []
    for rel in FILES:
        if not (root / rel).is_file(): errors.append('missing: ' + str(rel))
    for name, markers in CHECKS.items():
        rel = next((p for p in FILES if p.name == name), None)
        if rel is None or not (root / rel).exists(): continue
        source = (root / rel).read_text(encoding='utf-8')
        for marker in markers:
            if marker not in source: errors.append(f'R3 missing: {name}: {marker}')
    # Cross-file behavior invariants: marker presence alone is insufficient.
    receiver = root / PKG / 'TaskerActionReceiver.java'
    if receiver.is_file():
        src = receiver.read_text(encoding='utf-8')
        positions = [src.find('if (!journal.enqueue(id, profileId, state, callbackUri))'),
                     src.find('if (config.getBoolean(TaskerPluginWire.KEY_DYNAMIC, false)'),
                     src.find('BaseProfile.fromPath(')]
        if -1 in positions or positions != sorted(positions):
            errors.append('Every authenticated profile attempt must be journaled before validation or profile I/O')
    journal = root / PKG / 'TaskerExecutionJournal.java'
    if journal.is_file():
        src = journal.read_text(encoding='utf-8')
        if 'ORDER BY terminal_seq DESC' not in src or \
                'Record cancelled = find(id);\n        prune();\n        return cancelled;' not in src:
            errors.append('Recently cancelled requests must be retained and reported reliably')
    events = root / PKG / 'TaskerProfileEvents.java'
    if events.is_file() and 'if (!pref.getBoolean("running", false))' not in events.read_text(encoding='utf-8'):
        errors.append('Cancellation must preserve unrelated active profile state')
    manifest = root / MANIFEST
    if manifest.exists():
        try:
            m = ET.parse(manifest).getroot()
            ns = '{http://schemas.android.com/apk/res/android}'
            jobs = [n for n in m.findall('.//service')
                    if (n.get(ns+'name') or '').endswith('.automation.tasker.TaskerDispatchJobService')]
            if len(jobs) != 1 or jobs[0].get(ns+'exported') != 'true' or \
                    jobs[0].get(ns+'permission') != 'android.permission.BIND_JOB_SERVICE':
                errors.append('JobService must be exported for OS binding and signature-permission guarded')
        except ET.ParseError as e: errors.append('manifest parse: ' + str(e))
    return {'phase':'AMT-03-R3A-R3D','status':'PASS' if not errors else 'FAIL',
            'source_only':True,'errors':errors}


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--check', action='store_true')
    p.add_argument('--json', action='store_true')
    args = p.parse_args()
    result = inspect()
    if args.json: print(json.dumps(result, indent=2))
    else:
        print('AMT03_SOURCE_CONTRACT_' + result['status'])
        for e in result['errors']: print('- ' + e)
    return 0 if not result['errors'] else 1

if __name__ == '__main__': raise SystemExit(main())
