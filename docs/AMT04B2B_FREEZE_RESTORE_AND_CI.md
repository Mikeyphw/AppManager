# AMT-04B2B — Native Tasker freeze/unfreeze with durable exact-state restore

## Scope

This is a **single consolidated** additive Devtool overlay for commit `79f29ea9c592cfb53e177512b76c32717c9516f8` on `feature/tasker-automation`, after the successful AMT-04B2A milestone. It extends the same *Manage Package* Tasker editor with `Freeze (remember original state)` and `Unfreeze (restore saved state)` and retains Query Package, Profile, Enable, Disable and Force-stop. It adds no exported component, no permission and no manifest line changes. The existing Locale receiver stays unique. HMAC saved configurations remain bound to an exact package *and* an exact action. No per-task approval screen and no root/ADB escalation are introduced.

## Chosen freeze model

**Disable-based only**, using existing `PackageManagerCompat.setApplicationEnabledSetting` and `CHANGE_COMPONENT_ENABLED_STATE`. This does **not** expose arbitrary App Manager's suspend/hide/advanced-freeze methods, nor imply they can safely be rolled back. Only the current Android user and a selected installed non-system package are in scope. App Manager itself and Tasker/Locale automation hosts cannot be frozen or disrupted.

- Before freeze, the exact integer `getApplicationEnabledSetting` is saved to an app-private `AtomicFile` under `noBackupFilesDir` with package UID and `firstInstallTime` as a best-effort installation identity. Only then is the package set to `COMPONENT_ENABLED_STATE_DISABLED_USER` and read back.
- A second Freeze for the *same* saved record is idempotent when already disabled. If the app changed externally to an unexpected state, execution fails with `freeze_state_conflict`; no silent replacement of the saved snapshot.
- Unfreeze requires the **matching saved record**, same installed-package identity, and either the expected frozen state or the saved original state (the latter covers a crash after write but before ledger cleanup). It restores the **exact original enabled-state integer**, read-backs the result, then atomically removes the saved record.
- Unfreeze without a saved record returns `restore_record_missing`, not a blind enable. An app already disabled by something else returns `already_disabled_without_snapshot`; a changed UID/first-install time returns `freeze_identity_changed`. Another actor's differing package state returns `restore_state_conflict` instead of being overwritten.
- Existing Enable/Disable actions refuse to mutate any package with an outstanding owned freeze record (`managed_freeze_pending`). The ledger is private, bounded to 128 records/64 KiB, and is never included in Android backup.
- Power loss, process death, or a Binder failure may leave the app disabled with a saved record. **No automatic package mutation on process restart** and no promise of atomicity across the Android package manager and the private file. A later explicit Unfreeze can restore if still safe. In ambiguous cases App Manager fails closed and may require manual intervention.

## Tasker outputs

- Existing `%am_success`, `%am_status`, `%am_verified`, `%am_error`, `%am_package`, `%am_operation`, `%am_previous_state`, `%am_new_state` are retained.
- `%am_restore_recorded` is `true` when the private ledger has a record, `false` when it has no record, `unknown` on an inaccessible ledger, or empty for legacy operations.
- Force-stop continues to report `dispatched` and `%am_verified=false`, not proven process termination.

## Acceptance gates

- Local (Termux): preflight exact Git commit and six exact blob hashes, all AMT00–AMT04B source contracts and all Python source tests. No Gradle, Android emulator or ADB run locally.
- Hosted: push automatically runs source-only; `gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=test` executes Android compilation and unit tests; `mode=full` is reserved for milestone qualification and still runs lint, build, tests and emulator instrumentation.
- Emulator: safe negative mutation tests only, including App Manager self-freeze/self-unfreeze rejection. Never disable a live automation host on a shared emulator.
- Device: test with a **disposable installed user app** only; verify initial DEFAULT vs ENABLED state, freeze, process death after freeze, exact restore, repeated freeze, no-snapshot unfreeze, external state conflict, and package uninstall/reinstall identity mismatch. Verify real Tasker discovery and output variables. Stop for any unexpected system-app change or unverified success. Device acceptance remains pending until this matrix is recorded.

## Limitations and next roadmap

Freeze means disable-based freeze, not App Manager's suspend/hide. Multi-user, backup/restore, generic external API, queued background retries, dynamic mutator package variables, unattended batch operations, and crash-atomic package mutations are not part of this milestone. Later improvements can add explicit audit/recovery UI and support the other freeze methods after real-device qualification.

## HF01 — Legacy contract compatibility

The original B2B preflight passed, but the Devtool validation correctly rejected
the renamed Tasker force-stop warning required by the previously accepted B2A
source contract. The replacement overlay retains **both** protection paths: the
existing `Cannot force-stop Tasker itself.` warning for that exact operation and
the more general Tasker/Locale automation-host denial for disruptive operations.
No privileged permissions are relaxed. The B2B source/negative tests now include
a B2A contract compatibility test to prevent this regression.

**Use HF01 as a full replacement for the failed B2B overlay.** It is source-pinned
to the same pre-B2B commit and includes all six source changes plus the five new
files. There is no need to stack HF01 on the failed attempt.
