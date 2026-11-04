# Email-change qualification fixture

`with-pinned-email-e2e.sh` builds an archive of the pushed Hub revision in a
temporary directory. It uses ports 3148/8848 by default, named
`rownd-email-e2e` containers, pinned Core/Postgres images, plugin 1.0.4 and
the local Rownd SDK with published SuperTokens Android 0.5.4. It does not use
the sibling Hub working tree. Override `ROWND_HUB_REPOSITORY`,
`ANDROID_HARNESS_PORT`, `ANDROID_HUB_PORT`, or `ANDROID_CONTAINER_PREFIX` to
avoid collisions. The in-app controller is emulator-only: it requires
`ANDROID_HOST=127.0.0.1` and scoped `adb reverse`; physical devices are not supported.

Host-only backend characterization:

```sh
bash test-server/with-pinned-email-e2e.sh node test-server/qualify-email-backend.mjs
```

The in-app controller passed the three runs below; its subsequent review fixes
and final full-suite compatibility still require validation:

```sh
ANDROID_API_URL=http://127.0.0.1:3148 ANDROID_HUB_URL=http://127.0.0.1:8848 ANDROID_APP_KEY=test_app_key \
  ./gradlew :app:assembleLocalDebug :app:assembleLocalDebugAndroidTest -ProwndUseLocalSdk=true
bash test-server/with-pinned-email-e2e.sh python3 test-server/run-email-e2e-in-app.py --device emulator-5554
```

The runner clears only the sandbox package before a scenario, then preserves its
data across both target-only force-stops. It withholds raw instrumentation output
because it may contain credentials. It creates and removes its own adb reverse
mappings; cleartext verification requires the loopback URLs shown above. Do not share raw logs, app preferences,
verification links, or unredacted screenshots. This fixture does not establish
external-verification E2E coverage or canonical account-link rendering.

CI's `npm run test:e2e` uses remote Hub `develop` (recorded in the job artifact),
loopback with scoped adb reverse mappings, and the same pinned backend images,
real-email-change mode and 90-second token validity as this fixture. It runs the
ordinary real-Hub class, including the real 605-second age proof, then the
controller's restart checkpoints. `ANDROID_HARNESS_COMMAND_TIMEOUT_MS` controls
the command deadline (default 2,400,000 ms); it must be a positive integer.
The independent pinned wrapper remains available for reproducing the historical
qualification; neither fixture qualifies a customer's external verification flow.

Integration CI installs a pinned Chrome/WebView 122 fixture on API 26/29 before
the bridge capability preflight. The download mirror is not trusted: the script
checks the complete APK SHA-256 and the Google signing certificate before adb
installs it. API 29 uses the x86 image for this fixture; API 33/35 retain x86_64.
Unsupported providers still fail the security preflight, and unsupported-capability
tests remain enabled.

With plugin 1.0.4 and Core 12.4.1, host characterization found that a pending
email-change link verified without a session or with a different session for
the same account returns `email change verification requires the initiating
session`. The link carries the verification token and pending ID, not session
credentials. Verification with the initiating session revokes that session,
returns a replacement for the same user, and leaves the new verified email as
the sole passwordless login method. This does not reproduce external revocation.
An independent Core control received a 30-second access token; after the
backend's HTTP Date crossed its expiry, a protected request returned 401.

One loopback B run passed in-app verification and the first restart's rendered
email check, then failed the second restart's single-sample rendered email
assertion after real expiry. That sample did not record the actual value or
establish second-restart PID before the assertion. It is not a pre-fix red or
proof of a product bug. The corrected checkpoint waits for a unique,
visible input with the canonical value and records bounded redacted samples.

## Qualified in-app control: three runs

All three isolated runs of the command above passed with local Rownd source at
`8e468ee` plus the **uncommitted harness delta under review**. Each run created
a fresh backend and account, completed real in-app verification, and passed
both restart checkpoints. Both PIDs changed before UI assertions, the backend
reported the same owner, public tenant and one verified new-email passwordless
method, the sandbox showed authenticated/new-email state, and the real Hub
rendered a unique visible canonical email input. Between restarts, the retained
access token expired by server time and Core returned 401 for that token.

