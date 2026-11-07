# AMT-06B — Opt-in fixed-snapshot internal-data restore

Source pinned to AppManager branch `feature/tasker-automation`, Git SHA `78749f9edb88df373ea04a41854dfeaf155414f6`.

## Intended workflow
1. Create an **APK + internal data** backup with AMT-06A of a disposable, non-system app and confirm its successful Tasker receipt using Query Package.
2. In the trusted Tasker **Manage Package** editor, select **Restore INTERNAL data from fixed Tasker backup (destructive)**. Enter the exact `Tasker-UUID` backup name (not a Tasker variable), review the warning and check the consent checkbox.
3. The editor checks the selected backup against App Manager's current-user metadata, the installed application's UID, install time, and version code. It persists all of these in the package/operation HMAC-bound saved Locale configuration. It never stores a filesystem path in Tasker configuration.
4. At firing, App Manager checks the same package identity, current user, installed version, backup type, backup file presence and existing privileged data capability. It writes an app-private AtomicFile receipt **before** dispatching a native foreground batch service request.
5. The existing BatchOpsService rechecks the request ID, backup path and package identity on its own worker thread, *immediately before* calling native restore. It rejects unknown or mismatched Tasker restore requests. Restore flags are strictly `BACKUP_INT_DATA`: no APK reinstall, external/ADB data, other user, signature bypass, or keystore restoration.
6. A direct native completion callback records `native_completed` or `failed`; if the service disappears, the result eventually becomes `unknown`. Query Package exposes `%am_restore_request_*` for the package and current user. **`%am_restore_request_verified` is always false:** native completion cannot prove that the app's restored data are functionally correct.

## Limitations and safety
- Android app-data restore is destructive, potentially non-atomic, and **has no guaranteed rollback**. An installed app and its data may be partially modified even when the native operation reports failure. Test on a disposable app with separate independent data backup first.
- **No automatic retries, undo, or force-clearing**. At most one restore is pending in the private journal at a time. The old status can become `unknown` after two hours; this is not a declaration of success/failure.
- `BackupManager.restore(...)` and `RestoreOp` remain the only actual restore implementation; the overlay does not bypass their metadata/signature/integrity checks or add system permissions.
- Stored Tasker proof authorizes one exact package+backup+installed UID+first-install timestamp. Uninstall/reinstall invalidates it. Saved automations continue to work without a new interactive confirmation on each run **only** because the user explicitly opted in while saving that fixed configuration.
- Works only with a previously created `Tasker-UUID` backup containing APK + internal data and the same app version. Restore of external data, other users, system apps, encrypted-backup key material, and historic mismatching versions are disallowed.
- Running source tests on Termux and Android runtime emulator tests is not evidence that privileged restore succeeded on a real phone. Test on a non-critical app before considering general use.

## Qualification
The Devtool archive has exact-base and exact-blob pins, transaction rollback, automatic commit, source regression checks, four negative/isolated emulator tests, and tiered GitHub Actions. The full legacy Devtool contracts run on apply; local Gradle builds are intentionally disabled. Only hosted GitHub Actions runs build/test Android code.
