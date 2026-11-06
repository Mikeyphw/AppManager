#!/usr/bin/env python3
"""AMT-00: source-only App Manager/Devtool consumer contract.

Never executes Gradle, Android SDK commands, GitHub dispatch, or privileged code.
Use with Python >=3.11 and a clean checkout (or CI workspace).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRANCH = "feature/tasker-automation"
WORKFLOW = "devtool-tasker-ci.yml"
# Exact canonical wrapper-native set verified against current Mikeyphw/devtool
# wrapper_config.py (inspect/doctor are CLI-only and not wrapper actions).
WRAPPER_NATIVE = frozenset({
    "actions", "build", "clean", "lint", "package", "restore",
    "run", "test", "validate", "wine-validate",
})
TASKS = {
    "build": [":app:assembleDebug"],
    "test": ["test"],
    "lint": [":app:lintDebug"],
    "package": ["packageDebugUniversalApk"],
}


def inspect(root: Path = ROOT) -> dict[str, object]:
    errors: list[str] = []
    def require(ok: bool, msg: str) -> None:
        if not ok:
            errors.append(msg)

    required = (
        "settings.gradle", "versions.gradle", "app/build.gradle", "gradlew",
        "gradle/wrapper/gradle-wrapper.properties", ".devtool.toml",
        "devtoolw", "devtoolw.cmd", ".github/workflows/" + WORKFLOW,
        "scripts/devtool/check_contract.py", "docs/WRAPPER.md",
    )
    for name in required:
        require((root / name).is_file(), "missing: " + name)
    if errors:
        return {"status": "FAIL", "errors": errors}

    try:
        config = tomllib.loads((root / ".devtool.toml").read_text())
    except Exception as exc:
        return {"status": "FAIL", "errors": [f".devtool.toml: {exc}"]}
    project = config.get("project", {})
    target = config.get("targets", {}).get("app", {})
    wrapper = config.get("wrapper", {})
    commands = wrapper.get("commands", {})
    require(project.get("runner") == "android", "[project].runner must be android")
    require(project.get("platform") == "android", "[project].platform must be android")
    require(config.get("workspace", {}).get("default_target") == "app", "default target must be app")
    require(target.get("root") == ".", "app target root must be the upstream Gradle root")
    require(target.get("runner") == "android", "target runner must be android")
    require(target.get("android", {}).get("instrumentation", {}).get("policy") == "disabled", "no implicit device instrumentation")
    require(wrapper.get("schema") == 2, "canonical wrapper schema must be 2")
    require(wrapper.get("default_command") == "source-check", "default command must stay source-only")
    for action, expected in TASKS.items():
        require(target.get("tasks", {}).get(action) == expected, f"unexpected {action} Gradle tasks")
    workflows = target.get("workflows", {})
    require(workflows.get("source-check") == ["job:source_contract", "job:automation_contract"], "source-check must include both contracts")
    require(workflows.get("ci") == ["job:source_contract", "job:automation_contract", "runner:android#build", "runner:android#test", "runner:android#lint"], "CI workflow must preserve Devtool runner actions")
    job = target.get("jobs", {}).get("source_contract", {})
    require(job.get("runner") == "command" and job.get("command") == ["python3", "scripts/devtool/check_contract.py", "--check"], "source contract must be pure Python")

    automation_job = target.get("jobs", {}).get("automation_contract", {})
    require(automation_job.get("runner") == "command" and
            automation_job.get("command") == ["python3", "scripts/devtool/check_automation_contract.py", "--check"],
            "AMT-01 must be a target-local Python source contract")
    for name, mode in (("ci-build", "build"), ("ci-test", "test"), ("ci-lint", "lint"), ("ci-full", "full")):
        action = commands.get(name, {})
        args = action.get("actions", {})
        yes = action.get("options", {}).get("yes", {})
        require(action.get("devtool") == "actions", f"{name} must use Devtool's native Actions API")
        require(args.get("operation") == "dispatch" and args.get("workflow_id") == WORKFLOW, f"{name} dispatch identity drift")
        require(args.get("ref") == BRANCH and args.get("inputs") == {"mode": mode}, f"{name} GitHub ref/mode drift")
        require(yes.get("type") == "boolean" and yes.get("default") is not True and yes.get("command_argv") == ["--yes"], f"{name} must require explicit --yes")
    require(commands.get("source-check", {}).get("workflow") == "source-check", "missing source-check wrapper")
    require(set(commands) == {"source-check", "ci-build", "ci-test", "ci-lint", "ci-full"},
            "unexpected wrapper command; check Devtool's canonical command registry")
    for command_name, spec in commands.items():
        direct = spec.get("devtool")
        if direct:
            require(direct in WRAPPER_NATIVE,
                    f"unsupported canonical wrapper action {command_name}={direct!r}")
        else:
            require(bool(spec.get("workflow")), f"wrapper {command_name} must bind a workflow or native action")
    require("inspect" not in commands and "doctor" not in commands,
            "inspect and doctor must use Devtool CLI directly, not wrapper aliases")

    launcher = (root / "devtoolw").read_text()
    windows_launcher = (root / "devtoolw.cmd").read_text()
    require("DEVTOOL_WRAPPER_LAUNCHER=1" in launcher, "Unix wrapper is not Devtool-generated form")
    require("wrapper exec" in launcher and "exec " in launcher, "Unix wrapper does not delegate to Devtool")
    require("DEVTOOL_WRAPPER_LAUNCHER=1" in windows_launcher, "Windows wrapper is not Devtool-generated form")
    require(os.access(root / "devtoolw", os.X_OK), "devtoolw must be executable")

    dist = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    require("distributionSha256Sum=" in dist and re.search(r"distributionSha256Sum=[a-fA-F0-9]{64}", dist) is not None,
            "upstream Gradle wrapper distribution must be SHA-256-pinned")
    require("services.gradle.org/distributions/gradle-" in dist, "unexpected Gradle distribution origin")
    # GitHub Actions is the only proposed automated Gradle execution surface.
    workflow = (root / ".github/workflows" / WORKFLOW).read_text()
    for keyword in ('workflow_dispatch:', 'android-actions/setup-android@v3',
                    'devtool --repo "$PWD" --target app', 'git merge-tree --write-tree',
                    'actions/upload-artifact@v4'):
        require(keyword in workflow, "hosted workflow missing: " + keyword)
    require("pull_request:" in workflow and "feature/tasker-automation" in workflow,
            "upstream-compatible CI triggers missing")
    require("--release" not in workflow and "assembleRelease" not in workflow,
            "production release build must remain outside initial automation")
    require((root / "docs/DEVTOOL_TASKER.md").is_file(), "missing consumer documentation")
    require("config resolve --json" not in workflow, "invalid Devtool config CLI flag in CI")
    require("config resolve" in workflow, "missing native configuration resolution")
    return {"schema": 1, "repository": "Mikeyphw/AppManager", "target": "app", "branch": BRANCH,
            "status": "PASS" if not errors else "FAIL", "errors": errors, "checked": list(required)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Check contract (no writes)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = inspect()
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print("AMT00_DEVTOOL_CONTRACT_" + result["status"])
        for error in result["errors"]:
            print("- " + error, file=sys.stderr)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
