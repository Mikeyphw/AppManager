# AMT-04B1 — Native Tasker package enable/disable (first write slice)

This consolidated overlay includes **AMT-04A Package Query** plus **AMT-04B1 Manage Package** and applies to commit `8ec7b99a6bff7d5dbb37dcd684b00e995d0336f5`. Do not also apply the separate AMT-04A overlay.

## Behavior

- New **Tasker > Actions > Plugin > App Manager · Manage Package** action, limited to `enable` and `disable` for exactly one *installed, non-system, non-App Manager* application selected when the Tasker task is configured.
- Saved token is an HMAC-SHA256 of the exact package+operation with a private, no-backup 256-bit device secret. Neither the read-only query token nor the profile token grants package-write access. Edited saved package names, operation names and unknown keys fail closed.
- No dynamic `%package`, wildcards, batch, system packages, root shell, force-stop, suspend, freeze, unfreeze or backup/restore yet. These require separate gates and acceptance before exposure.
- Receiver requires Tasker/Locale's ordered completion channel, validates the saved package and token, optionally checks the OS-identified sender on Android 14+, and runs the existing `PackageManagerCompat.setApplicationEnabledSetting` on a background worker.
- Existing App Manager privilege checks (`SelfPermissions.checkSelfOrRemotePermission(CHANGE_COMPONENT_ENABLED_STATE)`) apply. The plugin never grants root or ADB. Missing privilege returns `backend_permission_required`.
- Success requires post-write state readback through App Manager's `PackageManagerCompat`. If state cannot be verified, return `state_unverified`; if a binder error occurs after a possible side effect, return `state_unknown_after_error`, **not** a false claim of rollback.
- `%am_success`, `%am_status`, `%am_error`, `%am_package`, `%am_operation`, `%am_previous_state`, `%am_new_state` are returned. Prior-state and current-state values are Android enabled-state integers; this first slice does not implement transactional state restoration.

## Acceptance boundaries

**Source acceptance only** until the new head finishes GitHub Actions full mode. The hosted API 35 instrumentation suite includes negative tests only for package mutation: it must never disable applications on a shared emulator. The real-device acceptance must test a disposable user-installed application with reversible enabled-state changes and demonstrate both successful and denied cases. No active Tasker tasks should target system components.

After applying/pushing the overlay, run:

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=full
```

Do not run Gradle locally on Termux. Keep AMT-03 behavior intact. Next AMT-04B2 will consider force-stop and freeze/unfreeze with separately qualified permissions, state behavior and rollback policy.

## HF01 — single Locale receiver interoperability

Tasker exposes separate Profile Action, Query Package and Manage Package editors
(`EDIT_SETTING` activities). Android manifest registers exactly **one**
`FIRE_SETTING` receiver (`TaskerActionReceiver`), which routes saved bundles
to internal package-query or package-manage evaluators after checking the
request type. Capability tokens and authorization are validated independently
by those evaluators. Ambiguous package-action bundles are rejected. There are
no exported package-query or package-management receivers. The original
profile action remains unchanged for older saved Tasker configurations.
