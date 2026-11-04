#!/usr/bin/env bash
set -u

bash test-server/provision-ci-webview.sh || exit 1
adb shell dumpsys webviewupdate
status=0
./gradlew :android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.rownd.android.WebViewCapabilityInstrumentedTest || status=$?
adb logcat -d -s RowndWebViewPreflight:I '*:S'
npm run test:integration || status=$?
exit "$status"
