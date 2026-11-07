# AMT-05A — Read-only Tasker backup inventory + AMT-04B2C emulator acceptance

## Qualification state

Source-pinned to `96ab1fc862f7da511c6126be1bd473272c6135be` and all six edited Git blobs. Single Devtool overlay, atomic rollback, automatic single commit. No Termux Gradle, ADB, root, network, manifest insertion, permission change, or extra Tasker receiver during application. Host Android Gradle actions only.

## Native Tasker capability

The existing **App Manager · Query Package** action now returns read-only backup inventory for **that package and the current Android user only**, in addition to the original package metadata. No new Tasker editor is necessary. The original saved query token and strict package resolution continue to authenticate the request; malformed or unresolved `%package` values cannot read the inventory.

Tasker variables: `%am_backup_status` (`available`, `inventory_unavailable`, `inventory_serialization_failed`, `not_queried`), `%am_backup_count` (blank on failed read, true `0` on a successful empty read), `%am_backup_latest_ms`, and `%am_backups_json` (at most 24 entries, with exact total count and `truncated=true` when applicable). Includes backup name, creation time, flags, version code. No relative directories, archive paths, app data, encryption keys, private files, backup contents, or other-user metadata are returned.

**Read-only** means no new `BackupManager.backup`, `.restore`, delete, shell, system permission or privileged action. An unavailable DB is reported separately rather than falsifying a missing-backup result; the original package-query result remains unchanged. No attempt to expose full backup/restore until separate explicit scope, permissions, destructive-operation safeguards, confirmation semantics and restoration policy are qualified.

## Real emulator (not source-only) coverage

Adds four tests to existing `TaskerJournalInstrumentedTest`:

- `testBackupInventoryDeviceScopedReadOnly`: tests Android Bundle/JSONObject serialization, filters cross-user backups and excludes private paths
- `testLedgerSurvivesReopenAndRemovesOnlyMatchingSnapshot`: real `AtomicFile` persistence and exact record finalization after new ledger instance
- `testLedgerCorruptionFailsClosedOnDevice`: malformed JSON fails as IOException, rather than becoming an empty ledger
- `testLedgerNeverOverwritesPriorSnapshotOnDevice`: duplicate snapshot rejected and previous snapshot retained

The four tests use **private isolated scratch files**. They do not require or fake `CHANGE_COMPONENT_ENABLED_STATE`, freeze a real app, or force-stop Tasker. With the prior eight device tests the hosted receipt must report **12 or more actual executed/passed tests**; existing zero-fail, return-code and KVM/JDK21 guards remain. Real privileged package mutation and recovery still require a disposable app on a real device with explicit user authorization.

## CI cadence

Automatic push: `source` only. First manual dispatch after application: `mode=test` for Gradle/unit tests. Then `mode=instrumentation` for the expanded emulator tests; no need for full lint+build+emulator on each change. Use `mode=full` once for release/milestone qualification.
