# Development

The reproducible development environment uses mise. The setup script currently
supports Linux x86_64; other platforms need a compatible Android SDK and JDK setup.

## Setup

Install mise, Bash, Git, curl, unzip and tar using your platform's package manager,
then run:

```sh
./dev setup
./dev check
```

Version pins live in `mise.toml`. Tool binaries, SDK data and build caches stay
under ignored `.toolchains/`. Setup accepts the Android SDK licenses and does
not replace existing tool installations implicitly.

Java 17 runs Gradle. Java 21 runs JVM tests that load Quartz crypto classes;
this does not change the shipped app's Java target.

## Commands

| Command | Purpose |
| --- | --- |
| `./dev` | Open the project shell |
| `./dev -- COMMAND` | Run a command in the project environment |
| `./dev doctor` | Inspect local toolchain and signing configuration |
| `./dev check` | Run JVM tests, lint, minified release, benchmark compilation and Python tests |
| `./dev privacy` | Check staged and publishable working files for private metadata |
| `./dev privacy:secrets` | Scan local Git history for secrets with redacted output |
| `./dev build` | Build the minified release APK |
| `./dev debug` | Build the separate development app |
| `./dev devices` | List connected Android devices |
| `./dev install` | Build and update release in place |
| `./dev install:debug` | Build and install the separate development app |

The debug app uses `com.unsilence.app.dev`, allowing it to coexist with release.
The local build gate does not require a phone. Benchmark compilation is not a
device performance measurement.

## Device access and signing

Enable USB debugging, connect a data-capable cable, and authorize the computer.
Configure USB permissions using your platform's Android tooling documentation.
ADB must report the device as authorized; do not run ADB as root.

Local release and benchmark builds currently use the locally generated
development key. They are not publisher-signed release artifacts. Keep keys
private and backed up. An installed app can only be updated with a compatible
signing key; stop on a mismatch. Do not uninstall or clear app data automatically.

## Validation

Unit tests, lint and builds do not validate real scrolling, navigation or media
behavior. Runtime changes also need human validation on a device. Do not publish,
react, pay, change accounts or weaken device security settings for automated tests.

Account-free rendered tests use the separate debug app. Offline minified crypto
coverage uses `ReleaseCryptoTest` with `-PvalidationBuildType=release`; a debug pass
does not establish release-shrinker correctness.

Baseline-profile generation uses the non-minified `baselineProfile` variant;
performance measurements use the release-like `benchmark` variant. Generation
requires ART JIT profiling and a populated feed. An empty output is not a measured
profile. Use a suitable test device or the optional isolated emulator:

```sh
./dev profile:emulator:setup
./dev profile:emulator
```

The emulator requires host KVM access. Disposable test-identity provisioning is
restricted to that isolated emulator and must never run on a personal device.

## Local-only information

Keep personal setup notes in ignored `*.local.md` files, configuration overrides
in `mise.local.toml`, and logs, screenshots, traces and build evidence under
`.toolchains/`. Do not commit device identifiers, personal paths, installation
histories, signing keys or raw diagnostic output. Review staged changes before
committing; ignore rules do not protect files that are already tracked.

The build gate includes a privacy metadata check. See
[privacy checks and local commit hooks](tools/privacy/README.md) for secret
scanning, machine-specific denylisting and protection of staged contents.
