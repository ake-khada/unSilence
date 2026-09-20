import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("guard", Path(__file__).parents[1] / "check_public_tree.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ContentPolicyTest(unittest.TestCase):
    def test_private_paths_are_blocked(self):
        for path in (
            "AGENTS.md", "docs/CLAUDE.md", ".codex/config.toml", "nested/.env",
            "keys/signing.jks", "DEVELOPMENT.local.md", ".kotlin.bak/errors/x.log",
            "unsilence-session-example.md", "app/release/example.apk", "._example",
        ):
            with self.subTest(path=path):
                self.assertTrue(guard.private_path(path))

    def test_generic_docs_and_tools_are_allowed(self):
        for path in ("DEVELOPMENT.md", "mise.toml", "tools/privacy/README.md", ".env.example"):
            self.assertFalse(guard.private_path(path))

    def test_host_identifiers_and_keys_are_reported_without_values(self):
        values = (
            b"/" + b"home/" + b"fixture/project/",
            b"/" + b"Users/" + b"fixture/project/",
            b"C:" + b"\\Users\\" + b"fixture\\project",
            b"adb -s " + b"TEST" * 4,
            b"firstInstallTime=" + b"2026-01-01",
            b"nsec" + b"1" + b"q" * 58,
            b"-----BEGIN " + b"PRIVATE KEY-----",
            b"ghp_" + b"a" * 36,
        )
        for value in values:
            with self.subTest(value=value[:4]):
                issues = guard.findings("example.txt", value)
                self.assertTrue(issues)
                self.assertNotIn(value.decode(), str(issues))

    def test_generic_placeholders_and_emulator_commands_are_allowed(self):
        self.assertEqual([], guard.findings("example.md", b"adb -s $DEVICE shell\nadb -s emulator-5554 shell\n${HOME}/project"))

    def test_local_literal_denylist_is_case_insensitive(self):
        self.assertTrue(guard.findings("example.md", b"private-machine", [b"PRIVATE-MACHINE"]))
        self.assertNotIn("private-machine", str(guard.findings("example.md", b"private-machine", [b"PRIVATE-MACHINE"])))


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.repo = Path(self.folder.name)
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE)

    def write(self, name, data):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def test_staged_secret_cannot_be_hidden_by_clean_worktree(self):
        secret = b"nsec" + b"1" + b"q" * 58
        self.write("example.txt", secret)
        self.git("add", "example.txt")
        self.write("example.txt", b"clean")
        self.assertTrue(any("index:" in item for item in guard.check(self.repo)))

    def test_unstaged_and_untracked_public_files_are_checked(self):
        self.write("example.txt", b"clean")
        self.git("add", "example.txt")
        value = b"/" + b"home/" + b"fixture/project/"
        self.write("example.txt", value)
        self.write("new name.txt", value)
        issues = guard.check(self.repo)
        self.assertTrue(any("worktree: example.txt" in item for item in issues))
        self.assertTrue(any("worktree: new name.txt" in item for item in issues))
        self.assertEqual([], guard.check(self.repo, index_only=True))

    def test_ignored_private_files_are_not_scanned_but_forced_adds_fail(self):
        self.write(".gitignore", b".toolchains/\n")
        self.write(".toolchains/private.txt", b"local only")
        self.git("add", ".gitignore")
        self.assertEqual([], guard.check(self.repo))
        self.git("add", "-f", ".toolchains/private.txt")
        self.assertTrue(guard.check(self.repo))

    def test_staged_deletion_preserves_local_ignored_doc(self):
        self.write(".gitignore", b"CLAUDE.md\n")
        self.write("CLAUDE.md", b"local constraints")
        self.git("add", ".gitignore")
        self.assertEqual([], guard.check(self.repo))
        self.assertTrue((self.repo / "CLAUDE.md").exists())

    def test_symlinks_are_not_followed_to_private_data(self):
        self.write(".gitignore", b".toolchains/\n")
        self.write(".toolchains/private", b"nsec" + b"1" + b"q" * 58)
        (self.repo / "public-link").symlink_to(".toolchains/private")
        self.git("add", ".gitignore", "public-link")
        issues = guard.check(self.repo)
        self.assertTrue(any("symlink to private" in item for item in issues))
        self.assertFalse(any("Nostr private key" in item for item in issues))

    def test_safe_relative_symlinks_are_allowed(self):
        self.write("source.txt", b"public content")
        (self.repo / "link").symlink_to("source.txt")
        self.git("add", "source.txt", "link")
        self.assertEqual([], guard.check(self.repo))

    def test_commit_message_uses_local_denylist(self):
        self.write(".privacy-local-patterns", b"private-device\n")
        self.write("message", b"fix on PRIVATE-DEVICE")
        self.assertTrue(guard.check(self.repo, message=self.repo / "message"))

    def test_effective_commit_identities_are_checked_without_printing_email(self):
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "private@example.invalid")
        self.write(".privacy-local-patterns", b"private@example.invalid\n")
        self.write(".gitignore", b".privacy-local-patterns\n")
        self.git("add", ".gitignore")
        issues = guard.check(self.repo, index_only=True, commit_identities=True)
        self.assertEqual(2, len(issues))
        self.assertTrue(any("GIT_AUTHOR_IDENT" in item for item in issues))
        self.assertTrue(any("GIT_COMMITTER_IDENT" in item for item in issues))
        self.assertNotIn("private@example.invalid", str(issues))
        self.git("config", "user.email", "fixture@users.noreply.github.com")
        self.assertEqual([], guard.check(self.repo, index_only=True, commit_identities=True))


if __name__ == "__main__":
    unittest.main()
