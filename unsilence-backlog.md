# unSilence - open backlog

Canonical list of unresolved work only.

Last reconciled: **2026-09-19**, navigation completion committed in `655a683e`
on baseline `5a871f59`. This is not fresh runtime
verification of every carried product/security item. Active completion plan and
the owner's single-midpoint human-validation override:
[AUDIT_WORKPLAN.md](AUDIT_WORKPLAN.md).

**Installed release:** navigation-completion candidate based on `5a871f59`,
updated in place on 2026-09-19; certificate and phone APK hash verified
(`a0380fce…22490b5`). Its app/test source matches `655a683e`; the APK was built
before committing, not rebuilt from the commit.
No uninstall/data clear; app ID and first-install time unchanged, signed-in feed
renders. Debug and test helpers are installed alongside it for validation.
The previous exact-HEAD APK remains available for rollback.
[Current installation and validation evidence](.toolchains/nav-completion-5a871f59.CyBHuY/VALIDATION_SUMMARY.md).

**Committed navigation follow-through (`655a683e`):** settings/editors/drafts/relay sets/articles moved
to Nav3 entries; account-session store ownership added; own-profile timeline
and article comment work are visibility-scoped. **1,815 JVM tests, 9 Python
tests and 13 account-free phone tests pass**, plus **2 minified offline crypto
tests**. Final mise gate passed: lint 0 errors/118 warnings, signed release and
benchmark compilation. One intermediate phone run had two screenshot-test
failures while the phone was asleep; the unchanged APK passed all 13 after
unlocking. Failed-run logs are retained, not hidden by the successful repeat.
Owner subsequently reported **“Seems good, validated on device”** and authorized
clearly designated commits. No push. This is general device acceptance, not
proof of every remaining interaction case or a measured performance gain.
[Current execution record](.toolchains/nav-completion-5a871f59.CyBHuY/VALIDATION_SUMMARY.md).

**Prior completed validation:** 1,809 JVM tests, 9 Python tests, lint 0 errors/
118 warnings, five rendered layout/draw/clock/press tests and two minified
offline crypto/protocol tests passed for the prior audit batch. ART profile
generation returned empty on GrapheneOS; emulator capture remains pending.
These results must not be presented as tests of the new navigation candidate.

**Midpoint acceptance:** owner: “Done. No evident hiccups.”
Captured log has three committed snapshots, one 3s stop-deadline rollback and
no candidate fatal/ANR/OOM. Allocation stalls remain; no measured performance
improvement is claimed. Continue the remaining findings under the agreed
single-checkpoint override.
[Checkpoint evidence](.toolchains/audit-completion-0758322c.eqhcKV/human-validation.HIheHk/SUMMARY.md).

**Earlier diagnostic:** isolated HEAD `0758322c` plus temporary
snapshot timing probes, installed without clearing data (PID 14584). Pending
feed-resume changes are excluded. Human reports no evident issues; measurement
captured 9 committed writes, 2 coalesced requests and 3 matched onStop saves.
Encoding dominated (73–85% of onStop save time); one save committed at 3.052s
after cancellation with no timeout result. No OOM in this smaller workload does
not close the previous large-buffer failure. Diagnostic has been replaced;
it was not a production fix.
[Timing evidence](.toolchains/snapshot-timing-0758322c.Xr6Mt1/validation.Zb05KN/SUMMARY.md).
[Clean HEAD rollback](.toolchains/head-0758322c.EAPq0l/STATUS.md).

Security reconciliation carried from 2026-08-08 (`dc82c5a0`): closed since that audit:
NIP-18 attribution, H-1/H-2/H-3 zap/NWC trust, H-9 NIP-05, H-10/H-17 media SSRF,
H-4/H-5 follow safety, H-6/H-7/H-8 mute durability, H-11/H-13/H-14 composer+parser
DoS, H-15 upload metadata scrub, the MES eviction/admission + tags-storage
performance work, and the harden track: R-2 (relay capability escalation +
search routing, `6429a740`), M-36 (favicon privacy, `51baac29`), the relay-pool
lifecycle slice M-11/M-12/M-15 + H-12/M-16 (`4db83c1d`), and M-14 (NIP-42
auto-auth gating, `dc82c5a0`). Completed work is deliberately removed; its
history lives in git. Re-check each item against the current tree before
implementation because surrounding code may have moved.

---

## UI/UX audit — September 2026

Source: the owner's supplied Fable 5.1 audit. **UI-01…UI-20 retain its original
numbering**, separate from the security audit's H/M IDs. Source patterns are not
proof of the audit's frame-time, allocation, or leak estimates; measure those
claims before calling a performance fix successful.

**Status:** most UI implementation, including nested navigation/lifetimes, is
committed. The agreed midpoint and the owner's subsequent device acceptance of
`655a683e` are recorded above. UI-01 and UI-14 retain
compatibility/performance validation; measured profiles and matched workload
evidence remain open. The audit's payoff order was 02, 01, 14.
Keep **UI-02 + UI-07 + UI-08 in the same workstream/week**, not isolated cleanups.
UI-15/16 are independent compatibility fixes. This ordering does not authorize a
broad rewrite.

