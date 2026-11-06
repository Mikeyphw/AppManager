# App Manager × Devtool — AMT-00 consumer setup

This file documents the isolated Devtool consumer integration on
`feature/tasker-automation`. The source of truth for Android package-management
logic remains MuntashirAkon/AppManager (`master`).

## Repository identity and compatibility

- Fork: `Mikeyphw/AppManager`, branch `feature/tasker-automation`.
- Upstream: `MuntashirAkon/AppManager`, branch `master`.
- Devtool: `Mikeyphw/devtool`, pinned in GitHub Actions to
  `bee6e0b7e49aaaa0c56f755fd5be434a27c52b6c` (review and update deliberately).
- Baseline App Manager ancestor at creation: `dc4154f4c04399602895f31f9390bb66b25ea8a0`.
- Do **not** change upstream code, build scripts, Gradle wrapper, or core modules
  for this AMT-00 integration. Later Tasker code belongs in a scoped package.
- Preserve the original `master` branch as an upstream mirror; merge upstream
  into the Tasker feature branch. Never rewrite published branch history.

## Termux / Zsh source-only commands

```zsh
cd "$HOME/Code/AppManager"
git switch feature/tasker-automation
python3 scripts/devtool/check_contract.py --check
python3 -m unittest discover -s scripts/devtool -p 'test_*.py' -v
./devtoolw source-check
devtool --repo "$PWD" --target app inspect
```

`./devtoolw` is a **Devtool-managed** thin launcher. It resolves the repository
root and delegates to the canonical `devtool --repo ... wrapper exec` command.
It does not implement a new runner/scheduler. The wrapper's default command is
safe (`source-check`). The `inspect` and `doctor` actions are supported by the canonical Devtool CLI,
**not** by schema-2 repository wrapper bindings. Use `devtool --repo "$PWD"
--target app inspect` or `devtool --repo "$PWD" --target app doctor` directly.
These read-only checks may report missing local Android toolchains on Termux.

If Devtool is not installed as an executable, use your local Devtool source:

```zsh
cd "$HOME/Code/AppManager"
PYTHONPATH="$HOME/Code/devtool${PYTHONPATH:+:$PYTHONPATH}" \
  python3 -m devtool --repo "$PWD" wrapper verify
```

`rich` and `PyYAML` are the minimal Devtool runtime dependencies. Do **not**
run Gradle/Android build, test, lint, package, or full validate on native
Termux. Those operations are assigned to hosted Ubuntu GitHub Actions below.

## Native Devtool CI dispatch — exact ref, explicit authorization

Run after the integration commit has been pushed, with `gh auth` configured
for the fork's GitHub Actions permissions:

```zsh
cd "$HOME/Code/AppManager"
./devtoolw ci-build --yes    # GitHub-hosted :app:assembleDebug
./devtoolw ci-test --yes     # GitHub-hosted Gradle test
./devtoolw ci-lint --yes     # GitHub-hosted :app:lintDebug
./devtoolw ci-full --yes     # GitHub-hosted Devtool workflow DAG
```

These are **typed Devtool Actions dispatch wrappers**, not untracked `gh`
subprocesses. Devtool validates the workflow, requires `--yes`, binds the
source HEAD, and writes a request ID. A successful dispatch **does not**
prove the remote job succeeded. Use Devtool's `actions review` and `actions
collect` with the exact request ID and artifact name before importing any
result; never guess the latest run.

Fallback for manual dispatch if the Devtool GitHub provider is unavailable:

```zsh
gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager \
  --ref feature/tasker-automation -f mode=full
gh run list -R Mikeyphw/AppManager --workflow devtool-tasker-ci.yml --limit 5
```

The fallback is a GitHub CLI operation, not an assertion that Devtool has
validated or correlated the request.

## Hosted workflow

`.github/workflows/devtool-tasker-ci.yml` runs:

1. Python source-only and negative contract checks.
2. A non-mutating Git `merge-tree` check against upstream `master`.
3. Native Devtool config/launcher/contract/seal checks using a frozen Devtool
   source commit. This is a source-only job.
4. **Only on dispatch or pull request:** install Java 21, Android SDK 36,
   build-tools 36.1.0, and delegate Android work to the native Devtool runner.
5. Upload debug APKs with an SHA-256 manifest, if produced. These are debug
   artifacts, **not production release builds**, and are not yet qualified for
   device installation or release distribution.

Gradle configuration is multi-module and includes Android native/JNI work.
CI can fail due to network/download/toolchain/provisioning issues; source-only
validation is not evidence that a real hosted APK was built. The build and
signature paths must be verified on the first green hosted run.

## Known restrictions and roadmap next steps

- AMT-00: configuration, wrapper, GitHub Actions, upstream merge safety.
- AMT-01: secure internal automation request dispatcher + scoped identity.
- AMT-02: native Tasker actions, respecting existing App Manager services.
- AMT-03: structured job IDs and completion events; then privileged operations.
- No blanket root access is exported to Tasker.
- No default release signing, uninstall, freeze, permission edit, or device
  modification is introduced by this integration.
- Do not add Devtool features to App Manager's core module simply to make
  the wrapper work; prefer target-local config and scripts.

## Routine upstream sync

```zsh
cd "$HOME/Code/AppManager"
# First verify a clean tree; do not reset/discard work.
git status --short
git fetch upstream master
git switch master
git merge --ff-only upstream/master
git push origin master
git switch feature/tasker-automation
git merge master
git push origin feature/tasker-automation
```

Conflicts stop the merge and require resolution. The CI merge-tree check is
non-mutating and does not automatically merge or push upstream changes.

## Devtool source references

- `Mikeyphw/devtool/docs/CONFIGURATION.md`
- `Mikeyphw/devtool/docs/WRAPPER.md`
- `Mikeyphw/devtool/docs/DEVTOOL_ACTIONS_ADOPTION.md`
- `Mikeyphw/devtool/devtool/wrapper_config.py`
- `Mikeyphw/devtool/devtool/resources/wrapper/devtoolw`
