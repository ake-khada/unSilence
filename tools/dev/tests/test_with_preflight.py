"""Exercise the launch boundary with real child processes and no Android SDK."""

import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().parents[1] / "with_preflight.py"


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="preflight test ")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.hook = self.root / "local-check"
        self.started = self.root / "started.json"
        self.checked = self.root / "checked"
        self.command = self.root / "command.py"
        self.command.write_text(
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "Path('started.json').write_text(json.dumps({\n"
            "    'args': sys.argv[1:], 'cwd': os.getcwd(),\n"
            "    'environment': os.environ.get('PREFLIGHT_TEST_VALUE'),\n"
            "    'checked': Path('checked').is_file(),\n"
            "}))\n"
            "sys.exit(int(os.environ.get('PREFLIGHT_COMMAND_STATUS', '0')))\n"
        )

    def hook_script(self, body):
        self.hook.write_text("#!/bin/sh\n" + body + "\n")
        self.hook.chmod(0o700)

    def launch(self, *args, env=None):
        return subprocess.run(
            [sys.executable, "-I", str(RUNNER), str(self.hook),
             sys.executable, "-I", str(self.command), *args],
            cwd=self.root,
            env={**os.environ, **(env or {})},
            capture_output=True,
            text=True,
            timeout=10,
        )

    def assert_blocked(self, result):
        self.assertNotEqual(result.returncode, 0, result)
        self.assertFalse(self.started.exists(), result)
        self.assertTrue(result.stderr.strip(), result)

    def test_absent_optional_hook_allows_command(self):
        result = self.launch()
        self.assertEqual(result.returncode, 0, result)
        self.assertFalse(json.loads(self.started.read_text())["checked"])

    def test_success_runs_first_and_preserves_arguments_directory_and_environment(self):
        self.hook_script("printf 'checked' > checked")
        args = ["two words", "", "$(touch unexpected)", "semi;colon", "--flag"]
        result = self.launch(*args, env={"PREFLIGHT_TEST_VALUE": "preserved"})
        self.assertEqual(result.returncode, 0, result)
        self.assertEqual(json.loads(self.started.read_text()), {
            "args": args, "cwd": str(self.root), "environment": "preserved", "checked": True,
        })
        self.assertFalse((self.root / "unexpected").exists())

    def test_nonzero_check_blocks_command_and_preserves_status(self):
        self.hook_script("printf 'check failed\\n' >&2\nexit 37")
        result = self.launch()
        self.assert_blocked(result)
        self.assertEqual(result.returncode, 37)
        self.assertIn("check failed", result.stderr)

    def test_terminated_check_blocks_command(self):
        self.hook_script('kill -TERM "$$"')
        result = self.launch()
        self.assert_blocked(result)
        self.assertEqual(result.returncode, 128 + signal.SIGTERM)

    def test_nonexecutable_hook_blocks_command(self):
        self.hook_script("exit 0")
        self.hook.chmod(0o600)
        self.assert_blocked(self.launch())

    @unittest.skipIf(os.getuid() == 0, "root bypasses file read permissions")
    def test_unreadable_hook_blocks_command(self):
        self.hook_script("exit 0")
        self.hook.chmod(0o100)
        self.assert_blocked(self.launch())

    def test_directory_hook_blocks_command(self):
        self.hook.mkdir()
        self.assert_blocked(self.launch())

    def test_fifo_hook_blocks_command_without_waiting(self):
        os.mkfifo(self.hook, 0o700)
        self.assert_blocked(self.launch())

    def test_broken_symlink_hook_blocks_command(self):
        self.hook.symlink_to(self.root / "missing")
        self.assert_blocked(self.launch())

    def test_valid_symlink_hook_blocks_command(self):
        target = self.root / "target"
        target.write_text("#!/bin/sh\nexit 0\n")
        target.chmod(0o700)
        self.hook.symlink_to(target)
        self.assert_blocked(self.launch())

    def test_empty_executable_hook_blocks_command(self):
        self.hook.touch(mode=0o700)
        self.assert_blocked(self.launch())

    def test_missing_interpreter_blocks_command(self):
        self.hook.write_text("#!" + str(self.root / "missing-interpreter") + "\n")
        self.hook.chmod(0o700)
        self.assert_blocked(self.launch())

    def test_malformed_shell_check_blocks_command(self):
        self.hook_script("if then")
        self.assert_blocked(self.launch())

    def test_malformed_local_configuration_blocks_command(self):
        checker = self.root / "check.py"
        checker.write_text(
            "import json\nfrom pathlib import Path\n"
            "json.loads(Path('configuration.json').read_text())\n"
        )
        self.hook_script(f"exec {shlex.quote(sys.executable)} -I {shlex.quote(str(checker))}")
        (self.root / "configuration.json").write_text('{"enabled":')
        result = self.launch()
        self.assert_blocked(result)
        self.assertIn("JSONDecodeError", result.stderr)

    def test_missing_command_fails(self):
        result = subprocess.run(
            [sys.executable, "-I", str(RUNNER), str(self.hook), str(self.root / "missing-command")],
            capture_output=True, text=True, timeout=10,
        )
        self.assert_blocked(result)

    def test_command_exit_status_is_preserved(self):
        result = self.launch(env={"PREFLIGHT_COMMAND_STATUS": "23"})
        self.assertTrue(self.started.is_file())
        self.assertEqual(result.returncode, 23)

    def test_incomplete_invocation_fails(self):
        for args in ([], [str(self.hook)]):
            with self.subTest(args=args):
                result = subprocess.run(
                    [sys.executable, "-I", str(RUNNER), *args],
                    capture_output=True, text=True, timeout=10,
                )
                self.assertEqual(result.returncode, 2)
                self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
