#!/usr/bin/env python3
"""Offline regression tests: no Gradle / Android SDK / network calls."""
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from check_upstream_lint_baseline import KNOWN, baseline, check, read_error_fingerprints


def make_report(path, issues):
    doc = ET.Element("issues", format="6")
    for issue_id, file, line, severity in issues:
        e = ET.SubElement(doc, "issue", id=issue_id, severity=severity, message="known or test error")
        ET.SubElement(e, "location", file="/home/runner/work/AppManager/AppManager/" + file,
                      line=str(line), column="1")
    ET.ElementTree(doc).write(path, encoding="utf-8", xml_declaration=True)


class LintBaselineTest(unittest.TestCase):
    def test_exact_known_fingerprint_passes(self):
        key = "AppLinkUrlError|app/src/main/AndroidManifest.xml"
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            make_report(report, [("AppLinkUrlError", "app/src/main/AndroidManifest.xml", KNOWN[key][0], "Error")])
            ok, message = check(report)
            self.assertTrue(ok, message)

    def test_new_tasker_error_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            make_report(report, [("NewApi", "app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerActionReceiver.java", 56, "Error")])
            ok, message = check(report)
            self.assertFalse(ok)
            self.assertIn("NEW_LINT_ERROR", message)

    def test_same_upstream_file_new_location_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            make_report(report, [("AppLinkUrlError", "app/src/main/AndroidManifest.xml", 50000, "Error")])
            self.assertFalse(check(report)[0])

    def test_duplicate_at_existing_location_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            p = ("NewApi", "app/src/main/java/io/github/muntashirakon/AppManager/compat/ActivityManagerCompat.java", 81, "Error")
            make_report(report, [p, p])
            self.assertFalse(check(report)[0])

    def test_warnings_not_mistaken_for_errors(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            make_report(report, [("SomeNewWarning", "app/src/main/res/values/strings.xml", 1, "Warning")])
            self.assertTrue(check(report)[0])

    def test_missing_or_broken_report_refused(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            with self.assertRaises(ValueError):
                read_error_fingerprints(report)
            report.write_text("<foo/>")
            with self.assertRaises(ValueError):
                read_error_fingerprints(report)

    def test_error_budget_cannot_grow(self):
        self.assertEqual(sum(baseline().values()), 115)
        self.assertEqual(sum(map(len, KNOWN.values())), 115)
        self.assertTrue(all("/automation/" not in key for key in KNOWN))

    def test_fixed_legacy_errors_do_not_block(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "lint.xml"
            make_report(report, [])
            self.assertTrue(check(report)[0])


if __name__ == "__main__":
    unittest.main()
