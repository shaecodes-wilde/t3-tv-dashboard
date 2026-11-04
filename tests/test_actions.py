import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'bridge'))
from actions import Actions

class ActionsTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.config={'environments':[{'id':'env','api':{'node':'node'}}]}
        self.path=str(Path(self.temp.name)/'actions.sqlite');self.api=Actions(self.config,self.path)
        self.body={'environmentId':'env','threadId':'t','clientRequestId':'12345678-1234-1234-1234-123456789abc','action':'send','text':'Hello','mode':'auto'}
        self.calls=[]
        def call(source,payload):
            self.calls.append(payload)
            return {'command':{'type':'message.dispatch','commandId':'stable'}} if payload['operation']=='prepare' else {'accepted':True,'sequence':1}
        self.api.call=call
    def tearDown(self):self.api.db.close();self.temp.cleanup()
    def test_duplicate_submission_returns_original_receipt(self):
        self.assertEqual(self.api.mutate(self.body),self.api.mutate(self.body));self.assertEqual(len(self.calls),2)
    def test_changed_payload_with_same_id_rejected(self):
        self.api.mutate(self.body)
        with self.assertRaisesRegex(ValueError,'differs'):self.api.mutate(dict(self.body,text='changed'))
    def test_restart_retries_same_prepared_command_after_unknown_delivery(self):
        normal=self.api.call
        def lost(source,payload):
            if payload['operation']=='dispatch':raise ValueError('lost connection after send')
            return normal(source,payload)
        self.api.call=lost
        with self.assertRaises(ValueError):self.api.mutate(self.body)
        self.api.db.close();self.api=Actions(self.config,self.path);self.api.call=normal
        self.assertTrue(self.api.mutate(self.body)['accepted'])
        self.assertEqual([c['operation'] for c in self.calls],['prepare','dispatch'])
        self.assertEqual(self.calls[-1]['prepared']['command']['commandId'],'stable')
    def test_unknown_environment_and_action_rejected(self):
        for update in [{'environmentId':'other'},{'action':'shell'},{'clientRequestId':'bad'}]:
            with self.assertRaises(ValueError):self.api.mutate(dict(self.body,**update))

if __name__=='__main__':unittest.main()
