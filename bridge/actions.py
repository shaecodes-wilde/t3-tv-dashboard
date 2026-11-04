"""Narrow TV API and durable retry receipts; no direct T3 database writes."""
import hashlib
import json
import re
import shlex
import sqlite3
import subprocess
import threading
from pathlib import Path


class Actions:
    def __init__(self, config, state):
        self.config = config
        self.lock = threading.Lock()
        self.db = sqlite3.connect(state, check_same_thread=False)
        self.db.execute('''CREATE TABLE IF NOT EXISTS actions (
            environment TEXT, request_id TEXT, fingerprint TEXT NOT NULL,
            prepared TEXT, result TEXT, PRIMARY KEY(environment,request_id))''')
        self.db.commit()

    def source(self, env):
        for source in self.config['environments']:
            if source.get('id') == env and source.get('api'):
                return source
        raise ValueError('This environment is unavailable or is not paired for replies.')

    def call(self, source, payload):
        api = source['api']
        args = [api['node'], api['helper'], source['userdata'], api['session']]
        if source.get('ssh'):
            args = ['ssh', '-T', '-oBatchMode=yes', '-oConnectTimeout=5', '--', source['ssh'], shlex.join(args)]
        try:
            result = subprocess.run(args, input=json.dumps(payload), text=True, capture_output=True, timeout=38)
        except subprocess.TimeoutExpired:
            raise ValueError('Connection timed out. Your draft is saved; retry it to check delivery safely.') from None
        try:
            response = json.loads(result.stdout)
        except (ValueError, TypeError):
            raise ValueError('Could not contact this T3 environment. Your draft is saved.') from None
        if not response.get('ok'):
            raise ValueError(response.get('error', 'T3 could not accept the request.'))
        return response['result']

    def read(self, operation, body):
        source = self.source(body.get('environmentId'))
        return self.call(source, {'operation': operation, 'threadId': body.get('threadId'), 'cursor': body.get('cursor')})

    def mutate(self, body):
        source = self.source(body.get('environmentId'))
        request_id = body.get('clientRequestId', '')
        if not isinstance(request_id, str) or not re.fullmatch(r'[a-zA-Z0-9-]{16,80}', request_id):
            raise ValueError('Invalid request identifier.')
        if body.get('action') not in {'send', 'answer', 'approve'}:
            raise ValueError('Unsupported action.')
        canonical = json.dumps(body, sort_keys=True, separators=(',', ':'))
        fingerprint = hashlib.sha256(canonical.encode()).hexdigest()
        key = (source['id'], request_id)
        # Serializes double-clicks. A persisted prepared command survives bridge restarts.
        with self.lock:
            existing = self.db.execute('SELECT fingerprint,prepared,result FROM actions WHERE environment=? AND request_id=?', key).fetchone()
            if existing and existing[0] != fingerprint:
                raise ValueError('This retry differs from the original draft. Start a new reply.')
            if existing and existing[2]:
                return json.loads(existing[2])
            if existing and existing[1]:
                prepared = json.loads(existing[1])
            else:
                prepared = self.call(source, dict(body, operation='prepare'))
                with self.db:
                    self.db.execute('INSERT OR REPLACE INTO actions VALUES (?,?,?,?,NULL)', (*key, fingerprint, json.dumps(prepared)))
            result = self.call(source, {'operation': 'dispatch', 'prepared': prepared})
            with self.db:
                self.db.execute('UPDATE actions SET result=? WHERE environment=? AND request_id=?', (json.dumps(result), *key))
            return result
