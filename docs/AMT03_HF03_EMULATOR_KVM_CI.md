# AMT-03 HF03 — Hosted Android emulator KVM acceleration

## Verified failing run

`Mikeyphw/AppManager` workflow_dispatch run [42332595942](https://github.com/Mikeyphw/AppManager/actions/runs/42332595942) on commit `fa10356744dedadb2a8b1a9ce6d2328283762ad9`: the source-contract job and full Android Devtool build/test/lint job passed; the instrumentation emulator failed at *boot* with `Timeout waiting for emulator to boot`, before executing tests. The log shows `disable Linux hardware acceleration: true`, `-accel off`, and KVM permission denied.

## Remediation

- Enable `/dev/kvm` read/write access via the official `reactivecircus/android-emulator-runner` Linux KVM udev recipe before starting the emulator.
- Fail early when `/dev/kvm` is absent or unavailable; never silently use a slow software-emulated x86_64 device.
- Explicitly require `disable-linux-hw-accel: false` and allow 900 seconds for first boot on API 35.
- Preserve HF02's Bash wrapper, pinned Devtool runtime, test count/receipt assertions, and artifact upload paths.
- No Android Java or Tasker runtime source is changed; Android builds still run only on GitHub Actions.

## Verification & limits

Source contract: `python3 -B -m unittest discover -s scripts/devtool -p 'test_amt03_emulator_kvm_contract.py' -v`.

After applying and pushing, dispatch `gh workflow run devtool-tasker-ci.yml -R Mikeyphw/AppManager --ref feature/tasker-automation -f mode=full`.

Do not declare AMT-03 instrumentation PASS until the hosted job boots and executes >=2 tests. The physical Android/Tasker acceptance matrix is still separate.
