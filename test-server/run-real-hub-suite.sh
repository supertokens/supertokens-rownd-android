#!/usr/bin/env bash
set -euo pipefail

if [ "${ANDROID_HOST:-}" != 127.0.0.1 ]; then
  echo 'Real Hub suite requires ANDROID_HOST=127.0.0.1 and adb reverse' >&2
  exit 1
fi
export ANDROID_HUB_PORT=${ANDROID_HUB_PORT:-8787}
node -e "const sdk = require('./node_modules/supertokens-node/package.json').version; const plugin = require('./node_modules/@supertokens-plugins/rownd-nodejs/package.json').version; if (sdk !== '24.0.3' || plugin !== '1.0.4') throw Error('Install the locked Node dependencies with npm ci'); console.log('Node SDK: ' + sdk + ', Rownd plugin: ' + plugin)"
device=${ANDROID_SERIAL:-$(adb get-serialno)}
if [ "$device" = unknown ]; then
  echo 'Select one emulator with ANDROID_SERIAL' >&2
  exit 1
fi
existing=$(adb -s "$device" reverse --list)
for port in "$ANDROID_HARNESS_PORT" "$ANDROID_HUB_PORT"; do
  if grep -Eq "(^|[[:space:]])tcp:$port([[:space:]]|$)" <<< "$existing"; then
    echo "adb reverse tcp:$port already exists" >&2
    exit 1
  fi
done
ports=()
cleanup() {
  for port in "${ports[@]}"; do adb -s "$device" reverse --remove "tcp:$port" || true; done
}
trap cleanup EXIT
for port in "$ANDROID_HARNESS_PORT" "$ANDROID_HUB_PORT"; do
  adb -s "$device" reverse "tcp:$port" "tcp:$port"
  ports+=("$port")
done
./gradlew :app:connectedLocalDebugAndroidTest -ProwndUseLocalSdk=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.rownd.rowndtestsandbox.RealHubE2ETest \
  "-Pandroid.testInstrumentationRunnerArguments.harnessUrl=$ANDROID_HARNESS_URL"
cleanup
ports=()
python3 test-server/run-email-e2e-in-app.py --device "$device"
