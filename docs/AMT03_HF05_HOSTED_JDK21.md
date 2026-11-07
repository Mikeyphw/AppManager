# AMT-03 HF05 — Hosted Devtool instrumentation JDK 21

**Source-pinned baseline:** `Mikeyphw/AppManager` `feature/tasker-automation`, commit `c6185d8e574a26cb233faa3e51ed85096fdef771`.

## Observed failure

GitHub Actions full run `42383665406`: Devtool SDK qualification passed, KVM and API-35 emulator boot passed, and instrumentation reached the Android build. The staged Android builder selected `/usr/lib/jvm/temurin-8-jdk-amd64` as its Java runtime, producing `Gradle requires JVM 17 or later to run`. No instrumentation tests ran. The separate hosted Android build/test/lint job uses `--java-home "$JAVA_HOME"` and was not subject to this particular failure.

## Fix

HF05 changes only two hosted instrumentation scripts. `amt03_hosted_config.py` now takes `--java-home` and injects **the GitHub setup-java JDK 21 path** into the *temporary* `[targets.app.android]` target configuration. The existing shell runner passes `--java-home "$JAVA_HOME"` and checks Java's major version before starting Devtool. It also asserts that `load_project_config(... target_name='app')` resolves `android.java_home` to the same path. This uses Devtool's native `android_builder_arguments_for_tasks` bridge, which passes explicit `java_home` to its staged Android builder as `--java-home`. The project's Java 8 source/target compatibility is unchanged.

The runner retains the current strict instrumented-test receipt, SQLite tests, SDK/NDK preflight, KVM, report upload, and restoration trap. No credentials or JDK binaries are stored in this overlay. Normal `.devtool.toml` remains untouched after the job; its default instrumentation policy remains `disabled`.

## Qualification

Local source-only tests cover JDK21 binding, Java 8 rejection, and restoration of the original configuration after both failed and successful preflights. They do **not** run Gradle locally or establish device acceptance. Full hosted CI must be rerun before any `AMT03_FULL_CI_PASS` claim. Physical-device Tasker acceptance is a separate gate.
