# UI/UX Performance Experiment Ledger

Historical experiment plan imported from the earlier macOS workflow. July paths,
APKs and pending run sets below are preserved as provenance, not current Omarchy
validation. September audit status and the owner's single-midpoint-check override
are recorded in `AUDIT_WORKPLAN.md` and `unsilence-backlog.md`.

This ledger makes performance design decisions inspectable. A change is not
called an improvement because it feels plausible, compiles, or produces one
good run.

## Design flow

1. Observe a user-visible problem on a release build.
2. State a falsifiable mechanism and predicted user benefit.
3. Predeclare the workload, primary outcome, guardrails, and decision rule.
4. Measure the unchanged build on a physical device.
5. Make the smallest intervention that tests the mechanism.
6. Compare paired runs, preserving failures and null results.
7. Keep, revise, or revert from the evidence.
8. Validate the full user flow with a human before shipping.

The desired product flow is:

```text
user intent → immediate visual acknowledgement → stable content →
only work that can affect the visible result → quiet idle state
```

Every proposal must say which edge it improves. Visual decoration by itself is
not a performance hypothesis.

## Measurement contract

### Experimental unit

One complete, human-executed gesture script is one run. Frames within a run are
correlated and are descriptive samples, not independent experimental units.
Confirmatory inference uses paired run summaries.

### Builds and device

- Physical device: Pixel 9 Pro XL (`komodo`), Android 17.
- Build type: minified, non-debuggable release.
- Baseline commit: `2abb9cb3efbf76f4e5bee63123a5c14a9bb83b57`.
- Preserved measurement APK SHA-256:
  `15cd8f25d97915637aaf65ce35d2d6fa59de6910c8e415c739bab4824ec96123`.
- Installed APK was byte-for-byte verified against that exact artifact on
  2026-07-29. A prior build of the same commit had SHA-256 `a12cd2…94bb`;
  it is not mixed into this experiment. The measurement APK is preserved at
  `/private/tmp/unsilence-perf/baseline_head_2abb9cb3/artifact/app-release-15cd8f25.apk`.
- Tests use in-place installs. App storage is never cleared or uninstalled.

Record refresh mode, brightness, network transport, battery/charging state,
thermal status, and app PID with every run set. Reject a pair if either member
is thermally throttled or the workload was interrupted.

### Workload F-01: Following-feed fling

Surface: Following feed, Notes, Show = Text. This removes video decoder and
large-image variance while stressing the main scrolling and card-hydration
path.

Human script:

1. Open the Following feed and select Notes, Show = Text.
2. Wait until visible cards are stable.
3. Perform 10 hard, fast flings, waiting about 500 ms between flings.
4. Stop completely and leave the screen untouched for 5 seconds.

The host resets `gfxinfo` and captures resource counters immediately before the
script, then captures them immediately after the human replies. Scripted
touches and swipes are prohibited.

### Pairing and order

- Minimum confirmatory sample: 7 baseline/candidate pairs.
- Alternate build order to reduce temperature, learning, and live-feed drift.
- Fixed pair order: `B→C, C→B, B→C, C→B, C→B, B→C, B→C`.
- Preserve raw files per run; never pool frames across files for inference.
- A first baseline set can locate a bottleneck. It cannot prove an improvement.

### Outcomes

Primary:

- Per-run frame-deadline miss rate:
  `FrameCompleted > FrameDeadline`.

Confirmatory support:

- Per-run p95 deadline overrun.
- Per-run p95 completion duration from intended vsync.

Descriptive:

- p50/p90/p99/max completion duration.
- Fixed `>16.67 ms` and `>33.33 ms` rates.
- Frame interval and deadline budget.

`tools/perf/analyze_framestats.py` parses raw, dynamically headed
`dumpsys gfxinfo ... framestats` sections, drops invalid and duplicate rows,
and reports a Wilson interval for each run. Paired inference uses an exact
one-sided sign-flip test at 7 pairs plus a deterministic paired bootstrap
interval. The Wilson interval is descriptive; it does not turn frames into
experimental replicates.

### Predeclared frame decision rule

Frame evidence passes only when all conditions hold:

- At least 7 matched pairs.
- Mean deadline-miss rate falls by at least 20% relative.
- One-sided paired sign-flip `p ≤ 0.05`, candidate lower.
- The paired-bootstrap 95% interval for the absolute miss-rate difference is
  entirely below zero.
- The upper 95% interval for candidate-minus-baseline p95 deadline overrun is
  at most `+0.50 ms`.
- The upper 95% interval for candidate-minus-baseline p95 completion duration
  is at most `+0.50 ms`.

The last two are non-inferiority guards. They prevent an average miss-rate win
from hiding a materially worse tail.

### Resource and behavior guardrails

A frame win is not an overall pass. Also require:

- Process CPU per unit wall time: no more than 5% relative regression.
- UID received and transmitted bytes: no more than 5% relative regression for
  a matched network workload.
- Median total PSS: no increase greater than both 5% and 15 MiB.
- Thermal status: no worse category in the candidate pair.
- Battery: no regression in a later, longer fixed-duration soak. Short scroll
  runs are too noisy for an energy claim.
- No crash, ANR, blank card, lost navigation state, or interaction regression.
- Human validation follows `VALIDATION_PROTOCOL.md`.

Network and energy measurements use a separate workload from F-01 when live
relay variance would dominate the UI signal. CPU, bandwidth, and battery claims
must name their own workload and raw counters.

## Experiment H-001 — Locate the dominant Following-feed frame cost

Status: baseline measurement pending.

Observation:

The feed is the primary motion surface, but the repository has no current
release-device run distribution. Historical `gfxinfo` reports are cumulative,
include a debug emulator, and use different content; they cannot establish the
current bottleneck or a statistically defensible improvement.

Hypothesis:

One of three mechanisms dominates deadline misses during F-01:

1. UI-thread/recomposition work while cards enter the viewport.
2. Render or media upload cost.
3. Hydration/network bursts competing with visible-frame work.

Prediction and selection rule:

- High process CPU with deadline misses points first to composition/model work.
- Stable CPU with long draw/GPU completion points first to rendering/media.
- Miss clusters alongside network/hydration bursts point first to scheduling
  or fan-out work.

No production candidate is selected before the baseline artifacts identify one
of these mechanisms. That is deliberate: changing code first would convert the
baseline into a story about the change rather than a measurement of the
product.

Required artifacts:

- Seven raw F-01 baseline `framestats` files.
- Per-run analyzer summaries.
- CPU, UID byte, PSS, thermal, display, and battery-state snapshots.
- A baseline report naming what the evidence proves and what remains unknown.

Result: pending.

Decision: pending.

Attempt log:

- `f01_run01_20260729_2248` — rejected before analysis. ADB disconnected at
  the post-run boundary, leaving all post-run and frame files empty; the
  gesture-to-capture interval also exceeded the declared window. Failure
  record:
  `/private/tmp/unsilence-perf/baseline_head_2abb9cb3/f01_run01_20260729_2248/RUN_INVALID.md`.

## Experiment template

Copy this section for each candidate:

```text
Experiment:
Status:
User observation:
Mechanism:
Falsifiable hypothesis:
User-flow edge affected:
Baseline commit/APK:
Candidate commit/APK:
Workload and pairing:
Primary outcome:
Guardrails:
Predeclared decision:
Raw artifacts:
Results:
Failures/null findings:
Decision:
```
