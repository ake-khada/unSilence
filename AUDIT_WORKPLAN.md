# UI audit completion — active goal

Owner authorization (2026-09-18): complete the reviewed UI/performance workstream,
self-validate throughout, and request human validation only around the midpoint.
This overrides per-change human checkpoints for this run, not the distinction
between automated evidence and human UX acceptance. On 2026-09-19 the owner
authorized committing the current batch with clear designations and deferring
further tests. No push, user-account mutations, paid actions, uninstall, or data
clearing is authorized.

## Execution and evidence

- Preserve pre-existing Omarchy/benchmark and feed-resume work; split review by concern.
- Keep original audit IDs. Do not implement unverified performance claims blindly.
- Automated focused regressions per change, full tests/lint/minified builds per batch.
- Freeze app sources during compile/lint. Use mise (`./dev`).
- Device automation is diagnostic; no synthetic social actions or adb input gestures.
- One midpoint checkpoint after UI-01/02/07/08/09/10/11/14/15/16 implementation and
  foundational restoration/snapshot work. Then finish remaining ten audit findings.
- Final debug/release and available device regression. Record missing older-device,
  accessibility, performance or subjective coverage; do not invent validation.
- Final candidate contains no temporary timing probes. Phone has the probe-free
  second-half minified candidate; preserve data on replacement.

## Work queue

| Slice | Status | Acceptance |
| --- | --- | --- |
| Feed restoration coordination | IMPLEMENTED; midpoint accepted | Edge-case tests pass; broader restoration/performance coverage remains |
| Snapshot bounded memory + deadline | IMPLEMENTED; automated gate passed | Atomic rollback/large-data tests pass; phone has successful commits and one deadline rollback; allocation/deadline follow-through remains |
| UI-15/16 system bars + splash | IMPLEMENTED; midpoint accepted | Older API/system-mode matrix remains |
| UI-02/07/08/09/10/11 lifetimes/work | PARTIAL implementation committed in this batch | Root destinations/cold card handles/lazy shell done; nested settings/articles and session-root ownership remain |
| UI-01/14 follow-through | IMPLEMENTED at HEAD | Preserve content-behind-bars, touch barrier, fail-closed sensitive rendering and playback consent; regression coverage |
| Midpoint human checkpoint | ACCEPTED by owner | “Done. No evident hiccups.” Raw log captured before further work; persistence deadline remains open |
| UI-03/04 geometry/draw | IMPLEMENTED; rendered tests passed | Actual first-frame geometry/hidden-tail and logo draw-phase assertions pass; full workload measurements remain |
| UI-05/06 timestamps/haptics | IMPLEMENTED; timestamp device test passed | Leaf clock assertion passes; subjective haptics and gesture integration remain |
| UI-12/17/18/19/20 consistency | IMPLEMENTED; partial device coverage | Large-font card and press cleanup tests pass; full typography/image/count/density matrix remains |
| UI-13 measured profiles/R8 | OPEN | Real profile generation/packaging, explicit installer; conservative retention audit and release crypto/serialization checks |
| Final regression/backlog/tooling handoff | OPEN | Accurate tracked backlog, tests and signed final build; no probes/keys/binaries committed; list remaining evidence limits |

## Starting evidence

- HEAD `0758322c`; UI-01/14 committed; first UI-02/07/08 slice in `68e63f36`.
- Pending feed-resume candidate passed 1,776 tests and owner observed recovery,
  but loading flash/scroll-jump attribution and restoration edge coverage remain.
- Withdrawn snapshot streaming candidate combined memory and cancellation changes;
  phone saves did not complete. Source rollback is already complete.
- Timing diagnostic: `.toolchains/snapshot-timing-0758322c.Xr6Mt1/validation.Zb05KN/SUMMARY.md`.
  Nine commits, two coalesced requests, three onStop saves; encoding dominates.
  One 3.052s save committed after cancellation without a timeout result; no OOM in
  this smaller workload. Measurement PASS is not a persistence fix.
- New work artifacts: `.toolchains/audit-completion-0758322c.eqhcKV/`.

## Progress notes

