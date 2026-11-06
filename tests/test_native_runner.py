import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    'native_runner', Path(__file__).resolve().parents[1] / 'scripts/test-native.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class NativeRunnerTests(unittest.TestCase):
    def test_refused_or_aborted_run_cannot_reuse_old_passing_report(self):
        old_report = 'Candidate: head\nInvocation: old\nChecks: 63, failures/errors: 0\n'
        self.assertTrue(runner.report_passed(old_report, 'head', 'old'))
        for transcript in [
            'INSTRUMENTATION_RESULT: stream=Refusing fixtures\nINSTRUMENTATION_CODE: 0\n',
            'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n',
            'INSTRUMENTATION_FAILED: aborted\n',
            '',
        ]:
            with self.subTest(transcript=transcript):
                with self.assertRaises(RuntimeError):
                    runner.require_instrumentation_success(transcript)
        self.assertFalse(runner.report_passed(old_report, 'head', 'fresh'))

    def test_fresh_success_and_report_are_both_required(self):
        runner.require_instrumentation_success('INSTRUMENTATION_CODE: -1\n')
        report = 'Candidate: head\nInvocation: fresh\nChecks: 63, failures/errors: 0\n'
        self.assertTrue(runner.report_passed(report, 'head', 'fresh'))
        self.assertFalse(runner.report_passed(report, 'other-head', 'fresh'))
        self.assertFalse(runner.report_passed(report + 'FAIL a check\n', 'head', 'fresh'))
        self.assertFalse(runner.report_passed(report.replace('errors: 0', 'errors: 1'), 'head', 'fresh'))


if __name__ == '__main__':
    unittest.main()
