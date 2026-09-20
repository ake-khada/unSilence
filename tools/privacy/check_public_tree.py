#!/usr/bin/env python3
"""Check the index and publishable working files without printing matched values.

This metadata guard complements a secret scanner; it is not a complete PII or
credential detector. Ignored local files are deliberately not traversed.
"""
import argparse
import fnmatch
import os
from pathlib import Path
import re
import subprocess
import sys

PRIVATE_PARTS = {
    ".toolchains", ".claude", ".codex", ".agents", ".local", ".superpowers",
    ".worktrees", ".android", ".ssh", ".gnupg", ".idea", ".gradle",
    ".kotlin", ".kotlin.bak", "validation_logs", "_audit_dump", "audits",
}
PRIVATE_NAMES = {
    "AGENTS.md", "CLAUDE.md", "VALIDATION_PROTOCOL.md", "AUDIT_WORKPLAN.md",
    "PERFORMANCE_EXPERIMENTS.md", "unsilence-backlog.md", "RENDERING_ARCHITECTURE.md",
    "hydration-frontier-spec.md", "settings-relays.html", "wisp-feed-debug.txt",
    "local.properties", "keystore.properties", ".privacy-local-patterns", ".DS_Store",
    "Thumbs.db", "Desktop.ini",
}
PRIVATE_GLOBS = (
    "*.local.*", "*.jks", "*.keystore", "*.pem", "*.key", "*.p12", "*.pfx",
    "*.apk", "*.aab", "*.apks", "*.hprof", "*.perfetto-trace", "._*",
    "unsilence-sprint*.md", "unsilence-session-*.md", "unsilence-*-plan.md",
)
RULES = {
    "absolute personal home path": re.compile(
        rb"(?:/Users/|/home/)[A-Za-z0-9._-]+/|[A-Za-z]:\\Users\\[A-Za-z0-9._-]+\\"
    ),
    "literal ADB device identifier": re.compile(
        rb"\badb\s+-s\s+(?!emulator-\d+\b)[A-Za-z0-9][A-Za-z0-9._:-]{7,}"
    ),
    "private installation record": re.compile(
        rb"(?:firstInstallTime|lastUpdateTime)\s*[:=]\s*\d{4}-\d\d-\d\d"
    ),
    "Nostr private key": re.compile(rb"\bnsec1[023456789acdefghjklmnpqrstuvwxyz]{58}\b"),
    "private key material": re.compile(rb"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    "GitHub access token": re.compile(
        rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"
    ),
}


def private_path(path):
    parts = Path(path).parts
    name = parts[-1]
    return (
        bool(PRIVATE_PARTS.intersection(parts)) or name in PRIVATE_NAMES
        or ((name == ".env" or name.startswith(".env.")) and name != ".env.example")
        or any(fnmatch.fnmatchcase(name, pattern) for pattern in PRIVATE_GLOBS)
    )


def findings(path, data, local_patterns=()):
    result = ["private/local-only file"] if private_path(path) else []
    # Byte patterns also catch identifiers in binary payloads; no values printed.
    for number, line in enumerate(data.splitlines(), 1):
        for label, regex in RULES.items():
            if regex.search(line):
                result.append(f"line {number}: {label}")
        if any(pattern.lower() in line.lower() for pattern in local_patterns):
            result.append(f"line {number}: local denylist match")
    return result


def unsafe_link(path, target):
    link = Path(os.fsdecode(target))
    return link.is_absolute() or ".." in link.parts or private_path(str(Path(path).parent / link))


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def check(repo, index_only=False, message=None, commit_identities=False):
    pattern_file = repo / ".privacy-local-patterns"
    patterns = tuple(
        line.strip() for line in pattern_file.read_bytes().splitlines()
        if line.strip() and not line.lstrip().startswith(b"#")
    ) if pattern_file.exists() else ()
    if message is not None:
        return [f"commit message: {item}" for item in findings("COMMIT_MESSAGE", message.read_bytes(), patterns)]

    issues = []
    if commit_identities:
        for variable in ("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT"):
            issues.extend(f"{variable}: {item}" for item in findings("COMMIT_IDENTITY", git(repo, "var", variable), patterns))
    entries = git(repo, "ls-files", "--stage", "-z").split(b"\0")
    with subprocess.Popen(
        ["git", "-C", str(repo), "cat-file", "--batch"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    ) as process:
        for entry in filter(None, entries):
            metadata, raw_path = entry.split(b"\t", 1)
            mode, oid, stage = metadata.split()
            path = os.fsdecode(raw_path)
            if stage != b"0":
                issues.append(f"index: {path}: unresolved merge")
                continue
            if mode == b"160000":
                issues.append(f"index: {path}: submodule requires separate privacy review")
                continue
            process.stdin.write(oid + b"\n")
            process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3 or header[1] != b"blob":
                raise RuntimeError("Could not read staged blob")
            data = process.stdout.read(int(header[2]))
            if process.stdout.read(1) != b"\n":
                raise RuntimeError("Incomplete staged blob")
            issues.extend(f"index: {path}: {item}" for item in findings(path, data, patterns))
            if mode == b"120000" and unsafe_link(path, data):
                issues.append(f"index: {path}: symlink to private or external files")
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError("git cat-file failed")

    if not index_only:
        paths = git(repo, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
        for raw_path in sorted(set(filter(None, paths.split(b"\0")))):
            path = os.fsdecode(raw_path)
            file = repo / path
            if file.is_symlink():
                data = os.fsencode(os.readlink(file))
                if unsafe_link(path, data):
                    issues.append(f"worktree: {path}: symlink to private or external files")
            elif file.is_file():
                data = file.read_bytes()
            else:
                continue
            issues.extend(f"worktree: {path}: {item}" for item in findings(path, data, patterns))
    return issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index-only", action="store_true", help="inspect the exact staged tree")
    parser.add_argument("--message", type=Path, help="inspect a commit message instead")
    parser.add_argument("--commit-identities", action="store_true", help="check effective author/committer against the local denylist")
    args = parser.parse_args()
    try:
        repo = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
        issues = check(repo, args.index_only, args.message, args.commit_identities)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Privacy check could not complete ({type(error).__name__}).", file=sys.stderr)
        return 2
    if issues:
        print("Privacy check failed (matched values withheld):", file=sys.stderr)
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("Privacy metadata check passed. This does not erase or scan published history.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
