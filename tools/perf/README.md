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

Device preparation and every human gesture window follow
`VALIDATION_PROTOCOL.md`. In particular:

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

The frame analyzer intentionally does not turn CPU, network, or battery
counters into a verdict. Those signals need workload-specific denominators and
their own paired analysis.
