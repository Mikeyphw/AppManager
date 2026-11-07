# AMT-06A — Native current-user APK + internal-data backup through Tasker

This adds an opt-in **Backup APK + internal data** operation to App Manager's existing
fixed-package Tasker/Locale editor. It requires the existing App Manager data-write
privilege (`SelfPermissions.canWriteToDataData()`) and refuses system apps, Tasker,
Locale and App Manager itself. No new privileges, exports, shell or cross-user logic.

Backups use native BatchOpsService, with flags **BACKUP_APK_FILES |
BACKUP_INT_DATA | BACKUP_MULTIPLE**. The routing flag MULTIPLE prevents overwriting
existing backups; the native BackupOp strips that flag in persisted metadata.
The app's user-configured backup storage and encryption settings remain in effect.
App data may contain secrets; verify encrypted and safely stored backups before use.

Receipt identity uses existing `Tasker-UUID` names. New data receipts get a `type=data`
field in the same private AtomicFile; older 7-field APK receipts default to `type=apk`
without an on-disk migration. Native callbacks match package, user, name, exact
requested flags, type and persisted metadata, so queue acceptance is never called
successful completion. In Query Package, `%am_backup_request_type` says `apk` or
`data`, and each history item includes its type. Archived data and keys are never
returned to Tasker.

Testing: new Robolectric tests, three negative/fixture Android instrumentation
cases, source/mutation gates, old contract compatibility and hosted emulator
count >=24. Android Gradle and emulator run only in GitHub Actions; source-only
validation remains available on Termux. No restore, external/ADB data, signature
bypass, automatic retry or cross-user work. Real-device capability and backup
contents still need acceptance; a green emulator proves only the tested paths.
