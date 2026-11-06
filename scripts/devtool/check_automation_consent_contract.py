#!/usr/bin/env python3
"""AMT-02 R2A: source-only checks for authenticated consent/revocation UI.

No Gradle, Android SDK, package installs or GitHub actions are performed.
"""
from __future__ import annotations
import json
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path("app/src/main/java/io/github/muntashirakon/AppManager")


def inspect(root: Path = ROOT) -> dict:
    errors = []
    xml = root / "app/src/main/res/xml/preferences_main.xml"
    strings = root / "app/src/main/res/values/strings_automation.xml"
    ui = root / SOURCE / "automation/ui/AutomationPreferences.java"
    identity = root / SOURCE / "automation/android/AndroidCallerVerifier.java"
    store = root / SOURCE / "automation/android/AndroidGrantRepository.java"
    manifest = root / "app/src/main/AndroidManifest.xml"
    for path in (xml, strings, ui, identity, store, manifest):
        if not path.is_file():
            errors.append("missing: " + str(path.relative_to(root)))
    if errors:
        return {"status": "FAIL", "errors": errors}
    try:
        tree = ET.parse(xml)
        node = tree.getroot().find(".//Preference[@{http://schemas.android.com/apk/res/android}fragment='io.github.muntashirakon.AppManager.automation.ui.AutomationPreferences']")
        if node is None:
            errors.append("consent settings navigation missing")
    except ET.ParseError:
        errors.append("invalid settings preference XML")
    try:
        names = [e.attrib.get('name') for e in ET.parse(strings).getroot().findall('string')]
        if len(set(names)) != len(names) or not {'automation_title','automation_approve','automation_revoke'}.issubset(names):
            errors.append("consent UI strings malformed")
    except ET.ParseError:
        errors.append("invalid consent strings XML")
    content = ui.read_text()
    for marker in ('extends PreferenceFragment',
                   'verifyInstalledForConsent(', 'new AlertDialog.Builder(',
                   'putUserApprovedGrant(', 'revokeGrant(', 'listForUser(',
                   'AutomationRequest.APPLY_PROFILE', 'AutomationRequest.QUERY_CAPABILITIES',
                   'GRANT_TTL_MILLIS', 'expiry <= now', 'current.signingCertSha256.equals(initialCaller.signingCertSha256)',
                   'ProfileManager.getProfiles()', 'new Thread('):
        if marker not in content:
            errors.append('missing explicit consent safeguard: '+marker)
    for marker in ('verifyInstalledForConsent(', 'AutomationIdentityPolicy.uniquelyOwned(',
                   'currentSigningCertificates(packageName)', 'net.dinglisch.android.taskerm'):
        if marker not in identity.read_text():
            errors.append('missing installed caller verification: '+marker)
    for marker in ('listForUser() throws IOException', 'Collections.unmodifiableList(',
                   'new AtomicFile(', 'putUserApprovedGrant(', 'revokeGrant('):
        if marker not in store.read_text():
            errors.append('missing scoped private grant operations: '+marker)
    # No exported automation component is permitted until runtime acceptance.
    if 'android:name=".automation.' in manifest.read_text():
        errors.append('automation component exported/registered before IPC qualification')
    return {'status':'PASS' if not errors else 'FAIL', 'phase':'AMT-02-R2A',
            'source_only':True, 'errors':errors}


def main() -> int:
    parser=argparse.ArgumentParser()
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--json',action='store_true')
    args=parser.parse_args()
    result=inspect()
    if args.json: print(json.dumps(result,indent=2))
    else:
        print('AMT02_R2A_CONSENT_CONTRACT_'+result['status'])
        for item in result['errors']:print('- '+item)
    return 0 if result['status']=='PASS' else 1

if __name__=='__main__': raise SystemExit(main())
