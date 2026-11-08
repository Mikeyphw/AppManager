#!/usr/bin/env python3
"""Offline AMT06-C device harness tests; never calls ADB or Gradle."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import amt06c_device_acceptance as am


class FakeAndroid:
    marker_value = None
    def __init__(self, serial):
        self.serial = serial
    def identity(self):
        return {"package": am.FIXTURE, "user": 0, "uid": 10421,
                "version": 1, "first_install_time": "2026-11-07 17:00:00"}
    def absent_marker(self):
        return type(self).marker_value is None
    def marker(self):
        if type(self).marker_value is None:
            raise am.Refused("missing fixture marker")
        return type(self).marker_value
    def write_marker(self, token):
        assert token.startswith("AMT06C_")
        type(self).marker_value = token


class DeviceAcceptanceTests(unittest.TestCase):
    def setUp(self):
        FakeAndroid.marker_value = None
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.case = str(Path(self.temp.name) / "escrow")
        self.patcher = patch.object(am, "Android", FakeAndroid)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.serial_patch = patch.object(am, "selected_serial", return_value="fixture-device")
        self.serial_patch.start()
        self.addCleanup(self.serial_patch.stop)

    def call(self, action, *args):
        return am.main([action, "--case-dir", self.case, *args])

    def test_seed_requires_explicit_consent(self):
        self.assertEqual(self.call("seed"), 2)
        self.assertFalse(Path(self.case).exists())
        self.assertIsNone(FakeAndroid.marker_value)

    def test_seed_escrows_synthetic_data_independently(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        case = am.read_case(self.case)
        self.assertEqual(case["baseline"], FakeAndroid.marker_value)
        self.assertEqual(case["phase"], "seeded")
        self.assertEqual(os.stat(Path(self.case) / "amt06c_case.json").st_mode & 0o777, 0o600)

    def test_seed_does_not_replace_existing_fixture(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        self.assertEqual(self.call("seed", "--ack", am.ACK), 2)

    def test_wrong_backup_status_cannot_mutate(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        baseline = FakeAndroid.marker_value
        receipt = self.write_receipt({"%am_package": am.FIXTURE,
                "%am_backup_request_name": "Tasker-12345678-1234-1234-1234-123456789abc",
                "%am_backup_request_type": "data", "%am_backup_request_status": "queued",
                "%am_backup_request_verified": "false"})
        self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", receipt), 2)
        self.assertEqual(FakeAndroid.marker_value, baseline)

    def write_receipt(self, data):
        p = Path(self.temp.name) / ("receipt_" + str(len(list(Path(self.temp.name).glob("receipt*")))) + ".json")
        p.write_text(json.dumps(data), encoding="utf8")
        return str(p)

    def complete_backup(self):
        return self.write_receipt({"%am_package": am.FIXTURE,
            "%am_backup_request_name": "Tasker-12345678-1234-1234-1234-123456789abc",
            "%am_backup_request_type": "data", "%am_backup_request_status": "succeeded",
            "%am_backup_request_verified": "true"})

    def complete_restore(self):
        return self.write_receipt({"%am_package": am.FIXTURE,
            "%am_restore_request_id": "Tasker-Restore-12345678-1234-1234-1234-123456789abc",
            "%am_restore_request_status": "native_completed",
            "%am_restore_request_verified": "false",
            "selected_backup_name": "Tasker-12345678-1234-1234-1234-123456789abc"})

    def test_successful_full_mock_roundtrip(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", self.complete_backup()), 0)
        self.assertNotEqual(FakeAndroid.marker_value, am.read_case(self.case)["baseline"])
        FakeAndroid.marker_value = am.read_case(self.case)["baseline"]  # Manual restore, never the harness.
        self.assertEqual(self.call("verify", "--receipt", self.complete_restore()), 0)
        self.assertTrue(am.read_case(self.case)["independently_restored_verified"])

    def test_wrong_fixed_backup_name_cannot_verify(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", self.complete_backup()), 0)
        FakeAndroid.marker_value = am.read_case(self.case)["baseline"]
        r = json.loads(Path(self.complete_restore()).read_text())
        r["selected_backup_name"] = "Tasker-00000000-0000-0000-0000-000000000001"
        bad = self.write_receipt(r)
        self.assertEqual(self.call("verify", "--receipt", bad), 2)

    def test_native_completed_without_data_change_fails(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", self.complete_backup()), 0)
        self.assertEqual(self.call("verify", "--receipt", self.complete_restore()), 1)
        self.assertFalse(am.read_case(self.case)["independently_restored_verified"])

    def test_native_unknown_never_claims_success(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", self.complete_backup()), 0)
        r = self.write_receipt({"%am_package": am.FIXTURE,
             "%am_restore_request_id": "Tasker-Restore-12345678-1234-1234-1234-123456789abc",
             "%am_restore_request_status": "unknown", "%am_restore_request_verified": "false"})
        self.assertEqual(self.call("verify", "--receipt", r), 2)

    def test_receipt_package_mismatch_fails_closed(self):
        r = self.write_receipt({"%am_package": "com.example.valuable"})
        with self.assertRaises(am.Refused):
            am.receipt(r)

    def test_receipt_claiming_native_verified_is_rejected(self):
        r = {"%am_restore_request_id": "Tasker-Restore-12345678-1234-1234-1234-123456789abc",
             "%am_restore_request_status": "native_completed", "%am_restore_request_verified": "true"}
        with self.assertRaises(am.Refused):
            am.restore_proof(r)

    def test_mismatched_install_identity_is_refused(self):
        self.assertEqual(self.call("seed", "--ack", am.ACK), 0)
        with patch.object(FakeAndroid, "identity", return_value={"package": am.FIXTURE, "user": 0,
                "uid": 99999, "version": 1, "first_install_time": "changed"}):
            self.assertEqual(self.call("mutate", "--ack", am.ACK, "--receipt", self.complete_backup()), 2)

    def test_symlink_escrow_is_refused(self):
        path = Path(self.case)
        path.mkdir(parents=True)
        target = Path(self.temp.name) / "outside.json"
        target.write_text("secret", encoding="utf8")
        (path / "amt06c_case.json").symlink_to(target)
        with self.assertRaises(am.Refused):
            am.read_case(self.case)
        with self.assertRaises(am.Refused):
            am.write_case(self.case, {"schema": 1}, create=False)

    def test_no_restore_shell_commands_exist_in_harness(self):
        source = Path(am.__file__).read_text(encoding="utf8")
        for banned in ("clearApplicationUserData", "pm clear", "su -c", "adb root", "am broadcast", "pm uninstall"):
            self.assertNotIn("\"" + banned + "\"", source)


if __name__ == "__main__":
    unittest.main()
