# AMT-06C1 — guarded physical Android fixture acceptance

## Scope, qualifications, and limits

This milestone supplies a hosted-built, independently installable disposable debug fixture (`dev.mikeyphw.amt06c.fixture`), a physical-device ADB test harness, isolated restore-journal negative instrumentation tests, and strict source contracts. **The harness does not invoke backup, restore, uninstall, root, or application-data clear.** A human must explicitly invoke AMT-06A backup and AMT-06B fixed-snapshot internal-data restore in Tasker, **only for the disposable fixture**, after reviewing the risks. Native restore clears the fixture's existing data before restoring; on native failure this may be irrecoverable.

Existing authorization, cryptographic checks, source gates, lint baseline and real-app restrictions are unmodified. `native_completed` is **not** a guarantee of functional restoration; AMT-06C's host-side evidence may report `restored_verified` only after an independent marker readback.

**CI qualification and physical-device qualification are separate.** This overlay is source-only. Do not call AMT-06C device tests passed merely because the Python mocks, Android Gradle build, or emulator instrumentation pass. No production app is a valid test target. A different Android user, system app, reinstalled/repackaged fixture or changed UID/version/install timestamp must fail closed.

## Hosted qualification

On `feature/tasker-automation`, run these after the consolidated overlay is applied and pushed:

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=instrumentation
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=build
```

The emulator gate preserves all previous thresholds, and requires at least **30 actually executed and passed instrumentation tests**, zero failures, successful report and actual exit code 0. The build mode now creates an additional separate, downloadable `appmanager-amt06c-fixture-*` artifact with an **unsigned-for-release debug fixture APK**, package-name check, and SHA-256 manifest. Normal pushes remain source-only. `mode=test` does not build the fixture. This fixture is not included in AppManager's normal Gradle project unless `-Pamt06cFixture=true` is passed on the hosted CI job.

## Physical-device procedure (explicit/manual)

Prerequisites: an ADB-enabled physical Android phone in **primary user 0**, a disposable and newly installed AMT-06C fixture APK from hosted Actions, AppManager with privilege support properly configured, Tasker integration, and a new private host-side case directory outside the repo. Keep an additional independent copy of test state; the harness creates a private escrow proof. Verify the debug fixture's APK checksum against the hosted manifest, then install the fixture with `adb -s "$ANDROID_SERIAL" install ...` yourself. Never use `pm clear` on an app you care about.

1. Read-only preflight, which checks installed third-party package name, UID, version, installation timestamp, primary user, actual `run-as` UID, and **rejects emulators**:

```zsh
python3 scripts/devtool/amt06c_device_acceptance.py preflight --serial "$ANDROID_SERIAL"
```

2. Seed **only** the debug fixture's `files/amt06c_proof.txt` with a synthetic marker; escrow it on the host. This is not an AppManager backup:

```zsh
CASE="$HOME/.local/state/appmanager/amt06c/disposable-001"
python3 scripts/devtool/amt06c_device_acceptance.py seed --serial "$ANDROID_SERIAL" --case-dir "$CASE" --ack I_ACCEPT_DISPOSABLE_AMT06C_FIXTURE
```

3. Manually create an AMT-06A `backup_data` Tasker action for **exactly** `dev.mikeyphw.amt06c.fixture`. Wait for its native Query Package receipt to report `succeeded`, `verified=true`, `type=data` and note its `Tasker-UUID` backup name. Retain a separate copy of the disposable marker escrow. Export a minimal JSON object (e.g. `backup-receipt.json`) containing `%am_package`, `%am_backup_request_name`, `%am_backup_request_type`, `%am_backup_request_status`, `%am_backup_request_verified`. Do **not** paste secret app data, archive paths or any tokens into this JSON.

4. Only after the backup receipt succeeds, mutate the synthetic marker:

```zsh
python3 scripts/devtool/amt06c_device_acceptance.py mutate --serial "$ANDROID_SERIAL" --case-dir "$CASE" --receipt backup-receipt.json --ack I_ACCEPT_DISPOSABLE_AMT06C_FIXTURE
```

5. **Manual, destructive, disposable-fixture-only action:** configure the AMT-06B editor for exactly the fixed `Tasker-UUID` from step 3, with the fixture UID and first installation timestamp captured by the editor. Execute restore in Tasker yourself; **do not automate or retry**. If you do not want to lose the fixture's mutated data, do not perform this step. For failed/unknown receipts, preserve evidence and stop; never claim success or auto-retry.

6. Export a minimal Query Package receipt (e.g. `restore-receipt.json`) with `%am_package`, `%am_restore_request_id`, `%am_restore_request_status`, `%am_restore_request_verified`, and `selected_backup_name` (the exact `Tasker-UUID` chosen in the editor, manually recorded; this is not cryptographically authenticated by the harness). Only native status `native_completed` and `verified=false` are accepted for independent verification. Check that the original marker (not the mutated marker) exists again:

```zsh
python3 scripts/devtool/amt06c_device_acceptance.py verify --serial "$ANDROID_SERIAL" --case-dir "$CASE" --receipt restore-receipt.json
```

Exit 0 plus `restored_verified` means **this fixture marker** roundtrip was independently observed, not that arbitrary app data, encrypted databases, partial-write failures, rollback, or production apps have been qualified. Exit 1 means native completion but readback mismatched. Exit 2 means fail-closed (missing/invalid evidence or identity). The private case file records both receipt status and independent SHA-256 marker comparison. The harness never declares Android physical acceptance based on synthetic Python mocks.

## Outstanding AMT-06C2 items

End-to-end hosted/device execution and actual receipts, developer-controlled failure injection, signing mismatch/reinstall/other-user/archive deletion/crypto revocation, service process death, separate unaffected fixture app validation, and full-release gates remain **unqualified** until verified on real devices. No automatic native restore rollback or retry is implemented or implied. Evaluate these after passing emulator and fixture build gates. The physical test run cannot be initiated from GitHub Actions on your local phone.
