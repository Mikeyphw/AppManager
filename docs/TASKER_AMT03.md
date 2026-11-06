# AMT-03 R3A–R3D — Tasker profile execution reliability

**Source baseline:** `Mikeyphw/AppManager`, `feature/tasker-automation`, commit `f527013ffd373972ad62bf1ef6b395f09c2c0834`.

This is an additive development overlay. It does not change the Tasker/Locale grant-free capability scheme, expose the profile service, or grant Tasker new root/ADB capabilities. Profiles still run using App Manager's existing `ProfileManager` APIs and privileges. It preserves R2D's fixed/dynamic profile selection, Event/State filters, existing saved Tasker configurations, and `%am_*` variable names.

## R3A — Persistent execution history

- `TaskerExecutionJournal` is a no-backup, app-private SQLite table, with unique 32-character request IDs, selected profile ID/state, timestamps (epoch milliseconds), route (`direct` / `job`), structured error, and terminal outcome.
- Terminal rows are bounded to the latest 256 **terminal transitions**, using a monotonic terminal sequence rather than creation-time ordering. Queued/running rows are **never** pruned to keep duplicate prevention and cancellation correct.
- **Task → Plugin → App Manager · Profile Action → Recent execution history** returns up to 20 records in `%am_history` (JSON) and the newest record in `%am_request_id`, `%am_profile_id`, `%am_state`, `%am_status`, `%am_error`, `%am_created_at`, `%am_started_at`, `%am_finished_at`.
- The capability token is never included in the output or journal. The callback URI, if present, is stored only in the private table to deliver cancellation outcomes safely.
- A crash during active application is recorded as `interrupted` with `process_died_outcome_unknown` on next process start. A direct queued request lost on process death is marked `interrupted`/`process_died_before_start`; a scheduled request older than ten minutes whose OS job disappeared is marked `interrupted`/`scheduled_job_missing_outcome_unknown` on the next process initialization. Neither is retried automatically. Reconciliation also clears matching stale Tasker State `running` observations without inventing a successful or failed Event. An interrupted request may have partially applied profile changes. App Manager does not promise rollback.

## R3B — Safe cancellation

- **Task → Plugin → App Manager · Profile Action → Cancel queued request** accepts a literal 32-character request ID or (where the Tasker host advertises substitution) `%am_request_id` or another full Tasker variable.
- Cancellation and the worker's start claim use conditional SQLite updates. Cancelling an old queued request produces a durable recent terminal result even when many later-created operations have finished; cancellation, queued notifications and dispatch failures cannot overwrite a different active operation's global or per-profile running observation, including for the same profile. Queued notifications first verify the authoritative journal status. Only `queued` or `scheduled` requests can become `cancelled`.
- `cancelled` returns `%am_status=cancelled`; unknown, completed and already-running requests fail explicitly (`unknown_request`, `already_terminal`, `already_running`).
- A scheduled job is cancelled by the OS only if its ID and saved request identity match. An already-started profile is **not interrupted**, because package/profile operations may not be reversible or interruptible.
- The original Tasker action's validated completion callback, if available, receives the cancellation result; no arbitrary third-party callback target is permitted.

## R3C — Android background fallback

