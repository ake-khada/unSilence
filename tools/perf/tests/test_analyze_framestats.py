from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path


PERF_DIR = Path(__file__).resolve().parents[1]
FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(PERF_DIR))

import analyze_framestats as analyzer  # noqa: E402


class ParseFramestatsTest(unittest.TestCase):
    def test_parses_dynamic_headers_sections_and_filters_invalid_rows(self) -> None:
        parsed = analyzer.parse_framestats(
            (FIXTURE_DIR / "sample_framestats.txt").read_text()
        )

        self.assertEqual(2, parsed.diagnostics.sections)
        self.assertEqual(4, len(parsed.frames))
        self.assertEqual(1, parsed.diagnostics.duplicate_frames)
        self.assertEqual(1, parsed.diagnostics.frames_without_deadline)
        self.assertEqual(
            {
                "column_count": 1,
                "nonmonotonic_timestamp": 1,
                "nonzero_flags": 1,
            },
            dict(parsed.diagnostics.rejected),
        )

        summary = analyzer.summarize_parsed(parsed, "fixture")
        self.assertEqual(4, summary["frame_count"])
        self.assertEqual(2, summary["deadline_miss_count"])
        self.assertEqual(3, summary["deadline_frame_count"])
        self.assertAlmostEqual(2.0 / 3.0, summary["deadline_miss_rate"])
        self.assertEqual(1, summary["duration_over_16_67_count"])
        self.assertEqual(1, summary["duration_over_33_33_count"])
        self.assertAlmostEqual(8.333333, summary["median_frame_interval_ms"])

    def test_no_profile_rows_is_rejected_at_summary_boundary(self) -> None:
        parsed = analyzer.parse_framestats("no frame data")
        with self.assertRaisesRegex(analyzer.FramestatsError, "no valid frames"):
            analyzer.summarize_parsed(parsed, "empty")


class StatisticsTest(unittest.TestCase):
    def test_percentile_uses_linear_interpolation(self) -> None:
        values = [1.0, 2.0, 3.0, 4.0]
        self.assertEqual(1.0, analyzer.percentile(values, 0.0))
        self.assertEqual(2.5, analyzer.percentile(values, 0.5))
        self.assertAlmostEqual(3.85, analyzer.percentile(values, 0.95))
        self.assertEqual(4.0, analyzer.percentile(values, 1.0))

    def test_wilson_interval_known_extremes(self) -> None:
        low, high = analyzer.wilson_interval(0, 10)
        self.assertEqual(0.0, low)
        self.assertAlmostEqual(0.2775328, high, places=6)

        low, high = analyzer.wilson_interval(10, 10)
        self.assertAlmostEqual(0.7224672, low, places=6)
        self.assertEqual(1.0, high)

    def test_sign_flip_is_exact_and_run_level(self) -> None:
        p_value, exact, permutations = analyzer.paired_sign_flip_p_value(
            [-1.0] * 7
        )
        self.assertTrue(exact)
        self.assertEqual(128, permutations)
        self.assertEqual(1.0 / 128.0, p_value)

    def test_bootstrap_is_deterministic_for_a_seed(self) -> None:
        first = analyzer.paired_bootstrap_mean_interval(
            [-4.0, -3.0, -2.0, -1.0],
            iterations=2_000,
            seed=42,
        )
        second = analyzer.paired_bootstrap_mean_interval(
            [-4.0, -3.0, -2.0, -1.0],
            iterations=2_000,
            seed=42,
        )
        self.assertEqual(first, second)
        self.assertLess(first[1], 0.0)


def _run_summary(
    source: str,
    miss_rate: float,
    p95_overrun: float,
    p95_duration: float,
) -> dict[str, object]:
    return {
        "source": source,
        "deadline_miss_rate": miss_rate,
        "deadline_overrun_ms": {"p95": p95_overrun},
        "duration_ms": {
            "p95": p95_duration,
            "mean": p95_duration / 2.0,
        },
    }


class PairedComparisonTest(unittest.TestCase):
    def test_predeclared_gate_passes_seven_consistent_pairs(self) -> None:
        baseline = [
            _run_summary(f"b{index}", 0.20 + index * 0.01, 2.0, 12.0)
            for index in range(7)
        ]
        candidate = [
            _run_summary(f"c{index}", 0.10 + index * 0.005, 1.4, 11.5)
            for index in range(7)
        ]

        comparison = analyzer.compare_run_summaries(
            baseline,
            candidate,
            bootstrap_iterations=2_000,
            seed=7,
        )

        self.assertEqual("PASS", comparison["frame_evidence_verdict"])
        self.assertTrue(
            all(comparison["predeclared_frame_gates"].values())
        )
        miss = comparison["metrics"]["deadline_miss_rate"]
        self.assertEqual(1.0 / 128.0, miss["one_sided_sign_flip_p"])
        self.assertLess(miss["bootstrap_mean_difference_95"][1], 0.0)

    def test_six_pairs_are_explicitly_insufficient(self) -> None:
        baseline = [_run_summary(f"b{i}", 0.20, 2.0, 12.0) for i in range(6)]
        candidate = [_run_summary(f"c{i}", 0.10, 1.0, 11.0) for i in range(6)]

        comparison = analyzer.compare_run_summaries(
            baseline,
            candidate,
            bootstrap_iterations=1_000,
        )

        self.assertEqual(
            "INSUFFICIENT_OR_FAIL",
            comparison["frame_evidence_verdict"],
        )
        self.assertFalse(
            comparison["predeclared_frame_gates"]["minimum_pairs"]
        )

    def test_mismatched_pair_counts_are_rejected(self) -> None:
        with self.assertRaisesRegex(
            analyzer.FramestatsError,
            "equal baseline and candidate",
        ):
            analyzer.compare_run_summaries(
                [_run_summary("b", 0.2, 2.0, 12.0)],
                [],
            )


if __name__ == "__main__":
    unittest.main()
