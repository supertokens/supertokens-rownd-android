# Regression and E2E test plan: email change (#3832)

## Goal and boundaries

Preserve existing base Android/iOS behavior while qualifying the customer's Android email-change flow. Add tests, not an offline/loading UX redesign. Fix production behavior only when a test identifies an unintended regression, in a separately explained change.

Current PRs: Rownd Android #5, base Android #83, base iOS #80. Planning baselines: Rownd `4470332`, Android `2c443fc`, iOS `c4bf4c6`.

The approved event contract stays unchanged: legacy callbacks admitted before replacement may execute afterward. Generation-bearing events support conditional SDK mutations; they do not fence unconditional application/UI writes. Automatic cookie persistence and cross-process session synchronization remain outside the generation fence.

## 1. Establish the coverage baseline

- Preserve and run the existing backend regression suites unchanged. Report counts and intentional skips separately from new tests.
- Inventory each proposed test against an existing test before adding it. Do not duplicate the already-covered late response, CAS, listener removal/reentrancy, same-session rotation and stale sign-out cases.
- Separate compatibility characterization from new generation-fence requirements. Run compatibility tests against published/pre-change 0.5.4 and the candidate where feasible; record intentional differences rather than demanding identical stale-response behavior.
- Use real public transports and persisted storage where those boundaries matter. Keep fast deterministic tests for ordering; add device tests for actual platform behavior rather than moving every case onto a device.
- Give each run isolated users, storage, ports and backend configuration. Tests release gates, unregister listeners and stop owned resources even on failure.

## 2. Base SDK regressions

### P0: refresh outcomes and recovery, both SDKs

Extend Android `SessionTransportGenerationTest.java` and iOS `sessionGenerationTests.swift` using gated real transport responses.

- Current-session refresh succeeds, gets terminal 401, or fails before headers due to timeout/disconnect.
- Include concurrent waiting requests. Assert bounded completion, correct success/error results, coherent retry credentials, hook/event behavior, and no stuck refresh lock/flight.
- Terminal invalidation clears the session; a transport failure must not be silently treated as confirmed logout. First characterize the existing SDK's precise public return/error semantics.
- A later healthy request can refresh successfully after a failed attempt. Check successful refresh deduplication; do not invent a guarantee that failed attempts always share one network call.
- Android exercises OkHttp and HttpURLConnection separately. iOS exercises the intercepted URLSession path, not just direct coordinator calls.

### P0: normal sign-out contract

- iOS: fill the current-session success, API error and network error matrix next to the existing accepted-clear/post-hook tests. Assert completion once, hook arguments/counts and persisted/auth state.
- Android: reuse existing normal and stale sign-out tests rather than duplicating them. Add a real-device current/stale pre-header failure check if the device transport gap is not covered by the cookie flow below.
- Keep normal-success controls alongside stale-response tests: a guard that never completes sign-out must fail.

### P0: real Android cookie happy path

Extend the standalone `testHelpers/testapp` instrumented tests with a reachable controlled backend and actual cookie handling.

- For each public transport: login, authenticated request, actual expiry/refresh, retried request and sign-out.
- Assert server-observed cookies, anti-CSRF behavior, expected request outcomes and final local state.
- This checks compatibility on Android, not stale automatic-cookie-jar protection. Do not promise that unsupported guarantee.

### P1: request/retry compatibility

- Android: combine existing custom-header and retry tests into a wire-level 401 → refresh → retry scenario. Verify caller headers/body survive, refresh-provider headers go to the correct request, SDK credentials rotate coherently, and retry limits still hold.
- Android: gate same-generation access/CSRF rotation during request preparation on both transports. The server receives an old or new coherent pair, never a mixture.
- iOS: exercise matching/excluded domains, caller authorization and custom headers together through an intercepted URLSession refresh/retry. Excluded requests bypass SDK mutation; distinct caller authorization is not accidentally overwritten.
- Assert observable requests/results, not private implementation steps or exact token equality after a server endpoint that reissues access tokens.

### P1: real persistence and public getters

- Android: seed legacy preference keys, initialize through public APIs, exercise legacy writers/removal, and relaunch the process without clearing data. Assert restored credentials and public getter behavior. Process-local generation resets on restart; do not reuse a previous process's CAS token as a durable identity.
- iOS: use an isolated real Keychain service on a simulator; install, reinitialize with the same configuration and inspect public getters/restored values. Cover existing legacy migration and service/configuration selection without promising cross-process synchronization.
- iOS: valid versus expired front-token cases through public session/user/token getters, followed by refresh success, 401 and network failure. Use controlled timestamps/transport rather than reviving disabled timing-sensitive tests unchanged.
- Preserve exact storage-key compatibility. A mocked store reset is not evidence of process-death persistence.

### P2: targeted secondary gaps

- iOS default/ephemeral URLSession and supported completion/delegate variants: normal response, refresh retry and single completion. Characterize cancellation/upload/download/background support before deciding whether additional guarantees belong in scope.
- iOS two URLSession instances share the documented global SDK configuration; do not accidentally test or promise independent multi-tenant sessions.
- Android pending event superseded by same-generation rotation: assert documented non-audit notification behavior and immutable event metadata, not strict legacy callback-entry ordering.
- Extend generation-bearing event coverage only for missing create/refresh/sign-out metadata and safe CAS cases. Keep synchronous refresh/reentrant callback controls.

## 3. Rownd Android: qualify the actual customer flow

### What the existing nine tests establish

`EmailChange3832ReproInstrumentedTest` and `ProfileWriteOrdering3832ReproInstrumentedTest` remain fast regression coverage. Their fixture changes email/revokes sessions through test endpoints, rebuilds the graph in the same process, and simulates expiry with a clock/front-token change. They do not prove external verification, actual process restart, server-token expiry, or rendered account links.

