#!/usr/bin/env bash
# AMUI-01 hosted-only real APK install, replacement and uninstall on disposable fixture.
set -Eeuo pipefail
export PYTHONDONTWRITEBYTECODE=1
: "${RUNNER_TEMP:?GitHub hosted runner required}"
: "${JAVA_HOME:?Hosted Java 21 required}"
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd -- "$repo"
fixture='dev.mikeyphw.amt06c.fixture'
original="$(mktemp "$RUNNER_TEMP/amui01-devtool.XXXXXXXX.toml")"
cp -- .devtool.toml "$original"
cleanup() {
  adb uninstall "$fixture" >/dev/null 2>&1 || true
  cp -- "$original" .devtool.toml
  rm -f -- "$original"
}
trap cleanup EXIT

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ -n "${AMUI01_EXPECT_API:-}" && "$api" != "$AMUI01_EXPECT_API" ]]; then
  echo "AMUI01_WRONG_API: wanted $AMUI01_EXPECT_API got $api" >&2
  exit 1
fi
[[ "$api" == '35' || "$api" == '36' ]] || {
  echo "AMUI01_UNSUPPORTED_EMULATOR_API: $api" >&2; exit 1;
}
python3 -B scripts/devtool/amt03_hosted_config.py .devtool.toml --java-home "$JAVA_HOME"
# Gradle is executed only on GitHub Actions; no local/Termux Gradle work.
./gradlew --no-daemon -Pamt06cFixture=true :amt06cfixture:assembleDebug
fixture_apk="$(find scripts/devtool/amt06c_fixture/build/outputs/apk/debug -name '*.apk' -type f | head -1)"
[[ -f "$fixture_apk" ]] || { echo 'AMUI01_FIXTURE_APK_MISSING' >&2; exit 1; }
sha256sum "$fixture_apk" > "$RUNNER_TEMP/amui01-fixture.sha256"
installed="$RUNNER_TEMP/amui01-installed.json"
removed="$RUNNER_TEMP/amui01-removed.json"
install_log="$RUNNER_TEMP/amui01-installed.log"
remove_log="$RUNNER_TEMP/amui01-removed.log"
rm -f -- "$installed" "$removed" "$install_log" "$remove_log"

adb install --user 0 "$fixture_apk"
adb shell pm list packages --user 0 "$fixture" | tr -d '\r' | grep -Fx "package:$fixture"
# Replacement/update event (same signed debug APK); no valuable app state is touched.
adb install --user 0 -r "$fixture_apk"
set +e
python3 -B -m devtool.android_instrumentation "$PWD" --target app \
  --class io.github.muntashirakon.AppManager.main.Amui01InstalledInstrumentedTest \
  --report "$installed" 2>&1 | tee "$install_log"
installed_rc="${PIPESTATUS[0]}"
set -e
python3 -B - "$installed" "$installed_rc" 5 <<'PY'
import json,sys,pathlib
file,rc,minimum=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
p=pathlib.Path(file)
if not p.is_file(): raise SystemExit('AMUI01_MISSING_INSTALLED_RECEIPT')
r=json.loads(p.read_text())
assert rc==0 and r.get('returncode')==0 and r.get('status') in ('passed','success'), r
assert int(r.get('executed',0))>=minimum and int(r.get('passed',0))>=minimum, r
assert int(r.get('failed',1))==0, r
print('AMUI01_INSTALLED_DEVICE_PASS', r.get('executed'))
PY

adb uninstall "$fixture"
if adb shell pm list packages --user 0 "$fixture" | tr -d '\r' | grep -Fxq "package:$fixture"; then
  echo 'AMUI01_UNINSTALL_DID_NOT_COMPLETE' >&2
  exit 1
fi
set +e
python3 -B -m devtool.android_instrumentation "$PWD" --target app \
  --class io.github.muntashirakon.AppManager.main.Amui01RemovedInstrumentedTest \
  --report "$removed" 2>&1 | tee "$remove_log"
removed_rc="${PIPESTATUS[0]}"
set -e
python3 -B - "$removed" "$removed_rc" 1 <<'PY'
import json,sys,pathlib
file,rc,minimum=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
p=pathlib.Path(file)
if not p.is_file(): raise SystemExit('AMUI01_MISSING_REMOVED_RECEIPT')
r=json.loads(p.read_text())
assert rc==0 and r.get('returncode')==0 and r.get('status') in ('passed','success'), r
assert int(r.get('executed',0))>=minimum and int(r.get('passed',0))>=minimum, r
assert int(r.get('failed',1))==0, r
print('AMUI01_REMOVED_DEVICE_PASS', r.get('executed'))
PY
python3 -B - "$RUNNER_TEMP/amui01-meta.json" "$api" "$fixture_apk" <<'PY'
import json,sys,os,hashlib
from pathlib import Path
p=Path(sys.argv[3])
Path(sys.argv[1]).write_text(json.dumps({'commit':os.environ.get('GITHUB_SHA',''),
  'api':int(sys.argv[2]),'fixture_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
  'disposable_fixture':'dev.mikeyphw.amt06c.fixture',
  'stages':['real_install','signed_replacement','authoritative_reconcile','real_uninstall','reconcile_removed']},indent=2)+'\n')
PY
echo 'AMUI01_LIFECYCLE_HOSTED_PASS'
