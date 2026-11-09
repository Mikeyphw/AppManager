#!/usr/bin/env python3
"""AMUI-01 CI01: branch push qualifies at exact commit, retaining Tasker gates."""
from pathlib import Path

PUSH="(github.event_name == 'push' && startsWith(github.ref, 'refs/heads/feature/amui01'))"
ORIGINAL_AMT="inputs.mode == 'full' || inputs.mode == 'instrumentation'"
ORIGINAL_16="inputs.mode == 'instrumentation' || inputs.mode == 'full'"

def must(test, message):
    if not test: raise AssertionError('AMUI01_CI01_REFUSED: '+message)

def validate_workflow(src):
    must("branches: [feature/tasker-automation, 'feature/amui01*']" in src,
         'AMUI-01 feature branch does not trigger push workflow')
    must(src.count(PUSH) == 6, 'unexpected number of explicit hosted push predicates')
    must(src.count(ORIGINAL_AMT) == 1, 'legacy Tasker full/instrumentation gate duplicated or lost')
    must(src.count(ORIGINAL_16) == 1, 'Android16 full/instrumentation gate duplicated or lost')
    must(src.count('disable-linux-hw-accel: false') == 1, 'Tasker KVM gate lost or duplicated')
    must(src.count("disable-linux-hw-accel: 'false'") == 1, 'Android16 KVM gate lost or duplicated')
    must("github.event_name == 'pull_request' && 'full' || (github.event_name == 'push' && 'full') || inputs.mode || 'source'" in src,
         'push event would use unsupported source-only Gradle mode')
    before_build, after_build = src.split('  hosted-android:\n',1)
    android, rest = after_build.split('  device-instrumentation:\n',1)
    legacy, rest = rest.split('  amt06c-fixture:\n',1)
    fixture, api36 = rest.split('  amui01-android16:\n',1)
    must(PUSH in android.split('    runs-on:',1)[0], 'hosted Gradle not qualified on AMUI push')
    must(PUSH in legacy.split('    runs-on:',1)[0], 'Tasker API35 instrumentation skipped on AMUI push')
    must(PUSH in fixture.split('    runs-on:',1)[0], 'Tasker disposable fixture skipped on AMUI push')
    must(PUSH in api36.split('    runs-on:',1)[0], 'API36 instrumentation skipped on AMUI push')
    must('api-level: 35' in legacy and 'api-level: 36' in api36, 'Android API scopes changed')
    must('script: bash scripts/devtool/amt03_hosted_instrumentation.sh && bash scripts/devtool/amui01_hosted_instrumentation.sh' in legacy,
         'Tasker and package tests not chained on API35')
    must('script: AMUI01_EXPECT_API=36 bash scripts/devtool/amui01_hosted_instrumentation.sh' in api36,
         'API36 package lifecycle script lost')
    must(PUSH in android and 'Upload hosted Android lint report' in android, 'lint evidence not retained')
    must("if: github.event_name == 'pull_request' || (github.event_name == 'workflow_dispatch'" in android,
         'original hosted full mode weakened')
    must("if: github.event_name == 'workflow_dispatch' && (" in legacy,
         'legacy manual instrumentation trigger removed')
    must("if: github.event_name == 'workflow_dispatch' && (" in api36,
         'Android16 manual instrumentation trigger removed')
    return True

if __name__=='__main__':
    root=Path(__file__).resolve().parents[2]
    validate_workflow((root/'.github/workflows/devtool-tasker-ci.yml').read_text())
    print('AMUI01_CI01_SOURCE_CONTRACT_PASS')
