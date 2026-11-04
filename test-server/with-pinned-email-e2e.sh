#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo 'Usage: bash test-server/with-pinned-email-e2e.sh command [args...]' >&2
  exit 2
fi

hub_repo=${ROWND_HUB_REPOSITORY:-../supertokens-rownd-hub}
hub_revision=4b70115232edbee3f7459a1ec86332606db0c8e9
git -C "$hub_repo" merge-base --is-ancestor "$hub_revision" refs/remotes/origin/feat/draft-email-verification
work=$(mktemp -d "${TMPDIR:-/tmp}/rownd-email-e2e.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
git -C "$hub_repo" archive "$hub_revision" -o "$work/hub.tar"
mkdir "$work/hub"
tar -xf "$work/hub.tar" -C "$work/hub"
python3 -c 'import subprocess,sys; subprocess.run(["npm", "ci", "--prefix", sys.argv[1], "--ignore-scripts"], check=True, timeout=180)' "$work/hub"

export ANDROID_HUB_DIR="$work/hub"
export ANDROID_HOST=${ANDROID_HOST:-127.0.0.1}
export ANDROID_HARNESS_PORT=${ANDROID_HARNESS_PORT:-3148}
export ANDROID_HUB_PORT=${ANDROID_HUB_PORT:-8848}
export ANDROID_CONTAINER_PREFIX=${ANDROID_CONTAINER_PREFIX:-rownd-email-e2e}
export ANDROID_CORE_IMAGE='supertokens/supertokens-postgresql@sha256:c74eef1f69b78388d6a3b5af142c8338be7824e532fe6d9e778a932227151637'
export ANDROID_POSTGRES_IMAGE='postgres@sha256:c2427de38f998489d36de7ca3553db2134872c400f2b08be4b824e5c50e4d619'
export ANDROID_ACCESS_TOKEN_VALIDITY_SECONDS=${ANDROID_ACCESS_TOKEN_VALIDITY_SECONDS:-90}
export ANDROID_REAL_EMAIL_CHANGES=1
export ANDROID_E2E_LOCAL_HUB=1
printf 'Hub revision: %s\nCore image: %s\nPostgres image: %s\n' "$hub_revision" "$ANDROID_CORE_IMAGE" "$ANDROID_POSTGRES_IMAGE"
node -e "const sdk = require('./node_modules/supertokens-node/package.json').version; const plugin = require('./node_modules/@supertokens-plugins/rownd-nodejs/package.json').version; if (sdk !== '24.0.3' || plugin !== '1.0.4') throw Error('Install the locked Node dependencies with npm ci'); console.log('Node SDK: ' + sdk + ', Rownd plugin: ' + plugin)"
npx tsx test-server/with-harness.ts -- "$@"
