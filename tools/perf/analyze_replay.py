#!/usr/bin/env python3
"""Cumulative replay mechanisms and direct whole-UID bytes; one human run per unit."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import statistics
import sys

from analyze_framestats import paired_bootstrap_mean_interval, paired_sign_flip_p_value

COUNTERS = ("req_accepted", "replay_req_accepted", "event_frames",
            "subscription_duplicates", "processor_duplicates")
BYTES = ("uid_rx", "uid_tx")
PAIR_ORDER = ("AC", "CA", "AC", "CA", "CA", "AC", "AC")


class ReplayError(ValueError):
    """Evidence does not support a run or paired comparison."""


def integer(value, field):
    if type(value) is not int or value < 0:
        raise ReplayError(f"{field}: missing, unsupported or negative counter")
    return value


def parse_app_samples(text):
    """Read only the versioned traffic line, never MES memory/heap estimates."""
    samples = []
    for line in text.splitlines():
        match = re.search(r"\bMES/traffic\s*:\s*(\{.*)$", line)
        if match:
            try:
                sample = json.loads(match[1])
            except (ValueError, TypeError) as exc:
                raise ReplayError("malformed traffic snapshot") from exc
            samples.append(sample)
    if not samples:
        raise ReplayError("missing traffic snapshots")
    return samples


def parse_xt_qtaguid(text, uid):
    """Use header names and sum EVERY matching UID row, regardless of tag/set/iface."""
    integer(uid, "uid")
    lines = [line.split() for line in text.splitlines() if line.strip()]
    if not lines:
        raise ReplayError("missing xt_qtaguid header")
    header = lines[0]
    required = ("uid_tag_int", "rx_bytes", "tx_bytes")
    if len(set(header)) != len(header) or not set(required) <= set(header):
        raise ReplayError("missing/conflicting xt_qtaguid columns")
    indexes = {name: header.index(name) for name in required}
    rx = tx = matches = 0
    seen_indexes = set()
    for fields in lines[1:]:
        if len(fields) != len(header):
            raise ReplayError("malformed xt_qtaguid row")
        try:
            row_uid = int(fields[indexes["uid_tag_int"]])
            if row_uid != uid:
                continue
            if "idx" in header:
                row_index = fields[header.index("idx")]
                if row_index in seen_indexes:
                    raise ReplayError("conflicting xt_qtaguid row index")
                seen_indexes.add(row_index)
            rx += integer(int(fields[indexes["rx_bytes"]]), "rx_bytes")
            tx += integer(int(fields[indexes["tx_bytes"]]), "tx_bytes")
            matches += 1
        except ValueError as exc:
            raise ReplayError("invalid xt_qtaguid counter") from exc
    if not matches:
        raise ReplayError("UID absent from xt_qtaguid; not zero traffic")
    return {"uid": uid, "uid_rx": rx, "uid_tx": tx}


def checked_series(samples, fields, start, end, uid=None, pid=None, direct=False):
    selected = {}
    for sample in samples:
        if not isinstance(sample, dict) or sample.get("v") != 1:
            raise ReplayError("unsupported sample version")
        time = integer(sample.get("elapsed_ms"), "elapsed_ms")
        if not start <= time <= end:
            continue
        if time in selected and sample != selected[time]:
            raise ReplayError("conflicting samples at the same timestamp")
        selected[time] = sample
    if start not in selected or end not in selected:
        raise ReplayError("missing exact run boundary samples")
    ordered = [selected[time] for time in sorted(selected)]
    identity = (ordered[0].get("uid"), ordered[0].get("pid"))
    for value, field in zip(identity, ("uid", "pid")):
        integer(value, field)
    if identity[1] == 0:
        raise ReplayError("missing process identity")
    if uid is not None and identity != (uid, pid):
        raise ReplayError("direct/app UID or PID mismatch")
    sources = set()
    previous = None
    for sample in ordered:
        if (sample.get("uid"), sample.get("pid")) != identity:
            raise ReplayError("UID/PID changed within run")
        if direct:
            sources.add(sample.get("source"))
            if sample.get("source") not in ("xt_qtaguid", "trafficstats"):
                raise ReplayError("unvalidated direct UID source")
        elif set(BYTES) <= set(fields) and sample.get("uid_status") != "ok":
            raise ReplayError("unsupported/invalid TrafficStats counters")
        values = {key: integer(sample.get(key), key) for key in fields}
        if previous is not None and any(values[key] < previous[key] for key in fields):
            raise ReplayError("counter reset or negative delta within run")
        previous = values
    if len(sources) > 1:
        raise ReplayError("direct UID source changed within run")
    return ordered


def summarize_run(run):
    if run.get("v") != 1:
        raise ReplayError("unsupported run version")
    if not run.get("run_id") or not run.get("pair_id") or run.get("arm") not in ("A", "C"):
        raise ReplayError("missing run/pair/build identity")
    workload = run.get("workload")
    if not isinstance(workload, dict) or not workload.get("name"):
        raise ReplayError("missing workload")
    for key in ("surface", "filter", "show", "transport", "configuration", "home_seconds", "visible_seconds"):
        if key not in workload:
            raise ReplayError(f"missing workload {key}")
    if run.get("workload_end") != workload:
        raise ReplayError("missing or changed end-of-run workload/configuration")
    if (workload["surface"], workload["filter"], workload["show"]) != ("Following", "Notes", "Text"):
        raise ReplayError("wrong feed workload")
    if workload["name"] not in ("resume-v1", "foreground-idle-v1", "cold-start-v1"):
        raise ReplayError("unknown workload")
    expected_home = 15 if workload["name"] == "resume-v1" else 0
    if workload["home_seconds"] != expected_home or workload["visible_seconds"] != 60:
        raise ReplayError("wrong declared gesture durations")
    if not workload["transport"] or not workload["configuration"]:
        raise ReplayError("missing transport/configuration identity")
    method = run.get("execution_method", "human")
    if method == "human":
        complete = run.get("human_complete") is True
    elif method == "scripted-home-launcher-v1":
        complete = (run.get("script_complete") is True and
                    run.get("owner_authorized_script") is True and
                    run.get("human_complete") is False)
    else:
        raise ReplayError("unknown execution method")
    if not complete or run.get("thermal_ok") is not True or run.get("deviations") != []:
        raise ReplayError("incomplete, interrupted, thermal or deviating workload")
    start = integer(run.get("start_elapsed_ms"), "start_elapsed_ms")
    end = integer(run.get("end_elapsed_ms"), "end_elapsed_ms")
    if end <= start:
        raise ReplayError("nonpositive run duration")
    source = run.get("byte_source")
    if source not in ("trafficstats", "direct"):
        raise ReplayError("missing direct byte source")
    fields = COUNTERS + BYTES if source == "trafficstats" else COUNTERS
    samples = checked_series(run.get("samples", []), fields, start, end)
    first, last = samples[0], samples[-1]
    byte_start = start
    byte_end = end
    if source == "direct":
        byte_start = integer(run.get("byte_start_elapsed_ms"), "byte_start_elapsed_ms")
        byte_end = integer(run.get("byte_end_elapsed_ms"), "byte_end_elapsed_ms")
        if byte_end <= byte_start or max(abs(byte_start-start), abs(byte_end-end)) > 1000:
            raise ReplayError("direct/app sample boundaries differ by more than 1000ms")
    byte_samples = samples if source == "trafficstats" else checked_series(
        run.get("direct_uid_samples", []), BYTES, byte_start, byte_end,
        first["uid"], first["pid"], direct=True)
    deltas = {key: last[key] - first[key] for key in COUNTERS}
    deltas.update({key: byte_samples[-1][key] - byte_samples[0][key] for key in BYTES})
    replay = deltas["replay_req_accepted"]
    ratios = {key + "_per_replay": deltas[key] / replay if replay else None
              for key in ("event_frames", "subscription_duplicates", "processor_duplicates")}
    return {"run_id": run["run_id"], "pair_id": run["pair_id"], "arm": run["arm"],
            "sequence": integer(run.get("sequence"), "sequence"), "workload": workload,
            "execution_method": method,
            "uid": first["uid"], "pid": first["pid"], "duration_ms": end - start,
            "byte_duration_ms": byte_end - byte_start,
            "byte_boundary_offsets_ms": [byte_start-start, byte_end-end],
            "byte_source": source, "deltas": deltas, "ratios": ratios}


def load_run(path):
    run = json.loads(path.read_text())
    if "log" in run:
        if "samples" in run:
            raise ReplayError("conflicting embedded and log samples")
        run["samples"] = parse_app_samples((path.parent / run["log"]).read_text())
    return summarize_run(run)


def effect(before, after, seed=0x554E5349, iterations=20000):
    differences = [b - a for a, b in zip(before, after)]
    interval = paired_bootstrap_mean_interval(differences, iterations=iterations, seed=seed)
    p, exact, permutations = paired_sign_flip_p_value(differences, seed=seed)
    a, b = statistics.fmean(before), statistics.fmean(after)
    return {"baseline_mean": a, "candidate_mean": b, "mean_difference": b-a,
            "relative_difference": (b-a)/a if a else None,
            "paired_differences": differences, "bootstrap_95": list(interval),
            "one_sided_sign_flip_p": p, "exact": exact, "permutations": permutations}


def compare_runs(baseline, candidate, baseline_controls=(), idle_controls=(), iterations=20000):
    if not baseline or len(baseline) != len(candidate):
        raise ReplayError("missing or unequal paired runs")
    all_runs = list(baseline) + list(candidate)
    control_runs = [run for pair in baseline_controls for run in pair] + list(idle_controls)
    evidence_runs = all_runs + control_runs
    if baseline[0]["workload"]["name"] != "resume-v1":
        raise ReplayError("confirmatory comparison requires resume workload")
    if len({r["run_id"] for r in evidence_runs}) != len(evidence_runs):
        raise ReplayError("reused run is not an independent experimental unit")
    if len({r["pair_id"] for r in baseline}) != len(baseline):
        raise ReplayError("duplicate pair identity")
    if len({r["sequence"] for r in evidence_runs}) != len(evidence_runs):
        raise ReplayError("duplicate run sequence")
    for run in evidence_runs:
        if run.get("execution_method", "human") != baseline[0].get("execution_method", "human"):
            raise ReplayError("mixed execution methods")
        if run["uid"] != baseline[0]["uid"] or run["byte_source"] != baseline[0]["byte_source"]:
            raise ReplayError("run UID/byte source mismatch")
    used_pairs = {r["pair_id"] for r in baseline}
    for a, b in baseline_controls:
        if a["pair_id"] != b["pair_id"] or a["pair_id"] in used_pairs:
            raise ReplayError("mismatched or reused baseline control pair ID")
        used_pairs.add(a["pair_id"])
    idle_pairs = {}
    for run in idle_controls:
        if run["pair_id"] in used_pairs:
            raise ReplayError("idle pair ID reused by another experiment pair")
        idle_pairs.setdefault(run["pair_id"], []).append(run)
    if any(len(pair) != 2 or {r["arm"] for r in pair} != {"A", "C"} for pair in idle_pairs.values()):
        raise ReplayError("idle controls require matched pair IDs with one A and one C")
    orders = []
    for a, c in zip(baseline, candidate):
        if a["pair_id"] != c["pair_id"] or a["arm"] != "A" or c["arm"] != "C":
            raise ReplayError("mismatched pair/arm")
        if a["workload"] != c["workload"] or a["workload"] != baseline[0]["workload"]:
            raise ReplayError("mismatched workloads")
        if a["uid"] != c["uid"] or a["byte_source"] != c["byte_source"]:
            raise ReplayError("mismatched UID/byte source across pair")
        if a["sequence"] == c["sequence"]:
            raise ReplayError("conflicting run order")
        orders.append("AC" if a["sequence"] < c["sequence"] else "CA")
        if not a["deltas"]["replay_req_accepted"] or not c["deltas"]["replay_req_accepted"]:
            raise ReplayError("resume pair without accepted replay REQs")
    windows = [(min(a["sequence"], c["sequence"]), max(a["sequence"], c["sequence"]))
               for a, c in zip(baseline, candidate)]
    if any(right[0] <= left[1] for left, right in zip(windows, windows[1:])):
        raise ReplayError("pairs were not collected in declared sequence")
    metrics = {}
    for key in COUNTERS + BYTES:
        metrics[key] = effect([r["deltas"][key] for r in baseline], [r["deltas"][key] for r in candidate], iterations=iterations)
    for key in baseline[0]["ratios"]:
        metrics[key] = effect([r["ratios"][key] for r in baseline], [r["ratios"][key] for r in candidate], iterations=iterations)
    drift = []
    for a, b in baseline_controls:
        if a["arm"] != "A" or b["arm"] != "A" or a["workload"] != b["workload"] or a["workload"] != baseline[0]["workload"]:
            raise ReplayError("mismatched baseline-baseline control")
        drift.append(abs(b["deltas"]["uid_rx"] - a["deltas"]["uid_rx"]))
    for run in idle_controls:
        expected = dict(baseline[0]["workload"], name="foreground-idle-v1", home_seconds=0)
        if run["workload"] != expected:
            raise ReplayError("mismatched foreground-idle control")
        # Conservatively scale idle RX to the longest primary run boundary.
        drift.append(run["deltas"]["uid_rx"] * max(r["duration_ms"] for r in all_runs) / run["duration_ms"])
    rx, tx = metrics["uid_rx"], metrics["uid_tx"]
    gates = {
        "seven_pairs_in_declared_order": len(baseline) == 7 and tuple(orders) == PAIR_ORDER,
        "rx_reduction_at_least_10_percent": rx["relative_difference"] is not None and rx["relative_difference"] <= -0.10,
        "rx_interval_below_zero": rx["bootstrap_95"][1] < 0,
        "rx_sign_flip_p_at_most_0_05": rx["one_sided_sign_flip_p"] <= 0.05,
        "rx_exceeds_control_drift": bool(baseline_controls) and bool(idle_controls) and -rx["bootstrap_95"][1] > max(drift),
        "accepted_replays_do_not_increase": metrics["replay_req_accepted"]["mean_difference"] <= 0,
        "tx_regression_at_most_5_percent": tx["relative_difference"] is not None and tx["relative_difference"] <= 0.05,
    }
    for key in ("event_frames", "subscription_duplicates", "processor_duplicates"):
        gates[key + "_per_replay_interval_below_zero"] = metrics[key + "_per_replay"]["bootstrap_95"][1] < 0
    unit = "human" if baseline[0].get("execution_method", "human") == "human" else "scripted"
    return {"experimental_unit": f"complete paired {unit} run", "pairs": len(baseline),
            "metrics": metrics, "rx_control_drift_bound": max(drift) if drift else None,
            "gates": gates, "quantitative_pass": all(gates.values()),
            "limitation": "Whole-UID bytes are not per-relay attribution. Unique delivery, coverage, reconnect and human continuity require separate acceptance."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    extract = sub.add_parser("extract")
    extract.add_argument("log", type=Path)
    xt = sub.add_parser("xt-qtaguid")
    xt.add_argument("file", type=Path)
    xt.add_argument("--uid", type=int, required=True)
    summary = sub.add_parser("summarize")
    summary.add_argument("runs", nargs="+", type=Path)
    compare = sub.add_parser("compare")
    compare.add_argument("--baseline", nargs="+", required=True, type=Path)
    compare.add_argument("--candidate", nargs="+", required=True, type=Path)
    compare.add_argument("--baseline-control", nargs=2, action="append", default=[], type=Path)
    compare.add_argument("--idle-control", nargs="*", default=[], type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "extract": result = parse_app_samples(args.log.read_text())
        elif args.command == "xt-qtaguid": result = parse_xt_qtaguid(args.file.read_text(), args.uid)
        elif args.command == "summarize": result = [load_run(p) for p in args.runs]
        else: result = compare_runs([load_run(p) for p in args.baseline], [load_run(p) for p in args.candidate],
                                   [(load_run(a), load_run(b)) for a, b in args.baseline_control],
                                   [load_run(p) for p in args.idle_control])
        print(json.dumps(result, indent=2))
        return 0
    except (ReplayError, ValueError, KeyError, TypeError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
