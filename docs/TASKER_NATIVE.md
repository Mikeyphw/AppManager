# App Manager — native Tasker plug-in (AMT-02 R2B)

**Branch:** `feature/tasker-automation`. This is a first native plug-in cut,
not a full remote operations API. No root/ADB access is exported beyond the
pre-existing App Manager profile service, and no user grants are required.

## Tasker UX

- **Task → Plugin → App Manager · Profile Action**: Apply a selected existing
  profile (On/Off), or read the most recent profile result. Saving stores a
  configuration automatically. No tokens, broadcast extras, or approval dialogs
  to copy/configure manually.
- **Profile → Event → Plugin → App Manager · Profile Event**: Profile started,
  completed, or failed.
- **Profile → State → Plugin → App Manager · Profile State**: Profile running,
  last profile succeeded, or last profile failed.

**Output variables:** `%am_request_id`, `%am_profile_id`, `%am_state`,
`%am_status`, `%am_error`. Tasker Action returns **queued**, not **success**,
when the internal foreground service accepts a profile job; the real outcome
appears in Event/State updates and Last Profile Result. The Event/State
implementation observes the existing `ProfileApplierService`, including
profiles applied from inside App Manager.

## Security model

Only Tasker/Locale may launch the configuration editors through Android's
OS-verified `getCallingPackage()`. On saving, App Manager creates a 256-bit
random capability inside `noBackupFilesDir`; the editor returns that opaque
capability to Tasker's *saved* plug-in configuration. Subsequent `FIRE_SETTING`
/ `QUERY_CONDITION` requests must include the matching capability. On Android
14+ the receiver also rejects an identifiable untrusted broadcast sender.
No token is exposed in an App Manager settings screen, logs, or result variables.
Old R2A grant-storage files remain dormant for source compatibility; the
Tasker plug-in does not consult them and the grant-approval UI is removed.

The edit Activity rejects direct launches without Tasker/Locale's
`startActivityForResult` caller. Neither exported receiver accepts arbitrary
shell commands, file paths, package-management requests, or privilege mode
changes. `ProfileManager` is consulted at execution time and the service
remains non-exported.

## Limits and acceptance

- Android may disallow a foreground service launch from the background. In
  that case the action returns `failed` with `profile_dispatch_failed` instead
  of incorrectly reporting success. A later fallback implementation can use
  the normal Android scheduled-work mechanism if device tests demonstrate a
  need. No background workaround is silently enabled.
- One recent profile operation is used for State queries. Event snapshots are
  identified by Tasker's pass-through message IDs, expire after two minutes,
  and do not replay when Tasker queries at startup. On Tasker hosts that do not
  pass through event message IDs, these events intentionally fail closed.
- This does not add dynamic Tasker variable replacement for profile IDs or
  wait-for-final-result action callbacks. Output variables are available for
  Tasker hosts supporting the standard Tasker/Locale extension.
- The repository's `android:installLocation` is left untouched for upstream
  compatibility. Keep App Manager installed in internal storage for reliable
  plug-in discovery on devices that allow apps to move to external storage.
- Validation before installation: `python3 scripts/devtool/check_tasker_plugin_contract.py --check`
  and `python3 -m unittest discover -s scripts/devtool -p 'test_*.py' -v`.
  These are **source-only checks**. APK build/test and plugin device acceptance
  must run on GitHub Actions and the user's device, respectively.
- To build through pinned Devtool on GitHub Actions: `./devtoolw ci-build --yes`.
