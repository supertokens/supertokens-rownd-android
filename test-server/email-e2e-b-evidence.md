# Sanitized in-app verification evidence

This is a manually transcribed, redacted excerpt of the three successful
foreground command outputs in the implementation session. It is not a raw log;
raw instrumentation, credentials and verification links are not retained here.
Each run used the same command and APK hashes documented in
[`README-email-e2e.md`](README-email-e2e.md), against a freshly created backend
and account. Wall-clock durations were not recorded.

All three runs reported these exact phase outcomes:

```text
Resolved SuperTokens Android: 0.5.4
draftEmailVerificationReplacesSessionThroughReleasedSdk: PASS
verifiedEmailIsVisibleInHostAndRenderedHubAfterProcessRestart: PASS
Retained token expired and was rejected by Core: PASS
verifiedEmailIsVisibleInHostAndRenderedHubAfterProcessRestart: PASS
Two distinct process restarts with retained app data: PASS
```

| Run | Process sequence | First reopen rendered input | Second reopen rendered input |
|---|---|---|---|
| 1 | 12417 → 12670 → 12871 | canonical | canonical |
| 2 | 13124 → 13390 → 13593 | canonical | canonical |
| 3 | 13837 → 14111 → 14315 | canonical | canonical |

For each rendered checkpoint the output reported one visible `INPUT` of type
`email`, Hub origin `http://127.0.0.1:8848`, authenticated native state,
matching native/server email, matching server owner, and HTTP 200 for server
session and profile observations. The test also asserted public tenant, no
pending change, and one verified passwordless method matching the new email.
Some bounded histories showed the field `empty` before `canonical`.

These observations do not establish an external verification flow, pre-fix
behavioral red, linked-account rendering, customer settings UI, screenshots,
or the final full instrumented-suite result.