Chronological execution log: earlier “pending/running” notes below describe that
moment and are superseded by later results and the commit-boundary section.

- Restoration: added account-fenced snapshot completion (also missing/rejected files),
  seeded timeline identity, and a single rows/readiness presentation. Stored Global
  lens must load before spending a restored anchor; repeated saves retain pending
  references. Focused regressions plus full JVM suite passed (`restoration-tests.log`).
- UI-15/16: explicit dark system-bar styles and installSplashScreen before super.
  Included in the first green JVM/compile batch; device/API matrix still pending.
- UI-08/09: cold CardDataFlow handles replace cached ViewModel-owned stateIn jobs.
  Visible lifecycle owners collect them; eviction never cancels a visible handle.
  Parent-resolved avatar pictures remove duplicate profile subscriptions, including
  article layouts. Full JVM suite passed (`card-lifetimes-tests.log`); no measured
  frame/memory improvement claimed yet.
- UI-02: migrated root-level full-screen flags to serializable navigation entries;
  preserve connections → profile → back history. Ordinary destination back delegates
  to NavDisplay; dirty composers still own discard confirmation. Compile passed.
  Relay editor completion now must finish before entry pop, with saveable draft fields.
  Nested profile/settings/article hosts still need review; do not call UI-02 complete.
- UI-10/11: relay health now sheet-local; notification screen is demand-created and
  collector-driven, badge uses muted repository rows without profile hydration;
  zap preferences come from the existing actions owner. Graph startup decision
  moved to a lightweight repository, graph picker VM to its entry. Required deep-link
  handling stays eager. Integration suite currently being checked.
- Snapshot memory-only run: **1,798 tests, 0 failed/skipped**. Direct 8 KiB buffered
  file writing plus header patch; V16–V19 bytes, >64 MiB fixture and atomic failure
  rollback covered. No new cancellation in that run; artifact is
  `snapshot-memory-only-tests.log` (tracked patch also saved; new helper in worktree).
- Subsequent deadline batch restores cooperative checks at section/write boundaries
  and uses the dedicated IO dispatcher for manual saves too. Stop outcomes distinguish
  timed-out rollback from a deadline reached inside final atomic commit. The first
  deadline run passed both boundary assertions but failed retry assertions because
  those retries used runTest virtual time against real IO; retries now use a real
  dispatcher. Full combined check is running in `midpoint-check.log`.
- Combined gate PASS: **1,802 tests, 167 suites, zero failures/errors/skips**, 9 Python
  tests, lint 0 errors/111 warnings, release and benchmark compile. Installed
  `midpoint-release.apk` in place; device hash matches `76db0971…b1f8f0c7`, PID24739.
  No temporary probes. The phone is Dozing, so black screenshot is not visual
  acceptance. No captured fatal/ANR/OOM in new PID; old diagnostic OOM records
  are separate. Full evidence and the sole human script are in
  `.toolchains/audit-completion-0758322c.eqhcKV/MIDPOINT_VALIDATION.md`.
- Midpoint accepted: owner reports “Done. No evident hiccups.” First action was
  immutable log capture in `human-validation.HIheHk/`. Candidate PID24739 has three
  committed snapshots (40.9–42.6 MB), one onStop deadline rollback, no captured
  fatal/ANR/OOM. Allocation stalls remain; this is not a frame-time or memory A/B.
  Continue the second half with automated validation, without another routine
  human checkpoint. Snapshot deadline/allocation investigation remains open.
- Second-half JVM gates passed: draw/clock/images, text geometry, feedback/pill/
  tokens, typography. Latest **1,809 tests, zero failures/errors**. Logs use those
  batch names in the artifact directory. Account-free Android rendered tests are
  being built separately (`render-test-build.log`); not yet run.
- UI-03 preflight shares the exact formatted AnnotatedString and emoji placeholders
  with rendering, including blockquotes. It decides the collapsed tail before any
  OG/quote/media descendants compose. No onTextLayout → state → second-frame loop.
