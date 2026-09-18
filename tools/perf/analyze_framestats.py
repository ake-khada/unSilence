#!/usr/bin/env python3
"""Analyze Android gfxinfo framestats without third-party dependencies.

Each input file is one human-executed run. Statistical comparisons operate on
paired run summaries; frames are never treated as independent experimental
replicates.
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import math
import random
import statistics
import sys
from collections import Counter
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Optional, Sequence


PROFILE_MARKER = "---PROFILEDATA---"
NANOS_PER_MILLISECOND = 1_000_000.0
MAX_FRAME_DURATION_NS = 10_000_000_000
MAX_FRAME_BUDGET_NS = 1_000_000_000
WILSON_Z_95 = 1.959963984540054
DEFAULT_BOOTSTRAP_ITERATIONS = 20_000
DEFAULT_RANDOM_SEED = 0x554E5349
MIN_PAIRED_RUNS = 7
PRIMARY_RELATIVE_REDUCTION = 0.20
NON_INFERIORITY_MARGIN_MS = 0.50


class FramestatsError(ValueError):
    """Raised when an input cannot support a valid analysis."""


@dataclass(frozen=True)
class Frame:
    intended_vsync_ns: int
    frame_completed_ns: int
    frame_deadline_ns: Optional[int]
    frame_interval_ns: Optional[int]

    @property
    def duration_ms(self) -> float:
        return (
            self.frame_completed_ns - self.intended_vsync_ns
        ) / NANOS_PER_MILLISECOND

    @property
    def deadline_overrun_ms(self) -> Optional[float]:
        if self.frame_deadline_ns is None:
            return None
        return (
            self.frame_completed_ns - self.frame_deadline_ns
        ) / NANOS_PER_MILLISECOND

    @property
    def deadline_budget_ms(self) -> Optional[float]:
        if self.frame_deadline_ns is None:
            return None
        return (
            self.frame_deadline_ns - self.intended_vsync_ns
        ) / NANOS_PER_MILLISECOND


@dataclass
class ParseDiagnostics:
    sections: int = 0
    rows_seen: int = 0
    accepted_frames: int = 0
    duplicate_frames: int = 0
    frames_without_deadline: int = 0
    rejected: Counter[str] = field(default_factory=Counter)

    def to_dict(self) -> dict[str, object]:
        result = asdict(self)
        result["rejected"] = dict(sorted(self.rejected.items()))
        return result


@dataclass(frozen=True)
class ParsedFramestats:
    frames: tuple[Frame, ...]
    diagnostics: ParseDiagnostics


def _clean_cells(line: str) -> list[str]:
    try:
        cells = next(csv.reader([line]))
    except csv.Error as error:
        raise FramestatsError(f"invalid CSV row: {error}") from error
    cells = [cell.strip() for cell in cells]
    while cells and not cells[-1]:
        cells.pop()
    return cells


def _parse_required_int(row: dict[str, str], key: str) -> int:
    value = row.get(key, "")
    if not value:
        raise KeyError(key)
    return int(value)


def _parse_optional_int(row: dict[str, str], key: str) -> Optional[int]:
    value = row.get(key, "")
    if not value:
        return None
    parsed = int(value)
    return parsed if parsed > 0 else None


def parse_framestats(text: str) -> ParsedFramestats:
    """Parse all PROFILEDATA sections and reject invalid or duplicate frames."""

    diagnostics = ParseDiagnostics()
    frames: list[Frame] = []
    seen_frames: set[tuple[int, int]] = set()
    inside_section = False
    header: Optional[list[str]] = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line == PROFILE_MARKER:
            if inside_section:
                inside_section = False
                header = None
            else:
                inside_section = True
                header = None
                diagnostics.sections += 1
            continue

        if not inside_section or not line:
            continue

        try:
            cells = _clean_cells(line)
        except FramestatsError:
            diagnostics.rows_seen += 1
            diagnostics.rejected["invalid_csv"] += 1
            continue

        if "Flags" in cells and "IntendedVsync" in cells:
            header = cells
            continue
        if header is None:
            continue

        diagnostics.rows_seen += 1
        if len(cells) != len(header):
            diagnostics.rejected["column_count"] += 1
            continue

        row = dict(zip(header, cells))
        try:
            flags = _parse_required_int(row, "Flags")
            intended_vsync = _parse_required_int(row, "IntendedVsync")
            frame_completed = _parse_required_int(row, "FrameCompleted")
            frame_deadline = _parse_optional_int(row, "FrameDeadline")
            frame_interval = _parse_optional_int(row, "FrameInterval")
        except KeyError:
            diagnostics.rejected["missing_required_value"] += 1
            continue
        except ValueError:
            diagnostics.rejected["non_integer_value"] += 1
            continue

        if flags != 0:
            diagnostics.rejected["nonzero_flags"] += 1
            continue
        if intended_vsync <= 0 or frame_completed <= 0:
            diagnostics.rejected["nonpositive_timestamp"] += 1
            continue

        duration = frame_completed - intended_vsync
        if duration < 0:
            diagnostics.rejected["nonmonotonic_timestamp"] += 1
            continue
        if duration > MAX_FRAME_DURATION_NS:
            diagnostics.rejected["implausible_duration"] += 1
            continue

        if frame_deadline is not None:
            budget = frame_deadline - intended_vsync
            if budget <= 0 or budget > MAX_FRAME_BUDGET_NS:
                diagnostics.rejected["invalid_deadline"] += 1
                continue
        else:
            diagnostics.frames_without_deadline += 1

        if frame_interval is not None and frame_interval > MAX_FRAME_BUDGET_NS:
            diagnostics.rejected["invalid_frame_interval"] += 1
            continue

        identity = (intended_vsync, frame_completed)
        if identity in seen_frames:
            diagnostics.duplicate_frames += 1
            continue
        seen_frames.add(identity)

        frames.append(
            Frame(
                intended_vsync_ns=intended_vsync,
                frame_completed_ns=frame_completed,
                frame_deadline_ns=frame_deadline,
                frame_interval_ns=frame_interval,
            )
        )

    diagnostics.accepted_frames = len(frames)
    return ParsedFramestats(tuple(frames), diagnostics)


def percentile(values: Sequence[float], quantile: float) -> float:
    """Return a linearly interpolated percentile for quantile in [0, 1]."""

    if not values:
        raise FramestatsError("percentile requires at least one value")
    if not 0.0 <= quantile <= 1.0:
        raise FramestatsError("quantile must be between 0 and 1")

    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def wilson_interval(
    successes: int,
    total: int,
    z: float = WILSON_Z_95,
) -> tuple[float, float]:
    """Return a two-sided Wilson score interval for a binomial proportion."""

    if total <= 0:
        raise FramestatsError("Wilson interval requires a positive total")
    if successes < 0 or successes > total:
        raise FramestatsError("successes must be within [0, total]")

    proportion = successes / total
    denominator = 1.0 + z * z / total
    center = (proportion + z * z / (2.0 * total)) / denominator
    margin = (
        z
        * math.sqrt(
            proportion * (1.0 - proportion) / total
            + z * z / (4.0 * total * total)
        )
        / denominator
    )
    lower = 0.0 if successes == 0 else max(0.0, center - margin)
    upper = 1.0 if successes == total else min(1.0, center + margin)
    return lower, upper


def _distribution(values: Sequence[float]) -> dict[str, float]:
    if not values:
        raise FramestatsError("distribution requires at least one value")
    return {
        "mean": statistics.fmean(values),
        "p50": percentile(values, 0.50),
        "p90": percentile(values, 0.90),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
    }


def summarize_parsed(
    parsed: ParsedFramestats,
    source: str,
) -> dict[str, object]:
    if not parsed.frames:
        raise FramestatsError(f"{source}: no valid frames found")

    durations = [frame.duration_ms for frame in parsed.frames]
    overruns = [
        overrun
        for frame in parsed.frames
        if (overrun := frame.deadline_overrun_ms) is not None
    ]
    deadline_budgets = [
        budget
        for frame in parsed.frames
        if (budget := frame.deadline_budget_ms) is not None
    ]
    frame_intervals = [
        frame.frame_interval_ns / NANOS_PER_MILLISECOND
        for frame in parsed.frames
        if frame.frame_interval_ns is not None
    ]

    result: dict[str, object] = {
        "source": source,
        "frame_count": len(durations),
        "duration_ms": _distribution(durations),
        "duration_over_16_67_count": sum(value > 16.67 for value in durations),
        "duration_over_16_67_rate": sum(value > 16.67 for value in durations)
        / len(durations),
        "duration_over_33_33_count": sum(value > 33.33 for value in durations),
        "duration_over_33_33_rate": sum(value > 33.33 for value in durations)
        / len(durations),
        "median_frame_interval_ms": (
            percentile(frame_intervals, 0.50) if frame_intervals else None
        ),
        "median_deadline_budget_ms": (
            percentile(deadline_budgets, 0.50) if deadline_budgets else None
        ),
        "parse": parsed.diagnostics.to_dict(),
    }

    if overruns:
        deadline_misses = sum(value > 0.0 for value in overruns)
        interval_low, interval_high = wilson_interval(
            deadline_misses,
            len(overruns),
        )
        result.update(
            {
                "deadline_frame_count": len(overruns),
                "deadline_miss_count": deadline_misses,
                "deadline_miss_rate": deadline_misses / len(overruns),
                "deadline_miss_wilson_95": [interval_low, interval_high],
                "deadline_overrun_ms": _distribution(overruns),
            }
        )
    else:
        result.update(
            {
                "deadline_frame_count": 0,
                "deadline_miss_count": None,
                "deadline_miss_rate": None,
                "deadline_miss_wilson_95": None,
                "deadline_overrun_ms": None,
            }
        )
    return result


def summarize_file(path: Path) -> dict[str, object]:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise FramestatsError(f"{path}: {error}") from error
    return summarize_parsed(parse_framestats(text), str(path))


def paired_sign_flip_p_value(
    differences: Sequence[float],
    seed: int = DEFAULT_RANDOM_SEED,
    random_iterations: int = 200_000,
) -> tuple[float, bool, int]:
    """One-sided paired randomization p-value; negative means improvement.

    Returns (p_value, exact, permutations_evaluated).
    """

    if not differences:
        raise FramestatsError("paired test requires at least one difference")
    observed = statistics.fmean(differences)
    magnitudes = [abs(value) for value in differences]
    tolerance = 1e-15

    if len(differences) <= 20:
        total = 1 << len(differences)
        at_least_as_favorable = 0
        for signs in itertools.product((-1.0, 1.0), repeat=len(differences)):
            permuted = statistics.fmean(
                sign * magnitude
                for sign, magnitude in zip(signs, magnitudes)
            )
            if permuted <= observed + tolerance:
                at_least_as_favorable += 1
        return at_least_as_favorable / total, True, total

    rng = random.Random(seed)
    at_least_as_favorable = 1
    total = random_iterations + 1
    for _ in range(random_iterations):
        permuted = statistics.fmean(
            magnitude if rng.getrandbits(1) else -magnitude
            for magnitude in magnitudes
        )
        if permuted <= observed + tolerance:
            at_least_as_favorable += 1
    return at_least_as_favorable / total, False, total


def paired_bootstrap_mean_interval(
    differences: Sequence[float],
    iterations: int = DEFAULT_BOOTSTRAP_ITERATIONS,
    seed: int = DEFAULT_RANDOM_SEED,
) -> tuple[float, float]:
    """Percentile bootstrap interval for the mean paired difference."""

    if not differences:
        raise FramestatsError("bootstrap requires at least one difference")
    if iterations < 1_000:
        raise FramestatsError("bootstrap iterations must be at least 1000")

    rng = random.Random(seed)
    count = len(differences)
    resampled_means = [
        statistics.fmean(differences[rng.randrange(count)] for _ in range(count))
        for _ in range(iterations)
    ]
    return (
        percentile(resampled_means, 0.025),
        percentile(resampled_means, 0.975),
    )


def _metric(summary: dict[str, object], path: Sequence[str]) -> float:
    value: object = summary
    for component in path:
        if not isinstance(value, dict) or component not in value:
            raise FramestatsError(
                f"{summary.get('source', 'run')}: missing metric "
                + ".".join(path)
            )
        value = value[component]
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise FramestatsError(
            f"{summary.get('source', 'run')}: unavailable metric "
            + ".".join(path)
        )
    return float(value)


def _compare_metric(
    name: str,
    baseline: Sequence[dict[str, object]],
    candidate: Sequence[dict[str, object]],
    path: Sequence[str],
    bootstrap_iterations: int,
    seed: int,
) -> dict[str, object]:
    baseline_values = [_metric(run, path) for run in baseline]
    candidate_values = [_metric(run, path) for run in candidate]
    differences = [
        candidate_value - baseline_value
        for baseline_value, candidate_value in zip(
            baseline_values,
            candidate_values,
        )
    ]
    p_value, exact, permutations = paired_sign_flip_p_value(
        differences,
        seed=seed,
    )
    interval = paired_bootstrap_mean_interval(
        differences,
        iterations=bootstrap_iterations,
        seed=seed,
    )
    baseline_mean = statistics.fmean(baseline_values)
    candidate_mean = statistics.fmean(candidate_values)
    mean_difference = statistics.fmean(differences)
    relative_difference = (
        mean_difference / baseline_mean if baseline_mean != 0.0 else None
    )
    return {
        "name": name,
        "baseline_values": baseline_values,
        "candidate_values": candidate_values,
        "paired_differences": differences,
        "baseline_mean": baseline_mean,
        "candidate_mean": candidate_mean,
        "mean_difference": mean_difference,
        "relative_difference": relative_difference,
        "bootstrap_mean_difference_95": list(interval),
        "one_sided_sign_flip_p": p_value,
        "sign_flip_exact": exact,
        "sign_flip_permutations": permutations,
        "direction": "candidate_minus_baseline; lower_is_better",
    }


def compare_run_summaries(
    baseline: Sequence[dict[str, object]],
    candidate: Sequence[dict[str, object]],
    bootstrap_iterations: int = DEFAULT_BOOTSTRAP_ITERATIONS,
    seed: int = DEFAULT_RANDOM_SEED,
) -> dict[str, object]:
    if len(baseline) != len(candidate):
        raise FramestatsError(
            "paired comparison requires equal baseline and candidate run counts"
        )
    if not baseline:
        raise FramestatsError("paired comparison requires at least one pair")

    metric_specs = (
        ("deadline_miss_rate", ("deadline_miss_rate",)),
        ("p95_deadline_overrun_ms", ("deadline_overrun_ms", "p95")),
        ("p95_frame_duration_ms", ("duration_ms", "p95")),
        ("mean_frame_duration_ms", ("duration_ms", "mean")),
    )
    metrics = {
        name: _compare_metric(
            name,
            baseline,
            candidate,
            path,
            bootstrap_iterations,
            seed + index,
        )
        for index, (name, path) in enumerate(metric_specs)
    }

    miss = metrics["deadline_miss_rate"]
    p95_overrun = metrics["p95_deadline_overrun_ms"]
    p95_duration = metrics["p95_frame_duration_ms"]
    relative_difference = miss["relative_difference"]
    relative_reduction = (
        -float(relative_difference)
        if relative_difference is not None
        else None
    )
    miss_interval = miss["bootstrap_mean_difference_95"]
    overrun_interval = p95_overrun["bootstrap_mean_difference_95"]
    duration_interval = p95_duration["bootstrap_mean_difference_95"]

    gates = {
        "minimum_pairs": len(baseline) >= MIN_PAIRED_RUNS,
        "deadline_relative_reduction_at_least_20_percent": (
            relative_reduction is not None
            and relative_reduction >= PRIMARY_RELATIVE_REDUCTION
        ),
        "deadline_one_sided_p_at_most_0_05": (
            float(miss["one_sided_sign_flip_p"]) <= 0.05
        ),
        "deadline_bootstrap_interval_below_zero": (
            float(miss_interval[1]) < 0.0
        ),
        "p95_overrun_noninferior_with_0_5ms_margin": (
            float(overrun_interval[1]) <= NON_INFERIORITY_MARGIN_MS
        ),
        "p95_duration_noninferior_with_0_5ms_margin": (
            float(duration_interval[1]) <= NON_INFERIORITY_MARGIN_MS
        ),
    }
    pass_frame_evidence = all(gates.values())

    return {
        "paired_run_count": len(baseline),
        "experimental_unit": "paired human-executed run",
        "metrics": metrics,
        "deadline_relative_reduction": relative_reduction,
        "predeclared_frame_gates": gates,
        "frame_evidence_verdict": (
            "PASS" if pass_frame_evidence else "INSUFFICIENT_OR_FAIL"
        ),
        "scope_warning": (
            "This verdict covers frame evidence only. CPU, bandwidth, memory, "
            "battery/thermal, and functional guardrails require separate evidence."
        ),
    }


def compare_files(
    baseline_paths: Sequence[Path],
    candidate_paths: Sequence[Path],
    bootstrap_iterations: int,
    seed: int,
) -> dict[str, object]:
    return compare_run_summaries(
        [summarize_file(path) for path in baseline_paths],
        [summarize_file(path) for path in candidate_paths],
        bootstrap_iterations=bootstrap_iterations,
        seed=seed,
    )


def _format_number(value: object, digits: int = 3) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.{digits}f}"


def _format_percent(value: object, digits: int = 2) -> str:
    if value is None:
        return "n/a"
    return f"{100.0 * float(value):.{digits}f}%"


def print_summary(summary: dict[str, object]) -> None:
    duration = summary["duration_ms"]
    assert isinstance(duration, dict)
    diagnostics = summary["parse"]
    assert isinstance(diagnostics, dict)
    print(f"Run: {summary['source']}")
    print(
        "  Frames: "
        f"{summary['frame_count']} accepted / {diagnostics['rows_seen']} rows; "
        f"{diagnostics['duplicate_frames']} duplicates"
    )
    print(
        "  Timing budget: interval median "
        f"{_format_number(summary['median_frame_interval_ms'])} ms; "
        "deadline median "
        f"{_format_number(summary['median_deadline_budget_ms'])} ms"
    )
    print(
        "  Completion duration ms: "
        f"mean={_format_number(duration['mean'])} "
        f"p50={_format_number(duration['p50'])} "
        f"p90={_format_number(duration['p90'])} "
        f"p95={_format_number(duration['p95'])} "
        f"p99={_format_number(duration['p99'])} "
        f"max={_format_number(duration['max'])}"
    )
    print(
        "  Fixed thresholds: "
        f">16.67 ms={_format_percent(summary['duration_over_16_67_rate'])}; "
        f">33.33 ms={_format_percent(summary['duration_over_33_33_rate'])}"
    )
    if summary["deadline_miss_rate"] is None:
        print("  Deadline metrics: unavailable")
    else:
        overrun = summary["deadline_overrun_ms"]
        assert isinstance(overrun, dict)
        interval = summary["deadline_miss_wilson_95"]
        assert isinstance(interval, list)
        print(
            "  Deadline misses: "
            f"{summary['deadline_miss_count']}/{summary['deadline_frame_count']} "
            f"({_format_percent(summary['deadline_miss_rate'])}); "
            "Wilson 95% "
            f"[{_format_percent(interval[0])}, {_format_percent(interval[1])}]"
        )
        print(
            "  Deadline overrun ms: "
            f"mean={_format_number(overrun['mean'])} "
            f"p50={_format_number(overrun['p50'])} "
            f"p90={_format_number(overrun['p90'])} "
            f"p95={_format_number(overrun['p95'])} "
            f"p99={_format_number(overrun['p99'])} "
            f"max={_format_number(overrun['max'])}"
        )
    rejected = diagnostics["rejected"]
    if rejected:
        print(f"  Rejected rows: {json.dumps(rejected, sort_keys=True)}")


def print_comparison(comparison: dict[str, object]) -> None:
    print(
        f"Paired runs: {comparison['paired_run_count']} "
        f"({comparison['experimental_unit']})"
    )
    metrics = comparison["metrics"]
    assert isinstance(metrics, dict)
    for metric in metrics.values():
        assert isinstance(metric, dict)
        relative = metric["relative_difference"]
        relative_text = (
            "n/a" if relative is None else f"{100.0 * float(relative):+.2f}%"
        )
        interval = metric["bootstrap_mean_difference_95"]
        assert isinstance(interval, list)
        print(
            f"  {metric['name']}: "
            f"baseline={_format_number(metric['baseline_mean'], 5)} "
            f"candidate={_format_number(metric['candidate_mean'], 5)} "
            f"delta={float(metric['mean_difference']):+.5f} "
            f"relative={relative_text} "
            f"bootstrap95=[{float(interval[0]):+.5f}, "
            f"{float(interval[1]):+.5f}] "
            f"one-sided-p={float(metric['one_sided_sign_flip_p']):.6f}"
        )
    print("  Predeclared frame gates:")
    gates = comparison["predeclared_frame_gates"]
    assert isinstance(gates, dict)
    for name, passed in gates.items():
        print(f"    {'PASS' if passed else 'FAIL'} {name}")
    print(f"Frame evidence verdict: {comparison['frame_evidence_verdict']}")
    print(comparison["scope_warning"])


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Summarize Android gfxinfo framestats or compare paired runs. "
            "One input file must represent one human-executed run."
        )
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    summarize = subparsers.add_parser("summarize")
    summarize.add_argument("files", nargs="+", type=Path)
    summarize.add_argument("--json", action="store_true")

    compare = subparsers.add_parser("compare")
    compare.add_argument("--baseline", nargs="+", required=True, type=Path)
    compare.add_argument("--candidate", nargs="+", required=True, type=Path)
    compare.add_argument(
        "--bootstrap-iterations",
        type=int,
        default=DEFAULT_BOOTSTRAP_ITERATIONS,
    )
    compare.add_argument("--seed", type=int, default=DEFAULT_RANDOM_SEED)
    compare.add_argument("--json", action="store_true")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.command == "summarize":
            summaries = [summarize_file(path) for path in args.files]
            if args.json:
                json.dump(summaries, sys.stdout, indent=2, sort_keys=True)
                print()
            else:
                for index, summary in enumerate(summaries):
                    if index:
                        print()
                    print_summary(summary)
            return 0

        comparison = compare_files(
            args.baseline,
            args.candidate,
            bootstrap_iterations=args.bootstrap_iterations,
            seed=args.seed,
        )
        if args.json:
            json.dump(comparison, sys.stdout, indent=2, sort_keys=True)
            print()
        else:
            print_comparison(comparison)
        return 0
    except FramestatsError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