### Butter — frame time and motion

- **UI-01 — VALIDATION ONLY: bottom-bar touch protection and inset follow-through.**
  `1c8ac453` moves constant bottom clearance into scrollable contentPadding on
  Feed, own Profile, Notifications, and every Search list. Full-height content
  extends behind the bar; immersive retains zero inset. Full local checks passed
  and the owner accepted the observed layout. Do not restore outer viewport
  padding: the first candidate's black strip was rejected and reverted.
  Owner then reported Home taps opening a note underneath. `a37a4f6f` makes tab
  slots fully selectable and uses a moving Surface to block background tap-through;
  the touch barrier moves away with the hidden bar. Full local check passed and
  the owner accepted the phone checkpoint, explicitly approving the rectangular
  grey press feedback. PID stayed unchanged; captured logs had zero app fatal/ANR
  entries. See local
  [touch validation](.toolchains/ui01-nav-touch-1c8ac453.SB9cwP/VALIDATION_SUMMARY.md).
  Remaining coverage: explicitly check last-item reachability, keyboard/IME,
  immersive entry/exit, window resizing and other system-navigation modes/devices
  beyond the general phone acceptance. A workload-matched frame comparison is
  required before claiming an overall speedup; the diagnostic captures differ.
  Local [validation evidence](.toolchains/ui01-scroll-padding-7b591a62.nq9oFA/VALIDATION_SUMMARY.md)
  records results and limits for the committed layout (not the new touch fix).

- **UI-02 — COMMITTED; structural tests pass and owner accepted on device.**
  `655a683e` extends the Nav3 shell work (`68e63f36`, `af47d122`) to own-profile
  editing/settings, every settings child, draft resumption, relay-set editing,
  article reading and article comments. One shared entry/motion policy retains
  actual caller history, including article → profile → Back → article and
  settings → drafts → composer → Back → drafts. Route state contains IDs/hints,
  not whole posts, drafts, profiles or relay sets. Dirty/publishing composer
  guards remain intentional; editor save completion is RESUMED-only.
  Article reading position/focused-comment state is saveable; the viewport waits
  for parsed body and initial comments before consuming its restored anchor.
  Restored editors resolve their IDs before rendering (a missing item never
  becomes a blank new item). Pending image-upload results go to the current editor, not callbacks
  captured by a disposed composition. Existing NIP-36 consent remains mandatory
  for comments; the explicitly opened main article remains intentionally ungated.
  Article player ownership uses the visit ID and existing navigation gate.
  In-flight settings writes delay Back/header dismissal until they finish;
  ordinary Back remains navigation-owned. This is not a durable publication
  journal or protection against process death/account teardown.
  Phone assertions pass for cover/pop ownership, fake account replacement,
  saved-state restoration, queued-save dismissal and deferred article anchors.
  Remaining: real predictive-back cancellation, keyboard/photo-picker/draft
  behavior, process recreation, deep links and media handoff. StateRestorationTester
  coverage is not a process-kill test, nor an account-changing integration test.
  No measured frame/heap improvement claimed. Coordinate with UI-07/08.

- **Related persistence/scroll follow-through — OPEN.**
  `dab747b3` commits bounded atomic snapshot streaming, account-fenced restore
  readiness and deadline outcomes. Feed source/filter/anchor restoration is
  implemented; the owner accepted the midpoint. Its capture contains three
  committed snapshots (~41–43 MB), one three-second stop-deadline rollback and
  allocation stalls, without captured candidate fatal/ANR/OOM. The prior
  whole-section byte-array writer is no longer current; the earlier withdrawn
  candidate/rollback logs remain historical evidence, not current source status.
  Existing selection copies and per-record UTF-8 allocations remain. Investigate
  remaining deadline/allocation cost with matched phone workloads; preserve the
  three-second budget and atomic rollback. Intermittent jumpy scrolling was
  reported, but its cause/attribution and resolution are not established.
  [Accepted midpoint capture](.toolchains/audit-completion-0758322c.eqhcKV/human-validation.HIheHk/SUMMARY.md).
  [Prior timing evidence](.toolchains/snapshot-timing-0758322c.Xr6Mt1/validation.Zb05KN/SUMMARY.md).
  [Earlier withdrawn-candidate evidence](.toolchains/snapshot-streaming-0758322c.PyqIbd/validation.GHSyYn/SUMMARY.md).

- **UI-03 — IMPLEMENTED, RENDERED TESTS PASS: first-frame “Show more” geometry.**
  Exact formatted text/emoji preflight now replaces onTextLayout state feedback;
  also cuts the media/quote tail before composition. On-phone tests pass for
  normal text and 2x-font blockquotes: first measured height is final, hidden
  quote lookups remain zero until expansion. Only composed video runs register
  playback permission. Broader mixed-media/emoji/window-width matrix remains;
  these tests are not a scroll-frame performance comparison.

