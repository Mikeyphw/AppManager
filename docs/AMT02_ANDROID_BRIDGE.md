# AMT-02-R1 — Android caller verification and durable authorization infrastructure

**Scope:** App Manager fork, `feature/tasker-automation`. No upstream core files altered.

## Security boundary

- `AndroidCallerVerifier` MUST run within a *real* Binder IPC transaction, never an `Activity` intent lifecycle. `Binder.getCallingUid()` supplies the UID, `getPackagesForUid()` must return exactly one package, and `PackageManager` supplies the **current** APK signing certificate digest. A package string in a Bundle is never trusted as identity.
- Shared UIDs and packages with multiple current signers are temporarily denied, rather than relying on an ambiguous identity mapping.
- `AndroidGrantRepository` stores approved narrow grants in private app files using `AtomicFile`. Grant creation/revocation is UI-only; none is exposed by this phase. Corrupt storage => no grants.
- `AndroidReplayGuard` atomically reserves requests using a SQLite primary key. There is deliberately no automatic pruning or reset that would re-enable old requests.
- `AndroidAutomationIntentParser` rejects unknown Bundle fields and invalid request schema.
- `AndroidAutomationGateway` is a future service integration point. It is **NOT** an exported Android component, does not create grants, and does not provide a privileged executor.
- All access fails closed until the later AMT-02-R2 consent UI, user-facing permission management, actual Binder IPC service and device validation land.

## CI fix

The hosted Android job in run `42204988890` failed before Gradle because `android-actions/setup-android@v3` asked `sdkmanager` for the removed `tools` package. This overlay uses `setup-android@v4` with `packages: 'platform-tools'` and retains the explicit SDK platform/Build Tools/CMake install step.

## Boundaries and next steps

1. Hosted test/build via `./devtoolw ci-test --yes`, then `./devtoolw ci-full --yes`. These execute on GitHub Actions only; *never* run Gradle inside Termux.
2. Approve and revoke grants in authenticated App Manager UI; bind specific packages, signers, operations and profile IDs.
3. Add one explicitly exported, narrowly scoped Binder service with caller UID verification at the immediate transaction boundary, and Tasker plugin UI. Do not create a broadcast or activity authorization shortcut.
4. Instrumentation: caller impersonation, shared-UID refusal, signer changes, reboot/replay persistence, failed storage, consent denial, profile state restoration and Android 12–17.

Until the hosted Android job passes on this commit, Java/Android compilation is **not qualified**. Source-only tests must never be mistaken for device acceptance.
