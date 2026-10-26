from __future__ import annotations

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import analyze_replay as analyzer


def sample(time=1000, **changes):
    value = dict(v=1, elapsed_ms=time, pid=123, uid=10001, reason="stop", uid_status="ok",
                 uid_rx=1000, uid_tx=500, req_accepted=10, replay_req_accepted=4,
                 event_frames=200, subscription_duplicates=100, processor_duplicates=120)
    value.update(changes)
    return value


def run(arm="A", pair="p1", sequence=1):
    before = sample()
    after = sample(81000, uid_rx=2000, uid_tx=1000, req_accepted=20, replay_req_accepted=14,
                   event_frames=1200, subscription_duplicates=900, processor_duplicates=1020)
    if arm == "C":
        after.update(uid_rx=1500, event_frames=700, subscription_duplicates=400, processor_duplicates=420)
    workload = dict(name="resume-v1", surface="Following", filter="Notes", show="Text",
                    transport="test-wifi", configuration="synthetic-unchanged", home_seconds=15, visible_seconds=60)
    return dict(v=1, run_id=pair+arm+str(sequence), pair_id=pair, arm=arm, sequence=sequence,
                workload=workload, workload_end=copy.deepcopy(workload), human_complete=True, thermal_ok=True, deviations=[], byte_source="trafficstats",
                start_elapsed_ms=1000, end_elapsed_ms=81000, samples=[before, after])


class ReplayParsingTest(unittest.TestCase):
    def test_only_versioned_traffic_lines_not_heap_are_parsed(self):
        text = 'W MES/size: mesTotal~99mb heapUsed=123mb\nW MES/traffic: '+json.dumps(sample())
        self.assertEqual([sample()], analyzer.parse_app_samples(text))
        with self.assertRaises(analyzer.ReplayError):
            analyzer.parse_app_samples('W MES/size: events=100 heapUsed=123mb')
        with self.assertRaises(analyzer.ReplayError):
            analyzer.parse_app_samples('W MES/traffic: {broken')

    def test_xt_columns_reordered_and_every_uid_row_summed(self):
        text = '''tx_bytes iface uid_tag_int rx_bytes acct_tag_hex cnt_set idx
20 wlan0 10001 100 0x0 0 1
30 wlan0 10001 200 0x1 1 2
40 rmnet0 10001 300 0x0 0 3
900 wlan0 99999 900 0x0 0 4
'''
        self.assertEqual(dict(uid=10001, uid_rx=600, uid_tx=90), analyzer.parse_xt_qtaguid(text, 10001))

    def test_xt_missing_unsupported_conflicting_or_invalid_is_not_zero(self):
        for text in ('', 'uid_tag_int rx_bytes\n10001 2',
                     'uid_tag_int rx_bytes tx_bytes\n99999 2 3',
                     'uid_tag_int rx_bytes tx_bytes\n10001 -1 3',
                     'uid_tag_int rx_bytes tx_bytes\n10001 x 3',
                     'uid_tag_int rx_bytes tx_bytes\n10001 2',
                     'uid_tag_int rx_bytes rx_bytes tx_bytes\n10001 2 3 4',
                     'idx uid_tag_int rx_bytes tx_bytes\n1 10001 2 3\n1 10001 4 5'):
            with self.subTest(text=text), self.assertRaises(analyzer.ReplayError):
                analyzer.parse_xt_qtaguid(text, 10001)

    def test_cumulative_deltas_and_ratios_are_distinct(self):
        summary = analyzer.summarize_run(run())
        self.assertEqual(1000, summary['deltas']['uid_rx'])
        self.assertEqual(500, summary['deltas']['uid_tx'])
        self.assertEqual(10, summary['deltas']['replay_req_accepted'])
        self.assertEqual(100, summary['ratios']['event_frames_per_replay'])
        self.assertEqual(80, summary['ratios']['subscription_duplicates_per_replay'])
        self.assertEqual(90, summary['ratios']['processor_duplicates_per_replay'])

    def test_zero_replays_have_unavailable_ratio_not_zero(self):
        value = run(); value['samples'][-1]['replay_req_accepted'] = 4
        self.assertIsNone(analyzer.summarize_run(value)['ratios']['event_frames_per_replay'])

    def test_direct_uid_samples_are_supported_without_trafficstats(self):
        value = run(); value['byte_source'] = 'direct'; value.update(byte_start_elapsed_ms=1000, byte_end_elapsed_ms=81000)
        value['direct_uid_samples'] = [dict(s, source='xt_qtaguid') for s in value['samples']]
        for s in value['samples']: s.update(uid_status='invalid', uid_rx=None, uid_tx=None)
        self.assertEqual(1000, analyzer.summarize_run(value)['deltas']['uid_rx'])
        value['direct_uid_samples'][-1]['source'] = 'netstats-history'
        with self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)

    def test_direct_counters_use_actual_nearby_timestamps_and_reject_stale_boundaries(self):
        value=run(); value.update(byte_source='direct', byte_start_elapsed_ms=1100, byte_end_elapsed_ms=81100)
        value['direct_uid_samples']=[dict(s, source='xt_qtaguid', elapsed_ms=s['elapsed_ms']+100) for s in value['samples']]
        summary=analyzer.summarize_run(value)
        self.assertEqual([100,100],summary['byte_boundary_offsets_ms'])
        value['byte_start_elapsed_ms']=3000
        with self.assertRaises(analyzer.ReplayError):analyzer.summarize_run(value)

    def test_missing_conflicting_and_unsupported_samples_are_rejected(self):
        for mutate in (
            lambda r: r['samples'].pop(),
            lambda r: r['samples'].append(sample(uid_rx=999)),
            lambda r: r['samples'][0].pop('event_frames'),
            lambda r: r['samples'][0].update(uid_status='invalid'),
            lambda r: r['samples'][0].update(uid_rx=-1),
            lambda r: r['samples'][0].update(uid_tx=None),
            lambda r: r['samples'][0].update(v=2),
            lambda r: r['samples'][0].update(uid_rx=True),
            lambda r: r.update(byte_source='mes_estimate'),
        ):
            value=run(); mutate(value)
            with self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)

    def test_exact_duplicate_log_capture_is_not_a_conflict(self):
        value=run(); value['samples'].insert(1, copy.deepcopy(value['samples'][0]))
        self.assertEqual(1000, analyzer.summarize_run(value)['deltas']['uid_rx'])

    def test_all_counter_resets_including_interior_are_rejected(self):
        for counter in analyzer.COUNTERS + analyzer.BYTES:
            value=run(); interior=sample(5000); interior[counter]=0
            value['samples'].insert(1, interior)
            with self.subTest(counter=counter), self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)

    def test_uid_pid_and_direct_identity_changes_are_rejected(self):
        for key in ('uid', 'pid'):
            value=run(); value['samples'][-1][key]+=1
            with self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)
            value=run(); value['byte_source']='direct'; value.update(byte_start_elapsed_ms=1000, byte_end_elapsed_ms=81000)
            value['direct_uid_samples']=[dict(s, source='xt_qtaguid') for s in value['samples']]
            value['direct_uid_samples'][0][key]+=1
            with self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)

    def test_incomplete_deviating_and_thermal_runs_are_rejected(self):
        for changes in (dict(human_complete=False), dict(thermal_ok=False), dict(deviations=['transport changed']),
                        dict(start_elapsed_ms=81000), dict(workload={}), dict(samples=[]), dict(workload_end={})):
            value=run(); value.update(changes)
            with self.assertRaises(analyzer.ReplayError): analyzer.summarize_run(value)

    def test_log_reference_load_and_conflicting_inline_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'run.json'; value=run()
            (path.parent/'log.txt').write_text('\n'.join('W MES/traffic: '+json.dumps(s) for s in value.pop('samples')))
            value['log']='log.txt'; path.write_text(json.dumps(value))
            self.assertEqual(1000, analyzer.load_run(path)['deltas']['uid_rx'])
            value['samples']=[]; path.write_text(json.dumps(value))
            with self.assertRaises(analyzer.ReplayError): analyzer.load_run(path)