- **UI-04 — IMPLEMENTED, DRAW-PHASE TEST PASS: draw-phase pull feedback.**
  Shell passes State; snapshotFlow drives the spring, Canvas reads its value and
  cursor alpha. Bar geometry arrays are hoisted. On-phone pixel test verifies
  changed bar height without parent recomposition or resizing. Full navigation-
  shell drag/spring frame measurements remain; no measured FPS claim.

- **UI-05 — IMPLEMENTED, LEAF RECOMPOSITION TEST PASS: live relative timestamps.**
  One root STARTED-lifecycle clock, immediate resume refresh, timestamp-leaf
  reads across cards/quotes/parents/notifications. Boundary tests pass. On-phone
  clock update changes “now” to “1m” without recomposing the parent. Resume and
  wall-clock discontinuities remain outside rendered device coverage.

- **UI-06 — IMPLEMENTED, DEVICE VALIDATION PENDING: deliberate haptics.**
  Non-replayed signed-reaction/zap confirmation flow with one resumed root
  collector; one threshold pulse per pull and one user-driven revolver settle.
  No initial/programmatic settle feedback. Threshold regression tests pass.
  Cover successful zaps/reactions, refresh-threshold crossing, and notification
  revolver settle. Check available Compose/platform feedback types when
  implementing; honor device preferences and fire once per transition, never per
  animation frame or failed action. Verify on the phone rather than assuming feel.

### Lean — work paid for but not seen

- **UI-07 — IN PROGRESS: make WoT updates per-card instead of replacing list hosts.**
  The UI-02/08 slice committed in `68e63f36` replaces whole-map remember keys with stable
  State-backed lookup providers across card-hosting screens. Author/meta leaves
  read structurally compared derived state; unit tests verify unrelated hydration
  does not invalidate a card and that additions/removals use the current map.
  Phone validation and runtime recomposition evidence remain. Standalone person/
  notification summaries have separate map-based paths. Ship with UI-02/08.

- **UI-08 — IN PROGRESS: scope overlay ViewModels and card-data collectors correctly.**
  Follow-up committed in `af47d122` replaces cached `stateIn(viewModelScope)` jobs
  with cold `CardDataFlow` handles and fresh synchronous snapshots. Cache eviction
  cannot strand sharing jobs or cancel a visible card. Full JVM tests pass, including
  cache eviction/remount cases. Actual runtime retention evidence is still pending.
  The earlier committed work moves thread/user-profile stores to navigation entries and
  gates their work by visibility. Profile timeline handles close on cover/pop;
  tests cover cancellation, resumption and repeated cycles. `TimelineCardData`
  is now ViewModelScoped with two bounded 500-entry caches (profile and stats).
  `655a683e` adds a replaceable account-session store for shell VMs
  and entry ownership for the remaining settings/editors/readers. Own-profile
  subscriptions now close on cover, retaining loaded pages for return; article
  comment fetches/warming are RESUMED-only and cancellation-scoped. Account-free
  phone assertions verify that popped entry stores clear, covered entries keep
  state without active content, and account replacement clears shell plus nested
  stores. Actual app collector/heap measurements remain open; probe-VM ownership
  tests do not establish a heap plateau or absence of repository retention.
  Sensitive-content mode is intentionally one shared, always-current repository
  state (fail-closed before load), not a stopped per-screen cache that could
  replay stale SHOW. The ordinary WoT display preference is subscriber-driven.
  **Do not restore the old caller-scope `stateIn` cache or promote it to Singleton.**
  That implementation reused flow jobs owned by individual ViewModels. The new
  cold handles retain entry-local bounded caches without owning jobs. Measure active
  collectors and retained memory across repeated thread/profile open/pop cycles,
  including recreation. The audit's “50 threads” leak estimate is not a measurement.

- **UI-09 — IMPLEMENTED, VALIDATION PENDING: avoid duplicate author-profile subscriptions within a card.**
  Committed in `af47d122`: avatars receive their parent's resolved picture; standalone
  avatar chips collect at their own leaf. Article layouts reuse the already-resolved
  author. Lifecycle-aware collection is preserved. Full JVM suite passes; phone
  acceptance covers the midpoint batch; subscription-count measurements remain.
  The original audit found `EventCard.kt` resolving the author and `AvatarImage`
  subscribing again; reposts added more. Do not globally replace lifecycle-aware
  collection with `collectAsState`: cached lazy items and covered/background
  screens do not all immediately leave composition. Verify subscription counts
  and background/covered-screen suspension alongside UI-02/08.

- **UI-10 — IN PROGRESS: defer ViewModels not needed for first paint.**
  Committed in `af47d122`: relay/zap/graph picker are entry-created, notification VM is tab-created;
  unread badge is repository-backed without actor hydration. Graph startup policy
  remains active through a small repository flow. Deep-link handling stays eager.
  Integration tests and cold-start/first-entry runtime checks remain.
  The original shell created seven ViewModels up front. Preserve required auth,
  startup graph, and cold-start deep-link routing instead of deferring them blindly.
  Compare cold-start timing and initial collectors, then test each first tab open.

