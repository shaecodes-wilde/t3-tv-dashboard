import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bridge'))
from server import Tracker
from reader import read_environment


class TrackerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / 'events.sqlite')
        self.tracker = Tracker(self.path)

    def tearDown(self):
        self.tracker.db.close()
        self.temp.cleanup()

    def update(self, status, run='r1', request=''):
        self.tracker.update({'id':'env', 'label':'Environment', 'threads':[
            {'id':'thread', 'title':'Task', 'status':status, 'eventKey':f'{run}:{status}:{request}'}]})

    def test_initial_history_does_not_alert(self):
        self.update('completed')
        self.assertEqual(self.tracker.events(), [])

    def test_completion_once_and_persists_after_restart(self):
        self.update('running')
        self.update('completed')
        self.update('completed')
        self.assertEqual(len(self.tracker.events()), 1)
        stream = self.tracker.stream_id
        self.tracker.db.close()
        self.tracker = Tracker(self.path)
        self.update('completed')
        self.assertEqual(len(self.tracker.events()), 1)
        self.assertEqual(stream, self.tracker.stream_id)

    def test_new_run_and_distinct_questions_alert(self):
        self.update('completed')
        self.update('completed', 'r2')
        self.update('waiting', 'r2', 'question1')
        self.update('waiting', 'r2', 'question1')
        self.update('waiting', 'r2', 'question2')
        self.assertEqual(len(self.tracker.events()), 3)

    def test_disconnect_does_not_reset_history(self):
        self.update('running')
        # Offline environments never call Tracker.update.
        self.update('completed')
        self.update('completed')
        self.assertEqual(len(self.tracker.events()), 1)


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root/'environment-id').write_text('test-env')
        self.db=sqlite3.connect(self.root/'statev2.sqlite')
        self.db.executescript('''
          CREATE TABLE projection_projects(project_id TEXT,title TEXT);
          CREATE TABLE orchestration_v2_projection_threads(thread_id TEXT,project_id TEXT,title TEXT,updated_at TEXT,payload_json TEXT,deleted_at TEXT,archived_at TEXT);
          CREATE TABLE orchestration_v2_projection_runs(run_id TEXT,thread_id TEXT,status TEXT,completed_at TEXT,ordinal INTEGER);
          CREATE TABLE orchestration_v2_projection_runtime_requests(runtime_request_id TEXT,thread_id TEXT,kind TEXT,status TEXT);
        ''')

    def tearDown(self):
        self.db.close();self.temp.cleanup()

    def thread(self, id, status='completed', parent=None, **payload):
        if parent: payload['lineage']={'relationshipToParent':'subagent','parentThreadId':parent}
        self.db.execute('INSERT INTO orchestration_v2_projection_threads VALUES (?,?,?,?,?,NULL,NULL)',(id,'p',id,'2026-01-01',json.dumps(payload)))
        self.db.execute('INSERT INTO orchestration_v2_projection_runs VALUES (?,?,?,NULL,1)',('run-'+id,id,status))
        self.db.commit()

    def test_settled_and_snoozed_hidden(self):
        self.thread('done', settledAt='2026-01-01')
        self.thread('hidden', snoozedUntil='2999-01-01')
        self.thread('active')
        self.assertEqual([t['id'] for t in read_environment(self.root)['threads']], ['active'])

    def test_live_descendants_prevent_false_completion(self):
        self.thread('parent')
        self.thread('child', parent='parent')
        self.thread('grandchild','running',parent='child')
        tasks=read_environment(self.root)['threads']
        self.assertEqual(len(tasks),1)
        self.assertEqual(tasks[0]['status'],'running')

    def test_pending_question_overrides_running(self):
        self.thread('active','running')
        self.db.execute("INSERT INTO orchestration_v2_projection_runtime_requests VALUES ('question','active','user_input','pending')")
        self.db.commit()
        task=read_environment(self.root)['threads'][0]
        self.assertEqual(task['status'],'waiting')
        self.assertIn('question',task['eventKey'])

    def test_queued_followup_does_not_hide_running_task(self):
        self.thread('active','running')
        self.db.execute("INSERT INTO orchestration_v2_projection_runs VALUES ('queue','active','queued',NULL,2)")
        self.db.commit()
        self.assertEqual(read_environment(self.root)['threads'][0]['status'],'running')


if __name__=='__main__': unittest.main()
