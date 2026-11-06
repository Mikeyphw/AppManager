#!/usr/bin/env python3
"""AMT-01 static contract: no Gradle, network, Android SDK, or privileged actions.

Android/Java behavior is verified by the JUnit tests in hosted GitHub Actions.
This Python gate checks source structure and is NOT device acceptance.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/core')
TEST = Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/core/AutomationCoreTest.java')
ANDROID = Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/android')
IDENTITY_TEST = Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/core/AutomationIdentityPolicyTest.java')
ANDROID_CLASSES = {
 'AndroidCallerVerifier.java': ('Binder.getCallingUid()', 'getPackagesForUid(', 'getApkContentsSigners()', 'AutomationIdentityPolicy.verifiedCaller'),
 'AndroidGrantRepository.java': ('new AtomicFile(', 'Collections.emptyList()', 'putUserApprovedGrant', 'revokeGrant'),
 'AndroidReplayGuard.java': ('SQLiteDatabase.CONFLICT_IGNORE', 'PRIMARY KEY(uid, pkg, signer, request_id)', 'return false;'),
 'AndroidAutomationIntentParser.java': ('payload.keySet()', 'AutomationRequest.fromFields', 'am.package'),
 'AndroidAutomationGateway.java': ('dispatchFromBinder(', 'identity.verifyBinderCaller(', 'new AndroidGrantRepository(', 'new AndroidReplayGuard(', 'dispatcher.dispatch('),
}
NAMES = (
    'AutomationRequest.java', 'AutomationCaller.java', 'AutomationGrant.java',
    'AutomationIdentityPolicy.java',
    'AutomationPolicy.java', 'AutomationDispatcher.java', 'AutomationResult.java',
)

def inspect(root: Path = ROOT) -> dict:
    errors = []
    source = {}
    for name in NAMES:
        p = root / CORE / name
        if not p.is_file():
            errors.append(f'missing: {p.relative_to(root)}')
            continue
        source[name] = p.read_text(encoding='utf-8')
        if 'package io.github.muntashirakon.AppManager.automation.core;' not in source[name]:
            errors.append(f'wrong package: {name}')
        if re.search(r'^\s*import (?:android\.|androidx\.|io\.github\.)', source[name], re.M):
            errors.append(f'core must be Java-only and isolated: {name}')
    for name, marker in (
        ('AutomationRequest.java', 'unsupported protocol version'),
        ('AutomationRequest.java', 'invalid profile state'),
        ('AutomationCaller.java', 'verifiedUid < 0'),
        ('AutomationGrant.java', 'profile permission must be narrowly scoped'),
        ('AutomationPolicy.java', 'nowMillis >= grant.expiresAtMillis'),
        ('AutomationPolicy.java', 'grant.signingCertSha256'),
            ('AutomationPolicy.java', 'caller.uid != grant.uid'),
        ('AutomationDispatcher.java', 'replayGuard.reserve'),
        ('AutomationDispatcher.java', 'AutomationPolicy.permits'),
        ('AutomationResult.java', 'DUPLICATE_REQUEST'),
    ):
        if name in source and marker not in source[name]:
            errors.append(f'missing security contract: {name} / {marker}')
    for name, markers in ANDROID_CLASSES.items():
        path = root / ANDROID / name
        if not path.is_file():
            errors.append('missing Android bridge: ' + name)
            continue
        code = path.read_text(encoding='utf-8')
        for marker in markers:
            if marker not in code:
                errors.append('missing Android security marker: ' + name + ' / ' + marker)
    # AMT-02: native Tasker plug-in; no in-app approval UI.
    from check_tasker_plugin_contract import inspect as inspect_tasker
    errors.extend(inspect_tasker(root)["errors"])
    if not (root / IDENTITY_TEST).is_file():
        errors.append('missing verified identity JUnit tests')
    else:
        identity_test = (root / IDENTITY_TEST).read_text()
        for marker in ('rejectsMissingWrongAndAmbiguousUidPackageBindings', 'verifiedIdentityUsesExactUidAndDigest'):
            if marker not in identity_test:
                errors.append('missing identity test: ' + marker)
    if 'AutomationIdentityPolicy.java' in source and 'uidPackages.length == 1' not in source['AutomationIdentityPolicy.java']:
        errors.append('shared UID rejection missing')
    if not (root / TEST).is_file():
        errors.append(f'missing: {TEST}')
    else:
        tests = (root / TEST).read_text()
        for marker in ('invalidRequestsFailClosed', 'policyRequiresNarrowMatchingGrant',
                       'executorNeverCalledOnDeniedRequest', 'authorizedRequestExecutesExactlyOnce',
                       'exceptionsAndMismatchedIdsCannotLeak', 'replayGuardRefusesSecondExecution'):
            if marker not in tests:
                errors.append('missing JUnit case: ' + marker)
    manifest = root / 'app/src/main/AndroidManifest.xml'
    if not manifest.is_file():
        errors.append('missing upstream manifest')
    elif 'automation.core' in manifest.read_text():
        errors.append('AMT-01 must not export an automation endpoint')
    return {'status': 'PASS' if not errors else 'FAIL', 'errors': errors,
            'phase': 'AMT-02-NATIVE', 'source_only': True}

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--check', action='store_true')
    p.add_argument('--json', action='store_true')
    args = p.parse_args()
    result = inspect()
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print('AMT02_ANDROID_BRIDGE_' + result['status'])
        for item in result['errors']:
            print('-', item)
    return 0 if result['status'] == 'PASS' else 1

if __name__ == '__main__':
    raise SystemExit(main())
