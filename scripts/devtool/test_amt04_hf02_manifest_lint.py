#!/usr/bin/env python3
"""AMT-04A+B1 HF02 source-only lint-location regression (no Gradle)."""
from __future__ import annotations

import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'app/src/main/AndroidManifest.xml'
BASELINE = ROOT / 'scripts/devtool/upstream_lint_baseline_42266028816.json'
TASKER_BLOCK = '        <!-- AMT-04: additional Tasker editors use the existing single'
LEGACY_BLOCK = '        <!-- AMT-02: native Tasker/Locale plugin.'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class AMT04HF02LintLocationTest(unittest.TestCase):
    def test_pinned_upstream_diagnostic_positions_still_precede_editors(self):
        data = json.loads(BASELINE.read_text(encoding='utf-8'))
        self.assertEqual(121, data['expected_error_count'])
        self.assertEqual(2, data['schema'])
        manifest_findings = [x for x in data['findings']
                             if x[1] == 'app/src/main/AndroidManifest.xml']
        self.assertEqual(38, len(manifest_findings))
        final_upstream = max(x[2] for x in manifest_findings)
        lines = MANIFEST.read_text(encoding='utf-8').splitlines()
        self.assertEqual(1, lines.count(TASKER_BLOCK))
        self.assertGreater(lines.index(TASKER_BLOCK) + 1, final_upstream)
        # The legacy comment is longer than the fixed prefix. Match the exact
        # beginning of its line, not an entire line with list.index().
        legacy_indices = [i for i, line in enumerate(lines)
                          if line.startswith(LEGACY_BLOCK)]
        self.assertEqual(1, len(legacy_indices),
                         "legacy Tasker marker missing or duplicated")
        self.assertGreater(lines.index(TASKER_BLOCK), legacy_indices[0])

    def test_exactly_three_editors_and_one_shared_fire_receiver(self):
        root = ET.parse(MANIFEST).getroot()
        app = root.find('application')
        self.assertIsNotNone(app)
        wanted = ('TaskerActionEditActivity', 'TaskerPackageQueryEditActivity',
                  'TaskerPackageManageEditActivity')
        for name in wanted:
            found = [x for x in app.findall('activity')
                     if (x.get(ANDROID + 'name') or '').endswith('.automation.tasker.' + name)]
            self.assertEqual(1, len(found), name)
            self.assertEqual('true', found[0].get(ANDROID + 'exported'))
        action = 'com.twofortyfouram.locale.intent.action.FIRE_SETTING'
        receivers = [x for x in app.findall('receiver') if any(
            y.get(ANDROID + 'name') == action for y in x.findall('intent-filter/action'))]
        self.assertEqual(1, len(receivers))
        self.assertTrue(receivers[0].get(ANDROID + 'name', '').endswith('.TaskerActionReceiver'))

    def test_legacy_manifest_diagnostic_lines_unchanged(self):
        lines = MANIFEST.read_text(encoding='utf-8').splitlines()
        # These exact addresses were pinned by the upstream lint XML.
        for lineno in (327, 331, 804, 1279, 1478):
            self.assertLessEqual(lineno, len(lines))
        self.assertIn('<data', lines[330])  # 331: historical AppLinkUrlError path pattern


if __name__ == '__main__':
    unittest.main()
