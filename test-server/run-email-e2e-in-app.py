"""Run the real in-app callback control with two target-only process restarts."""
import argparse
import atexit
import base64
import email.utils
import hashlib
import json
import os
import re
import signal
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from email_e2e_diagnostics import checkpoint_markers

parser = argparse.ArgumentParser()
parser.add_argument('--device', required=True)
args = parser.parse_args()
package = 'io.rownd.rowndtestsandbox.app.local'
test_package = package + '.test/androidx.test.runner.AndroidJUnitRunner'
test_class = 'io.rownd.rowndtestsandbox.RealHubE2ETest'
base = os.environ['HARNESS_URL']
android_url = os.environ['ANDROID_HARNESS_URL']
adb = ['adb', '-s', args.device]

def terminate(_signal, _frame):
    raise SystemExit('controller terminated')

signal.signal(signal.SIGTERM, terminate)

def command(*parts):
    return subprocess.check_output([*adb, *parts], text=True, timeout=120)

if os.environ.get('ANDROID_HOST') != '127.0.0.1':
    raise RuntimeError('The emulator fixture requires ANDROID_HOST=127.0.0.1 and matching app BuildConfig URLs')
existing_reverses = command('reverse', '--list')
for port in (os.environ.get('ANDROID_HARNESS_PORT', '3148'), os.environ.get('ANDROID_HUB_PORT', '8848')):
    endpoint = 'tcp:' + port
    if any(endpoint in line.split() for line in existing_reverses.splitlines()):
        raise RuntimeError(f'adb reverse {endpoint} already exists; refusing to replace a shared mapping')
    command('reverse', endpoint, endpoint)
    atexit.register(lambda value=endpoint: command('reverse', '--remove', value))

def instrument(method, preserve=False, previous_pid=None):
    print(f'{method}: START', flush=True)
    try:
        result = command('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                         f'{test_class}#{method}', '-e', 'harnessUrl', android_url,
                         '-e', 'preserveDraft', str(preserve).lower(), '-e', 'minimumSessionAgeSeconds', '0',
                         '-e', 'expectedHubUrl', os.environ['ANDROID_HUB_URL'], test_package)
    except subprocess.TimeoutExpired:
        try:
            command('shell', 'am', 'force-stop', package)
        except (subprocess.SubprocessError, OSError):
            pass
        raise RuntimeError(f'{method}: instrumentation exceeded 120 seconds; target force-stopped') from None
    if previous_pid is not None:
        current_pid = checkpoint_pid()
        print(f'checkpoint process: previous={previous_pid} current={current_pid} changed={current_pid != previous_pid}', flush=True)
    markers = checkpoint_markers(result)
    if markers:
        print(f'Checkpoint observations: {json.dumps(markers)}', flush=True)
    if 'OK (1 test)' not in result:
        locations = re.findall(r'\bat (io\.rownd\.[\w.$]+\([\w.]+:\d+\))', result)
        diagnostics = re.findall(r'EMAIL_E2E_CALLBACK_UI (?:\d+=(?:true|false)\s*)+', result)
        dom_diagnostics = markers
        def optional_json(path):
            try:
                with urllib.request.urlopen(base + path, timeout=5) as response:
                    return json.load(response)
            except (OSError, ValueError):
                return 'unavailable'
        counters = optional_json('/counters')
        statuses = optional_json('/test/http-statuses')
        raise RuntimeError(f'{method} failed; Rownd stack locations: {locations[:5]}; safe UI markers: {diagnostics}; DOM: {dom_diagnostics}; counters: {counters}; HTTP: {statuses}; other output withheld')
    if previous_pid is not None:
        assert current_pid != previous_pid, 'process did not change before UI checkpoint'
        assert saved_pid('completedPid') == current_pid, 'UI checkpoint did not record completion in the new process'
    print(f'{method}: PASS', flush=True)

def server_time():
    with urllib.request.urlopen(base + '/health', timeout=5) as response:
        return email.utils.parsedate_to_datetime(response.headers['Date']).timestamp()

def token_from_storage():
    xml = command('shell', 'run-as', package, 'cat', 'shared_prefs/supertokens-android-shared-preferences.xml')
    values = {node.get('name'): node.text for node in ET.fromstring(xml)}
    return values['st-storage-item-st-access-token']

def token_expiry(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['exp']

def saved_pid(name):
    xml = command('shell', 'run-as', package, 'cat', 'shared_prefs/draft-email-qualification.xml')
    return next(node.get('value') for node in ET.fromstring(xml) if node.get('name') == name)

def checkpoint_pid():
    return saved_pid('checkpointPid')

dependency = subprocess.check_output(['./gradlew', ':app:dependencyInsight', '--dependency', 'supertokens-android',
                                      '--configuration', 'localDebugRuntimeClasspath', '-ProwndUseLocalSdk=true'],
                                     text=True, timeout=120)
assert re.search(r'^com\.github\.supertokens:supertokens-android:0\.5\.4\s*$', dependency, re.MULTILINE), 'Expected selected published SDK 0.5.4'
print('Resolved SuperTokens Android: 0.5.4', flush=True)
for apk in ('app/build/outputs/apk/local/debug/app-local-debug.apk',
            'app/build/outputs/apk/androidTest/local/debug/app-local-debug-androidTest.apk'):
    print(f'APK {Path(apk).name} sha256={hashlib.sha256(Path(apk).read_bytes()).hexdigest()}', flush=True)
    command('install', '-r', '-t', apk)
command('shell', 'pm', 'clear', package)
instrument('draftEmailVerificationReplacesSessionThroughReleasedSdk')
previous_pid = checkpoint_pid()
command('shell', 'am', 'force-stop', package)
instrument('verifiedEmailIsVisibleInHostAndRenderedHubAfterProcessRestart', True, previous_pid)
first_pid = checkpoint_pid()
assert previous_pid and first_pid and previous_pid != first_pid, 'first restart did not create a new process'
token = token_from_storage()
print(f'Before second stop: tokenFingerprint={hashlib.sha256(token.encode()).hexdigest()[:12]} exp={token_expiry(token)}', flush=True)
command('shell', 'am', 'force-stop', package)
token = token_from_storage()
deadline = time.monotonic() + 150
while True:
    expiry = token_expiry(token)
    print(f'Retained before launch: tokenFingerprint={hashlib.sha256(token.encode()).hexdigest()[:12]} exp={expiry}', flush=True)
    while server_time() <= expiry:
        if time.monotonic() >= deadline:
            raise RuntimeError('retained access token did not expire within 150 seconds')
        time.sleep(0.5)
    request = urllib.request.Request(base + '/test/protected', headers={
        'Authorization': 'Bearer ' + token, 'rid': 'session', 'st-auth-mode': 'header',
    })
    try:
        urllib.request.urlopen(request, timeout=5).close()
    except urllib.error.HTTPError as error:
        assert error.code == 401, f'expired token returned HTTP {error.code}'
    else:
        raise AssertionError('server accepted the expired access token')
    retained = token_from_storage()
    if retained == token:
        print('Retained token expired and was rejected by Core: PASS', flush=True)
        break
    if time.monotonic() >= deadline:
        raise RuntimeError('retained access token kept rotating past deadline')
    token = retained
instrument('verifiedEmailIsVisibleInHostAndRenderedHubAfterProcessRestart', True, first_pid)
second_pid = checkpoint_pid()
assert second_pid and second_pid != first_pid, 'second restart did not create a new process'
print('Two distinct process restarts with retained app data: PASS', flush=True)
command('shell', 'am', 'force-stop', package)
instrument('coldVerifiedEmailPersistsAndRefreshesAfterProcessRestart', True)
