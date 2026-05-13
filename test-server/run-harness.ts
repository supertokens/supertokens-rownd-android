import { spawn, type ChildProcess } from 'node:child_process';
import path from 'node:path';
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
let hubServer: ChildProcess | undefined;
let isShuttingDown = false;

// Hub repo is a sibling directory — same relative layout as the iOS test setup
const hubRepoDir = path.resolve(process.cwd(), '../supertokens-rownd-hub');

function run(command: string, args: string[], cwd: string) {
  const child = spawn(command, args, { cwd, stdio: 'inherit', shell: false });

  return new Promise<void>((resolve, reject) => {
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`${command} ${args.join(' ')} exited with ${code}`));
    });
    child.on('error', reject);
  });
}

async function waitForHealth(url: string, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Keep polling until the server is ready or timeout is reached
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw new Error(`Timed out waiting for ${url}`);
}

async function startHubServer() {
  await run('npm', ['run', 'build'], hubRepoDir);

  hubServer = spawn('npx', ['tsx', './test/e2e/harness/hub-server.ts'], {
    cwd: hubRepoDir,
    stdio: 'inherit',
    shell: false,
  });

  hubServer.on('exit', (code, signal) => {
    if (!isShuttingDown) {
      console.error(`Hub server exited unexpectedly: code=${code} signal=${signal}`);
      process.exit(1);
    }
  });

  await waitForHealth('http://127.0.0.1:8787/health');
  console.log('Hub server ready at http://127.0.0.1:8787 (emulator: http://10.0.2.2:8787)');
}

async function shutdown(exitCode = 0) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  try {
    if (harness) await harness.stop();
    if (hubServer) {
      hubServer.kill('SIGTERM');
      hubServer = undefined;
    }
  } catch (error) {
    console.error('Failed to stop Android integration harness', error);
    process.exit(1);
  }

  process.exit(exitCode);
}

void startHubServer()
  .then(() => startIntegrationHarness())
  .then((startedHarness) => {
    harness = startedHarness;
    console.log(`Android harness ready`);
    console.log(`  host:    ${startedHarness.serverUrl}`);
    console.log(`  android: ${startedHarness.androidUrl}`);
    console.log(`  hub:     ${startedHarness.hubUrl}`);
  })
  .catch((error) => {
    console.error('Failed to start Android integration harness', error);
    process.exit(1);
  });

process.on('SIGINT', () => void shutdown());
process.on('SIGTERM', () => void shutdown());