- UI-17: 120ms global crossfade, full-color default; only sized feed images opt
  into RGB565 with a separate decoded-memory cache key. Keep 8% cache cap. Coil
  validates cached size/hardware but not color precision; a request flag alone
  would not reliably protect the fullscreen viewer from a cached feed bitmap.
- UI-18 shared Foundation indication covers default clickables and formerly silent
  card/header controls. Material controls retain their standard bounded indications;
  do not claim every component now uses a custom tint. No content/video alpha changes.
- UI-13 explicit ProfileInstaller 1.4.1 added. Device generation and R8 verification
  remain open. Quartz consumer rules independently keep all Quartz models/enums and
  disable obfuscation; removing app rules is not a complete obfuscation fix.
- Android 17 rendered-test harness: the first three tests failed before assertions
  because Compose pulled Espresso 3.5.0, which reflects the removed
  `InputManager.getInstance`. Pin test-only Espresso 3.7.0 (public system service).
  The corrected APK builds; rerun pending. Preserve `render-device-1.log` as failed
  harness evidence, not a product regression or passing geometry result.
- Release instrumentation now builds with explicit test-only Error Prone annotations
  and a narrowly scoped javac-enum warning exception in `proguard-test-rules.pro`.
  Neither changes production R8 retention. `minified-crypto-test-build-3.log` passed;
  the two earlier failed build logs are retained. Device crypto run still pending.
- The unlocked GrapheneOS/API 37 phone is running the baseline-profile generator.
  Initial launch/scroll and ART profile flushing succeeded; convergence and final
  generated files remain pending. Original app data is preserved by in-place install.
- Profile capture result: both workloads reached capture but returned empty ART
  profiles (`baseline-generation-device-1.log`, 236.842s). Read-only device
  configuration confirms `dalvik.vm.usejit=false`; GrapheneOS documents disabled
  JIT profiling/full AOT. This is an environment limitation, not a measured
  profile. No security settings changed. Existing wildcard profile preserved.
  A stock Android device or suitable emulator is needed; neither emulator nor
  `/dev/kvm` is currently present in this workspace environment.
- Follow-up outside the command sandbox confirms host `/dev/kvm` exists and is
  accessible. The earlier absence was sandbox visibility, not a missing host
  capability. Provisioning a project-local API-35 emulator; no phone keys/data
  will be copied. Generator now rejects JIT-disabled runtimes before capture.
- Second-half automated gates PASS: **1,809 JVM tests / 171 suites, 9 Python tests,
  lint 0 errors/118 warnings**, minified release/benchmark builds; **5 rendered
  device tests and 2 minified offline crypto tests**. Phone restored to minified
  release, hash `4de5e9f4…1510402` verified, data intact. Detailed evidence and
  explicit remaining coverage are in `SECOND_HALF_VALIDATION.md` in the artifact
  directory. These results do not complete the remaining navigation/profile work.

## Commit boundary — 2026-09-19

Owner: “commit these with clear designation. The tests we can run after.”
Commit the current work in concern-based batches; do not call the audit complete.
Further device/profile runs are deferred. Emulator provisioning was interrupted
after the emulator package installed but before the API-35 image finished; no AVD
was created or started, no disposable identity created. Resume with
`./dev profile:emulator:setup` later. No physical-phone security setting changed.

Last full check and seven device assertions passed before the final formatting
cleanup and restoration of the original dark media-save error background via an
`ErrorContainer` token. Those final tiny edits have static review only, not a fresh
test run. The installed phone APK is the recorded pre-commit candidate, not an
exact build of these commits. Next validation should build committed HEAD.

### Concern-based commits

- `717bfeff` — Omarchy mise workflow, benchmark/profile/emulator scaffolding and
  test infrastructure (no generated profiles claimed).
- `dab747b3` — bounded atomic snapshot streaming, deadline outcomes and
  account-fenced restore readiness, with regression tests.
- `3246dd39` — narrowed R8 retention and passing minified offline crypto tests.
- `af47d122` — interconnected navigation/card-lifetime/feed-restoration and UI
  rendering/feedback work, including JVM and rendered regression tests.
- This documentation commit — canonical backlog, evidence and explicit remaining
  work. No push authorized or performed.
