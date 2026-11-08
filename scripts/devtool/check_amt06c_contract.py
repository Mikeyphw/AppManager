#!/usr/bin/env python3
"""AMT-06C source-only contract: fail closed without Android/Gradle/ADB."""
from pathlib import Path
import argparse
import re

ROOT = Path(__file__).resolve().parents[2]
FIX = ROOT/'scripts/devtool/amt06c_fixture'
PY = ROOT/'scripts/devtool/amt06c_device_acceptance.py'
WORKFLOW = ROOT/'.github/workflows/devtool-tasker-ci.yml'
RUNNER = ROOT/'scripts/devtool/amt03_hosted_instrumentation.sh'
TEST = ROOT/'app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'
SETTINGS = ROOT/'settings.gradle'

def require(condition, description):
    if not condition:
        raise SystemExit('AMT06C_SOURCE_CONTRACT_FAIL: ' + description)


def check():
    files = [FIX/'build.gradle', FIX/'src/main/AndroidManifest.xml', PY, WORKFLOW,
             RUNNER, TEST, SETTINGS, ROOT/'docs/AMT06C_DEVICE_ACCEPTANCE.md']
    for f in files:
        require(f.is_file() and not f.is_symlink(), 'missing source: ' + str(f.relative_to(ROOT)))
    harness = PY.read_text()
    workflow = WORKFLOW.read_text()
    instrumented = TEST.read_text()
    runner = RUNNER.read_text()
    setting = SETTINGS.read_text()
    fixture = (FIX/'build.gradle').read_text()
    manifest = (FIX/'src/main/AndroidManifest.xml').read_text()
    require('dev.mikeyphw.amt06c.fixture' in fixture and 'dev.mikeyphw.amt06c.fixture' in harness,
            'single fixed disposable fixture package')
    require('if (providers.gradleProperty("amt06cFixture").orNull == "true")' in setting,
            'fixture must be opt-in only')
    require('include \':amt06cfixture\'' in setting, 'missing opt-in fixture project')
    require('debuggable true' in fixture and 'debuggable false' in fixture,
            'fixture debug/release distinction')
    require('android:hasCode="false"' in manifest, 'fixture must contain no privileged code')
    require('AMT06C_DEVICE_ACCEPTANCE' in (ROOT/'docs/AMT06C_DEVICE_ACCEPTANCE.md').name,
            'documentation missing')
    require('python3 scripts/devtool/check_amt06c_contract.py --check' in workflow,
            'source gate not wired')
    require('amt06c-fixture:' in workflow and '-Pamt06cFixture=true :amt06cfixture:assembleDebug' in workflow,
            'missing hosted Gradle fixture job')
    require("inputs.mode == 'build' || inputs.mode == 'full'" in workflow,
            'fixture must build only when explicitly requested')
    require("assert int(r.get('passed', 0)) >= 26" in runner,
            'AMT06B emulator threshold removed')
    require("assert int(r.get('passed', 0)) >= 30" in runner and
            "assert int(r.get('executed', 0)) >= 30" in runner,
            'AMT06C actual emulator executed/passed count threshold absent')
    require('int(r.get(\'failed\', 0)) == 0' in runner and 'int(r.get(\'returncode\', 1)) == 0' in runner,
            'real emulator failure and exit gates missing')
    for case in ('testRestoreSecondPendingRequestRejectedBeforeWorker',
                 'testRestoreCorruptReceiptRefusesSecondRequest',
                 'testRestoreUnknownNeverReclassifiesAsSuccess',
                 'testRestoreForeignUserCannotSeePendingReceipt'):
        require(case in instrumented, 'missing emulator negative test ' + case)
    require('run-as' in harness and 'get-current-user' in harness and 'ro.kernel.qemu' in harness,
            'physical device and run-as identity gates')
    require('native_completed' in harness and 'independently_restored_verified' in harness,
            'native vs independent verification separation')
    require('I_ACCEPT_DISPOSABLE_AMT06C_FIXTURE' in harness,
            'explicit fixture write acknowledgement')
    for dangerous in ('clearApplicationUserData(', '"pm", "clear"', '"su"', '"uninstall"', '"broadcast"'):
        require(dangerous not in harness, 'unexpected privileged action in host harness')
    print('AMT06C_SOURCE_CONTRACT_PASS')

if __name__ == '__main__':
    argparse.ArgumentParser().add_argument('--check', action='store_true')
    check()
