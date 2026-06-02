import { startIntegrationHarness } from './server';

// The Rownd plugin creates a default client during init that fires a background
// app-config fetch with test credentials. The harness mock client overrides it
// immediately after, so the failed fetch has no impact on test behavior.
process.on('unhandledRejection', (reason) => {
  if (reason instanceof Error && reason.message.startsWith('Failed to fetch app config')) {
    return;
  }
  console.error('Unhandled rejection:', reason);
  process.exit(1);
});

let harness: Awaited<ReturnType<typeof startIntegrationHarness>> | undefined;
let isShuttingDown = false;

async function shutdown(exitCode = 0) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  try {
    if (harness) await harness.stop();
  } catch (error) {
    console.error('Failed to stop Android integration harness', error);
    process.exit(1);
  }

  process.exit(exitCode);
}

void startIntegrationHarness()
  .then((startedHarness) => {
    harness = startedHarness;
    console.log(`Android harness ready`);
    console.log(`  host:    ${startedHarness.serverUrl}`);
    console.log(`  android: ${startedHarness.androidUrl}`);
    console.log(`  public:  ${startedHarness.publicUrl}`);
    console.log(`  hub:     ${startedHarness.hubUrl}`);
    console.log(
      `  gradle:  ANDROID_API_URL=${startedHarness.androidUrl} ANDROID_HUB_URL=${startedHarness.hubUrl} ANDROID_APP_KEY=${startedHarness.appKey} ./gradlew :app:installLocalDebug`,
    );
  })
  .catch((error) => {
    console.error('Failed to start Android integration harness', error);
    process.exit(1);
  });

process.on('SIGINT', () => void shutdown());
process.on('SIGTERM', () => void shutdown());
