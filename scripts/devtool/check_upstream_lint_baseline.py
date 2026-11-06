#!/usr/bin/env python3
"""HF05: exact XML-native Android lint baseline, fail closed on new diagnostics.

HF04 was based on Devtool's 115 *displayed* rows. The actual lint XML has
121 diagnostics: six NewApi errors were emitted twice at distinct source
columns. This module freezes all 121 XML findings, including ID, path,
line, column and message; no lint rule is disabled or hidden.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE_RUN = '42266028816'
SOURCE_SHA = '3e82aff00b18fc2f1d7213b61cd6274a9285c39d'
BASELINE_FILE = Path(__file__).with_name('upstream_lint_baseline_42266028816.json')
Fingerprint = tuple[str, str, int, int, str]


def baseline() -> Counter[Fingerprint]:
    """Load the immutable reviewed snapshot, validating its shape and scope."""
    try:
        data = json.loads(BASELINE_FILE.read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        raise ValueError(f'missing or corrupt pinned baseline: {exc}') from exc
    if (data.get('schema') != 2 or data.get('expected_error_count') != 121
            or data.get('source', {}).get('workflow_run') != int(SOURCE_RUN)
            or data.get('source', {}).get('ref') != SOURCE_SHA):
        raise ValueError('unexpected pinned baseline identity/count')
    records = data.get('findings')
    if not isinstance(records, list) or len(records) != 121:
        raise ValueError('unexpected baseline findings length')
    result: Counter[Fingerprint] = Counter()
    for record in records:
        if (not isinstance(record, list) or len(record) != 5
                or not isinstance(record[0], str) or not record[0]
                or not isinstance(record[1], str) or not record[1].startswith('app/src/')
                or '/automation/' in record[1] or '..' in Path(record[1]).parts
                or not isinstance(record[2], int) or record[2] <= 0
                or not isinstance(record[3], int) or record[3] <= 0
                or not isinstance(record[4], str) or not record[4]):
            raise ValueError(f'invalid baseline record: {record!r}')
        result[tuple(record)] += 1
    if sum(result.values()) != 121 or len(result) != 121:
        raise ValueError('duplicated or missing XML-native baseline findings')
    return result


def relative_file(value: str, root: Path = ROOT) -> str:
    raw = value.replace('\\', '/')
    marker = '/app/src/'
    if marker in raw:
        return 'app/src/' + raw.rsplit(marker, 1)[1]
    path = Path(raw)
    if path.is_absolute():
        try:
            return path.relative_to(root).as_posix()
        except ValueError:
            return raw
    return path.as_posix().removeprefix('./')


def read_error_fingerprints(xml_path: Path) -> Counter[Fingerprint]:
    """Consume actual Android Lint XML, never Devtool's truncated console view."""
    if not xml_path.is_file():
        raise ValueError(f'required fresh Android lint XML report missing: {xml_path}')
    try:
        tree = ET.parse(xml_path)
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f'invalid Android lint report: {exc}') from exc
    if tree.getroot().tag != 'issues':
        raise ValueError('unexpected lint XML root; refusing zero-error assumption')
    result: Counter[Fingerprint] = Counter()
    for issue in tree.getroot().findall('issue'):
        severity = (issue.get('severity') or '').lower()
        if severity not in ('error', 'fatal'):
            continue
        issue_id, message = issue.get('id'), issue.get('message')
        locations = issue.findall('location')
        if not issue_id or not message or not locations:
            raise ValueError('error diagnostic missing ID, message or source location')
        location = locations[0]
        file, line_str, column_str = location.get('file'), location.get('line'), location.get('column')
        if not file or not line_str or not column_str:
            raise ValueError(f'incomplete lint location for {issue_id}')
        try:
            line, column = int(line_str), int(column_str)
        except ValueError as exc:
            raise ValueError(f'non-numeric lint location for {issue_id}') from exc
        if line <= 0 or column <= 0:
            raise ValueError(f'non-positive lint location for {issue_id}')
        result[(issue_id, relative_file(file), line, column, message)] += 1
    return result


def check(report: Path) -> tuple[bool, str]:
    expected = baseline()
    actual = read_error_fingerprints(report)
    new = actual - expected
    resolved = expected - actual
    rows = [
        'AMT02_LINT_BASELINE_' + ('PASS' if not new else 'FAIL'),
        f'Source: hosted XML run {SOURCE_RUN}, commit {SOURCE_SHA}',
        f'Accepted existing XML diagnostics: {sum((actual & expected).values())}/{sum(expected.values())}',
        f'New or drifted diagnostics: {sum(new.values())}',
        f'Resolved existing diagnostics: {sum(resolved.values())}',
    ]
    for (issue_id, path, line, column, message), count in sorted(new.items()):
        rows.append(f'NEW_LINT_ERROR {issue_id} {path}:{line}:{column} count={count} message={message[:160]}')
    return not new, '\n'.join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--summary', type=Path)
    args = parser.parse_args()
    try:
        ok, report = check(args.report)
    except ValueError as exc:
        ok, report = False, 'AMT02_LINT_BASELINE_FAIL\n' + str(exc)
    print(report)
    if args.summary:
        with args.summary.open('a', encoding='utf-8') as stream:
            stream.write('\n### App Manager upstream lint baseline\n\n```text\n' + report + '\n```\n')
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