- **UI-11 — IMPLEMENTED, VALIDATION PENDING: collect relay health only while its sheet needs it.**
  Committed in `af47d122`: shell collection removed; FeedSelectorSheet collects it
  locally. Confirm dismissal cancels this UI subscription without
  disrupting repository-owned relay management or creating a second monitor.

- **UI-12 — IMPLEMENTED, DEVICE VALIDATION PENDING: shared typography.**
  Eight shared size/line-height styles now cover 390 Text calls in 60 files;
  explicit non-matching heights, special sizes and weights are preserved.
  Material body styles use the same constants. JVM gate passes.
  Map repeated size/line-height/weight combinations to a small semantic style set
  (the audit proposes eight) using `UnsilenceTypography`; keep intentional variants.
  Apply incrementally, checking baseline alignment, line height, and large-font
  layouts. Measure allocation benefit rather than treating every individual Text
  argument as a demonstrated performance problem.

- **UI-13 — OPEN: measured startup/baseline profiles and evidence-led R8 rules.**
  Explicit ProfileInstaller 1.4.1 and separate source-symbol `baselineProfile`
  generation variant are implemented. Cold-start and populated-feed-scroll
  generators compile and both workloads ran on the phone, but capture failed:
  GrapheneOS disables ART JIT profiling (`dalvik.vm.usejit=false`), yielding empty
  profiles. Needs a stock Android API 33+ device or suitable emulator; preserve
  the phone's security settings. Host KVM is available. Emulator binary downloaded,
  API-35 image download interrupted at the owner's commit boundary; no AVD or
  test identity created. Resume `./dev profile:emulator:setup` when testing resumes.
  Existing handwritten profile remains unchanged; no measured profile claim.
  Evidence: `baseline-generation-device-1.log`, `profile-device-properties.log`
  under `.toolchains/audit-completion-0758322c.eqhcKV/`.
  Broad app Jackson/BC/Metadata keep rules were narrowed after dependency/rule
  inspection (BC is absent; Quartz/Kotlin consumers retain models/metadata).
  Both minified device tests pass: NIP-04/NIP-44 round trips, signed-event JSON
  and Amber's actual empty-collection mapper. Live Amber authorization remains
  outside automated coverage. Quartz's
  consumer rules still independently disable obfuscation (security M-74).

### Sleek — visible UI and compatibility

- **UI-14 — VALIDATION ONLY: all-version sensitive-content protection.**
  Implementation shipped in `7b591a62`: opaque, uncomposed hidden bodies on all
  Android versions; independently gated quotes/reposts; immersive protection;
  live-content playback consent for autoplay/fullscreen/resume. The persisted
  `BLUR` preference now presents “Tap to reveal”; HIDE remains compact.
  Feed-matched reveal sizing is accepted by the owner: one-third of usable window
  height, embedded quotes at 90% of that base (both with a 96dp floor), normal feed
  gutters, and the shared 8dp media radius. Revealed bodies retain the minimum and can grow.
  Remaining: explicitly exercise ordinary/fullscreen/immersive video, hidden →
  revealed → re-hidden states through mode changes/re-entry, independent nested
  consent, audio and background resume; cover API 26/29/30 and current Android,
  long warnings, large fonts, landscape/multi-window, and accessibility.
  This protects tagged content, not
  untagged content via an NSFW classifier. The deliberately opened full article
  reader remains outside this gate's scope. Do not restore blur/alpha as a gate.
  Local [layout/automated validation](.toolchains/audit14-feed-parity.z6Zhq0/VALIDATION.md)
  records owner acceptance, 1,729 JVM tests, 9 Python tests, lint and builds passing.
  A separate [exact-HEAD release install](.toolchains/head-7b591a62.zZ0cSk/STATUS.md)
  verifies signature, on-phone APK hash, and startup without clearing data.
  Neither record establishes exhaustive playback coverage or a measured speedup.

- **UI-15 — IMPLEMENTED, VALIDATION PENDING: keep system-bar icons visible on the always-dark app.**
  Committed in `af47d122`: both system bars use explicit dark styles; checks pass.
  Replaced `enableEdgeToEdge` system-theme defaults. Verify system light/dark mode, gesture
  and three-button navigation, and screen-specific window changes.

- **UI-16 — IMPLEMENTED, VALIDATION PENDING: complete or remove compat splash setup.**
  Committed in `af47d122`: installSplashScreen runs before super.onCreate; checks pass.
  The existing splash dependency/theme are now activated. Verify black startup
  and the final window background on pre-31/current devices; measure any overdraw
  claim instead of assuming a persistent extra layer is already demonstrated.

- **UI-17 — IMPLEMENTED, DEVICE VALIDATION PENDING: image arrival and precision.**
  120ms crossfade, full-color default, RGB565 only on opted-in sized feed media.
  Feed decode cache keys are separated so viewers cannot reuse a RGB565 bitmap.
  8% cache remains unchanged. Compile/JVM gate passes, device quality/PSS pending.
  The previous global no-crossfade/RGB565 settings have been replaced. Check
  memory-hit behavior and retain full-color decoding for fullscreen viewing.
  Compare dark-gradient quality, back-scroll misses, PSS, and GPU work before
  changing the cache budget; do not increase it solely on the audit's assertion.

