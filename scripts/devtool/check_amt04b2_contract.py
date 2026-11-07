#!/usr/bin/env python3
"""AMT-04B2A/CI01 source qualification. Only static source checks, no Gradle."""
import argparse, pathlib, re, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[2]
PKG=pathlib.Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')
def inspect(root=ROOT):
    errors=[]
    def get(path):
        p=root/path
        if not p.is_file(): errors.append('missing '+str(path));return ''
        return p.read_text(encoding='utf-8')
    wire=get(PKG/'TaskerPackageManageWire.java')
    editor=get(PKG/'TaskerPackageManageEditActivity.java')
    backend=get(PKG/'TaskerPackageManageBackend.java')
    access=get(PKG/'TaskerPackageManageAccess.java')
    workflow=get(pathlib.Path('.github/workflows/devtool-tasker-ci.yml'))
    manifest=get(pathlib.Path('app/src/main/AndroidManifest.xml'))
    unit=get(pathlib.Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageManageTest.java'))
    instrument=get(pathlib.Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'))
    guards=[(wire,'FORCE_STOP = "force_stop"'),(wire,'FORCE_STOP.equals(operation)'),
      (editor,'Force-stop application (irreversible)'),(editor,'Cannot force-stop Tasker itself.'),
      (backend,'ManifestCompat.permission.FORCE_STOP_PACKAGES'),
      (backend,'SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)'),
      (backend,'PackageManagerCompat.forceStopPackage(pkg, user)'),
      (backend,'FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP'),
      (backend,'tasker_target_denied'),(backend,'self_target_denied'),
      (backend,'"dispatched"'),(backend,'"%am_verified"'),
      (access,'MessageDigest.isEqual('),
      (unit,'forceStopCannotTargetTaskerHost'),
      (instrument,'testForceStopOwnPackageAlwaysDenied'),
      (workflow,'default: source'),
      (workflow,'python3 scripts/devtool/check_amt04b2_contract.py --check'),
      (workflow,'options: [source, build, test, lint, instrumentation, full]'),
      (workflow,"inputs.mode != 'source' && inputs.mode != 'instrumentation'"),
      (workflow,"inputs.mode == 'full' || inputs.mode == 'instrumentation'"),
      (workflow,"github.event_name == 'pull_request' && 'full'")]
    for src,guard in guards:
        if guard not in src: errors.append('missing '+guard)
    if 'FreezeUtils.freeze(' in backend or 'Runtime.getRuntime(' in backend or 'ProcessBuilder(' in backend:
        errors.append('unscoped mutation forbidden')
    if backend.count('PackageManagerCompat.forceStopPackage(pkg, user)')!=1:
        errors.append('force-stop must have one dispatch site')
    if "inputs.mode == 'full'" not in workflow:
        errors.append('full qualification removed')
    try:
        xml=ET.fromstring(manifest)
        ns='{http://schemas.android.com/apk/res/android}'
        fires=[r for r in xml.findall('.//receiver') if any(a.get(ns+'name')=='com.twofortyfouram.locale.intent.action.FIRE_SETTING' for a in r.findall('intent-filter/action'))]
        if len(fires)!=1 or not (fires[0].get(ns+'name') or '').endswith('TaskerActionReceiver'):
            errors.append('single Locale receiver invariant')
    except ET.ParseError:errors.append('bad manifest')
    return {'status':'FAIL' if errors else 'PASS','errors':errors}
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--check',action='store_true');a.parse_args()
    x=inspect();print('AMT04B2A_CI01_SOURCE_CONTRACT_'+x['status'])
    for e in x['errors']:print('FAIL '+e)
    raise SystemExit(0 if not x['errors'] else 1)
