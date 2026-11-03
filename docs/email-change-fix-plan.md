# Fix plan: email change inconsistencies on Android (Pylon #3832)

## Reported symptoms

After the customer changed their email on the latest Android SDK, they saw four problems:

1. The link to the old account is still visible.
2. On reopen, the email field in the "Manage Account" Hub web view is empty.
3. App settings still show the old email.
4. They were logged out, but only after the **second** reopen.

## Approach

`fix/race-issues` and the base SDK's `feat/draft-email-verification` branch together come to about 53k lines. The base SDK work alone is a 0.6.0 rewrite: 27 production files, +4.7k lines. Most of it goes well beyond this ticket. We will not ship either branch as it is.

Instead:

- **Phase 1** lands a small Rownd-only fix from `main`, against the published SuperTokens Android 0.5.4. Symptoms 1–3 are Rownd state bugs. The common trigger for symptom 4 is also a Rownd bug: we trust a cached token on startup.
- **Phase 2** adds a minimal upstream fence. Without it, a late response or refresh for the old session can still write that session back after the new one is installed. Only the base SDK can stop those writes.
- **Phase 3** treats the rest of `fix/race-issues` as separate work, if we want it at all: the verification recovery journal, Apple OAuth navigation fencing, and the session coordinator.

Keep `fix/race-issues` as a reference. Commit its uncommitted work and push or tag it before branching. Port changes by hand, because each commit mixes several concerns.

Line numbers below refer to `main`. The root causes are inferred from reading the code; none of the symptoms has been reproduced yet. Phase 1, step 0 is to reproduce them.

## Root causes

### Symptom 3: old email in settings

Three bugs combine:

- **Stale state is persisted and restored.** `GlobalState` is persisted with `auth` and `user` included (`StateRepo.kt:41-50`, `:59-76`).
- **Startup overwrites newer in-memory state.** Startup restores with `SetGlobalState(persistedState)` (`StateRepo.kt:143-151`). That replaces the whole state, including anything written while `dataStore.data.first()` was suspended.
- **Stale snapshots are written back.** The persist collector (`StateRepo.kt:186-192`) queues snapshots that it captured earlier. An old snapshot can land on disk after a newer sign-in.

On top of that, **the profile is never refreshed on reopen.** The cold-start load (`StateRepo.kt:179-182`) needs both of these conditions:

- `AppLifecycleListener.isAppInForeground`. This is only set in `onActivityResumed`, and init finishes first, so it is false.
- `auth.isAccessTokenValid`. This is usually false after a reopen, because the access token has expired.

So the stale cached profile stays on screen.

### Symptom 1: old account link

This is mostly the same stale profile. The profile also has three race windows (`UserRepo.kt`):

- **PUT responses are not fenced.** A late PUT response always dispatches `SetUser` (`:93-115`).
- **Optimistic writes don't invalidate loads.** The optimistic `set()` (`:139`, `:151`) doesn't bump `loadGeneration`, so a GET already in flight overwrites it.
- **Hub updates are not fenced.** The Hub's `UserDataUpdate` (`RowndWebView.kt:1213-1222`) dispatches `SetUser` unconditionally. A Hub document from before the email change can write the old user into the new session.

### Symptom 2: empty email in Manage Account

`toRphInitHash` (`Auth.kt:63-82`) has two problems:

- **The tokens can come from different sessions.** It mixes the access token cached in the store with refresh, front and anti-CSRF tokens read from SDK prefs. After a session replacement or a background refresh, these can belong to different sessions.
- **The user id can be missing.** `app_user_id` comes from the cached profile, which is blank or stale because of symptoms 1 and 3.

### Symptom 4: logged out on the second reopen

This explanation is inferred, not reproduced:

1. The email change revokes the old session on the server.
2. **First reopen.** The persisted access token is still unexpired, so the startup refresh is skipped (`StateRepo.kt:171`).
   - The profile request gets a 401.
   - The interceptor's refresh fails and the SDK clears its own prefs.
   - Rownd's store is not touched, because non-404 errors are swallowed (`UserRepo.kt:74-83`). The collector then re-persists the stale auth.
3. **Second reopen.** The cached token has now expired. `resolveAuthState` (bridge `:150-202`) sees that no SDK session exists and clears auth.

Nothing listens for the SDK's `UNAUTHORISED` or `SIGN_OUT` events. The `EventHandler` is only registered in debug mode, and it only logs (bridge `:77-83`).