- **UI-18 — IMPLEMENTED, DEVICE VALIDATION PENDING: shared Foundation press tint.**
  Theme-level bounded grey tint handles default and previously silent clickables,
  including the note body/card. Material controls retain standard indications.
  On-phone pixel assertions pass for grey tint and cancel/release cleanup;
  nested gestures and full component coverage remain.
  Owner-approved reference: the bottom navigation's rectangular grey press
  feedback (`a37a4f6f`, accepted 2026-09-17). Use this preference when evaluating
  shared feedback; Foundation controls now share that direction.
  Define a shared press-tint or scale treatment using existing surface tokens and
  apply it consistently to appropriate card/body controls. Audit nested click
  targets so a text tap does not add a second conflicting effect. Preserve
  semantics, minimum targets, and clear pressed/disabled states; validate on black.

- **UI-19 — IMPLEMENTED, DEVICE VALIDATION PENDING: visible new-post pill.**
  Accessible N-new overlay appears away from top; tap uses the existing pending
  merge path. Count uses feed safety/filter/trust policies off-main and excludes
  already displayed IDs. No per-frame read. Visibility test passes.
  Add an accessible “N new” affordance over the feed instead of relying solely on
  the small navigation dot. Keep scroll position stable until selected; define
  count/reset behavior for refresh, feed switches, dismissal, and account changes.
  Avoid a new per-frame shell state reader or continuously running counter job.

- **UI-20 — IMPLEMENTED, DEVICE VALIDATION PENDING: dividers and color tokens.**
  1dp separators, shared 10%-white divider color, hoisted feed/header gradient,
  and semantic color replacements. Bitcoin's protocol-brand orange remains
  intentional. JVM gate passes; density/contrast visual coverage remains.
  Standardize intended separators (audit proposal: 1dp at 10% alpha), hoist the
  immutable `FeedDivider` gradient, and replace accidental raw-color drift.
  Check actual density rasterization: 0.5dp is not universally a zero/one-pixel
  defect; use an explicit pixel hairline only where that is the intended design.
  Preserve purposeful exceptions and sufficient contrast at supported densities.

---

## Security — remaining audit items

Source: `docs/security-audit-2026-08-03.md` (+ reviewed ledger). Only items still
open are listed; the High trust/SSRF cluster is closed (see reconciliation note
above). Severities are the audit's.

### High

- **H-16 — production release signing not configured** (`app/build.gradle.kts`).
  The release variant still uses the debug signing configuration. The current
  Omarchy installation uses a newly generated local development key, verified
  against the installed APK; do not describe that key as a known public key.
  Deferred by owner decision until public distribution (F-Droid / Zapstore).
  Keystore ignore patterns are already in place; keep keys and credentials out of
  git. Action when distributing: establish the production key/upgrade plan and wire
  `signingConfigs.create("release")` from gitignored `keystore.properties`.

  (H-16 is the only audit High still open. H-1..H-15, H-17, and the M-14 auth
  gating are closed. **M-13 was assessed and dropped** — field logs showed isolated
  DNS/timeout/503 failures spaced minutes apart, not the audit's claimed ~16s
  reconnect metronome; reconnect-attempt retention + a 30s healthy window already
  exist. Do not re-open without fresh field evidence of a fixed-interval loop.)

### Medium (verified or load-bearing; the rest of the audit's Mediums are not re-checked)

- **M-74 — release obfuscation silently disabled by Quartz's bundled consumer
  ProGuard rules** (`-dontobfuscate`, `-keep class com.vitorpamplona.quartz.**`).
  Verified: release `mapping.txt` is ~all identity mappings despite
  `isMinifyEnabled = true`. Not a boundary, but undocumented hardening loss for an
  nsec/NWC app, and it keeps Quartz unshrunk (~19MB APK). Folds into the Quartz
  upgrade below. Until then, document "release unobfuscated by design" or strip
  Quartz's `proguard.txt` and re-verify.

### Run loose-ends (from the security work itself)

- **Enforce the zap Gate-2 rejection** — verification currently logs rejections
  (`zapAuthGate2Rejected`, `selfClaimedClient`) but still renders them anonymous.
  Capture a real-session rejection rate first; if a legitimate client sends
  unsigned inner payloads, decide accommodate-vs-anonymous on data. Only then make
  enforcement user-visible.
- **Retire the `senderPubkey`/`comment` shims** on `ZapDetail` (`Models.kt`). They
  re-flatten "genuinely anonymous" and "attribution failed verification" into one
  null; consumers (drawer, notifications) should match on `ZapAttribution` directly.
- **Quartz upgrade from 1.05.1** — 1.13.x was the previous candidate; re-evaluate
  the target and compatibility when this slice starts. The JDK-21 unit-test
  launcher can load its bytecode. Own slice with its own validation (signing,
  NIP-19, NIP-04/44, event verify); subsumes M-74 and unblocks NIP-B1 BOLT12. See
  `unsilence-nip85-wot-plan.md` / prior BOLT12 notes.
