#!/usr/bin/env python3
"""Fast source-only tests for the Devtool contract; never run Gradle."""
import tempfile
import unittest
from pathlib import Path
from shutil import copytree
from check_contract import ROOT, inspect


class TestContract(unittest.TestCase):
    def test_repository_contract(self):
        self.assertEqual(inspect()["status"], "PASS")

    def test_unsupported_native_wrapper_action_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            for name in inspect()["checked"]:
                src = ROOT / name
                dst = dest / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (dest / "docs/DEVTOOL_TASKER.md").write_text("fixture")
            (dest / "devtoolw").chmod(0o755)
            config = dest / ".devtool.toml"
            config.write_text(config.read_text() + '\n[wrapper.commands.inspect]\ndevtool = "inspect"\n')
            result = inspect(dest)
            self.assertEqual(result["status"], "FAIL")
            self.assertTrue(any("unsupported canonical wrapper action" in message
                                for message in result["errors"]))

    def test_missing_java_home_on_hosted_test_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            for name in inspect()["checked"]:
                src = ROOT / name
                dst = dest / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            docs = dest / "docs/DEVTOOL_TASKER.md"
            docs.parent.mkdir(parents=True, exist_ok=True)
            docs.write_text("fixture")
            (dest / "devtoolw").chmod(0o755)
            workflow = dest / ".github/workflows/devtool-tasker-ci.yml"
            original = workflow.read_text()
            correct = 'test)  devtool --repo "$PWD" --java-home "$JAVA_HOME" --target app test ;;'
            self.assertIn(correct, original)
            workflow.write_text(original.replace(correct,
                'test)  devtool --repo "$PWD" --target app test ;;', 1))
            result = inspect(dest)
            self.assertEqual(result["status"], "FAIL")
            self.assertTrue(any("hosted Devtool test must use JAVA_HOME" in message
                                for message in result["errors"]))

    def test_configuration_cache_cannot_be_reenabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            for name in inspect()["checked"]:
                src = ROOT / name
                dst = dest / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            docs = dest / "docs/DEVTOOL_TASKER.md"
            docs.parent.mkdir(parents=True, exist_ok=True)
            docs.write_text("fixture")
            (dest / "devtoolw").chmod(0o755)
            config = dest / ".devtool.toml"
            source = config.read_text()
            self.assertIn('configuration_cache = "off"', source)
            config.write_text(source.replace('configuration_cache = "off"',
                                             'configuration_cache = "auto"', 1))
            result = inspect(dest)
            self.assertEqual(result["status"], "FAIL")
            self.assertTrue(any("configuration_cache must be off" in error
                                for error in result["errors"]))

    def test_gradle_build_cache_remains_enabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            for name in inspect()["checked"]:
                src = ROOT / name
                dst = dest / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            docs = dest / "docs/DEVTOOL_TASKER.md"
            docs.parent.mkdir(parents=True, exist_ok=True)
            docs.write_text("fixture")
            (dest / "devtoolw").chmod(0o755)
            config = dest / ".devtool.toml"
            source = config.read_text()
            self.assertIn('build_cache = true', source)
            config.write_text(source.replace('build_cache = true',
                                             'build_cache = false', 1))
            result = inspect(dest)
            self.assertEqual(result["status"], "FAIL")
            self.assertTrue(any("build_cache must remain enabled" in error
                                for error in result["errors"]))

    def test_configuration_mutation_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            # Copy only contract inputs; keep test fully offline and fast.
            for name in inspect()["checked"]:
                src = ROOT / name
                dst = dest / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            p = dest / "docs/DEVTOOL_TASKER.md"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("fixture")
            (dest / "devtoolw").chmod(0o755)
            (dest / "docs/WRAPPER.md").write_text("fixture")
            config = dest / ".devtool.toml"
            config.write_text(config.read_text().replace('ref = "feature/tasker-automation"', 'ref = "master"', 1))
            actual = inspect(dest)
            self.assertEqual(actual["status"], "FAIL")
            self.assertTrue(any("ref/mode" in message for message in actual["errors"]))


if __name__ == "__main__":
    unittest.main()
