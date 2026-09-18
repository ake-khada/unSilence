# Terminal development on Omarchy

The system needs mise, Bash, Git, curl, unzip and tar. The setup uses mise's
`install-into` command to keep project binaries in this checkout. Pinned releases
are declared under `[vars]` in `mise.toml`; the `[tools]` path entries activate
those installations in ordinary mise shells as well as the `./dev` wrapper.

```bash
dev-shell unSilence
./dev setup
./dev check
```

Setup downloads Temurin 17 and 21, Python, Android command-line tools, SDK 36,
build-tools 35.0.0, and platform-tools (ADB), and accepts the Android SDK licenses.
Gradle 8.11.1 comes from the committed wrapper. No Android Studio or emulator is
required for the physical-phone workflow.
Perfetto analysis tools (pinned under `[vars]`) are also installed through mise
in `.toolchains/perfetto`; `./dev -- trace_processor_shell` analyzes captures
locally. Recording uses the phone's built-in Perfetto, not the host binary.

`./dev` starts an interactive shell. `./dev -- COMMAND` runs a single command in
the same environment. Nothing is installed in another project's tool directories.
Mise activation in zsh also makes `java`, `adb`, `sdkmanager`, `python3` and the
project environment available automatically when entering this folder.
The system mise executable and USB rules remain host installations. Mise's
normal shell metadata and ADB's host authorization files use the dev user's
standard home directories; downloaded project toolchains and build caches stay
inside `.toolchains/`.

| Command | Purpose |
| --- | --- |
| `./dev doctor` | Inspect the toolchain and signing certificate |
| `./dev build` | Build the minified release APK |
| `./dev debug` | Build the separate development app |
| `./dev test` | JVM crypto/unit tests and Python analyzer tests |
| `./dev lint` | Android lint |
| `./dev check` | Complete build/test gate, including benchmark compilation |
| `./dev devices` | Check the connected phone |
| `./dev install` | Build and update the release app in place |
| `./dev install:debug` | Build and install `com.unsilence.app.dev` |
| `./dev logs` | Stream logs for the running release app |

## Local files

`.toolchains/` contains `jdk17`, `jdk21`, `python`, `android-sdk`, `android-user`,
`gradle-home`, and mise's download/cache/state directories. Do not commit it or
copy its Linux binaries to a different operating system. Re-run setup there with
an appropriate platform configuration instead. Existing tool trees are preserved
by setup; changing a version pin requires an explicit tool reinstall.

The debug signing key is generated under `.toolchains/android-user/debug.keystore`.
Back it up securely if future builds must update this installation. Release and
benchmark builds currently use this development key; this is not a production
release-signing configuration.

## Phone connection

Builds, lint, and JVM tests can run without the phone. For device work:

1. Enable Developer options and USB debugging on the phone.
2. Connect a data-capable USB cable and unlock the phone.
3. Run `./dev devices` and accept the phone's USB debugging authorization.
4. The device must report `device`, not `unauthorized` or `no permissions`.

Linux USB access requires the host's `android-udev` rules (the one system-level
Android component). For initial setup on a new host, run these from the normal
administrator account:

```bash
sudo pacman -S --needed android-udev
sudo usermod -aG adbusers dev
sudo udevadm control --reload-rules
```

Then open a fresh `dev-shell unSilence` to acquire the group membership. If the
phone was already connected, disconnect and reconnect it. Do not run ADB as root.
If ADB was started before the group change, run `./dev -- adb kill-server` in
the fresh shell before `./dev devices` so the new server inherits the new groups.
The `dev` account does not currently have passwordless sudo, so this host setup
must be completed from an administrator terminal.

The Mac's installed app has a different signing key unless that original key is
imported. `./dev install` deliberately stops on a signature mismatch. Replacing
the app by uninstalling it erases local app data, so save anything needed first.
The separate `.dev` application can be installed alongside the existing release.

Human validation of scrolling, media and navigation follows
`VALIDATION_PROTOCOL.md`. Automated benchmark compilation is not a device run.

## Audit validation tools

- Rendered layout tests use the separate `com.unsilence.app.dev` app; no account
  is required. Build with `./dev -- ./gradlew :app:assembleDebug
  :app:assembleDebugAndroidTest`, install both APKs with `adb install -r`, and run
  `com.unsilence.app.dev.test/androidx.test.runner.AndroidJUnitRunner` with
  `adb shell am instrument -w`. The phone must be unlocked for rendered tests.