- **Promote hint relay to configured (M-14 reach recovery)** — M-14 gates NIP-42
  auto-auth to the user's chosen relays, so content whose only host is an
  auth-gated relay reached purely by hint no longer loads (the app refuses to
  attest identity to an unchosen relay). Narrow loss (open-read hint relays and all
  configured relays are unaffected), but recoverable: when a followed author's
  events repeatedly fail from such a relay, surface "add this relay to read from
  @author" — promoting it to a configured relay makes auto-auth legitimate because
  the user chose it. Approval attaches to the relay set (evaluable), never to an
  auth challenge (not evaluable). Follow-up to `dc82c5a0`.

---

## P1 - Product gaps

### Post language filtering

Add a language selector to the feed filter so users can include one or more post
languages.

- Default to `Any`; multiple selected languages use OR semantics.
- Prefer an event's explicit language tag when present. Detect language only when
  metadata is absent, off the main thread, and cache the result by event ID.
- Include an `Unknown` choice for media-only, very short, and unclassifiable posts.
- Put classification and matching in the shared feed policy, not in composables,
  so every feed row follows the same decision.
- Do not hide profiles, connection lists, relay facts, or notifications as a side
  effect of a content-language preference.
- Keep detection bounded during relay bursts; it must not delay ingest or first
  paint.
- Test explicit-tag precedence, detector fallback, Unknown, and multi-select OR.

### In-app Tor

Provide an optional embedded Tor route, without requiring a separately installed
proxy application. This is security-sensitive network architecture, not a relay
toggle. **Zero implementation today** (only `.onion` URL allowances in the SSRF
scheme allowlist). **Prerequisite now met:** the single guarded network seam the
spec below demands exists as `UntrustedHttpNetworkGuard` installed on every
app-owned client (`@ImageClient`, `@MediaClient`, OG, NIP-11, dimension probes,
and the `MediaDataSource` thumbnail reader) — the structural blocker is gone;
this is now a runtime-choice + routing feature, not an untangling job.

- Evaluate the maintained Android-compatible embedded Tor implementations before
  choosing the runtime. Record binary size, bootstrap time, maintenance posture,
  and supported Android API levels.
- Route every network client through one policy: relay WebSockets, Primal
  cache/count, NIP-11 and other HTTP probes, OG fetches, profile/media requests,
  Blossom uploads and downloads, Coil, and the Media3 data source. (The guarded
  clients above are the seam to route through.)
- Proxy DNS through Tor and support `.onion` endpoints. Do not allow a direct DNS
  lookup before the Tor route is ready.
- Offer a strict Tor-only mode that fails closed. Never show a protected/connected
  state while any app-owned network surface can bypass Tor.
- Keep external intents such as Amber signing and links opened in another app
  explicitly outside the guarantee, and state that boundary in the UI. (When Tor
  lands, NIP-05 resolution and zap-service authority fetches must route through it
  too, or a Tor-only session leaks IP to those hosts.)
- Expose bootstrap, connected, degraded, and failed states with retry/new-circuit
  controls. Relay reconnects must follow route changes without creating retry
  storms.
- Validate relay, image, video, upload, follower-count, NIP-11, and OG traffic with
  direct networking blocked. Measure bootstrap latency, battery, heat, memory, and
  recovery across airplane-mode changes.

### Compose correctness and capture

- Add camera capture as an attachment source with URI permission, cancellation,
  rotation, size-limit, compression, and process-recreation handling.
- Add a full attachment preview from a thumbnail long-press or explicit preview
  action. Reuse the existing image/video viewer rather than creating a new viewer.
- Audit upload failures and distinguish authentication, quota/size, unsupported
  media, server rejection, cancellation, and transport failures where the source
  provides that information.

### Profile and identity

- Add `Copy user ID` and Android Sharesheet actions to the user-profile overflow.
- Consider a BOLT-12 profile field (`lno1...`) with copy (no QR per owner decision).
  Does not change the existing zap flow. Depends on the Quartz upgrade for the
  NIP-B1 classes if extended to receiving.

  (The profile `website` field — previously listed here — shipped with the H-15/M-59
  profile-metadata work: `UserEntity.website` exists, the edit form prefills, and
  save merges rather than dropping unknown kind-0 fields.)

### Moderation controls

- Make muted-user rows open the user's profile while retaining the dedicated
  unmute action.
- Let users choose public or private publication when adding mute words, hashtags,
  and users. Preserve the current private default.
- Add management for muted event IDs and an in-app mute-note action if event mutes
  are exposed to users.

### Android entry and search

- The manifest `nostr:` intent filter EXISTS (`AndroidManifest.xml`, `scheme=nostr`
  + `NOSTR`), with parser/routing unit coverage in `DeepLinkRouterTest` for entity
  types, uppercase schemes, malformed/secret inputs, and consume-once behavior.
  Remaining: instrument Activity/navigation delivery on cold and warm start and
  while logged out, including malformed input and process recreation. Re-run this
  integration matrix during UI-02; do not duplicate existing parser tests.
