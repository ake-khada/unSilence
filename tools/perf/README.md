# Release-device performance measurements

`analyze_framestats.py` turns raw Android `gfxinfo framestats` output into
per-run summaries and paired baseline/candidate evidence. It uses only the
Python standard library.

## Analyze one or more runs

```bash
python3 tools/perf/analyze_framestats.py summarize \
  /tmp/unsilence-perf/baseline_f01_01_framestats.txt
```

Use `--json` to save machine-readable results.

## Compare paired runs

The baseline and candidate lists are paired by argument position:

```bash
python3 tools/perf/analyze_framestats.py compare \
  --baseline /tmp/unsilence-perf/baseline_f01_*.txt \
  --candidate /tmp/unsilence-perf/candidate_f01_*.txt
```

Do not concatenate files. One file is one human run, and the run is the
experimental unit.

## Deterministic checks

```bash
python3 -m unittest discover -s tools/perf/tests -v
```

## Capture boundary

Use the [development validation guidance](../../DEVELOPMENT.md#validation).
Device preparation and every human gesture window must follow a predeclared
procedure. In particular:

- Build and install release in place.
- Force-stop/start and wait for bootstrap.
- Clear and expand logcat before presenting the checkpoint.
- Reset frame stats immediately before the human starts.
- Run no ADB command while the human is executing gestures.
- Capture only after the human replies.
- Never use `adb shell input` for performance evidence.

For each run, preserve:

- raw `dumpsys gfxinfo com.unsilence.app framestats`;
- logcat;
- `/proc/<pid>/stat` and `/proc/uptime` before and after;
- `dumpsys meminfo com.unsilence.app` after;
- UID network counters before and after;
- display mode, battery state, and thermal status;
- commit, APK hash, PID, device, workload, and any deviation.

Store this raw evidence only in an ignored local directory; device and host
details are not public repository documentation.

The frame analyzer intentionally does not turn CPU, network, or battery
counters into a verdict. Those signals need workload-specific denominators and
their own paired analysis.

## Replay traffic experiment (R-01)

`analyze_replay.py` analyzes version-1 cumulative `MES/traffic` snapshots.
These are independent of the memory estimates in `MES/size`. Mechanism counters
are process-cumulative and never reset:

- `req_accepted`: REQ payloads accepted by OkHttp, not confirmed wire delivery;
- `replay_req_accepted`: accepted sends at Subscription resume/reconnect;
- `event_frames`: callback text frames with the literal `["EVENT"` prefix, before
  inbox rejection/backpressure/dedup; no JSON parsing on the socket reader;
- `subscription_duplicates`: deliveries rejected by a subscription's known IDs;
- `processor_duplicates`: processor cache/admission rejections or batch coalescing.

The last two count different boundaries and must not be added as unique events.
No event IDs, pubkeys, raw filters, payloads or relay identities are included.
Counts aggregate all relay connections/subscriptions, not Timeline alone.

Snapshots also carry `v`, `elapsed_ms` (monotonic), `pid`, `uid`, `reason`,
`uid_status`, `uid_rx` and `uid_tx`. TrafficStats bytes cover the entire app UID,
including other app traffic. Negative values or an observed decrease invalidate
bytes for that process series; they are emitted as null, never zero. Atomics are
read consecutively, not as a transactional view of all pipeline stages. The
existing metrics logger serializes periodic and off-main lifecycle-stop writes;
stop sampling does not traverse MES. Baseline and candidate use identical probes.

### Predeclared workload and rules

Use Following → Notes → Show = Text, the same account, relay configuration and
network transport. Settle first. Capture a fresh pre-run cumulative sample before
presenting the checkpoint. Owner presses Home for 15 seconds, reopens the app,
leaves the visible feed untouched for 60 seconds, and presses Home for the final
stop sample. Capture logs/counters only after the owner replies done. Do no host
or device work during the gesture window. Record sample boundary timestamps and
any delay; do not silently substitute a stale periodic sample or a later idle
sample. Reject deviations rather than repairing them with estimates.

First collect three baseline reconnaissance runs. Require usable direct bytes,
accepted replay REQs and duplicate deliveries before implementing a candidate.
Reconnaissance is feasibility/noise evidence, not a performance result. For
confirmation use seven matched A/C pairs in this fixed order:
`A→C, C→A, A→C, C→A, C→A, A→C, A→C`. Every arm swap installs an archived APK by
path and verifies its hash, signer and retained app identity/data; rebuilding
from a candidate working tree does not restore the baseline.

Reject process death, UID/PID changes within a run, missing/conflicting samples,
unsupported counters, any counter reset (including intermediate samples), account/
relay-set/transport changes, thermal problems, interruptions or workload changes.
Resolve the UID from the actual installation. Preserve every raw/rejected run.
Check and archive raw `/proc/net/xt_qtaguid/stats` if accessible. Its parser resolves
columns by header and sums every row for the selected UID. If it is inaccessible,
cold-start byte evidence is unavailable unless another direct source is validated.
Bucketed netstats history, JSON lengths and MES estimates are not substitutes.

Targets, declared before candidate measurement:

- Whole-UID RX reduction of at least 10%, paired-bootstrap 95% C-minus-A interval
  entirely below zero, and one-sided paired sign-flip p ≤ 0.05.
- RX improvement exceeds baseline-baseline and foreground-idle drift. The analyzer
  conservatively compares the smallest interval-supported saving with the maximum
  absolute A/A RX difference and idle RX scaled to the longest primary run.
- Accepted replay REQs do not increase. EVENT frames and each distinct duplicate
  count per accepted replay REQ have paired 95% intervals below zero.
- Mean whole-UID TX does not regress more than 5%.
- Unique delivery, confirmed coverage, reconnect behavior and human feed continuity
  remain correct; these require separate functional evidence and owner acceptance.

Use at least one baseline-baseline pair and foreground-idle controls with each arm.
Each baseline-baseline control has a shared pair ID and two distinct A runs.
Each idle control pair has a shared pair ID and one A plus one C run. Pair IDs
must not be reused across primary, baseline-baseline or idle pairs. Run IDs and
sequence numbers must be unique across the entire evidence set; controls cannot
reuse primary runs. UID, byte source and configuration must match the primary
workload. Idle controls use the same surface/configuration for 60 seconds without a resume.
Run one cold-start A/C sanity pair to guard unchanged initial dispatch; that pair
cannot statistically establish cold-start bandwidth savings. Each complete human
run, not each event/frame/sample, is an experimental unit. Sign-flip and deterministic
paired bootstrap reuse the frame analyzer's helpers. Mechanism improvement without
the RX criterion supports only a duplicate-count claim. RX movement without replay
mechanism evidence is unrelated/noisy traffic. No per-relay byte attribution.

### Analyzer input and commands

Keep run manifests/raw captures in ignored local storage. Each manifest uses:

```json
{
  "v": 1, "run_id": "synthetic-A1", "pair_id": "pair1", "arm": "A", "sequence": 1,
  "workload": {
    "name": "resume-v1", "surface": "Following", "filter": "Notes", "show": "Text",
    "transport": "synthetic-wifi", "configuration": "synthetic-unchanged",
    "home_seconds": 15, "visible_seconds": 60
  },
  "workload_end": {
    "name": "resume-v1", "surface": "Following", "filter": "Notes", "show": "Text",
    "transport": "synthetic-wifi", "configuration": "synthetic-unchanged",
    "home_seconds": 15, "visible_seconds": 60
  },
  "human_complete": true, "thermal_ok": true, "deviations": [],
  "byte_source": "trafficstats", "start_elapsed_ms": 1000, "end_elapsed_ms": 81000,
  "log": "logcat.txt"
}
```

Record configuration identity privately without exposing keys. `workload_end` is
independently checked after the run; it must match. `sequence` records actual run
order. The log must contain exact start/end samples and all intervening snapshots.
Alternatively embed `samples` with the same versioned objects; both inputs together
are rejected. Exact duplicate log lines are tolerated; conflicting samples are not.
For validated direct counters, set `byte_source` to `direct` and supply
`direct_uid_samples` containing `v`, `elapsed_ms`, `uid`, `pid`, `source`
(`xt_qtaguid` or `trafficstats`), `uid_rx`, `uid_tx`. Supply the actual
`byte_start_elapsed_ms` and `byte_end_elapsed_ms`; each may differ from the app
boundary by at most 1000 ms, with offsets and byte duration reported explicitly.
Do not manufacture coincident timestamps from separately timed reads. Use the
same capture method for both arms; reject longer offsets.

```bash
python3 tools/perf/analyze_replay.py extract /tmp/replay/logcat.txt
python3 tools/perf/analyze_replay.py xt-qtaguid /tmp/replay/xt-stats.txt --uid 10001
python3 tools/perf/analyze_replay.py summarize /tmp/replay/run-A1.json
python3 tools/perf/analyze_replay.py compare \
  --baseline /tmp/replay/A*.json --candidate /tmp/replay/C*.json \
  --baseline-control /tmp/replay/control-A1.json /tmp/replay/control-A2.json \
  --idle-control /tmp/replay/idle-A.json /tmp/replay/idle-C.json
```

The example UID is synthetic; actual capture must resolve it dynamically. Lists
are paired by position. The analyzer reports quantitative gates, not final owner
acceptance. Synthetic standard-library tests run in `./dev check`.
