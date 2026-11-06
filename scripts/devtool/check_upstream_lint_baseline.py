#!/usr/bin/env python3
"""R2C HF04: fail on NEW lint errors; acknowledge only the exact legacy set.

Source of known findings: hosted full R2C run 42255012506, at commit
7f9de1a58e5f61cb7d4296cf5171acbf5b09c33e. Gradle :app:lintDebug
succeeded but Devtool's result parser marked the 115 pre-existing lint errors
as a failed run. This verifier checks actual fresh Android Lint XML output.

No issue ID, path or package is globally disabled. Adding a finding even in
an already-affected upstream file fails CI unless the baseline is reviewed.
The baseline deliberately pins source line numbers, so upstream line shifts
require an explicit update instead of silently accepting new diagnostics.
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE_RUN = "42255012506"
SOURCE_SHA = "7f9de1a58e5f61cb7d4296cf5171acbf5b09c33e"

# Error ID + exact repository-relative path -> matching one-based source lines.
# This list contains no Tasker or AMT automation source diagnostics.
KNOWN = {'RecyclerView|app/src/main/java/io/github/muntashirakon/AppManager/viewer/pdf/PdfPageAdapter.java': [59], 'NewApi|app/src/main/java/io/github/muntashirakon/AppManager/compat/ActivityManagerCompat.java': [81], 'NewApi|app/src/main/java/io/github/muntashirakon/AppManager/details/AppDetailsOverlaysFragment.java': [153, 154, 155, 156, 157, 158], 'NewApi|app/src/main/java/io/github/muntashirakon/AppManager/utils/ArrayUtils.java': [611, 612], 'NewApi|app/src/main/java/io/github/muntashirakon/AppManager/viewer/font/FontLoadController.java': [112, 114, 116], 'WrongThread|app/src/main/java/io/github/muntashirakon/AppManager/details/info/AppInfoFragment.java': [740], 'AppCompatResource|app/src/main/res/menu/activity_virus_total_actions.xml': [7, 11], 'AppLinkUrlError|app/src/main/AndroidManifest.xml': [327, 331, 334, 336, 338, 340, 342, 344, 347, 350, 353, 356, 359, 362, 365, 368, 371, 374, 377, 380, 383, 386, 389, 392, 395, 398, 401, 404, 407, 410, 413, 416, 419, 422, 804, 1279, 1279, 1478], 'ForegroundServiceType|app/src/main/java/io/github/muntashirakon/AppManager/adb/AdbPairingService.java': [174, 215], 'ForegroundServiceType|app/src/main/java/io/github/muntashirakon/AppManager/viewer/audio/AudioPlayerService.java': [743, 745], 'ForegroundServiceType|app/src/main/java/io/github/muntashirakon/AppManager/scanner/vt/VtScanService.java': [83], 'FullBackupContent|app/src/main/res/xml/backup_rules.xml': [3, 4, 5, 15, 16, 17], 'FullBackupContent|app/src/main/res/xml/full_backup_rules.xml': [2, 3, 4], 'RestrictedApi|app/src/main/java/io/github/muntashirakon/AppManager/misc/AdvancedSearchView.java': [80], 'RestrictedApi|app/src/main/java/io/github/muntashirakon/AppManager/main/MainActivity.java': [560], 'MissingQuantity|app/src/main/res/values-cs-rCZ/strings.xml': [105, 147, 320, 326, 349, 354, 359, 364, 429, 434, 439, 444, 449, 454, 459, 472, 522, 527, 587, 592, 598, 603, 608, 613, 618, 623, 628, 638, 643, 648, 655, 660, 666], 'ByteOrderMark|app/src/main/res/values-lv/strings.xml': [102, 116], 'HardcodedText|app/src/main/res/layout/dialog_audio_player.xml': [131], 'HardcodedText|app/src/main/res/layout/dialog_change_file_mode.xml': [30, 37, 44, 60, 91, 122, 148, 155, 162]}
assert sum(len(v) for v in KNOWN.values()) == 115


def baseline() -> Counter[tuple[str, str, int]]:
    result: Counter[tuple[str, str, int]] = Counter()
    for compound, lines in KNOWN.items():
        issue_id, file = compound.split("|", 1)
        for line in lines:
            result[issue_id, file, line] += 1
    return result


def relative_file(value: str, root: Path = ROOT) -> str:
    # Android Lint's XML location is absolute on a GitHub Actions runner.
    raw = value.replace("\\", "/")
    marker = "/app/src/"
    if marker in raw:
        return "app/src/" + raw.rsplit(marker, 1)[1]
    p = Path(raw)
    if p.is_absolute():
        try:
            return p.relative_to(root).as_posix()
        except ValueError:
            return raw
    return p.as_posix().lstrip("./")


def read_error_fingerprints(xml_path: Path) -> Counter[tuple[str, str, int]]:
    if not xml_path.is_file():
        raise ValueError(f"required fresh Android lint XML report missing: {xml_path}")
    try:
        document = ET.parse(xml_path)
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f"invalid Android lint report: {exc}") from exc
    result: Counter[tuple[str, str, int]] = Counter()
    if document.getroot().tag != "issues":
        raise ValueError("unexpected lint XML root; refuse to assume zero errors")
    for issue in document.getroot().findall("issue"):
        severity = (issue.get("severity") or "").lower()
        if severity not in ("error", "fatal"):
            continue
        issue_id = issue.get("id") or ""
        location = issue.find("location")
        if location is None or not location.get("file") or not location.get("line"):
            raise ValueError(f"missing source location for {issue_id!r}; cannot baseline")
        try:
            line = int(location.get("line", ""))
        except ValueError as exc:
            raise ValueError(f"non-numeric lint location for {issue_id!r}") from exc
        if line <= 0:
            raise ValueError(f"invalid source location for {issue_id!r}")
        result[issue_id, relative_file(location.get("file", "")), line] += 1
    return result


def check(report: Path, root: Path = ROOT) -> tuple[bool, str]:
    actual = read_error_fingerprints(report)
    recorded = baseline()
    new = actual - recorded
    resolved = recorded - actual
    rows = [
        f"AMT02_LINT_BASELINE_{'PASS' if not new else 'FAIL'}",
        f"Source: hosted run {SOURCE_RUN}, commit {SOURCE_SHA}",
        f"Accepted legacy findings: {sum((actual & recorded).values())} / {sum(recorded.values())}",
        f"New or drifted findings: {sum(new.values())}",
        f"Resolved existing findings: {sum(resolved.values())}",
    ]
    for (issue_id, path, line), count in sorted(new.items()):
        rows.append(f"NEW_LINT_ERROR {issue_id} {path}:{line} count={count}")
    return not new, "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        ok, report = check(args.report)
    except ValueError as exc:
        ok, report = False, f"AMT02_LINT_BASELINE_FAIL\n{exc}"
    print(report)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as stream:
            stream.write("\n### App Manager legacy lint baseline\n\n```text\n" + report + "\n```\n")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
