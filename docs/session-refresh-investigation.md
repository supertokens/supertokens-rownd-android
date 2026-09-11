# Android session-refresh investigation

## Conclusion

Android shared the iOS error-to-missing-token problem, but not the same destructive startup reconciliation path. The fix now distinguishes temporary refresh failures from missing sessions and reconciles native session results into Rownd's cached auth state.

Before the fix, three emulator characterization tests confirmed:

1. Normal expired-token refresh succeeds and retains the same native session. However, Rownd's cached access token stays expired.
2. A refresh HTTP 503 becomes `null` from `getAccessToken()` and `NoAccessTokenPresentException` with `throwIfMissing=true`. `_refreshToken()` also returns `null`. Native refresh credentials and Rownd auth survive; restoring refresh availability permits recovery and a successful protected request without signing in again.
3. A real backend session revocation clears native refresh credentials when refresh is attempted, but Rownd compatibility state remains `isAuthenticated=true` with its old token.

The tests now assert the corrected behavior and cover concurrency and cancellation.

## Relevant paths

- `android/src/main/java/io/rownd/android/util/SuperTokensSessionBridge.kt`: `resolveAuthState()` checks retained refresh credentials when the native getter returns `null`, raising `ServerException` if absence has not been established. Explicit refresh also propagates temporary failures as `ServerException`. Session reads and compatibility-state commits share the existing mutation lock with native authentication; sign-out generation and token checks reject stale completions.
- `android/src/main/java/io/rownd/android/models/repos/AuthRepo.kt`: normal and forced token reads use this shared resolution path.
- `android/src/main/java/io/rownd/android/Rownd.kt`: public token getters propagate the retryable error, including when `throwIfMissing=false`. `_refreshToken()` uses the same reconciliation path.
- `android/src/main/java/io/rownd/android/models/repos/StateRepo.kt`: `ReconcileNativeSession` atomically updates auth and profile, conditional on the session still being current. Same-session refresh preserves profile and auth metadata, while absent/revoked native sessions clear both. Pending legacy migration credentials remain intact. Startup's existing token read now updates the store, enabling its subsequent validity-gated profile fetch after successful refresh.

The pinned `com.github.supertokens:supertokens-android:0.5.4` artifact's bytecode was checked against the native SDK source. `doesSessionExist()` compares the refresh response with `RETRY`; an `API_ERROR` therefore becomes `false`. `getAccessToken()` then returns `null`. Calling native `SuperTokens.attemptRefreshingSession()` directly instead preserves the refresh failure as an `IOException`, verified during the baseline investigation.

## Implications for daily-login reports

A one-hour access token normally expires before the next day's launch. Before this fix, during a temporary refresh outage, application code treating `getAccessToken() == null` as a reason to request login could produce the same customer-visible symptom on Android, despite recoverable credentials. This differs from the iOS SDK actually persisting a signed-out state during startup.

The stale compatibility state could also mislead apps checking `isAccessTokenValid` after refresh or `isAuthenticated` after revocation. Confirm the affected app's SDK version, login gate, and refresh response before attributing its reports to either path.

Core's supplied validity settings are not a daily limit: `access_token_validity=3600` is one hour (seconds), whereas `refresh_token_validity=144000` is 100 days (minutes).

## Caller behavior

- On `ServerException`, retain the login UI state and retry when the service or network is available. The SDK does not add an automatic retry loop.
- `null` retains its missing-session meaning; `throwIfMissing=true` still throws `NoAccessTokenPresentException` in that case.
- Read a fresh token through the public getter before an authenticated request. Reconciliation occurs on token retrieval; it is not a background session-revocation subscription.

## Reproduction

The tests use the production Dagger graph and pinned native SDK, real SharedPreferences, HTTP requests, Node session middleware, Core, and Postgres. The existing harness issues a real session and uses Core to sign a three-second access JWT containing its session claims. Expiry scenarios check that the backend accepts it before expiry and rejects it after expiry. The 503 is injected at the harness refresh route; successful refresh and revocation run through the real session backend. An internal token-read hook makes commit-race and cancellation tests deterministic.

```sh
ANDROID_HARNESS_PORT=3142 npx tsx test-server/with-harness.ts -- sh -c \
  './gradlew :android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.rownd.android.SessionRefreshFailureInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.harnessUrl=$ANDROID_HARNESS_URL'
```

Result: **10 regression tests passed** on `Pixel_8_API_34` (Android 14), with SuperTokens Android 0.5.4, `supertokens-node` 24.0.2, Rownd Node plugin 0.3.0-beta.2, and the harness's `supertokens/supertokens-postgresql:latest` image. Coverage includes normal and forced refresh, outage/recovery, revocation, concurrent reads, sign-out during an in-flight refresh and before state commit, session replacement, cancellation, and pending legacy migration. The checkout included existing migration/sign-out changes. These are native refresh/compatibility-state tests, not process-kill/relaunch or 24-hour soak tests; the startup behavior above also relies on tracing `StateRepo.setup()`.

Test: `android/src/androidTest/java/io/rownd/android/SessionRefreshFailureInstrumentedTest.kt`.

Full-suite validation: **121 SDK instrumentation tests**, **6 example instrumentation/E2E tests** (including real Hub flows and Chrome handoff), **47 SDK unit tests**, and **1 example unit test** passed. The instrumentation suites ran in full; Gradle reused up-to-date passing unit-test results. Alternate-origin migration suites reset native SDK initialization between suites, and the example package-name assertion uses its flavor-specific application ID. Harness TypeScript checking and `git diff --check` also passed.
