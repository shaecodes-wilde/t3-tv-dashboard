#!/usr/bin/env python3
"""Run isolated native UI fixtures on an explicitly selected Android TV device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor
from xml.etree import ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial', help='explicit adb device serial; never inferred')
parser.add_argument('--output', type=Path, default=Path('.research/native-qa'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(root / '.tools/jdk'))
env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
env['JAVA_OPTS'] = '-XX:ActiveProcessorCount=2'
tools = root / '.tools/build-tools/android-14'
android = root / '.tools/platform/android-34/android.jar'
adb = [str(root / '.tools/platform-tools/adb'), '-P', '5037', '-s', args.serial]


def command(name, argv, allowed=(0,)):
    result = subprocess.run([str(a) for a in argv], cwd=root, env=env, capture_output=True)
    with (output / (name + '.log')).open('ab') as log:
        log.write((json.dumps([str(a) for a in argv]) + '\n').encode())
        log.write(result.stdout + result.stderr)
        log.write(('\nEXIT: ' + str(result.returncode) + '\n').encode())
    if result.returncode not in allowed:
        raise RuntimeError(f'{name} exited {result.returncode}; see {output / (name + ".log")}')
    return result.stdout.decode()


size = command('device-size', adb + ['shell', 'wm', 'size'])
density = command('device-density', adb + ['shell', 'wm', 'density'])
if re.findall(r'size:\s*(\d+x\d+)', size)[-1] != '1920x1080' or re.findall(r'density:\s*(\d+)', density)[-1] != '320':
    raise SystemExit('Native fixture profiles require a 1920x1080 / 320dpi TV; global display settings are never changed.')
package = command('device-package', adb + ['shell', 'pm', 'path', 'com.wilde.t3tv.preview'])
if package.strip():
    command('preview-access', adb + ['shell', 'run-as', 'com.wilde.t3tv.preview', 'pwd'])
    result = subprocess.run(adb + ['shell', 'run-as', 'com.wilde.t3tv.preview', 'test', '-f', 'files/connection.json'], capture_output=True)
    (output / 'preview-isolation.log').write_bytes(result.stdout + result.stderr + ('\nEXIT: ' + str(result.returncode) + ' (1 means absent)\n').encode())
    if result.returncode == 0:
        raise SystemExit('Preview is configured; refusing to overwrite its connection or drafts.')
    if result.returncode != 1:
        raise SystemExit('Could not verify preview isolation: ' + result.stderr.decode())

head = command('head', ['git', 'rev-parse', 'HEAD']).strip()
if command('status', ['git', 'status', '--porcelain']).strip():
    head += '-dirty'
command('build', ['bash', 'scripts/build.sh'])
ns = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', ns[1:-1])
manifest = ET.parse(root / 'android/AndroidManifest.xml')
manifest.getroot().set('package', 'com.wilde.t3tv.preview')
application = manifest.getroot().find('application')
application.set(ns + 'label', 'T3 Dashboard UI preview')
for component in application:
    name = component.get(ns + 'name')
    if name and name.startswith('.'):
        component.set(ns + 'name', 'com.wilde.t3tv' + name)
(output / 'preview').mkdir(exist_ok=True)
manifest.write(output / 'preview/AndroidManifest.xml', encoding='unicode')
command('preview-package', [tools / 'aapt', 'package', '-f', '-M', output / 'preview/AndroidManifest.xml', '-S', root / 'android/res', '-I', android, '-F', output / 'preview-unsigned.apk'])
with zipfile.ZipFile(output / 'preview-unsigned.apk', 'a') as archive:
    archive.write(root / 'build/dex/classes.dex', 'classes.dex')


def sign(name):
    command(name + '-align', [tools / 'zipalign', '-f', '4', output / (name + '-unsigned.apk'), output / (name + '-aligned.apk')])
    command(name + '-sign', [tools / 'apksigner', 'sign', '--ks', root / '.private/debug.keystore', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output / (name + '.apk'), output / (name + '-aligned.apk')])
    command(name + '-verify', [tools / 'apksigner', 'verify', output / (name + '.apk')])


sign('preview')
with zipfile.ZipFile(root / 'artifacts/t3-dashboard.apk') as candidate, zipfile.ZipFile(output / 'preview.apk') as preview:
    assert candidate.read('classes.dex') == preview.read('classes.dex')
    resources = {name: candidate.read(name) == preview.read(name) for name in candidate.namelist() if name.startswith('res/')}
    assert all(resources.values())
    (output / 'preview-equivalence.json').write_text(json.dumps({'head': head, 'dex_sha256': hashlib.sha256(candidate.read('classes.dex')).hexdigest(), 'dex_identical': True, 'resource_files_identical': resources, 'resource_table': 'package name changes; resource IDs/values retained'}, indent=2) + '\n')

for folder in ['classes', 'dex']:
    (output / folder).mkdir(exist_ok=True)
command('test-compile', ['javac', '-J-XX:ActiveProcessorCount=2', '-source', '8', '-target', '8', '-Xlint:-options', '-bootclasspath', str(android) + ':' + str(tools / 'core-lambda-stubs.jar'), '-classpath', root / 'build/classes.jar', '-d', output / 'classes', root / 'tests/com/wilde/t3tv/QaInstrumentation.java'])
command('test-jar', ['jar', 'cf', output / 'classes.jar', '-C', output / 'classes', '.'])
command('test-dex', [tools / 'd8', '--classpath', root / 'build/classes.jar', '--lib', android, '--min-api', '26', '--output', output / 'dex', output / 'classes.jar'])
command('test-package', [tools / 'aapt', 'package', '-f', '-M', root / 'tests/native/AndroidManifest.xml', '-I', android, '-F', output / 'test-unsigned.apk'])
with zipfile.ZipFile(output / 'test-unsigned.apk', 'a') as archive:
    archive.write(output / 'dex/classes.dex', 'classes.dex')
sign('test')
command('install-preview', adb + ['install', '-r', output / 'preview.apk'])
command('install-test', adb + ['install', '-r', output / 'test.apk'])
passed = True
for profile in ['1080p', '720p']:
    command(profile + '-instrument', adb + ['shell', 'am', 'instrument', '-w', '-e', 'profile', profile, '-e', 'head', head, 'com.wilde.t3tv.qa/com.wilde.t3tv.QaInstrumentation'])
    folder = output / profile
    folder.mkdir(exist_ok=True)
    names = command(profile + '-files', adb + ['shell', 'run-as', 'com.wilde.t3tv.preview', 'ls', 'files/qa-' + profile]).splitlines()

    def collect(name):
        if re.fullmatch(r'[A-Za-z0-9_.-]+\.(png|txt)', name):
            data = subprocess.check_output(adb + ['exec-out', 'run-as', 'com.wilde.t3tv.preview', 'cat', 'files/qa-' + profile + '/' + name])
            (folder / name).write_bytes(data)

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(collect, names))
    report = (folder / 'results.txt').read_text()
    ok = 'Candidate: ' + head + '\n' in report and 'failures/errors: 0\n' in report and not re.search(r'^(FAIL|ERROR) ', report, re.M)
    passed &= ok
    print(profile + ': ' + ('PASS' if ok else 'FAIL') + '; ' + str(folder / 'results.txt'), flush=True)
raise SystemExit(0 if passed else 1)
