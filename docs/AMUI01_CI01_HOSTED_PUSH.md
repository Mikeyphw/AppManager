# AMUI-01 CI01 — hosted qualification by feature-branch push

**Source-pinned:** `638d10ba37347a427724ef9792e99599787edbb5` on `feature/amui01-hf01-reissued-20261109`. This is a CI-only correction.

The earlier AMUI-01 commit was pushed with no Actions run because its workflow
only triggered `push` on `feature/tasker-automation`. The workflow is not on
`master`, so `workflow_dispatch` should not be relied on from the feature branch.

CI01 adds explicit AMUI feature-branch push eligibility and runs **full** Devtool
hosted Gradle build, test and lint, API 35 Tasker+AMUI instrumentation, API 36
AMUI installation/update/uninstall tests, and the disposable AMT06C fixture
on each AMUI feature push. Each job is tied to the exact pushed commit.

The source contracts and old `workflow_dispatch` and pull-request behavior remain.
No Tasker or application implementation is changed. No release build is invoked.

**Qualification:** Inspect all jobs, report exact SHA and test/artifact receipts.
A triggered workflow is not a passed workflow. Never claim qualification until
full hosted checks pass at the new commit. AMUI-02 and Tasker remain paused.
