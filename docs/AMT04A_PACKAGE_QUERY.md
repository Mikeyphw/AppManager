# AMT-04A — Native Tasker package query (first package milestone)

**Base:** `Mikeyphw/AppManager`, `feature/tasker-automation` at commit `8ec7b99a6bff7d5dbb37dcd684b00e995d0336f5` (AMT-03 HF05 hosted CI PASS, 2/2 profile-journal instrumentation tests).

This milestone adds a **read-only** Tasker / Locale `Query Package` Action, independent of `Profile Action`. It is additive: it does not expose root/ADB, call App Manager's privileged package engines, accept external shell commands, or authorize package modification. All existing saved profile Action/Event/State configurations remain unchanged. No new Android permission is requested; upstream already declares `QUERY_ALL_PACKAGES`.

## Tasker usage

1. In Tasker add **Action → Plugin → App Manager · Query Package**.
2. Enter a literal package name, e.g. `com.example.app`, or enable **Use Tasker variable** and enter one complete variable, e.g. `%package`, where the Tasker host supports variable replacement.
3. Save. App Manager automatically stores a separate 256-bit read-only query capability in `noBackupFilesDir` and returns it with the saved Tasker configuration; it is not displayed in the UI, logs or output variables.
4. Execute the action as an ordinary Tasker ordered setting broadcast. A package that is not installed is a successful negative query. A malformed/unresolved package, invalid capability, missing permission or unexpected runtime error is an explicit failure.

**Result strings**: `%am_success` (`true` / `false`), `%am_status` (`success` / `failed` / `denied`), `%am_error` (stable code), `%am_package`, `%am_installed` (`true` / `false`), `%am_version_name`, `%am_version_code`, `%am_uid`, `%am_installer`, `%am_enabled`, `%am_system`, and `%am_json`. `%am_json` is structured JSON with equivalent keys, suitable for Tasker's JSON features. An empty metadata string means unavailable; it must not be interpreted as a verified value.

`%am_installed=false` with `%am_success=true` means the queried package is not found / not visible to App Manager. On systems restricting package visibility, this may include hidden packages. Query returns the state visible to App Manager's own `PackageManager`, not an invented root-derived global inventory. The query does not include signatures, app data or private file paths.

## Security, compatibility and limits

- Only a Tasker/Locale-origin editor (`getCallingPackage()` OS value) can save a configuration. Android 14+ receivers additionally reject an identifiable unrelated broadcast sender. The saved query capability is private, no-backup and **distinct from the existing profile capability**. Configuration keys are restricted, versioned and validated on every fire. No arbitrary external API is enabled.
- The profile receiver **ignores query-tagged bundles**, and the package receiver ignores profile bundles; this prevents two receivers registered for `FIRE_SETTING` from processing each other's actions. Existing profile execution remains unchanged.
- Dynamic package names are resolved through Tasker's official `VARIABLE_REPLACE_KEYS` metadata on `am.pkgquery.package`. An unresolved `%variable` yields `%am_error=unresolved_tasker_variable`; no implicit package/default fallback exists. Dynamic mode is unavailable if the host does not advertise substitution support.
- Package queries use Android's ordinary PackageManager calls and app-user context, without a service or background job. No privileged package operations or destructive confirmations are present in this milestone.
- A host that doesn't deliver an **ordered broadcast** cannot receive variable outputs via this interface; device acceptance must verify real Tasker host behavior. Results are not sent to arbitrary callback URIs.

## Qualification

Source-only on Termux:

```sh
python3 -B scripts/devtool/check_amt04_contract.py --check
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s scripts/devtool -p 'test_*.py' -v
```

GitHub Actions (only place to run Gradle / instrumented Android):

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=full
```

The existing API-35 instrumentation class now tests profile journal (2 cases) **and package queries**. The new Robolectric unit tests cover validation, read-only query, not-installed result, separation of capabilities, and structured output. Never treat source-only tests as proof of device compatibility.

**Real device acceptance:** install the new debug APK plus Tasker on Android 16; verify both `App Manager · Profile Action` and `App Manager · Query Package` appear; test literal self-package, a missing package, `%package` substitution, an unresolved variable, existing saved profile tasks, and a denied/untrusted query. Capture Tasker's run log and an APK hash. These runtime host checks remain pending until completed.

## Roadmap still pending

- **AMT-04B:** package enable/disable/freeze/unfreeze/force-stop. Requires a *new* scoped, per-operation authorization gate, backend checks and reliable state capture; no reuse of the profile or read-only query token for privilege escalation.
- **AMT-04C:** qualified external API bridge and richer package queries (signature/permission metadata), with verified caller identity and explicit opt-in.
- **AMT-05+:** backup/restore, permissions, App Ops, components, network/batch operations, UX/settings, broad device qualification.

This is a functional first cut, **not** completion of the original T01–T06 scope.

## HF01 — single Locale receiver interoperability

Tasker exposes separate Profile Action, Query Package and Manage Package editors
(`EDIT_SETTING` activities). Android manifest registers exactly **one**
`FIRE_SETTING` receiver (`TaskerActionReceiver`), which routes saved bundles
to internal package-query or package-manage evaluators after checking the
request type. Capability tokens and authorization are validated independently
by those evaluators. Ambiguous package-action bundles are rejected. There are
no exported package-query or package-management receivers. The original
profile action remains unchanged for older saved Tasker configurations.
