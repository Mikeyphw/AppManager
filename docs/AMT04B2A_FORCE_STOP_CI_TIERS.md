# AMT-04B2A / CI01 — Scoped force-stop and tiered hosted CI

**Base commit:** `7eb17ddee70d5d9e38f1c6379e2cbfa8a82cff01`, branch `feature/tasker-automation`. This is an incremental overlay on the **successful HF02B** commit, not a replacement for AMT-04A+B1.

## Native Tasker package-management action

- Adds **Force-stop application (irreversible)** to the existing **Manage Package** editor.
- A saved Tasker configuration is still bound by the device-private HMAC to exactly one literal package and one operation. Copying its token to a different operation or package fails.
- The target must be installed and not a system/updated-system package. App Manager cannot target itself. Force-stop also refuses Tasker itself, preventing the Tasker task from killing its execution host.
- The plugin requires `android.permission.FORCE_STOP_PACKAGES` available through App Manager's **existing** privileged execution identity. It never grants or elevates privileges and never invokes a root shell.
- Uses existing `PackageManagerCompat.forceStopPackage()` for the current Android user. Failure or denial is returned in `%am_error`.
- On successful **IPC dispatch**, reports `%am_success=true`, `%am_status=dispatched`, `%am_verified=false`; this explicitly does **not** claim independent verification that the application's processes have terminated. Enable/disable continue using post-operation readback with `%am_status=success`, `%am_verified=true`.
- No implicit retries, no rollback claims, no wildcard/dynamic mutation targets. Freeze/unfreeze are deferred until state capture and restoration are designed.

## CI changes

GitHub Actions `devtool-tasker-ci.yml` now defaults to `source` rather than `full` when dispatched manually. It also accepts:

| Mode | Checks | Purpose |
|---|---|---|
| `source` | All source contracts, Python tests, upstream merge, Devtool wrapper/seal | Every overlay and fast hotfix |
| `test` | Source + hosted Android unit tests | Java/Kotlin logic changes |
| `build` | Source + hosted APK compilation, upload | UI or manifest integration |
| `lint` | Source + fresh lint XML and strict 121-diagnostic baseline | Manifest/resource/lint-related changes |
| `instrumentation` | Source + hosted API35 emulator only | Android device-contract checks |
| `full` | Source + build/test/lint + emulator | Milestone acceptance and release |

The GitHub `source-contract` job runs the AMT-04B2A source check explicitly alongside legacy AMT-03 checks. The legacy AMT-03 KVM test also asserts both permitted emulator modes (`full` and `instrumentation`) rather than requiring obsolete `full`-only behavior.

Pushes continue to run **source-only**; PRs retain the comprehensive hosted Android job. No Gradle is ever run in Termux. The strict lint baseline is preserved; no diagnostics waived or rule disabled.

Recommended after this overlay: **`mode=test`** first, then **`mode=full`** at AMT-04B2A qualification (not after every tiny hotfix). The emulator's negative safety test verifies only that App Manager cannot force-stop itself, not successful force-stop of another app. Real-device acceptance with a disposable user app remains required.

```zsh
# Fast source gate happens automatically on git push.
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=test
# Only when the milestone is ready for final qualification:
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=full
```
