#!/usr/bin/env python3
"""Regression: exactly one Locale FIRE_SETTING endpoint routes all Tasker action types."""
from __future__ import annotations
import tempfile, unittest, shutil, xml.etree.ElementTree as ET
from pathlib import Path
from check_amt04_contract import ROOT, REQUIRED, inspect as inspect_query
from check_amt04b_contract import FILES, inspect as inspect_manage

ANDROID='{http://schemas.android.com/apk/res/android}'
MANIFEST=Path('app/src/main/AndroidManifest.xml')
PKG=Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker')

class SharedReceiverContract(unittest.TestCase):
    def fixture(self,root):
        for path in set(REQUIRED)|set(FILES):
            dest=root/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/path,dest)
    def test_exactly_one_receiver_three_tasker_actions(self):
        root=ET.parse(ROOT/MANIFEST).getroot()
        fire=[r for r in root.findall('.//receiver') if any(
            x.get(ANDROID+'name')=='com.twofortyfouram.locale.intent.action.FIRE_SETTING'
            for x in r.findall('intent-filter/action'))]
        self.assertEqual(1,len(fire))
        self.assertTrue(fire[0].get(ANDROID+'name').endswith('TaskerActionReceiver'))
        self.assertEqual('true',fire[0].get(ANDROID+'exported'))
        for name in ('TaskerActionEditActivity','TaskerPackageQueryEditActivity','TaskerPackageManageEditActivity'):
            nodes=[n for n in root.findall('.//activity') if (n.get(ANDROID+'name') or '').endswith(name)]
            self.assertEqual(1,len(nodes),name)
            self.assertEqual(['com.twofortyfouram.locale.intent.action.EDIT_SETTING'],
                [x.get(ANDROID+'name') for x in nodes[0].findall('intent-filter/action')])
    def test_duplicate_action_filter_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root);p=root/MANIFEST
            s=p.read_text();needle='        <!-- AMT-02: native Tasker/Locale plugin.'
            self.assertIn(needle,s)
            extra="""        <receiver android:name=".automation.tasker.TaskerPackageManageReceiver" android:exported="true">
            <intent-filter><action android:name="com.twofortyfouram.locale.intent.action.FIRE_SETTING" /></intent-filter>
        </receiver>
"""
            p.write_text(s.replace(needle,extra+needle,1))
            self.assertEqual('FAIL',inspect_query(root)['status'])
            self.assertEqual('FAIL',inspect_manage(root)['status'])
    def test_mutation_must_route_through_scoped_evaluator(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root);p=root/PKG/'TaskerActionReceiver.java'
            s=p.read_text();self.assertIn('TaskerPackageManageReceiver.evaluate(app, saved)',s)
            p.write_text(s.replace('TaskerPackageManageReceiver.evaluate(app, saved)',
                'TaskerPackageQueryReceiver.evaluate(app, saved)'))
            self.assertEqual('FAIL',inspect_manage(root)['status'])
    def test_ambiguous_capabilities_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root);p=root/PKG/'TaskerActionReceiver.java'
            s=p.read_text();self.assertIn('ambiguous_plugin_configuration',s)
            p.write_text(s.replace('ambiguous_plugin_configuration','allow_both'))
            self.assertEqual('FAIL',inspect_manage(root)['status'])

if __name__=='__main__':unittest.main()
