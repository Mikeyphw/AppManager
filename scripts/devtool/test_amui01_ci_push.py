#!/usr/bin/env python3
"""Static negative CI-scheduling regressions, no Gradle or Android device."""
from pathlib import Path
import unittest
from check_amui01_ci_push import PUSH, validate_workflow
ROOT=Path(__file__).resolve().parents[2]
WF=(ROOT/'.github/workflows/devtool-tasker-ci.yml').read_text()

class AmuiHostedPushTests(unittest.TestCase):
    def test_all_jobs_qualify_on_feature_push(self):
        self.assertTrue(validate_workflow(WF))
    def test_reject_loss_of_amui_branch_trigger(self):
        with self.assertRaises(AssertionError):
            validate_workflow(WF.replace("'feature/amui01*'", "'feature/missing*'", 1))
    def test_reject_unqualified_default_gradle_mode(self):
        with self.assertRaises(AssertionError):
            validate_workflow(WF.replace("(github.event_name == 'push' && 'full') || ", "", 1))
    def test_reject_single_disabled_emulator_gate(self):
        with self.assertRaises(AssertionError):
            validate_workflow(WF.replace("disable-linux-hw-accel: 'false'", "disable-linux-hw-accel: 'true'",1))
    def test_reject_one_job_without_push(self):
        api16='  amui01-android16:\n'
        x,y=WF.split(api16,1)
        with self.assertRaises(AssertionError):
            validate_workflow(x+api16+y.replace(' || '+PUSH,'',1))
    def test_reject_removal_of_original_tasker_mode(self):
        with self.assertRaises(AssertionError):
            validate_workflow(WF.replace("inputs.mode == 'full' || inputs.mode == 'instrumentation'", "inputs.mode == 'source'",1))

if __name__=='__main__': unittest.main()
