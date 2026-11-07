# AMT-03 HF02 — GitHub Actions emulator shell fix

Source: `Mikeyphw/AppManager@f417411d6f26618f50a6cc367dd8ba263de9995d`.
Failed full run: https://github.com/Mikeyphw/AppManager/actions/runs/42315310568

## Confirmed failure

- Source and upstream merge checks: passed.
- Hosted Android build, unit tests, lint, APK and lint artifact uploads: passed.
- Emulator (API 35) boot: succeeded, albeit slowly.
- Device instrumentation never started. `reactivecircus/android-emulator-runner@v2`
  executes its `script:` with `/usr/bin/sh -c` on Ubuntu; `/usr/bin/sh`
  rejected `set -euo pipefail` with `Illegal option -o pipefail` (exit 2).
- The instrumentation artifact did not exist because execution stopped before the
  Devtool invocation. This does not prove the Android tests would pass.

## HF02 correction

The action still runs `script: |`, but its first line now starts an explicit
Bash heredoc: `bash -e -o pipefail <<'AMT03_EMULATOR_BASH'`.
The existing `python3 -m devtool.android_instrumentation` invocation and its
strict report verifier remain unchanged. The Bash process returns the actual
pipeline failure even though `tee` follows the Devtool command.

`test_amt03_emulator_shell_contract.py` reproduces the action's outer `sh`
behavior without installing Android SDK/Gradle or executing device operations.
It tests Bash syntax and positive and negative emulated receipts, including
propagation of a nonzero instrumentation exit status through `tee`.

The patch is source-pinned and never automatically reruns the failed workflow.
After applying, push and dispatch `devtool-tasker-ci.yml` with `mode=full`.
Check all three jobs; real Android 16 Tasker acceptance is a separate gate.
