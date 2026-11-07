# AMT-03 HF04 — API35 hosted instrumentation repair

**Parent commit:** `5e9475bc32cd3e5a27ddab3826e82b7908e812a7`

Observed on run `42380939552`: `/dev/kvm` enabled and emulator booted successfully, but pinned Devtool refused instrumentation because the standalone invocation did not load the AppManager target and required Build Tools/NDK were unresolved. `reactivecircus/android-emulator-runner@v2` then split the multiline `script:` field into individual `sh -c` commands; the multiline Python receipt verification failed with `import: not found`.

HF04 does the following:

- Explicitly installs the exact `ndk;27.0.12077973` matching AppManager AGP 8.13 and keeps `build-tools;36.1.0`, API 36, and CMake 3.22.1.
- Gives android-emulator-runner a **single** `script:` command invoking a tracked Bash file, so it cannot split Python heredocs.
- Creates a temporary, target-aware `.devtool.toml` for hosted instrumentation with explicit SDK, Build Tools, NDK, CMake versions, required policy and debug target package. Restores original config via shell EXIT trap.
- Keeps the committed default instrumentation policy **disabled**, avoiding device runs in local Termux workflows. Uses the pinned Devtool orchestrator and checks its Android environment qualification before building.
- Requires a machine-readable instrumentation receipt with at least 2 executed and passed tests, no failures and returncode 0. Does not waive failed setup or test execution.
- Preserves KVM acceleration, source-only CI, upstream merge checks, hosted build/test/lint and artifact upload.

After applying through Devtool on the exact parent commit, push the branch and dispatch `devtool-tasker-ci.yml` with `mode=full`. Do not claim runtime PASS until hosted instrumentation actually passes. Real Tasker/device acceptance remains separate.
