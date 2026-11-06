#!/usr/bin/env python3
"""HF05 offline tests for actual Android Lint XML semantics (no Gradle)."""
import json
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from check_upstream_lint_baseline import BASELINE_FILE, baseline, check, read_error_fingerprints


def fixture(path, records):
    root = ET.Element('issues', format='6')
    for issue_id, file, line, column, message, severity in records:
        i = ET.SubElement(root, 'issue', id=issue_id, severity=severity, message=message)
        ET.SubElement(i, 'location', file='/home/runner/work/AppManager/AppManager/' + file,
                      line=str(line), column=str(column))
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


class XmlBaselineTest(unittest.TestCase):
    def test_real_snapshot_has_121_errors_on_114_distinct_lines(self):
        records = baseline()
        self.assertEqual(sum(records.values()), 121)
        self.assertEqual(len(records), 121)
        self.assertEqual(len({(i, p, line) for i, p, line, col, msg in records}), 114)
        self.assertEqual(sum(1 for i,p,line,col,msg in records if i=='NewApi' and
            p.endswith('/AppDetailsOverlaysFragment.java')), 12)

    def test_full_frozen_snapshot_passes(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d)/'lint.xml'
            fixture(report, [(*x, 'Error') for x in baseline().elements()])
            ok, details = check(report)
            self.assertTrue(ok, details)
            self.assertIn('121/121', details)

    def test_duplicate_same_line_different_column_is_not_new(self):
        dupe = [(i,p,line,col,msg) for i,p,line,col,msg in baseline()
                if i=='NewApi' and p.endswith('/AppDetailsOverlaysFragment.java') and line == 153]
        self.assertEqual(len(dupe), 2)
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report, [(*x,'Error') for x in dupe])
            self.assertTrue(check(report)[0])

    def test_third_duplicate_or_different_column_fails_closed(self):
        point = next(x for x in baseline() if x[0] == 'NewApi' and
                     x[1].endswith('/AppDetailsOverlaysFragment.java'))
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report, [(*point,'Error'), (*point,'Error')])
            self.assertFalse(check(report)[0])
            altered=(*point[:3], point[3]+1000, point[4])
            fixture(report, [(*altered,'Error')])
            self.assertFalse(check(report)[0])

    def test_message_change_detected(self):
        point = next(iter(baseline()))
        altered=(*point[:4], point[4]+' new issue')
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report,[(*altered,'Error')])
            self.assertFalse(check(report)[0])

    def test_new_tasker_error_fails(self):
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report,[('NewApi','app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerActionReceiver.java',53,12,'New Tasker error','Error')])
            ok, msg=check(report)
            self.assertFalse(ok)
            self.assertIn('NEW_LINT_ERROR',msg)

    def test_resolved_previous_findings_allowed(self):
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report,[])
            self.assertTrue(check(report)[0])

    def test_warnings_are_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            fixture(report,[('Random','app/src/main/res/values/strings.xml',1,2,'warning','Warning')])
            self.assertTrue(check(report)[0])

    def test_missing_xml_report_and_column_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'lint.xml'
            with self.assertRaises(ValueError): read_error_fingerprints(report)
            report.write_text('<foo/>')
            with self.assertRaises(ValueError): read_error_fingerprints(report)
            report.write_text('<issues><issue id="NewApi" severity="Error" message="x"><location file="app/src/A.java" line="1" /></issue></issues>')
            with self.assertRaises(ValueError): read_error_fingerprints(report)

    def test_source_scope_and_pinning(self):
        data=json.loads(BASELINE_FILE.read_text())
        self.assertEqual(data['expected_error_count'],121)
        self.assertEqual(data['source']['workflow_run'],42266028816)
        self.assertTrue(all('/automation/' not in row[1] and row[1].startswith('app/src/') for row in data['findings']))


if __name__=='__main__':
    unittest.main()
