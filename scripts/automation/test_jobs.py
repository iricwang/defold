"""Task lifecycle regressions synchronized by events, without starting a game."""
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from server import ROOT, dispatch, tools
from core import AutomationError, Service
from jsonschema import ValidationError, validate


class JobTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=ROOT / 'tmp', prefix='automation-jobs-')
        self.addCleanup(self.directory.cleanup)
        self.service = self.make_service()
        self.addCleanup(self.service.close)

    def make_service(self):
        return Service({'engine': 'unused'}, [ROOT / 'scripts/automation/fixture'], self.directory.name)

    def submit(self, key, function):
        return self.service.submit(key, {'key': key}, function)['job_id']

    def blocked_job(self, key):
        entered, release = threading.Event(), threading.Event()
        self.addCleanup(release.set)
        def run(job):
            entered.set()
            if not release.wait(5):
                raise AssertionError('Test synchronization timeout')
            return {'evidence': key}
        job_id = self.submit(key, run)
        self.assertTrue(entered.wait(5))
        return job_id, release

    def test_wait_timeout_does_not_cancel_or_replay(self):
        job_id, release = self.blocked_job('blocked')
        state = dispatch(self.service, 'defold_job_wait', {'job_id': job_id, 'timeout_ms': 0})
        self.assertEqual('running', state['state'])
        self.assertFalse(state['terminal'])
        self.assertFalse(state['cancel_requested'])
        self.assertEqual(job_id, self.submit('blocked', lambda job: self.fail('Replayed task')))
        release.set()
        done = self.service.job_wait(job_id)
        self.assertTrue(done['terminal'])
        self.assertEqual('complete', done['state'])
        self.assertEqual({'evidence': 'blocked'}, done['result'])
        self.assertLessEqual(done['created_at'], done['started_at'])
        self.assertLessEqual(done['started_at'], done['finished_at'])
        self.assertEqual(done, self.service.job_wait(job_id, 0))
        json.dumps(done)  # Internal Future/Event/process objects must never enter the protocol.

    def test_wait_failure_keeps_typed_details(self):
        def fail(job):
            raise AutomationError('BUILD_NOT_FOUND', 'Compile first', {'path': 'build/default'})
        result = self.service.job_wait(self.submit('failed', fail))
        self.assertTrue(result['terminal'])
        self.assertEqual('failed', result['state'])
        self.assertEqual('BUILD_NOT_FOUND', result['error_code'])
        self.assertEqual({'path': 'build/default'}, result['error_details'])

    def test_queued_cancel_finishes_without_waiting_for_other_jobs(self):
        self.blocked_job('one')
        self.blocked_job('two')
        queued = self.submit('queued', lambda job: self.fail('Cancelled job executed'))
        self.assertEqual('queued', self.service.job_status(queued)['state'])
        cancelled = self.service.job_status(queued, cancel=True)
        self.assertTrue(cancelled['terminal'])
        self.assertTrue(cancelled['cancel_requested'])
        self.assertEqual('cancelled', cancelled['state'])
        self.assertNotIn('started_at', cancelled)
        self.assertEqual(cancelled, self.service.job_wait(queued, 0))

    def test_running_cancel_preserves_partial_result_until_finished(self):
        job_id, release = self.blocked_job('running')
        requested = self.service.job_status(job_id, cancel=True)
        self.assertFalse(requested['terminal'])
        self.assertTrue(requested['cancel_requested'])
        release.set()
        done = self.service.job_wait(job_id)
        self.assertTrue(done['terminal'])
        self.assertEqual('cancelled', done['state'])
        self.assertIn('evidence', done['result'])

    def test_cancelling_finished_job_does_not_relabel_success(self):
        job_id = self.submit('done', lambda job: {})
        done = self.service.job_wait(job_id)
        self.assertEqual(done, self.service.job_status(job_id, cancel=True))

    def test_shutdown_completes_queued_jobs_and_rejects_new_work(self):
        entered = [threading.Event(), threading.Event()]
        def cooperative(job, index):
            entered[index].set()
            self.assertTrue(job['cancel'].wait(5))
        for index in range(2):
            self.submit(str(index), lambda job, index=index: cooperative(job, index))
            self.assertTrue(entered[index].wait(5))
        queued = self.submit('queued', lambda job: self.fail('Shutdown ran queued work'))
        self.service.close()
        self.assertTrue(self.service.job_wait(queued, 0)['terminal'])
        for job_id in self.service.jobs:
            self.assertEqual('cancelled', self.service.job_status(job_id)['state'])
        with self.assertRaises(AutomationError) as failure:
            self.submit('after-close', lambda job: {})
        self.assertEqual('SERVICE_CLOSED', failure.exception.code)

    def test_restart_retries_remain_recovery_required_and_keep_journal(self):
        self.service.operation('old', {}, lambda: {'receipt': 1})
        journal = Path(self.directory.name) / ('operation-' + hashlib.sha256(b'old').hexdigest() + '.json')
        original = journal.read_bytes()
        replacement = self.make_service()
        try:
            for _ in range(2):
                with self.assertRaises(AutomationError) as failure:
                    replacement.operation('old', {}, lambda: self.fail('Replayed recovered operation'))
                self.assertEqual('RECOVERY_REQUIRED', failure.exception.code)
            status = dispatch(replacement, 'defold_operation_status', {'operation_id': 'old'})
            self.assertEqual('recovery_required', status['state'])
            self.assertEqual(original, journal.read_bytes())
        finally:
            replacement.close()

    def test_journal_failure_never_leaves_permanently_pending_operation(self):
        with patch('core.atomic_json', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.service.operation('disk-full', {}, lambda: self.fail('Unjournalled action ran'))
        with self.assertRaises(AutomationError) as failure:
            self.service.operation('disk-full', {}, lambda: {})
        self.assertEqual('OPERATION_FAILED', failure.exception.code)

    def test_wait_bounds_and_missing_job(self):
        schema = next(tool.inputSchema for tool in tools() if tool.name == 'defold_job_wait')
        for invalid in (-1, 10001, True, 1.5):
            with self.assertRaises(ValidationError):
                validate({'job_id': 'x', 'timeout_ms': invalid}, schema)
            with self.assertRaises(AutomationError) as failure:
                self.service.job_wait('x', invalid)
            self.assertEqual('INVALID_TIMEOUT', failure.exception.code)
        with self.assertRaises(AutomationError) as failure:
            self.service.job_wait('missing', 0)
        self.assertEqual('JOB_NOT_FOUND', failure.exception.code)


if __name__ == '__main__':
    unittest.main()