- The normal foreground-service dispatch remains the first attempt. Unrelated security or permission failures are **not** routed through JobScheduler. JobService cannot simply launch the same foreground service when Android explicitly forbids that start, so the fallback calls the **same existing ProfileManager operation engine** directly while serialized with ProfileApplierService. ProfileApplierService remains the foreground path; neither path defines another profile-application algorithm. When Android specifically rejects starting that service with `ForegroundServiceStartNotAllowedException` on Android 12+, App Manager schedules a private `JobScheduler` job (the JobService is exported **only for Android system binding** and protected by the signature-level `android.permission.BIND_JOB_SERVICE`; ordinary third-party apps cannot bind).
- The JobScheduler path revalidates the saved profile ID and loads the current profile from disk at execution time. The job and existing foreground service share a process-wide execution lock.
- Android may defer a scheduled job. Exact execution time is not guaranteed. A job that has not started can survive application process death, but **not device reboot** (`setPersisted(false)`).
- A request is claimed once. Neither Android job stop nor a killed process triggers automatic re-application after any possible side effect. The retry policy for a claimed request is **zero automatic retries**; start failures may fall back from direct launch to one scheduled job. This favors at-most-once execution over accidental repeated package modifications.
- Calling `%am_status=queued` after scheduling means accepted for OS scheduling, **not executed**. The final Event/State or callback is authoritative.
- On Android versions with special restrictions, JobScheduler can delay or stop work; manual device acceptance remains necessary. Profile application through a scheduled job uses the existing `ProfileManager` without a visible progress handler, rather than creating a new foreground-service privilege exemption.

## R3D — Qualification and device acceptance

### Source and hosted CI

The overlay validator disables Python bytecode writing to avoid untracked `__pycache__` residue in Termux.

```sh
python3 scripts/devtool/check_tasker_plugin_contract.py --check
python3 scripts/devtool/check_amt03_contract.py --check
python3 -m unittest discover -s scripts/devtool -p 'test_*.py' -v
```

Use the established workflow `.github/workflows/devtool-tasker-ci.yml` on the branch, then manually dispatch `mode=full`. The hosted flow runs source contracts, upstream merge-tree checks, Devtool build/test/lint, artifact upload, **and** a separate API-35 emulator job running Android instrumentation through pinned Devtool. New Robolectric unit tests cover SQLite cancellation, duplicate claiming and history output. Emulator instrumentation covers queued cancellation and exclusive claim. No Gradle execution is required on Termux.

### Real Tasker + Android 16 device acceptance matrix

**Do not claim AMT-03 runtime acceptance based on hosted CI alone.** On the user's real Android device, with Tasker and App Manager debug installed:

1. Create two harmless test profiles, `A` and `B`, with a reversible non-destructive setting. Do not use profiles that alter critical services or erase app data.
2. Apply `A` using a fixed ID. Verify `%am_request_id` (32 lowercase hex), `%am_status`, final callback (when available), and the corresponding Event/State profile filter. Verify success is recorded in Recent execution history.
3. Apply `B` using a Tasker variable containing its exact App Manager profile **ID**, not its display name. Verify `A` does not run and that unresolved `%variable` fails closed.
4. Start a long-running **harmless** queued profile behind another operation. Cancel its request ID. Verify it never starts; `%am_status=cancelled`, history is terminal, and there are no duplicate completion notifications.
5. Attempt to cancel an actively applying profile. Verify it returns `already_running`; application is not interrupted and final result is recorded normally.
6. Force a foreground-service start restriction with the app in background. Verify `queued` can represent scheduled delivery; wait for final Event/State/callback, and ensure the journal reports `route=job`. Do **not** infer success merely because scheduling succeeded.
7. Force-stop/kill the app *before* scheduled execution only in a safe test scenario. Check the pending job and history after relaunch. A force-stop may clear OS scheduled jobs; the app does not promise survival across a user force-stop or reboot.
8. Kill the process during an intentionally harmless long-running job. Verify it is eventually marked `interrupted`/`process_died_outcome_unknown` and is not silently replayed.
9. Verify previous R2D Tasker tasks still function without reconfiguration, and run an upstream merge-tree compatibility check.
10. Capture `adb logcat`, Tasker's run log, and the history JSON. Record the APK checksum and exact installed version before declaring device PASS.

**Stop conditions:** duplicate profile effects, unexpected privilege changes, a scheduled job executing after successful cancellation, any callback routed outside Tasker, or a falsely reported final `success`.

### Suggested hosted command

From the existing Termux checkout (with `gh` already configured), after applying and committing the overlay:

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=full
```

Only report `AMT03_FULL_CI_PASS` after **all** required hosted jobs finish successfully, and `AMT03_DEVICE_PASS` only after the real-device matrix is completed.
