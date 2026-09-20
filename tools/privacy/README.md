# Public repository privacy checks

`./dev privacy` checks the Git index **and** non-ignored working files. It rejects
internal working documents, local tool/key/artifact paths and recognizable host
paths, device records and private-key/token formats. It prints locations and rule
names, never matched values. Ignored local files stay local and are not traversed.
`./dev check` includes this gate and its regression tests.

`./dev setup` provisions the pinned Gitleaks binary through mise under ignored
`.toolchains/`. `./dev privacy:secrets` scans all locally reachable history with
redacted output. Findings require review: neither scanner proves all private data
is absent. The only secret-scanner exception is an exact parser character-range
constant, restricted to its source path.

## Commit protection

After reviewing existing hooks, enable the repository's hooks locally:

```sh
git config --local core.hooksPath tools/git-hooks
```

The pre-commit hook inspects the **staged contents**, including forced additions
of ignored files, checks the effective author/committer against the local denylist,
and scans the staged diff for secrets. The commit-msg hook
checks the proposed message. Do not replace an existing custom hook setup without
reviewing it. Hooks are local, not enforced on other clones or remote pushes.

For machine names, device IDs, personal email addresses or other literal values
that should not be committed, put one value per line in the ignored
`.privacy-local-patterns` file. Lines beginning with `#` and blank lines are
ignored. Do not publish that denylist; doing so would disclose the values it
protects. Generic policy and detector tests contain synthetic examples only.

## Existing history

Ignoring or untracking a file does not remove earlier versions. Historical
cleanup needs a backed-up, separately reviewed rewrite in an isolated clone,
including old paths, commit messages and any approved identity replacements.
Do not drop mixed implementation commits just to remove their documentation.
Do not push backup branches, old tags or stashes into a cleaned repository.

Remote replacement requires explicit coordination. Forks, clones, cached views,
PR refs and release assets are separate surfaces; a Git rewrite does not erase
them. Rotate any actual exposed credential before relying on history cleanup.
See [GitHub's sensitive-data removal guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
