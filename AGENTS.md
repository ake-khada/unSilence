# unSilence on Omarchy

This project is developed from the terminal on Linux x86_64. Read `DEVELOPMENT.md`
for setup and commands. All project tool binaries, SDK data, signing keys, and
Gradle caches live in the ignored `.toolchains/` directory.

- Use `./dev setup` to provision the mise-managed tools.
- Use `./dev check` for JVM tests, lint, minified release, benchmark compilation,
  and the Python performance-analysis tests. A phone is not needed for this gate.
- Use `./dev -- COMMAND` to run a command in the project environment, or `./dev`
  for an interactive shell. `mise run TASK` also works in a mise-activated shell.
- Java 17 runs Gradle; Java 21 runs JVM tests that load Quartz's crypto classes.
- Do not reuse build outputs or toolchains copied from macOS. Historical reports
  may retain the paths of the machine where the evidence was captured.
- Preserve unrelated work. Do not commit unless asked, and do not push.
- Read `CLAUDE.md` for the existing architecture and invariants. Its name is
  historical; these project constraints apply to all contributors.
- Runtime changes require the human gesture checks in `VALIDATION_PROTOCOL.md`.
  Builds and unit tests alone do not validate scrolling, navigation, or playback.
- Never automatically uninstall the phone app to resolve a signing mismatch:
  uninstalling erases its local data. Request specific authorization first.