- Persist bounded, account-local recent searches and show removable recent-query
  chips before results.

## P2 - Protocol and social expansion

### Relay sets and directory

Relay discovery is built (kind-30166 parse, `RelayDirectory`,
`RelayDiscoveryScreen`, per-hop icon guarding). Remaining refinements only:

- Discover NIP-66 monitors through kind-**10166** instead of the current
  hardcoded/seeded monitor pubkeys. Vet, rank, and bound the selected monitor set.
- Make `RelayDirectory` the single kind-30166 parse path and remove the partial
  `MemoryEventStore.handleRelayMonitor` parser after parity tests.
- Consolidate the two navigation paths that open relay management.

### Addressable video follow-through (short-form authoring)

- Add short-form video **capture and publishing** (kind-22 / 34235 / 34236).
  Consumption and the immersive feed are done; authoring needs its own capture,
  metadata, poster, upload, and publish UX. This is "short-form authoring."

### WoT and identity trust

- Add homoglyph-aware impersonation detection v2. The current v1 check flags only
  edit distance <= 1; retain its bounded candidate policy while adding script-aware
  confusable handling. Design context: `unsilence-nip85-wot-plan.md`.

### Content creation and storage

- Add a native Markdown **authoring** mode for kind-30023 long-form articles
  (reading/rendering is done; the composer only writes kind-1). Reuse the existing
  renderer for preview and the existing draft store for recovery.
- Add an in-app custom **emoji-pack creator**: Blossom upload + kind-**30030** pack
  publication, editing, and deletion/replacement. (The user's own kind-10030 list
  management — add/remove which packs you use — already ships; authoring a pack does
  not.)
- Replace the custom-emoji bootstrap timeout poll with the snapshot-complete signal
  so large restores cannot miss the user's kind-10030 list.
- Add bookmark/save-for-later support (kind-10003 or the selected interoperable
  list convention), account-local optimistic state and relay reconciliation.
  **Zero implementation today.**
- Add NIP-96 uploads with scoped NIP-98 authentication for servers that require it.
  Do not attach authentication headers to unrelated hosts.

## P3 - UX and accessibility

- Complete the engagement-drawer share-menu design pass: the njump link already
  ships, but fast phone-contact sharing from the long-press sheet is still missing.
- Add a text-size setting with bounded steps and verify compact cards, profiles,
  composer controls, immersive chrome, and long-form layout at every step.
  **Not implemented.**
- Revisit notification-row density after measuring the current release on small
  and large font scales; do not reduce hit targets to gain density. Notifications
  already use shared content cards as of `478e9089`; evaluate that implementation,
  not the previous row layout.
- Add horizontal overflow for genuinely wide Markdown tables only when a real
  fixture demonstrates that the current fit-to-width layout is unreadable.

## Engineering debt

### Omarchy workflow — fresh-clone and signing follow-through

- The mise/`./dev` setup, Linux JDK discovery, development instructions, benchmark
  module and analysis tools are committed separately in `717bfeff`. The backlog is
  versioned. Still verify a fresh checkout can provision and pass `./dev check`;
  successful use of this populated local toolchain is not a fresh-clone test.
  Keep local evidence/toolchains ignored; never force-add keys or generated APKs.
- Confirm recoverable, private backup of the current local signing key before
  retiring the old workstation/setup. Release upgrades now use the matching local
  key without uninstalling; production signing remains the separate H-16 item.

### Shared policy and pipelines

- Extract the duplicated notes/replies/reposts predicate used by feed, both profile
  view models, and the memory store. Pin every supported repost/video kind in one
  policy test. PARTIAL (c211f1b0): both profile view models share
  `ProfileContentPolicy` (1111-aware); the feed and memory-store copies remain.
  (NIP-18 repost UNWRAP is now single-path via `Nip18Repost`/`NostrEventDto`, and
  ingest decode is unified — `Subscription` no longer parses independently — so the
  earlier "consolidate Subscription.parseEvent and EventProcessor.handleEvent" item
  is retired.)
- Reduce `ProfileViewModel` and `UserProfileViewModel` duplication after their
  surface contracts are stable; prioritize shared state/policy rather than a large
  UI rewrite.

### Media performance

- Define one GIF/animated-image policy: animate only near the viewport, cap active
  decoders, pause during fast flings and Battery Saver, and downsample to display
  size. Validate Coil stream ownership before changing decoder plumbing.
- Consider a dedicated one-ahead Media3 preloader for immersive video only if
  release measurements still show first-frame pop-in. Keep the one-decoder thermal
  constraint and Battery Saver behavior.
- Optional polish: prefetch OG link-preview metadata one viewport ahead riding the
  existing hydration sweep, so preview cards stop popping in during scroll. Decline
  explicitly rather than dropping if judged not worth the network cost.
- Blossom mirror failover for media: when imeta carries an `x` SHA-256 and the
  primary host stalls, resolve the author's kind-10063 server list and retry the
  same hash on their mirrors (imeta `fallback` URLs first when present). Structural
  remedy for degraded single-origin media hosts (2026-07-14 DiVine incident).

