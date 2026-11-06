#!/usr/bin/env python3
"""R2D native Tasker variable/filter source regressions. Python only."""
import tempfile
import unittest
from pathlib import Path
from shutil import copy2
from check_tasker_plugin_contract import ROOT, FILES, PKG, inspect

class TaskerDynamicFilterContractTests(unittest.TestCase):
    def fixture(self, dest):
        for rel in FILES:
            p=dest/rel
            p.parent.mkdir(parents=True,exist_ok=True)
            copy2(ROOT/rel,p)

    def require_marker_rejected(self, filename, good, bad):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            self.fixture(root)
            path=root/PKG/filename
            text=path.read_text()
            self.assertIn(good,text)
            path.write_text(text.replace(good,bad))
            self.assertEqual('FAIL',inspect(root)['status'])
            self.assertTrue(any('R2D dynamic/filter contract missing' in err
                                for err in inspect(root)['errors']))

    def test_all_r2d_source_contracts(self):
        self.assertEqual('PASS',inspect(ROOT)['status'])

    def test_variable_replacement_header_required(self):
        self.require_marker_rejected('TaskerPluginWire.java',
            'output.putExtra(EXTRA_VARIABLE_REPLACE_KEYS, KEY_PROFILE)',
            'output.putExtra(EXTRA_BLURB, KEY_PROFILE)')

    def test_dynamic_variable_validation_required(self):
        self.require_marker_rejected('TaskerPluginWire.java',
            'return (validProfile(profile) || (dynamic && validVariableReference(profile)))',
            'return validProfile(profile)')

    def test_unresolved_variable_fails_closed(self):
        self.require_marker_rejected('TaskerActionReceiver.java',
            'unresolved_tasker_variable', 'silent_fallback')

    def test_event_filter_is_required(self):
        self.require_marker_rejected('TaskerConditionReceiver.java',
            'matchingEvent(context, messageId, requested, profileFilter)',
            'matchingEvent(context, messageId, requested)')

    def test_state_filter_is_required(self):
        self.require_marker_rejected('TaskerConditionReceiver.java',
            'stateMatches(context, requested, profileFilter)',
            'stateMatches(context, requested)')

    def test_condition_validation_is_required(self):
        self.require_marker_rejected('TaskerConditionReceiver.java',
            'validConditionConfiguration(', 'isConditionAccepted(')

    def test_per_profile_hashing_is_required(self):
        self.require_marker_rejected('TaskerProfileEvents.java',
            'MessageDigest.getInstance("SHA-256")', 'profileId.getBytes()')

    def test_bounded_snapshot_storage_is_required(self):
        self.require_marker_rejected('TaskerProfileEvents.java',
            'pruneProfileHistory(context);', 'unboundedHistory(context);')

    def test_editor_does_not_widen_deleted_profile_filter(self):
        self.require_marker_rejected('TaskerConditionEditActivity.java',
            'Missing profile:', 'Any profile')

if __name__=='__main__': unittest.main()