First tighten the authenticated Manage Account payload test: a missing `rph_init` must not count as a successful coherent payload when a valid session is expected. Absence remains valid in explicitly signed-out or `restoreSession=false` tests.

### Reuse the existing real-Hub infrastructure

- Extend `app/src/androidTest/java/io/rownd/rowndtestsandbox/RealHubE2ETest.kt`, the local host-app flavor, Espresso Web/UIAutomator, and `test-server/server.ts` real-email-change/email-capture support.
- Pin a pushed Hub continuation revision and backend/plugin/Core versions. Do not build from the sibling Hub's mutable working tree or its archived local race commits. Make the revision and resolved dependency versions part of the run report.
- Configure host/emulator-reachable API and Hub origins and compatible cleartext/HTTPS settings. Validate those before diagnosing an application failure.
- Add a small external controller or host-side runner that survives force-stopping the host app. Do not rely on instrumentation inside the force-stopped target process to orchestrate its own relaunch.

### Scenario A: external verification, the reported trigger

1. Create an isolated account and sign in through the real host app/Hub. Record canonical server identity, email, login methods and session handle.
2. Initiate email change through Manage Account. Capture the real verification link from the backend's email sink.
3. Complete verification with an independent external client/session. Do not send its replacement credentials to the host. Inspect the real server result and whether the original app session was revoked; do not simulate this by calling `/test/revoke-session`.
4. Force-stop and relaunch the host without clearing app data. Confirm a new process and await an observable initialization/UI checkpoint.
5. Cross genuine access-token expiry using a short test Core/backend TTL; verify token expiry and server time. A native clock offset or edited front token alone is insufficient.
6. Force-stop and relaunch a second time, preserving the scenario data and storage.

At each checkpoint:

- Inspect server session validity and canonical identity.
- Assert the host settings' visible email and authentication UI; never display the previous user's/profile's email under a new session.
- Open the actual Manage Account WebView. If authenticated, assert its rendered email field matches the canonical email. If revoked, assert an explicit signed-out/sign-in state rather than a misleading authenticated blank form.
- Assert first and second reopens agree with server validity: invalidation is reflected on the first reopen, not only the second; a valid replacement remains authenticated on both.
- Inspect backend account-linking semantics, then assert the rendered account link corresponds to that expected identity. Require removal of an old link only if the backend contract actually requires unlinking.

### Scenario B: in-app verification control

Repeat email change with the real callback/deep link delivered to the app. Observe replacement-session adoption, then both real relaunches and expiry checkpoints. Assert canonical new email, valid auth and rendered Hub consistency. This prevents fixing revocation by breaking the supported session-adoption path.

### Scenario C: delayed old response during replacement

Use existing backend response gates to hold an old profile/refresh response, complete a replacement sign-in through the supported flow, and release the old response. Assert server/native/UI identity stays with the replacement. Keep this deterministic race test separate from the unmodified external-verification flow.

## 4. Dependency gates and execution order

1. Add P0 base-SDK regressions in parallel, one implementation owner per SDK. Run compatibility cases against baseline and candidate; retain existing suites.
2. Qualify the Rownd E2E harness and scenarios A/B against the Rownd candidate with published SuperTokens Android 0.5.4. Capture a behavioral failure on pre-fix Rownd using the same working fixture; compilation/setup failure is not a reproduction.
3. Add P1 tests, prioritizing any gaps implicated by failures or changed boundaries. Treat P2 as follow-up unless evidence raises its priority.
4. Separately test Rownd against the candidate base artifact to establish drop-in compatibility. Artifact substitution does not establish generation-aware Rownd integration.
5. In a separate, minimal Rownd adoption change, wire snapshot/install/clear/listener APIs; then rerun A/B and scenario C against that integrated build. Do not claim Phase 2 customer-flow qualification before this step.
6. Use Sol to review the test plan and completed assertions; Terra implements. Update existing PRs with coverage and evidence, separating any newly discovered production fix. Do not merge/release as part of test work.

## Acceptance and evidence

Release checklist:

- [ ] Identify exact baseline/candidate artifacts and confirm which proposed assertions are genuinely missing.
- [ ] P0 base compatibility regressions pass, with final unchanged suites passing.
- [ ] Rownd Phase 1: real scenarios A/B pass against published 0.5.4, with pre-fix behavioral evidence and final Rownd suite passing.
- [ ] Base SDK drop-in compatibility is checked separately from API adoption.
- [ ] Generation-aware Rownd adoption, when included: integrated A/B/C pass against the candidate base SDK.
- [ ] Record targeted P1 coverage, final review and customer retest; defer unimplicated P2 cases.

- Tests assert public outcomes and real rendered UI at the relevant boundary, not only `rph_init` contents or internal state.
- Each new bug test has behavioral red evidence against the relevant old behavior, or a clearly labeled characterization result. Missing-API compile failures are not behavioral red evidence.
- Gates control ordering; bounded waits fail usefully and always unwind resources. No arbitrary sleeps to manufacture races.
- Run new deterministic race tests repeatedly (at least 10 runs); run each full E2E scenario at least three clean, isolated times. Run unchanged base suites and the full Rownd instrumented suite at the final tested commits.
- Record commit/artifact/Hub/backend versions, test counts/skips, UI screenshots at each checkpoint, redacted request/response ordering and server identity/session observations. Never retain auth headers, tokens or verification-link secrets in shared artifacts.
- If the real backend does not revoke the original session or the account-linking expectation differs, report it explicitly. Keep forced-revocation unit/integration coverage, but do not label that the reproduced customer E2E.
- Customer retest remains the final release confirmation. No added offline UX, startup-timeout redesign, unsupported cookie fencing or iOS Rownd E2E is implied by this plan.
