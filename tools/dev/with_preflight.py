"""Run an optional local check before replacing this process with a command."""

import os
from pathlib import Path
import stat
import subprocess
import sys


def main(argv):
    if len(argv) < 2:
        print("Usage: with_preflight.py HOOK COMMAND [ARG ...]", file=sys.stderr)
        return 2

    hook = Path(argv[0]).absolute()
    command = argv[1:]
    try:
        try:
            metadata = hook.lstat()
        except FileNotFoundError:
            metadata = None

        # Only a genuinely absent hook is optional. lstat keeps broken symlinks
        # distinguishable from absence; links and special files are not hooks.
        if metadata is not None:
            if not stat.S_ISREG(metadata.st_mode) or not os.access(hook, os.R_OK | os.X_OK):
                raise ValueError("the configured preflight must be a readable, executable regular file")
            # Execute directly: no shell fallback for empty files or invalid
            # executable formats, and no evaluation of command arguments.
            result = subprocess.run([str(hook)], check=False)
            if result.returncode != 0:
                print(f"Preflight failed ({result.returncode}); command was not started.", file=sys.stderr)
                return result.returncode if result.returncode > 0 else 128 - result.returncode

        os.execvpe(command[0], command, os.environ)
    except (OSError, ValueError) as error:
        print(f"Launch blocked: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