class ReplayComparisonTest(unittest.TestCase):
    def pairs(self, count=7):
        baseline=[]; candidate=[]
        for i in range(count):
            ac=analyzer.PAIR_ORDER[i]=='AC'
            baseline.append(analyzer.summarize_run(run('A', f'p{i}', 2*i+(1 if ac else 2))))
            candidate.append(analyzer.summarize_run(run('C', f'p{i}', 2*i+(2 if ac else 1))))
        return baseline, candidate

    def controls(self):
        a=analyzer.summarize_run(run('A', 'control', 90)); b=analyzer.summarize_run(run('A', 'control', 91))
        b['deltas']['uid_rx']+=5
        idle=analyzer.summarize_run(run('A', 'idle', 92))
        idle['workload'].update(name='foreground-idle-v1', home_seconds=0)
        idle['deltas']['uid_rx']=10
        other=copy.deepcopy(idle); other.update(run_id='idleC93', arm='C', sequence=93)
        return [(a,b)], [idle,other]

    def test_complete_paired_runs_pass_predeclared_quantitative_gates(self):
        a,c=self.pairs(); controls,idle=self.controls()
        result=analyzer.compare_runs(a,c,controls,idle,iterations=1000)
        self.assertTrue(result['quantitative_pass'])
        self.assertEqual(7,result['pairs'])
        self.assertEqual([-500]*7,result['metrics']['uid_rx']['paired_differences'])
        self.assertEqual([-500,-500],result['metrics']['uid_rx']['bootstrap_95'])
        self.assertEqual(1/128,result['metrics']['uid_rx']['one_sided_sign_flip_p'])
        self.assertEqual(result,analyzer.compare_runs(a,c,controls,idle,iterations=1000))

    def test_missing_controls_or_six_pairs_are_not_confirmation(self):
        a,c=self.pairs(); result=analyzer.compare_runs(a,c,iterations=1000)
        self.assertFalse(result['quantitative_pass'])
        a,c=self.pairs(6); controls,idle=self.controls()
        self.assertFalse(analyzer.compare_runs(a,c,controls,idle,iterations=1000)['quantitative_pass'])

    def test_wrong_order_and_tx_regression_fail_gates(self):
        a,c=self.pairs(); controls,idle=self.controls()
        a[0]['sequence'],c[0]['sequence']=c[0]['sequence'],a[0]['sequence']
        self.assertFalse(analyzer.compare_runs(a,c,controls,idle,iterations=1000)['gates']['seven_pairs_in_declared_order'])
        a,c=self.pairs()
        for r in c:r['deltas']['uid_tx']=600
        self.assertFalse(analyzer.compare_runs(a,c,controls,idle,iterations=1000)['gates']['tx_regression_at_most_5_percent'])

    def test_byte_change_without_mechanism_is_not_replay_evidence(self):
        a,c=self.pairs(); controls,idle=self.controls()
        for r in c:r['ratios']=copy.deepcopy(a[0]['ratios'])
        self.assertFalse(analyzer.compare_runs(a,c,controls,idle,iterations=1000)['quantitative_pass'])

    def test_drift_can_exceed_apparent_improvement(self):
        a,c=self.pairs(); controls,idle=self.controls(); idle[0]['deltas']['uid_rx']=800
        self.assertFalse(analyzer.compare_runs(a,c,controls,idle,iterations=1000)['gates']['rx_exceeds_control_drift'])

    def test_run_ids_are_unique_across_primary_baseline_and_idle_controls(self):
        for mutate in (
            lambda a,c,controls,idle:controls[0][0].update(run_id=a[0]['run_id']),
            lambda a,c,controls,idle:controls[0][1].update(run_id=controls[0][0]['run_id']),
            lambda a,c,controls,idle:idle[0].update(run_id=c[0]['run_id']),
            lambda a,c,controls,idle:idle[0].update(run_id=controls[0][0]['run_id']),
            lambda a,c,controls,idle:idle[1].update(run_id=idle[0]['run_id']),
        ):
            a,c=self.pairs(); controls,idle=self.controls(); mutate(a,c,controls,idle)
            with self.assertRaises(analyzer.ReplayError):analyzer.compare_runs(a,c,controls,idle,iterations=1000)

    def test_control_pair_ids_match_and_cannot_reuse_other_pair_ids(self):
        for mutate in (
            lambda a,c,controls,idle:controls[0][1].update(pair_id='wrong'),
            lambda a,c,controls,idle:idle[1].update(pair_id='wrong'),
            lambda a,c,controls,idle:[r.update(pair_id=a[0]['pair_id']) for r in controls[0]],
            lambda a,c,controls,idle:[r.update(pair_id=controls[0][0]['pair_id']) for r in idle],
            lambda a,c,controls,idle:idle.pop(),
            lambda a,c,controls,idle:idle[1].update(arm='A'),
        ):
            a,c=self.pairs(); controls,idle=self.controls(); mutate(a,c,controls,idle)
            with self.assertRaises(analyzer.ReplayError):analyzer.compare_runs(a,c,controls,idle,iterations=1000)

    def test_controls_match_uid_source_and_global_sequence(self):
        for mutate in (
            lambda controls,idle:controls[0][0].update(uid=999),
            lambda controls,idle:idle[0].update(byte_source='direct'),
            lambda controls,idle:idle[0].update(sequence=1),
        ):
            a,c=self.pairs(); controls,idle=self.controls(); mutate(controls,idle)
            with self.assertRaises(analyzer.ReplayError):analyzer.compare_runs(a,c,controls,idle,iterations=1000)

    def test_mismatched_workload_identity_or_reused_runs_rejected(self):
        for mutate in (
            lambda a,c:c[0]['workload'].update(transport='different'),
            lambda a,c:c[0].update(pair_id='wrong'),
            lambda a,c:c[0].update(uid=999),
            lambda a,c:c[0].update(byte_source='direct'),
            lambda a,c:c[0].update(run_id=a[0]['run_id']),
            lambda a,c:c[0]['deltas'].update(replay_req_accepted=0),
            lambda a,c:c.pop(),
        ):
            a,c=self.pairs(); mutate(a,c)
            with self.assertRaises(analyzer.ReplayError):analyzer.compare_runs(a,c,iterations=1000)


if __name__ == '__main__':unittest.main()
