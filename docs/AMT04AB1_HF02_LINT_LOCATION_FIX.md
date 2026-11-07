# AMT-04A+B1 HF02 — Preserve exact pinned Android lint locations

## Hosted evidence

- Full workflow run [42395626118](https://github.com/Mikeyphw/AppManager/actions/runs/42395626118) at commit `e0badda51d7c683609d065c3e21474cd42019ab4`.
- Source checks and upstream compatibility passed. Devtool Gradle build, unit tests, and lintDebug tasks completed successfully; Android instrumentation passed.
- The *post-Gradle* lint baseline gate failed because 36 pre-existing `AppLinkUrlError` diagnostics in `AndroidManifest.xml` shifted by exactly 20 lines when the new Tasker activity declarations were inserted before the upstream activities. The remaining 85 of 121 pinned diagnostics matched. This is not a previously unseen Android Lint rule.

## Fix

Move the existing 20-line block registering `TaskerPackageQueryEditActivity` and `TaskerPackageManageEditActivity` from immediately before AMT-02 native Tasker entries to the end of `<application>`. The block is **not removed** and its content is **not changed**. Without it, the manifest must match the exact last-green commit `8ec7b99a` byte-for-byte. Because the block is after the highest pinned manifest lint source line (1478), all the original XML-native lint diagnostics retain their line and column positions.

The pinned 121-error baseline is untouched; no lint rule is disabled, no diagnostic ignored, and no broad line number matching relaxation is introduced. AMT-04 action components and package permissions are unchanged, so no runtime behavior changes are intended.

## Validation

The overlay's exact Git HEAD/blob preflight refuses source drift; the source regression verifies the activities are beyond the baseline errors, the original Tasker receiver remains the only `FIRE_SETTING` receiver, and three editors remain discoverable. Existing AMT-00/02/03/04 source contracts and unit tests are run by `validate.sh`. GitHub Actions remains the only Gradle execution environment.

Expected hosted outcome: `AMT02_LINT_BASELINE_PASS`, with all existing 121 error fingerprints matched. **Do not claim hosted PASS until a fresh full workflow run confirms it.**

## HF02A — Devtool detached-HEAD preflight compatibility

The first HF02 archive failed during `after_extract` with `unexpected branch` although its exact-commit preflight had passed. Devtool's preflight project environment does not guarantee that `git branch --show-current` equals the intended push branch (it may be a detached HEAD). HF02A removes that redundant symbolic-branch requirement only; it still enforces exact commit `e0badda51d7c683609d065c3e21474cd42019ab4`, exact manifest Git blob `35077b48f61ae4e404d1227dc88e568c7f67277e`, the 121-diagnostic baseline identity, a single valid Tasker receiver, and exact byte-for-byte recovery of the last-green manifest after removing the relocated declarations. The normal Devtool atomic commit and rollback still apply.

Apply **HF02A instead of HF02** after the failed transaction has been rolled back. This is a complete incremental HF02 replacement, not another patch layered on HF02.

## HF02B — Regression test prefix correction

The HF02A manifest relocation and original AMT-02/AMT-04 source contracts passed
in Termux, but its new test `test_pinned_upstream_diagnostic_positions_still_precede_editors`
used `lines.index(LEGACY_BLOCK)` on a prefix of a longer comment line. That
raises `ValueError` despite correct positioning. HF02B replaces that exact-line
lookup with a unique, prefix-based match. It refuses missing or duplicate legacy
Tasker markers and still enforces that the 20-line editor block starts beyond
all baseline lint diagnostics and after the legacy Tasker marker. All other
HF02A protections are unchanged. This is a complete replacement for HF02A on
the original HF01 commit; do not layer it over HF02A.
