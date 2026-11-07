# AMT-05D — Durable backup receipt recovery and bounded Tasker history

## Why this change
A crashed APK backup can leave its receipt queued/running. AMT-05C showed the
status as unknown after two hours, but the underlying record remained pending.
At 64 such records, no new request could be saved. AMT-05D recovers only stale
pending records as **unknown** during the next enqueue, atomically in the same
ledger update, and then evicts the oldest terminal record if capacity is full.
It does not retry work, claim success, or modify backups.

## Tasker query outputs
Existing `%am_backup_request_name`, `_status`, `_error`, `_verified`, `_updated_ms`
are unchanged. Added:

- `%am_backup_request_pending_count`: number of non-expired queued/running APK
  requests for the queried package and current Android user.
- `%am_backup_request_history_json`: newest-first, at most 12 requests with
  `name`, `status`, `error`, `updated_ms`, `verified`. No archive paths, other
  users, payload bytes or encryption material.

A stale request is reported as `unknown` even before the next enqueue. Expiry
is persisted at the next enqueue; a late completion cannot change a terminal
receipt after persistence. A clock rollback cannot prematurely expire a task.

## Safety and CI
Single existing Locale FIRE_SETTING receiver; no manifest changes. Durable
AtomicFile stored under `getNoBackupFilesDir()`. All previous source contracts
run, plus AMT-05D source/mutation suite, three new JVM tests and two Android
instrumented tests. Hosted emulator requires >=19 passed and zero failed.
No local Gradle. Run targeted GitHub mode=test and mode=instrumentation before
claiming device acceptance. This does not enable application-data backup or restore.