### Memory / performance (post-investigation state)

- MES stage-2 cap raise is **not needed**: measurement proved caps are the wrong
  lever (raising costs heap, lowering doubles churn). Admission control + tiered
  eviction fixed the churn; the tags-storage diet cut per-event footprint; the
  memory estimator is now trustworthy (~1.28x MAT, was 0.35x). Graphics PSS was
  investigated and explained (fixed HWUI/window-buffer floor + bounded caches, no
  leak in that investigation) — no cap action. This historical result does not
  close the separate overlay-lifetime/cache investigation in UI-08. Leave here
  only as a pointer so nobody re-opens the cap debate without new evidence.

- Reconcile the historical `PERFORMANCE_EXPERIMENTS.md` ledger with the September
  device captures before selecting the next measured candidate. The unchanged
  `478e9089` [full FrameTimeline capture](.toolchains/f01-fulltrace-478e9089.GyAM2f/SUMMARY.md)
  contains 9,519 main-window slices over 180.53s, 0.630% classified app misses, and
  12.179ms p95 actual duration. It is diagnostic context, **not a controlled
  text-only baseline or a candidate comparison**: media activity was present and
  the interval includes instruction/response time. Buffer stuffing is not a
  dropped-frame percentage; PSS growth alone does not establish a leak or cause.
  Capture paired, workload-matched release runs for UI-01 and UI-02/07/08 with
  declared acceptance criteria. Keep the failed short-tail run and its exclusions
  as history, not a successful baseline. Evidence paths under `.toolchains` are
  local/ignored and need deliberate preservation if used for a durable report.

### Persistence

- Persist the derived WoT assertion snapshot so cold start can restore trust state
  without refetching it. Decision context: `unsilence-nip85-wot-plan.md`.
- Retire the legacy V2 TSV snapshot reader/writer once supported upgrade windows no
  longer require it. Until retirement, escape or reject tab/newline characters in
  serialized relay URLs.
- Account for the snapshot owner-prefix length before using binary section offsets
  for lazy seeking; current restore is sequential, so the offsets remain
  informational.

### Test infrastructure

- Inject the WebSocket factory needed to unignore `RelayPoolInvariantsTest`.
- Macrobenchmark tooling is committed in `717bfeff`; the feed-fling benchmark and
  startup/scroll profile generators compile. Measured profiles remain open (UI-13:
  GrapheneOS empty capture, emulator setup deferred). Add immersive entry and
  profile/composer journeys and run the performance comparisons. The resumed
  navigation pass has 13 debug device assertions and two minified crypto checks;
  these are not macrobenchmark measurements. The agreed midpoint and subsequent
  owner device acceptance are recorded above. Measured profile generation and
  broader performance/device coverage remain deferred.
- Use the verified GrapheneOS capture path rather than treating Perfetto as
  unavailable. FrameTimeline recording worked on the current Pixel 9 Pro XL
  (Android 17/API 37) without changing OS security settings. CPU scheduling/ftrace
  startup failed in preflight and was omitted from the successful recording;
  this does not establish support for every atrace/ftrace source or benchmark
  metric. Retain `am start -W` and `dumpsys gfxinfo/meminfo` as complementary checks.
- Crypto/signing now HAS executable coverage: the JDK-21 unit-test launcher
  (`gradle.properties` installations + `Test` task `javaLauncher`) loads Quartz's
  Java-21 bytecode, so `SignatureVerifierTest` runs real Schnorr (M-76 closed).
  Remaining gap: Android-integration paths that still need instrumented tests
  (EncryptedSharedPreferences, Amber intents).

## Validation and reproduce-first items

These are not confirmed implementation defects. Attribute them before changing
production behavior.

- Capture the pending API 26 and API 29 immersive engagement-overlay screenshots
  over active video and check for SurfaceView black frames or compositing artifacts.
  Coordinate with UI-14's device matrix, but keep overlay compositing and hidden
  content/playback protection as separate acceptance checks.
- Reproduce the carried kind-10002 mid-session own-pubkey update edge before adding
  another bootstrap or subscription path.
- Field-validate the engagement coverage/retry fix in `7ac6d2d9` against the old
  “zero until opening the note” symptom. Reopen implementation work only with a
  fresh reproduction and REQ/EOSE/timeout evidence; do not treat the old retry
  behavior as unchanged.
- Reproduce the rapid relogin/onboarding session-fence window before coupling key
  persistence more tightly to bootstrap teardown.

## Backlog rules

- This file contains unresolved work only. Delete an item when it ships; do not
  retain crossed-out completion history here. An audit ID may remain as
  **validation only** when implementation is committed but named acceptance checks
  are still open; remove it after those checks close and keep its ID in history.
- Use git commits and focused plan files for implementation history and acceptance
  evidence. Keep source-verified findings, measured results, owner visual approval,
  and pending device coverage distinct. Local/ignored artifacts are not a durable
  replacement for a versioned report.
- Field observations enter this file only after a stable reproduction or as an
  explicitly labeled reproduce-first item.
