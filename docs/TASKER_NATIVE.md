# App Manager — native Tasker plug-in (AMT-02 R2D)

**Branch:** `feature/tasker-automation`. This is a first native plug-in cut,
not a full remote operations API. No root/ADB access is exported beyond the
pre-existing App Manager profile service, and no user grants are required.

## Tasker UX

- **Task → Plugin → App Manager · Profile Action**: Apply a selected existing
  profile (On/Off), or read the most recent profile result. Saving stores a
  configuration automatically. No tokens, broadcast extras, or approval dialogs
  to copy/configure manually.
- **Profile → Event → Plugin → App Manager · Profile Event**: Profile started,
  completed, or failed, optionally limited to one existing profile.
- **Profile → State → Plugin → App Manager · Profile State**: Profile running,
  last profile succeeded, or last profile failed. Choose **Any profile** for the
  original behavior, or a specific profile to inspect its own latest result.

**Output variables:** `%am_request_id`, `%am_profile_id`, `%am_state`,
`%am_status`, `%am_error`. Newly configured Apply Profile actions default to
**Wait for final result** when the Tasker host supports synchronous plugin
completion. The service reports final **success/failed** and updates `%am_*`
via Tasker's standard completion intent. An unsupported host, or a pre-R2C
saved action without this option, still receives **queued** immediately; the
final outcome remains available through Event/State and Last Profile Result. The Event/State
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
- Global State observes the most recent operation; filtered State uses
  its profile-specific last observation. Event snapshots are
  identified by Tasker's pass-through message IDs, expire after two minutes,
  and do not replay when Tasker queries at startup. On Tasker hosts that do not
  pass through event message IDs, these events intentionally fail closed.
- Dynamic selection: in an **Apply profile** Action, set **Profile selection**
  to **Use Tasker variable** and enter one complete Tasker variable such as
  `%profile_id`. When the host supports setting variable replacement, the
  Action edit result requests replacement of Bundle key `am.profile` on each
  fire. The resolved string must be an existing App Manager **profile ID**,
  not the human-friendly name. Unresolved references fail with
  `%am_error=unresolved_tasker_variable` (no fallback to another profile).
  A host without variable replacement cannot save a dynamic Action.
- Event and State conditions now have a profile selector. Old saved conditions
  without a profile retain **Any profile** semantics. A removed profile in an
  existing configuration remains explicitly selected as **Missing profile:**
  instead of silently widening the filter.
- Per-profile State stores a bounded (128 profile) local observation snapshot;
  the original unfiltered State still describes the most recent global
  operation. This is not a complete execution history or durable job queue.
- Pending completion callbacks are accepted only after the saved Tasker token
  is checked. Callback URI targets are restricted to the Tasker package,
  and malformed or oversized callback URIs are rejected. A callback delivery
  problem never changes the outcome of the profile operation.
- When the Tasker host offers no valid completion URI, the action returns
  **queued**, rather than hanging indefinitely. The service's final result is
  also published as an Event/State observation.
- Tasker's requested wait timeout is ten minutes; profiles exceeding the host's
  timeout may finish later in App Manager, and the host may ignore a timeout
  request. Android process death can prevent final callback delivery.
- The repository's `android:installLocation` is left untouched for upstream
  compatibility. Keep App Manager installed in internal storage for reliable
  plug-in discovery on devices that allow apps to move to external storage.
- Validation before installation: `python3 scripts/devtool/check_tasker_plugin_contract.py --check`
  and `python3 -m unittest discover -s scripts/devtool -p 'test_*.py' -v`.
  These are **source-only checks**. APK build/test and plugin device acceptance
  must run on GitHub Actions and the user's device, respectively.
- To build through pinned Devtool on GitHub Actions: `./devtoolw ci-build --yes`.
