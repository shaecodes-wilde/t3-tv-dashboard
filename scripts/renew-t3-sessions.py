#!/usr/bin/env python3
"""Renew only the TV bridge's read/operate credentials, without printing secrets."""
import json
import os
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
config=json.loads((root/'.private/bridge.json').read_text())
worker=r'''
import json,os,pathlib,subprocess,sys
session=pathlib.Path(sys.argv[1]);cli=sys.argv[2]
os.umask(0o077)
old=json.loads(session.read_text()) if session.exists() else None
result=subprocess.run([cli,'auth','session','issue','--scope','orchestration:read','--scope','orchestration:operate','--ttl','90d','--label','T3 TV dashboard','--json'],capture_output=True,text=True,check=True)
new=json.loads(result.stdout)
temp=session.with_suffix('.new');temp.write_text(json.dumps(new));temp.chmod(0o600);temp.replace(session)
if old:
 revoked=subprocess.run([cli,'auth','session','revoke','--session-id',old['sessionId']],capture_output=True,text=True)
 if revoked.returncode: print('Credential renewed; previous credential could not be revoked. Review T3 client sessions.')
print('Scoped credential renewed; expires '+str(new.get('expiresAt','unknown')))
'''
for source in config['environments']:
    api=source.get('api')
    if not api:raise SystemExit('Environment '+source['label']+' is not configured for replies.')
    if source.get('ssh'):
        command='python3 - '+shlex.quote(api['session'])+' t3'
        args=['ssh','-T','-oBatchMode=yes','-oConnectTimeout=5',source['ssh'],command]
    else:
        args=['python3','-',api['session'],str(Path.home()/'.t3/bin/t3')]
    response=subprocess.run(args,input=worker,text=True,capture_output=True,timeout=60)
    if response.returncode:raise SystemExit('Could not renew '+source['label']+'. Existing credential was retained if issuance failed.')
    print(source['label']+': '+response.stdout.strip())
