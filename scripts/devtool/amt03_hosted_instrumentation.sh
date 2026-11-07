#!/usr/bin/env bash
# Dedicated runner: android-emulator-runner executes each script LINE with sh -c.
# Invoke this complete file with exactly one action script command.
set -Eeuo pipefail
export PYTHONDONTWRITEBYTECODE=1
: "${JAVA_HOME:?GitHub Actions must provision JDK 21}"
: "${RUNNER_TEMP:?GitHub Actions must provision RUNNER_TEMP}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk" ]] || { echo 'AMT03_HF04_REFUSED: Android SDK root missing' >&2; exit 1; }
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd -- "$repo"

# The CI-only target configuration is restored even after a failed Android run.
original="$(mktemp "$RUNNER_TEMP/amt03-original-devtool.XXXXXXXX.toml")"
cp -- .devtool.toml "$original"
restore_original() {
    cp -- "$original" .devtool.toml
    rm -f -- "$original"
}
trap restore_original EXIT
python3 -B scripts/devtool/amt03_hosted_config.py .devtool.toml --java-home "$JAVA_HOME"

# These are Devtool's structural preconditions, not just sdkmanager directory names.
for required in \
    "$sdk/build-tools/36.1.0/source.properties" \
    "$sdk/build-tools/36.1.0/lib/d8.jar" \
    "$sdk/ndk/27.0.12077973/source.properties" \
    "$sdk/ndk/27.0.12077973/build/cmake/android.toolchain.cmake" \
    "$sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \
    "$sdk/cmake/3.22.1/bin/cmake" \
    "$sdk/platforms/android-36/android.jar"; do
    [[ -f "$required" ]] || { echo "AMT03_HF04_SDK_NOT_READY: $required" >&2; exit 1; }
done

# Validate the exact JDK the Devtool Android builder will receive. Do not
# rely on JAVA_HOME alone: Devtool may select a narrower installed JDK (Java 8)
# for a project that compiles its sources with Java 8 compatibility.
python3 -B - "$JAVA_HOME" <<'PYJDK'
from pathlib import Path
import re
import subprocess
import sys
home = Path(sys.argv[1])
java, javac = home / 'bin/java', home / 'bin/javac'
if not java.is_file() or not javac.is_file():
    raise SystemExit('AMT03_HF05_REFUSED_JDK: JAVA_HOME lacks bin/java or bin/javac')
try:
    result = subprocess.run([str(java), '-version'], text=True,
                            capture_output=True, check=False, timeout=15)
except (OSError, subprocess.SubprocessError) as exc:
    raise SystemExit('AMT03_HF05_REFUSED_JDK: cannot execute java -version') from exc
output = (result.stdout or '') + '\n' + (result.stderr or '')
match = re.search(r'version\s+"([0-9]+)(?:\.([0-9]+))?', output)
major = int(match.group(2) if match.group(1) == '1' else match.group(1)) if match else None
if result.returncode != 0 or major != 21:
    raise SystemExit(f'AMT03_HF05_REFUSED_JDK_VERSION: required 21, observed {major}')
print('AMT03_HF05_JDK21_READY: ' + str(home))
PYJDK

# Verify that the *target-aware* Devtool Android environment is qualified.
python3 -B - <<'PY'
import json
import os
from devtool.config import load_project_config
from devtool.android.environment import resolve_android_environment
cfg = load_project_config('.', None, target_name='app', variant_name='debug', allow_parent_config=True)
selected = cfg.runner_options.get('android', {}).get('java_home')
if selected != os.environ['JAVA_HOME']:
    raise SystemExit('AMT03_HF05_DEVTOOL_JDK_MISMATCH: ' + str(selected))
print('AMT03_HF05_DEVTOOL_JDK21_BOUND')
request, qualification, _resolved = resolve_android_environment(cfg, action='instrumentation', fail_closed=False)
print('AMT03_HF04_SDK_QUALIFICATION ' + json.dumps({
    'status': qualification.status,
    'diagnostics': list(qualification.diagnostics),
    'build_tools': request.build_tools.requested,
    'ndk': request.ndk.requested,
    'platform': request.platform.requested,
}, sort_keys=True))
if not qualification.ok:
    raise SystemExit('AMT03_HF04_SDK_QUALIFICATION_FAILED')
PY

receipt="$RUNNER_TEMP/amt03-instrumentation.json"
log="$RUNNER_TEMP/amt03-instrumentation.log"
rm -f -- "$receipt" "$log"
set +e
python3 -B -m devtool.android_instrumentation "$PWD" \
    --target app \
    --class io.github.muntashirakon.AppManager.automation.tasker.TaskerJournalInstrumentedTest \
    --report "$receipt" 2>&1 | tee "$log"
instrumentation_rc="${PIPESTATUS[0]}"
set -e

# Required gate: success is never inferred from process exit alone.
python3 -B - "$receipt" "$instrumentation_rc" <<'PYCHECK'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
rc = int(sys.argv[2])
if not p.is_file():
    raise SystemExit('AMT03_HF04_FAIL: instrumentation did not create a receipt (command exit '+str(rc)+')')
r = json.loads(p.read_text())
assert rc == 0, ('instrumentation returned nonzero', rc, r)
assert r.get('status') in ('passed', 'success'), r
assert int(r.get('passed', 0)) >= 2, r
assert int(r.get('passed', 0)) >= 12, ('AMT05A_INSTRUMENTATION_COVERAGE_INCOMPLETE', r)
assert int(r.get('passed', 0)) >= 14, ('AMT05B_NEGATIVE_DEVICE_COVERAGE_INCOMPLETE', r)
assert int(r.get('passed', 0)) >= 17, ('AMT05C_RECEIPT_DEVICE_COVERAGE_INCOMPLETE', r)
assert int(r.get('passed', 0)) >= 19, ('AMT05D_RECEIPT_RECOVERY_DEVICE_COVERAGE_INCOMPLETE', r)
assert int(r.get('failed', 0)) == 0, r
assert int(r.get('executed', 0)) >= 2, r
assert int(r.get('returncode', 1)) == 0, r
print('AMT03_DEVICE_INSTRUMENTATION_PASS')
PYCHECK