- Android dependency: published `com.github.supertokens:supertokens-android:0.5.4`, resolved using `:app:dependencyInsight` and `-ProwndUseLocalSdk=true`; that resolution alone does not independently prove an already-built APK's contents. The recorded build command, APK hashes and compiled fixture configuration checks establish the available provenance together.
- Hub: pushed revision `4b70115232edbee3f7459a1ec86332606db0c8e9`, built from a Git archive and its lockfile, not a mutable sibling checkout.
- Node SDK `24.0.3`; Rownd plugin `1.0.4`.
- Core: `supertokens/supertokens-postgresql@sha256:c74eef1f69b78388d6a3b5af142c8338be7824e532fe6d9e778a932227151637` (12.4.1).
- PostgreSQL: `postgres@sha256:c2427de38f998489d36de7ca3553db2134872c400f2b08be4b824e5c50e4d619`.
- App APK SHA-256: `b21c5575480326769bc801ae7b4de2d6cb85cbddf3ec486b00da58eaf34644dd`.
- Instrumentation APK SHA-256: `8244a70059de043dbcc32e9c573327741ca9f5e24a9d8d15006105fc0940b486`.

The dependency check used:

```sh
./gradlew :app:dependencyInsight --dependency supertokens-android --configuration localDebugRuntimeClasspath -ProwndUseLocalSdk=true
```

Per-run wall-clock durations were not captured; do not infer them from token
timestamps. Future runs should record controller elapsed time without storing
tokens, link URLs or raw instrumentation output. A previous single-sample
failure is not a behavioral red: in the passing runs the input was sometimes
observed empty before its canonical value rendered.

Runs 1, 2 and 3 each used the same pinned-wrapper command shown above, with a
new harness and isolated account each time. Each returned exit code 0: one
verification phase, two successful restart/UI phases, and one retained-token
server-expiry check. Elapsed duration: **not recorded** for each run.
The reviewable sanitized transcript excerpt is
[`email-e2e-b-evidence.md`](email-e2e-b-evidence.md); it is transcribed from the
foreground outputs, not an independently preserved raw execution log.

## Remaining evidence and device-window recipe

The host backend finding blocks external scenario A until customer backend and
verification context are known. No pre-fix behavioral red was captured. The
three B runs do not establish linked-account UI, a customer app's settings,
screenshots, or the final full instrumented suite. In the next exclusive device
window, after review, run the two existing #3832 test classes (nine tests), the
full Rownd instrumented suite, and the focused `rph_init` test against an
isolated harness; rerun JVM tests at the final tested commit. Use the existing
`test:integration` script with a free `ANDROID_HARNESS_PORT` and an exclusive
device. Record counts and skips separately.

From the final reviewed commit, an example isolated integration invocation is:

```sh
ANDROID_SERIAL=emulator-5554 ANDROID_HARNESS_PORT=3150 ANDROID_CONTAINER_PREFIX=rownd-email-e2e-integration \
  ANDROID_CORE_IMAGE='supertokens/supertokens-postgresql@sha256:c74eef1f69b78388d6a3b5af142c8338be7824e532fe6d9e778a932227151637' \
  npx tsx test-server/with-harness.ts -- sh -c './gradlew :android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.harnessUrl=$ANDROID_HARNESS_URL -Pandroid.testInstrumentationRunnerArguments.class=io.rownd.android.EmailChange3832ReproInstrumentedTest,io.rownd.android.ProfileWriteOrdering3832ReproInstrumentedTest'
```

Use the same isolated wrapper without the `class` argument for the full
`:android:connectedDebugAndroidTest` suite. For the focused payload test, use
`io.rownd.android.EmailChange3832ReproInstrumentedTest#manageAccountAfterReopenGetsAUsableRphInit`
as the class argument. Run `./gradlew :android:testDebugUnitTest --rerun-tasks`
at the final tested commit. Verify ports and container prefix are free first;
do not stop any existing service to make room.

Screenshot plan: use synthetic accounts; capture only the settled host settings
and Manage Account screen, never the browser, verification page, notifications,
token diagnostics or app switcher. Keep the original private and ephemeral;
mask all identity text and any unexpected sensitive UI locally, inspect the
result before sharing, then delete the private original. If a safe crop cannot
be confirmed, omit the screenshot rather than retaining sensitive material.

### Account-method UI gap

In the pinned Hub, `Profile/AccountInformation/AccountInformation.tsx` renders
verification fields from `dataToShow`; `src/scripts/utils/form.tsx` assigns
`data-testid="rownd-ui-profile-field-email"` to the input. The existing B test
checks that input against the canonical email, but source inspection did not
find a separate linked-account list/test ID in this mobile Manage Account view.
The add-methods control is explicitly gated out for `mobile_app`. Therefore the
email field alone cannot prove which linked accounts the UI renders. On the
next device window, inspect the actual account/sign-in UI for a distinct method
row; if present, assert its visible label matches the server's sole verified
new-email passwordless method. If no such row exists, record that limitation;
do not infer unlinking or claim linked-account coverage from the input.
