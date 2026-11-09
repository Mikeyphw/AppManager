# AMUI-01 — awaited package refresh and app-list correctness

Base: `Mikeyphw/AppManager` `feature/tasker-automation`
`53089f794972c7f20a44aa8500eabf41b220c2c1`.

## Changes

* Separate cache-only startup from authoritative package reconciliation. Manual refresh does not finish until package enumeration, database update and a fresh visible list have been published, or an error surfaced.
* Coalesce list mutations, filter/sort and targeted package broadcasts onto one ViewModel-owned serial executor; monotonically invalidate stale UI publications rather than interrupting a potentially destructive database transaction.
* React to Android ACTION_PACKAGE_ADDED/REPLACED/REMOVED/CHANGED even without EXTRA_UID by accepting the `package:` URI. Receiver lifetime uses goAsync/finish and a bounded static dispatch executor, handing real work to the ViewModel.
* Resume checks recover from missed package changes, throttled to avoid repeated heavy reconciliation.
* Per-user enumeration is fail-closed. No silent Binder parcel failure or partial getPackageInfo workaround; inaccessible profiles remain in the cache. Full and targeted table writes use Room transactions. Empty enumeration is an error, not an uninstall-all instruction.
* Visible immutable-ish list snapshots and explicit content-aware DiffUtil comparisons for updates. Search/filters remain in effect and sync feedback includes filtered visible counts, profile limitation and failed scans.
* Real hosted-only APK lifecycle using disposable AMT06C fixture: install, signed replacement, synchronized DB/list, and uninstall, on Android API35 and API36. All preexisting Tasker source/CI gates and >=30 instrumented Tasker tests retained.

## Non-goals and remaining acceptance

The new source and tests must run on a **real exact-commit GitHub Actions run** after the overlay is applied, committed and pushed. This kit's offline Python/ZIP checks cannot prove Android compilation, emulator package lifecycle, UI screenshots or root/profile behavior. Physical hardware acceptance is still outstanding.

The AMUI-03 compact/detailed list redesign and AMUI-02 app-wide Material You theme are separate large UI milestones. Tasker development remains paused until AMUI-04 qualifies all of them. AMT06C1 exact-commit hosted qualification was not previously verified; this kit does not modify Tasker implementation, HMAC, signing, receipts or restore gates.

## Safety

The CI fixture has package `dev.mikeyphw.amt06c.fixture`, is disposable and contains no valuable application data. Do not test destructive backup/restore operations against installed user apps. A database scan error keeps the previous rows, reports failure and permits retry; it does **not** imply transactionally undoing any Android package manager operation.

## CI commands

Run `mode=test` for the fastest hosted Gradle test; `mode=instrumentation` for the required API35 Tasker+AMUI and API36 AMUI lifecycle emulators; `mode=full` for the full hosted Gradle and emulator suite. Source-only push is never a substitute for hosted Android qualification.
