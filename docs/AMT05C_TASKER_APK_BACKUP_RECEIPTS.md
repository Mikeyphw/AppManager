# AMT-05C — Durable Tasker APK backup receipts and native service lifecycle

Base: `Mikeyphw/AppManager` branch `feature/tasker-automation` at commit
`bbe763dbbf0b80f36818e077e1181e1979855423` (source-pinned exact files).

This **single consolidated** Devtool overlay builds on AMT-05A inventory and AMT-05B
APK-only backup dispatch. It does not add backup data, restore, deletion, silent
permission elevation, new exported Android components, or auto-retries.

## Behavior and trust boundaries

1. The existing privileged Tasker action editor still authenticates a literal
   target package and the exact `backup_apk` operation with a per-device proof.
   Existing system-app, automation-host and AppManager-self protections remain.
2. `TaskerApkBackupDispatcher` records an immutable, unique `Tasker-<UUID>`
   request **before** requesting the existing `BatchOpsService` foreground work.
   Failure to persist a record refuses dispatch (`backup_receipt_unavailable`).
3. `BatchOpsService` directly calls the internal `TaskerApkBackupJournal` at
   start and on completion. No exported receiver and no broadcast-controlled
   completion acceptance. Only an exact single-package, current-user-compatible
   `OP_BACKUP` queue with the APK-files + multiple/non-overwriting flags and
   generated name qualifies.
4. Native completion failure is recorded as `failed`. A native success is
   recorded as `succeeded` **only if** AppManager's own validated backup
   metadata contains the matching package, Android user ID, generated name
   and APK-only stored flag. The native backup code intentionally strips
   `BACKUP_MULTIPLE` before storing metadata; dispatch still uses it to avoid
   overwrites. Missing or inaccessible metadata => `unverified`.
5. If the process dies before completion, the record remains `queued` or
   `running`; a query after 2 hours reports `unknown`, never guessed success.
   A foreground-service start exception reports `backup_dispatch_rejected` and
   records an uncertain outcome. No automatic replay or rollback is attempted.
6. Only the latest matching receipt for the current Android user is returned
   by the existing **Query Package** action. Results do not expose storage
   paths, archive bytes, encryption keys, logs or other users' requests.

## Tasker query outputs

These outputs are **additional** to AMT-05A backup inventory and AMT-04A
package metadata:

| Variable | Meaning |
| --- | --- |
| `%am_backup_request_name` | Latest generated name for this package/user |
| `%am_backup_request_status` | `none`, `queued`, `running`, `succeeded`, `failed`, `unverified`, `unknown`, `unavailable` |
| `%am_backup_request_error` | Structured error, if available |
| `%am_backup_request_verified` | `true` **only** for `succeeded` |
| `%am_backup_request_updated_ms` | Last saved receipt update time |

**Important:** The Manage Package action still responds `queued` when service
start is accepted. The receipt status is obtained in a later Query Package
invocation. If the native backup result is uncertain, Tasker must not interpret
`queued`, `running`, `unknown` or `unverified` as completed.

## Persistence and safety

- `AtomicFile` in `getNoBackupFilesDir()`, schema v1; bounded 64 records / 64 KiB.
- Existing receipt IDs cannot be overwritten. Only oldest terminal records may
  be evicted; pending records are never silently discarded to create space.
- Corrupt/oversized/unknown-version ledger is refused; a failure to read it
  does not become an empty successful state.
- Package+user+request-name match for every lifecycle transition; terminal
  states cannot be rewritten by late/duplicate callbacks.
- Source contracts, negative mutation checks, Robolectric tests and emulator
  tests are included. Single Tasker FIRE_SETTING receiver and pinned manifest
  lint diagnostic lines are unchanged.

## Qualification

1. Apply the overlay through Devtool; it is pinned to the stated branch SHA and
   exact seven existing file Git blob IDs. Atomic rollback on source failures.
2. Push feature branch. Automatic source CI should pass without Gradle.
3. Run hosted targeted `mode=test` to validate actual Android Gradle/JVM.
4. Run hosted `mode=instrumentation` to test isolated receipt files on the
   emulator (>=17 real test results, no failures).
5. On a real device, verify a disposable user-installed app's APK-only backup
   completes and shows a validated matching receipt. Also test denied
   permission/storage and force-stopping the service while a backup is running.

No local Gradle is required or invoked. This overlay **does not** claim that
real Tasker backup operations have completed until step 5 is verified.