A second cause can only be closed upstream. Rownd writes the new session straight into the SDK's internal pref keys (`bootstrapSessionIfCurrent`, bridge `:291-332`, writes at `:316-327`). Meanwhile, an in-flight SDK refresh or ordinary response for the old session can call `saveTokenFromHeaders` and write the old tokens back. In 0.5.4 those writes are unconditional.

## Phase 1: Rownd-only fix (0.5.4, new branch from `main`)

### 0. Reproduce

1. Add an instrumented test that runs the customer's flow:
   1. Sign in.
   2. Change the email.
   3. Verify the new email.
   4. Reopen twice, with the access token expired in between.
2. Assert the user email, the Hub `rph_init` payload, and the auth state after each step.
3. Capture a failing baseline on `main`.
4. Where possible, ask the customer for logs or a screen recording of the reopen sequence.

### 1. Stop persisting `auth` and `user`

- Strip both fields when reading and when writing in `GlobalStateSerializer`. The branch does this at `StateRepo.kt:73,83`.
- On cold start, rebuild auth from the SDK with `doesSessionExist` / `getAccessToken`, not from disk.
- Trade-off: the profile is blank until hydration finishes. If that flash is unacceptable, keep `user` persisted, keyed by the session handle or `sub` of the SDK session, and discard it on mismatch.
- Upgrade: drop the old persisted auth and user when the app first reads its state after upgrading. Do **not** sign the user out. The SDK session in prefs is still valid.

### 2. Make the startup restore a guarded merge

- Replace `SetGlobalState(persisted)` with a `FinishInitialization(expected, persisted)` reducer.
- The reducer applies persisted `appConfig` and `signIn`. If `auth` or `user` is still persisted (the option in step 1), apply them only when state hasn't changed since entry.
- The branch's reducer is at `StateRepo.kt:162-177`. Port it without the `identityOf` / `recoverOnStartup` calls, which depend on the new upstream API.
- Wrap the persist collector in a `Mutex`, and read `store.currentState` inside it instead of using the value it captured (branch `StateRepo.kt:315-328`).

### 3. Always verify the session on startup, and hydrate the profile on first resume

- **Never trust a cached token on startup.** Before marking the app initialized, always run `resolveAuthState`, or `doesSessionExist`, which refreshes if needed. This fixes the first-reopen half of symptom 4.
- **Defer the profile load to the first resume.**
  - If the app is already in the foreground, load now.
  - Otherwise register a one-shot `RESUMED` listener through `appHandleWrapper.registerActivityListener` (the same pattern as `Rownd.kt:157-193`).
- **Gate on "authenticated".** Check `doesSessionExist`, not `isAccessTokenValid`. The interceptor refreshes expired tokens itself.
- Port `StartupProfileHydrationInstrumentedTest` from the branch, after replacing its `startupOwner` stamp with the captured session handle.

### 4. Fence profile requests

