#!/usr/bin/env python3
"""AMT-06C: human-gated physical Android fixture acceptance; never launches restore.

Only dev.mikeyphw.amt06c.fixture is writable. No root, no uninstall, no data-clear,
no automatic Tasker broadcasts or privileged modifications. Evidence never contains
private app files; the proof token is synthetic and independently escrowed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import uuid

FIXTURE = "dev.mikeyphw.amt06c.fixture"
MARKER = "files/amt06c_proof.txt"
ACK = "I_ACCEPT_DISPOSABLE_AMT06C_FIXTURE"
BACKUP_RE = re.compile(r"Tasker-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
RESTORE_RE = re.compile(r"Tasker-Restore-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")

class Refused(Exception):
    pass


def digest(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def run(argv):
    try:
        done = subprocess.run(argv, text=True, capture_output=True, timeout=35, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refused("ADB invocation unavailable") from exc
    if done.returncode:
        raise Refused("ADB command failed (details suppressed to avoid leaking app data)")
    return done.stdout.strip()


def selected_serial(explicit):
    serial = explicit or os.environ.get("ANDROID_SERIAL", "")
    if serial:
        if not re.fullmatch(r"[a-zA-Z0-9._:-]{1,100}", serial):
            raise Refused("malformed ADB device serial")
        return serial
    lines = run(["adb", "devices"]).splitlines()[1:]
    devices = [line.split()[0] for line in lines if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(devices) != 1:
        raise Refused("set --serial or ANDROID_SERIAL; exactly one authorized ADB device is required")
    return devices[0]


class Android:
    def __init__(self, serial):
        self.serial = serial
        self.prefix = ["adb", "-s", serial, "shell"]

    def shell(self, *args):
        return run(self.prefix + list(args))

    def fixture(self, *args):
        return self.shell("run-as", FIXTURE, *args)

    def identity(self):
        packages = self.shell("pm", "list", "packages", "-3").splitlines()
        if "package:" + FIXTURE not in packages:
            raise Refused("fixture is not an installed third-party app")
        if not self.shell("pm", "path", FIXTURE).startswith("package:"):
            raise Refused("fixture APK path unavailable")
        if self.shell("am", "get-current-user") != "0":
            raise Refused("physical fixture acceptance only supports Android user 0")
        if self.shell("getprop", "ro.kernel.qemu") == "1":
            raise Refused("physical acceptance cannot be reported from an emulator")
        detail = self.shell("dumpsys", "package", FIXTURE)
        uid = re.search(r"(?m)^\s*userId=(\d+)\s*$", detail)
        version = re.search(r"\bversionCode=(\d+)\b", detail)
        installed = re.search(r"\bfirstInstallTime=([^\r\n]+)", detail)
        if not (uid and version and installed):
            raise Refused("cannot read package UID, version or first installation timestamp")
        run_as_uid = self.fixture("id", "-u")
        if not run_as_uid.isdecimal() or int(run_as_uid) != int(uid.group(1)):
            raise Refused("debuggable run-as identity mismatch")
        return {"package": FIXTURE, "user": 0, "uid": int(uid.group(1)),
                "version": int(version.group(1)), "first_install_time": installed.group(1).strip()}

    def marker(self):
        # Failure here means data is missing; this must not be silently converted to an empty string.
        return self.fixture("sh", "-c", "cat " + MARKER)

    def absent_marker(self):
        return self.fixture("sh", "-c", "if test -e " + MARKER + "; then echo PRESENT; else echo ABSENT; fi") == "ABSENT"

    def write_marker(self, token):
        if not re.fullmatch(r"AMT06C_[a-f0-9]{32}", token):
            raise Refused("refusing non-synthetic fixture content")
        # Fixed marker path, restricted synthetic text; shell string is never user controlled.
        self.fixture("sh", "-c", "mkdir -p files && printf %s " + token + " > " + MARKER)
        if self.marker() != token:
            raise Refused("fixture token write/readback mismatch")


def read_case(directory):
    path = Path(directory) / "amt06c_case.json"
    if path.is_symlink() or not path.is_file():
        raise Refused("acceptance case missing or symlinked")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != 1 or data.get("identity", {}).get("package") != FIXTURE:
        raise Refused("invalid fixture evidence schema")
    if not re.fullmatch(r"AMT06C_[a-f0-9]{32}", str(data.get("baseline", ""))):
        raise Refused("malformed independent proof token")
    return data


def write_case(directory, data, create=False):
    base = Path(directory)
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    if base.is_symlink():
        raise Refused("case directory must not be a symlink")
    os.chmod(base, 0o700)
    path = base / "amt06c_case.json"
    if path.is_symlink() or (create and path.exists()):
        raise Refused("refusing overwrite of existing independent fixture escrow")
    payload = json.dumps(data, sort_keys=True, indent=2) + "\n"
    tmp = base / (".amt06c-case-" + secrets.token_hex(12))
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            out.write(payload)
            out.flush()
            os.fsync(out.fileno())
        if path.is_symlink() or (create and path.exists()):
            raise Refused("escrow changed during atomic write")
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def require_ack(got):
    if got != ACK:
        raise Refused("fixture write requires exact --ack " + ACK)


def require_identity(device, case):
    actual = device.identity()
    if actual != case["identity"]:
        raise Refused("fixture UID, version, user or installation timestamp changed")


def receipt(path):
    p = Path(path)
    if p.is_symlink() or p.stat().st_size > 16384:
        raise Refused("receipt symlink or oversized receipt refused")
    value = json.loads(p.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise Refused("receipt must be a JSON object of Tasker query values")
    if value.get("%am_package", value.get("package")) != FIXTURE:
        raise Refused("receipt package does not match the disposable fixture")
    return value


def backup_proof(obj):
    name = obj.get("%am_backup_request_name", obj.get("%am_backup_name", ""))
    if not isinstance(name, str) or not BACKUP_RE.fullmatch(name):
        raise Refused("missing fixed Tasker backup UUID name")
    if obj.get("%am_backup_request_status") != "succeeded" or obj.get("%am_backup_request_verified") != "true":
        raise Refused("native backup receipt has not confirmed an intact backup")
    if obj.get("%am_backup_request_type") != "data":
        raise Refused("native receipt must correspond to an internal-data backup")
    return name


def restore_proof(obj, fixed_backup=None):
    request = obj.get("%am_restore_request_id", "")
    if not isinstance(request, str) or not RESTORE_RE.fullmatch(request):
        raise Refused("missing unique restore request")
    if obj.get("%am_restore_request_status") != "native_completed":
        raise Refused("native restore did not report completion")
    # Independent human-recorded selection must match the fixed backup from the prior receipt.
    # This is a consistency assertion, not cryptographic proof of Tasker's saved configuration.
    if fixed_backup and obj.get("selected_backup_name") != fixed_backup:
        raise Refused("fixed backup selection does not match earlier backup receipt")
    if obj.get("%am_restore_request_verified") != "false":
        raise Refused("native receipt must not claim independent data verification")
    return request


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["preflight", "seed", "mutate", "verify"])
    parser.add_argument("--serial", help="physical Android ADB serial; defaults to ANDROID_SERIAL")
    parser.add_argument("--case-dir", help="private host-side fixture escrow directory")
    parser.add_argument("--receipt", help="JSON export of Tasker Query Package receipt")
    parser.add_argument("--ack", help="explicit disposable-fixture marker-write consent")
    args = parser.parse_args(argv)
    try:
        if args.action != "preflight" and not args.case_dir:
            raise Refused("--case-dir required")
        if args.action in ("seed", "mutate"):
            require_ack(args.ack)
        if args.action in ("mutate", "verify") and not args.receipt:
            raise Refused("--receipt required")
        device = Android(selected_serial(args.serial))
        identity = device.identity()
        if args.action == "preflight":
            print(json.dumps({"status": "read_only_pass", "identity": identity,
                              "destructive_restore_attempted": False}, sort_keys=True))
            return 0
        if args.action == "seed":
            if not device.absent_marker():
                raise Refused("fixture marker already exists; use a newly installed disposable fixture")
            token = "AMT06C_" + uuid.uuid4().hex
            write_case(args.case_dir, {"schema": 1, "identity": identity, "baseline": token,
                                       "phase": "seeded", "destructive_restore_attempted_by_harness": False}, create=True)
            device.write_marker(token)
            print("AMT06C_SEEDED: synthetic fixture marker independently escrowed; now run Tasker backup_data")
            return 0
        case = read_case(args.case_dir)
        require_identity(device, case)
        if args.action == "mutate":
            if case.get("phase") != "seeded" or device.marker() != case["baseline"]:
                raise Refused("fixture changed since independent baseline; no overwrite permitted")
            name = backup_proof(receipt(args.receipt))
            mutated = "AMT06C_" + uuid.uuid4().hex
            if mutated == case["baseline"]:
                raise Refused("ambiguous fixture mutation")
            device.write_marker(mutated)
            case.update(phase="mutated", changed_sha256=digest(mutated), backup_name=name)
            write_case(args.case_dir, case)
            print("AMT06C_MUTATED: fixture data changed; manually issue fixed-snapshot restore only if disposable")
            return 0
        if case.get("phase") not in ("mutated", "verified"):
            raise Refused("no independent before/after fixture baseline")
        req = restore_proof(receipt(args.receipt), case["backup_name"])
        value = device.marker()
        success = value == case["baseline"]
        if digest(value) == case.get("changed_sha256"):
            success = False
        case.update(phase="verified" if success else "unverified", restore_request=req,
                    native_status="native_completed", independently_restored_verified=success,
                    observed_sha256=digest(value))
        write_case(args.case_dir, case)
        print(json.dumps({"status": "restored_verified" if success else "restored_not_verified",
                          "native_status": "native_completed", "independent_marker_match": success,
                          "fixture_only": True, "request": req}, sort_keys=True))
        return 0 if success else 1
    except (Refused, ValueError, KeyError, json.JSONDecodeError, OSError) as error:
        print("AMT06C_REFUSED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
