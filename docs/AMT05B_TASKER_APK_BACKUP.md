# AMT-05B — Scoped Tasker APK backup request

This is a **source-pinned, consolidated** overlay for commit `d0750e80697d7c5a1345b40046f3f8bcc4fe1d2b` on `feature/tasker-automation`.

## Behavior

The existing **Tasker → App Manager → Manage Package** plugin adds **Backup APK** to Enable, Disable, Force-stop, Freeze, and Unfreeze. This is a literal, fixed-package action (no wildcard, dynamic package, multi-user target or batch selection), configured by the verified Tasker/Locale editor. It uses the existing package+operation HMAC authorization and shared `FIRE_SETTING` receiver, with no manifest or exported component changes.

A saved `backup_apk` command selects **exactly one installed, non-system, non-AppManager, non-Tasker/Locale** package, current Android user only. It queues App Manager's **existing foreground BatchOpsService** with `OP_BACKUP` and the strict `BACKUP_APK_FILES | BACKUP_MULTIPLE` flag set; no data/restore/custom-user/signature-bypass flags. An unpredictable `Tasker-<UUID>` name makes each saved archive distinct. This is a **non-overwriting, APK-only backup**, not an application-data backup and not backup restoration.

The Tasker response sets `%am_success=true`, `%am_status=queued`, `%am_verified=false` and `%am_backup_name=Tasker-...` **only after the OS accepts the explicit service-start request**. It does **not** claim the actual backup finished or succeeded. Package Query's separate read-only inventory may be used later to inspect stored metadata by name. If Android denies a background foreground-service start, the plugin returns `%am_success=false`, `%am_error=backup_dispatch_rejected`; no background privilege bypass or deceptive retry.

Repeated Tasker invocations each create a new named archive and consume storage. Batch service's own notification/history reports actual success/failure. If the underlying BatchOpsService fails or rejects the job after accepting start, the immediate Tasker response cannot prove completion. Do **not** treat `queued` as a usable backup. Any data or destructive restore operations require later distinct audited, reliable completion semantics and safeguards.

## Qualification

- One source-pinned Devtool overlay, atomic application and single commit.
- Existing AMT02/AMT03/AMT04/AMT05A contracts retained. New static contract and intentional regression/mutation tests included.
- `mode=source` automatically on push. Use `mode=test` for hosted Gradle/unit tests and `mode=instrumentation` for emulator tests. New emulator receipt minimum: **14 passed, zero failed**. No local Gradle.
- Android emulator tests are **negative-only** for write actions: they must not create any backup or modify any installed app. Manual acceptance requires one disposable third-party app and verified notifications/history/inventory and enough storage.
- No release gate is claimed until hosted `mode=test` and `mode=instrumentation` both pass on the exact new commit.

## Termux zsh

```zsh
cd ~/Code/AppManager
devtool --repo "$PWD" --target app apply-overlay \
  "/storage/emulated/0/Download/AMT05B_TASKER_APK_BACKUP_CONSOLIDATED.overlay.zip"
git push origin feature/tasker-automation
```

Then (sequentially to avoid GitHub concurrency cancelling one dispatch):

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=test
# After mode=test completes, run mode=instrumentation separately.
```