- **One counter for every profile write.** Use one `AtomicLong` request id, shared by GET, PUT, optimistic `set()` and Hub `UserDataUpdate`. Each one starts with `incrementAndGet()`.
- **Record the session at request start.** Store the session handle and `sub` from the access token, using the existing `sameSession` helper (bridge `:210`). Don't compare raw access tokens: a refresh within the same session would then drop valid responses.
- **Apply results only through a reducer action.** Add `CompleteProfileRequest(id, identity, user)`. It applies only if `id` is still the latest and the session is unchanged. The branch has this at `StateRepo.kt:100-115,149-156` and `UserRepo.kt:81-150`. Port the reducer actions, but not `SessionStamp` / `withSessionOwnership`.
- **Stale 404s must not sign the user out.** Apply the same check before the 404 → `signOut()` path (`UserRepo.kt:79-81`).
- **Reload after a stale completion.** When a completion is discarded, schedule a fresh load (the branch's `scheduleFreshLoad`).
- **Serialise PUTs per session.** Keep this optional. It is the branch's `pendingWrite` queue.

### 5. Fence Hub messages

- **Record the session when a Hub document loads.** Store its session handle, from the same snapshot used for `rph_init`.
- **Discard messages from an old session.** In `UserDataUpdate`, `AuthChallengeInitiated` and `AuthChallengeCleared` (`RowndWebView.kt:1213-1222`, `:1294-1319`), drop the message if the current session handle differs or there is no session.
- **Close the branch's gap.** `UserDataUpdate` must bump the shared profile request id from step 4, so a GET already in flight can't overwrite the Hub's newer data.

### 6. Build `rph_init` from one SDK read

- Read the access, refresh, front and anti-CSRF tokens together from the SDK prefs (bridge getters `:223-230`).
- Re-read the access token afterwards. If it changed, retry once, then return null.
- Never mix in the access token cached in the store.
- Don't send `rph_init` without a session. Delay it until the profile has hydrated, or leave `app_user_id` out and let the Hub fetch it.

### 7. React to SDK session loss

Always register the `EventHandler`, not only in debug mode.

- On `UNAUTHORISED` or `SIGN_OUT`, clear Rownd auth and user if the session they refer to is still current.
- On `REFRESH_SESSION`, sync the store's access token.

This makes the Rownd store follow the SDK as soon as the session is revoked.

### Tests to port

| Test | Source | Port as |
|---|---|---|
| `SessionStatePersistenceTest` | branch, unit | as is |
| `ProfileStateOwnershipTest` | branch, unit | after porting the reducer actions |
| `StartupProfileHydrationInstrumentedTest` | branch, instrumented | without the stamps |
| `ProfileOwnershipInstrumentedTest` (stale load, stale 404, late save, write order) | branch, instrumented | the stamp-free cases |

Extend the existing tests on `main`:

- `VerificationProfileInstrumentedTest`
- `RowndWebViewAuthenticationInstrumentedTest` (`user_data_update`)
- `SessionRefreshFailureInstrumentedTest`
- `SuperTokensBootstrapInstrumentedTest` (`rph_init`)
- `EmailChangeContinuationInstrumentedTest`

Add a regression test for each symptom, plus the step 0 end-to-end flow.

### 8. Hub: check the Manage Account email (investigation only)

- Phase 1 does not depend on any Hub change. The Hub message fences and `rph_init` fixes live in Android (`RowndWebView.kt`, `Auth.kt`).
- One Hub path still needs tracing for symptom 2. In `supertokens-rownd-hub`, `Profile.tsx` sets `needsEmailSignIn` from `!state.auth.access_token`, and its retry reads `getUser`.
  - Find out whether an expired or missing access token in `rph_init` makes Manage Account show an empty email.
  - Fold this into step 0.
  - Fix it in the Hub only if the reproduction shows the Android fixes are not enough. Base the fix on `origin/feat/draft-email-verification`, which already has the email-change continuation work that Android `main` uses.

#### Step 8 investigation findings

Code reading of Hub `origin/feat/draft-email-verification` (not a rendered-WebView test):

- `Profile.tsx` reads the email from `state.user.data`. Its `needsEmailSignIn` flag only controls the pending email-draft continuation UI, not the normal email field.
- Mobile Hub startup clears cached auth and user. `DefaultContext.tsx` handles `rph_init`, then `hooks/use-user-api.ts` fetches `/user` after authentication and app configuration are available.
- Missing access tokens prevent login. An expired token can leave the profile blank when `/user` fails; the native payload must contain a server-accepted token, not merely a coherent set of tokens.
- Missing `app_user_id` is supported for SuperTokens sessions: `handleRphHash` calls `recoverNativeSupertokensIdentity` to recover it from `/user` using the supplied access token. This also requires a usable token.
- These paths exist on the pushed Hub branch; no local-only native-attempt handshake changes are required. Phase 1 makes no Hub changes.

The Android repro fixture checks the handed-off token against the server, front-token coherence, and user identity. Those checks do not establish what the real Hub renders. A customer retest of Manage Account remains a release gate.

### Phase 1 does not change

- The required version of the SuperTokens SDK.
- The host-app contract. `SuperTokensInterceptor` stays, and there is no `RowndSessionInterceptor` requirement.

### Phase 1 release

1. Release as a Rownd patch.
2. Ask the customer to retest the exact flow, including two reopens with an expired access token.

## Phase 2: minimal upstream fence (supertokens-android, minor release)

Goal: a late SDK write for an old session can no longer replace or restore a newer one. Header mode is the default, and it can be fenced completely. Cookie mode can't, because the cookie jar saves `Set-Cookie` before our code sees the response. Document that.

Target size: about 400–600 production lines. Keep the existing storage keys, the existing refresh lock design, and Java 7 bytecode.

### Base SDK changes (`app/src/main/java/com/supertokens/session/`)

1. **Session store.**
   - Add a package-private `SessionStore`.
     - It holds one immutable snapshot `{generation, access, refresh, frontToken, antiCsrf, lastAccessTokenUpdate}` behind its own short-held lock.
     - Do **not** use `refreshAPILock`, which ordinary requests hold for the whole network call.
     - Write the whole snapshot through one SharedPreferences editor, using the existing keys.
     - The generation lives only in memory, starting from 0 on each process start.
   - Choose between `apply()` (the current behaviour, no main-thread disk I/O) and `commit()`, and document the choice.
   - Replace the static caches `FrontToken.frontTokenInMemory` and `AntiCSRF.antiCSRFTokenInfo` with reads from the snapshot.
   - `install` and `clear` must keep `FrontToken.tokenLock` waiters working by calling `notifyAll`.
2. **Public API on `SuperTokens`.**
   - `getSessionSnapshot(ctx)` returns the generation and the session state.
   - `installSession(ctx, expectedGeneration, tokens)` and `clearSession(ctx, expectedGeneration)` apply only if the generation still matches. Each bumps the generation, persists the change, and notifies listeners.
   - `addSessionListener` / `removeSessionListener`. Listeners are called after the lock is released, so they can call `install` or `clear`.
3. **Request start.** Read one snapshot at the start of every request and every retry. Use it for the authorization header, the anti-CSRF header and the pre-request state.
   - Interceptor: `SuperTokensInterceptor.java:113-140`.
   - Connection: `SuperTokensHttpURLConnection.java:149-168`.
   - Other header paths: `shouldRemoveAuthHeader`, and `SuperTokensCustomHttpURLConnection.setRequestProperty`.
4. **Ordinary responses.** At `Interceptor:143` and `Connection:179`, replace `saveTokenFromHeaders` with `SessionStore.applyResponse(generation, headers)`. If the generation has changed, skip the write, the event firing and `manuallySetCookiesFromResponse`.
5. **Refresh.**
   - After taking the write lock (`Interceptor:204-215`, `Connection:231-242`), compare generations. Today's reference comparison on the `lastAccessTokenUpdate` string is fragile.
   - Record the generation used for the refresh.
   - Apply the refresh response (`:247`, `:270`) and the clear-on-401 (`:252-254`, `:277-279`) with that generation.
   - If the session changed while the refresh ran, skip `REFRESH_SESSION` / `UNAUTHORISED` and return RETRY.
6. **Sign-out.**
   - Fix the generation at the `doesSessionExist` check, then pass it into `newRequest` through a package-private overload.
   - A late sign-out response for an old session must not clear the new one or fire `SIGN_OUT`.
7. **Legacy public writers.** Route `FrontToken.setToken` / `removeToken`, `Utils.setToken`, `saveTokenFromHeaders` and `storeInStorage` through the store, bumping the generation, and mark them `@Deprecated`. Existing callers keep working.
8. **Events.** Fire `EventHandler` events after the read lock is released, so a handler that calls `install` or `clear` can't deadlock.
9. **Tests.**
   - Extend `resetForTests` to reset the store.
   - Add `okhttp3:mockwebserver` and use latch-gated responses to cover:
     - a late refresh after install
     - a late ordinary response after clear
     - a late sign-out after install
     - the same cases over HttpURLConnection
   - Add a pure unit test for concurrent install and clear on the store.
   - Run the existing `testHelpers/testapp` suite unchanged as the regression gate.

### Out of scope for Phase 2

Everything below comes from `feat/draft-email-verification`. Handle it separately, if at all.

- the new storage schema, fingerprinting, and recovery states
- shared refresh flights with timeouts, cooldowns and limits
- request tags and the opt-in OkHttp refresh client
- the sign-out rewrite and redirect policy
- the 0.5.4 compatibility layers
- the CI and harness expansion

### Rownd follow-up (after the upstream release)

- Replace the direct pref writes in `bootstrapSessionIfCurrent` with `installSession(expectedGeneration, …)`. Replace sign-out's local clear with `clearSession`.
- Use the generation, rather than the session handle and `sub`, as the session identity in the Phase 1 fences.
- Subscribe with `addSessionListener` instead of polling `resolveAuthState`.
- Raise the minimum SuperTokens version, and add a regression test that installs a session while an old refresh is gated.

## Hub and iOS: race work to back out

The race work in `fix/race-issues` also produced Hub commits. None of them is in `develop`, on a release branch, or tagged. The latest Hub tag is v0.0.4, and `origin/release/prepare-next` contains none of them.

### Hub: 4 local, unpushed commits on `feat/draft-email-verification`

| Commit | Change | Effect on Android `main` | Action |
|---|---|---|---|
| `b560bdd` verification deadlines / support outcomes | Waits 20s instead of 11s when `__rowndNativeEmailVerificationCoordination === 1`, and shows a support message for `SUPPORT_REQUIRED`. | No effect: only `fix/race-issues` sets the flag. | Drop it with the Android branch, or keep the code and trim its race-specific docs. |
| `9440958` email-change tests vs published backend | Bumps the test-only dependency `@supertokens-plugins/rownd-nodejs` from 0.7.2 to 1.0.4, and updates the vitest config. | None. | **Keep**, as its own small PR. |
| `f545655` native auth attempt correlation | Adds an `auth_attempt_started` handshake (~35 files, +3.5k lines). It is gated on `__rowndNativeAuthAttemptHandshake === 1`, which only unreleased iOS `main` sets. It also refactors shared reducer, sign-out and success-handler code that every user runs. | No effect, but the shared refactors carry regression risk. | **Drop** from this fix. If iOS wants it, give it a separate branch with its own release gate. |
| `9adbdf9` docs | Adds handshake and checklist docs. Also adds `SESSION_FIX_CHANGES.md` and an SMS plan that do not belong in the Hub. | None. | **Drop.** Bring the handshake docs back only together with `f545655`. |

### Hub: already pushed to `origin/feat/draft-email-verification`

None of these needs a revert:

- `bb7989d` and `59e9a90` (email-change continuation). Android `main` v0.1.18 depends on them through `__rowndNativeEmailChangeContinuation = 2`. Keep.
- `4b70115` (immediate passwordless sign-in) and `9c36f1e` (assetlinks). These are features, not race fixes. Keep.

### Hub cleanup steps

1. Push the current local branch to an archive branch, for example `archive/hub-native-race-work`.
2. Reset local `feat/draft-email-verification` to `origin/feat/draft-email-verification`.
3. Cherry-pick `9440958`. Optionally cherry-pick `b560bdd` with trimmed docs.

### iOS

Out of scope for this fix. The iOS race work is set aside the same way as the Android work.

- The local, unpushed commits on iOS `main` (`39ecf17` "fence native authentication and session cleanup", `e3abdc6`, `c190152`) and the uncommitted testing docs are archived on the local branch `archive/ios-native-auth-race-work`. That branch has not been pushed.
- Local `main` is reset to `origin/main` (v0.2.9). It pins SuperTokensIOS 0.5.4 through `Package.resolved`.
- The Hub counterpart, `f545655`, is being dropped, so the archived iOS handshake must not be released without it.
- Untouched: the base `supertokens-ios` repo, which `Packages/supertokens-ios` symlinks to. It has uncommitted session changes on its `0.5.4` branch: `SessionSnapshot`, `LocalFirstSignOut`, refresh cooldown tests and others. Archive it the same way before any further iOS work.

## Phase 3: remaining `fix/race-issues` work (separate decision)

Review each of these against real incidents before porting:

- **Crash recovery for single-use verification requests.** Its journal currently grows without a limit.
- **Apple OAuth callback and navigation fencing.**
- **`SessionOperationCoordinator`'s leases.** Watch its 5 s `SessionBusyException` admission wait, its polling, and the throw on nested use.
- **The `X-Rownd-Session-Stamp` header side channel.**

## Checklist

- [ ] Commit and push or tag `fix/race-issues` and `feat/draft-email-verification` as archives.
- [ ] Hub cleanup: archive the local race commits, reset to origin, and reapply `9440958`.
- [x] Archive the iOS race work on `archive/ios-native-auth-race-work` and reset `main` to v0.2.9.
- [ ] Archive the uncommitted changes in base `supertokens-ios` (they sit on its `0.5.4` branch).
- [ ] Phase 1, step 0: reproduce on `main`, including tracing the Hub `Profile.tsx` email path.
- [ ] Phase 1, steps 1–7, with tests, on a new branch from `main`.
- [ ] Release the Rownd patch, and have the customer retest.
- [ ] Phase 2 upstream PR and minor release.
- [ ] Rownd follow-up adopting the upstream fence.
- [ ] Decide on Phase 3.