- `ReleaseCryptoTest` performs offline fixed-test-key NIP-04/NIP-44 and Quartz/
  Jackson round trips. It never opens the user's signer, wallet, or relay writes.
  For minified coverage, build the instrumentation APK with
  `-PvalidationBuildType=release :app:assembleReleaseAndroidTest` and run only
  that class against the same signed release APK. A debug pass is not an R8 pass.
- The `baselineProfile` app variant is non-minified and profileable for source-
  symbol generation. `benchmark` remains release-like for performance measurement.
  Build `:app:assembleBaselineProfile :macrobenchmark:assembleBaselineProfile`.
  `BaselineProfileGenerator` captures startup plus a populated feed scroll, without
  posting or changing accounts. It requires an unlocked, logged-in phone on a feed.
  Install in place with the matching local key, never uninstall or clear data.
  The measured profile must be captured and reviewed before replacing the current
  handwritten profile; generation compilation alone does not close UI-13.
- Baseline-profile capture requires ART JIT profiling. GrapheneOS disables it
  globally (`adb shell getprop dalvik.vm.usejit` returns `false`) and uses full
  AOT compilation. Our API-37 phone completed the startup/scroll workloads but
  generated empty profiles. Use a stock Android API 33+ device or suitable
  emulator for generation; do not change the user's security settings. This
  restriction is distinct from Perfetto tracing. Do not mark an empty profile
  or a passing build as measured profile coverage.
- Optional isolated capture device: `./dev profile:emulator:setup` downloads the
  emulator/API-35 x86_64 image into the existing local SDK and creates only
  `unsilence-profile-api35`. `./dev profile:emulator` starts it headlessly using
  host KVM. All AVD files remain in `.toolchains/android-user/avd`; physical-phone
  keys/data are never copied. `PrepareProfileEmulator` is a separate, guarded
  provisioning test requiring `allowLocalTestIdentity=true` and emulator hardware.
  It creates a disposable local test identity, skips follows and reads Global/Raw;
  it must never be run against the owner's phone or counted as profile measurement.
- Generated traces, private device logs and build evidence belong in ignored
  `.toolchains/`. Do not add them, signing keys, or tool binaries to Git.

## Migration evidence

The copied Mac benchmark outputs and machine-specific Claude settings are moved
out of active directories into `.toolchains/migration-backup/`. Historical
handoffs and measurement reports retain their original machine paths so they do
not imply that old results were captured on this laptop.

## Verified on this laptop — 2026-09-16

- Fresh zsh activation resolves Java, Python, ADB and SDK tools to this checkout.
- Temurin 17.0.20+8 builds; Temurin 21.0.12+8 runs JVM tests; Python 3.14.7.
- Android command-line tools 19.0, SDK 36, build-tools 35.0.0, platform-tools 37.0.1.
- `./dev check`: passed; 1,678 JVM tests and 9 Python tests, no failures/skips.
- Android lint: 0 errors, 108 warnings (plus 3 informational findings).
- Release and benchmark APKs built; both signatures verified with the new local key.
- Release APK: `app/build/outputs/apk/release/app-release.apk` (approximately 19 MiB).
- Release SHA-256: `51710e7eb0f66397e7533182c80bb93681c90fe23a874e8f5910765d0d8e644e`.
- Host `android-udev` is installed and `dev` is a member of `adbusers`.
- Pixel 9 Pro XL (`46031FDAS006LJ`) connected and authorized. The initial shell
  lacked the newly assigned USB group; restarting ADB through `newgrp adbusers`
  resolved access. Future sessions should start from a fresh dev-shell.
- At the owner's request, uninstalled `com.unsilence.app` (erasing its local
  data) and installed a clean archive build of HEAD
  `478e90899e19b8a682db1b65b744a56d199af80c` with the new local development key.
  Existing uncommitted source/build configuration was preserved.
- Installed HEAD APK:
  `.toolchains/head-478e9089.7CKBDx/app/build/outputs/apk/release/app-release.apk`.
  SHA-256 verified on device:
  `e1b3c40709f2afe29192bce533c4941bd43c747e085966fc858990a6d4cf4429`.
  Signing certificate SHA-256:
  `3b05d5f9c310063932966040b8872b2eaaed1e4a2ace2abf32e204c5bf5f8008`.
- Cold launch succeeded and the process remained running after bootstrap.
  Human runtime/gesture validation has not been performed.
- Previous phone APK saved as
  `.toolchains/migration-backup/phone-before-head-478e9089.apk`.
  This preserves the old binary only, not the erased app data.
