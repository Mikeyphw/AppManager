# AMT-01 — Isolated automation protocol and authorization core

## Status
Source implementation prepared. Compile/runtime tests are **not qualified on Android** until a
successful GitHub-hosted `:app:testDebugUnitTest` job runs on the exact commit.

## Source layout
`app/src/main/java/io/github/muntashirakon/AppManager/automation/core` is deliberately
pure Java, independent of Android and upstream package-management services.

- `AutomationRequest`: versioned request envelope; `capabilities.query` or `profile.apply`
  with validated profile ID, state and request ID. No arbitrary commands or package paths.
- `AutomationCaller`: caller identity *only* from a future verified Android/Binder bridge.
  Extras supplied by Tasker are never trusted as proof of identity.
- `AutomationGrant`: per-UID/package/signing-certificate/action/PROFILE-scoped, expiring user grant.
- `AutomationPolicy`: deny-by-default authorization before any executor access.
- `AutomationDispatcher`: an injected grant provider, an atomic replay-guard interface,
  and an execution adapter. No default allow-all implementation is provided.
- `AutomationResult`: minimal result codes and job reference; no sensitive strings.

## Not implemented in AMT-01
No exported Android activity/service/receiver, Tasker plugin UI, external intent parser,
caller identity binder, private grant database, durable replay database, app-ops access,
background service, actual profile execution, or backup/restore. The existing
`AuthFeatureDemultiplexer` and its legacy profile-intent interface remain unchanged.

**Fail-closed rule:** do not export this API until AMT-02 implements and tests verified
caller identity, explicit user consent, private persistent grants, process-restart-safe
request deduplication, Android background execution policy and audit logging.

## Validation
Termux (source only):
```sh
./devtoolw source-check
python3 -m unittest discover -s scripts/devtool -p 'test_*.py' -v
```

GitHub-hosted JUnit (must be explicitly dispatched):
```sh
./devtoolw ci-test --yes
```
The `ci-test` action invokes the repository's existing Devtool GitHub Actions integration.
It must not be run as `gradlew` from Termux.

## Future compatibility
Merging from upstream `master` should not modify upstream `AppManager` classes.
Only fork-owned `.devtool.toml`, `.github/workflows/devtool-tasker-ci.yml`,
`scripts/devtool/check_contract.py`, new `automation/core` sources, new JUnit tests,
and new documentation are changed by AMT-01.
