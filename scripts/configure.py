#!/usr/bin/env python3
"""Generate a private LAN bridge certificate and TV bootstrap. Never print tokens."""
import argparse
import ipaddress
import json
import os
import secrets
import subprocess
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--host', required=True, help='LAN IP of the computer running the bridge')
parser.add_argument('--fedora-ssh', default='fedora-t3')
parser.add_argument('--fedora-userdata', default='/home/t3-agent/.t3/userdata')
args=parser.parse_args()
ipaddress.ip_address(args.host)
root=Path(__file__).resolve().parents[1]
private=root/'.private'
private.mkdir(mode=0o700,exist_ok=True)
os.umask(0o077)
cert=private/'bridge.crt'
key=private/'bridge.key'
config_path=private/'bridge.json'
if config_path.exists():
    raise SystemExit('Configuration already exists. Edit .private/bridge.json to preserve the certificate and token.')
subprocess.run(['openssl','req','-x509','-newkey','rsa:3072','-nodes','-keyout',str(key),'-out',str(cert),'-days','825',
                '-subj','/CN=T3 TV Local Bridge','-addext',f'subjectAltName=IP:{args.host},IP:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
token=secrets.token_urlsafe(32)
config={'host':args.host,'port':8743,'token':token,'certificate':str(cert),'privateKey':str(key),'environments':[
    {'label':'the-orchestrator','userdata':str(Path.home()/'.t3/userdata')},
    {'label':'fedora','ssh':args.fedora_ssh,'userdata':args.fedora_userdata}]}
config_path.write_text(json.dumps(config,indent=2)+'\n')
(private/'connection.json').write_text(json.dumps({'url':f'https://{args.host}:8743','token':token,'certificate':cert.read_text()},indent=2)+'\n')
print('Private bridge and TV connection files created in .private/ (token omitted).')
