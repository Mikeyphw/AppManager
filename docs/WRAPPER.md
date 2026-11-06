# App Manager repository wrapper (AMT-00)

This is the required repository-local wrapper guide for Devtool's WRAP final
seal. The canonical command contract is in `.devtool.toml` under `[wrapper]`.
The `devtoolw` and `devtoolw.cmd` launchers are the unmodified, generated
Devtool templates and delegate to the canonical Devtool CLI.

## Source-only local commands

- `./devtoolw source-check` — run source contract validation.
- `devtool --repo "$PWD" --target app inspect` — inspect Android target without building.
- `devtool --repo "$PWD" --target app doctor` — inspect Devtool capabilities;
  missing local Android toolchains may be reported on Termux.
- `devtool --repo "$PWD" config resolve` — JSON configuration, **without**
  an unsupported `--json` option.
- `devtool --repo "$PWD" wrapper verify` — verify launcher identity.
- `devtool --repo "$PWD" wrapper seal` — check wrapper/help/doctor/launcher
  consistency; requires this documentation.

## GitHub-hosted Gradle commands

Use `./devtoolw ci-build --yes`, `ci-test --yes`, `ci-lint --yes`, or
`ci-full --yes` to request GitHub Actions execution through Devtool's typed
actions API. These actions do **not** run Gradle in Termux. A successful
dispatch does not imply the workflow completed successfully.

See `docs/DEVTOOL_TASKER.md` for the full upstream synchronization policy
and GitHub workflow details.
