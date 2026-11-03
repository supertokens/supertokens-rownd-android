import { spawn } from "node:child_process";
import { startLocalHub, type LocalHub } from "./local-hub";
import { startIntegrationHarness, type AndroidIntegrationHarness } from "./server";

process.on("unhandledRejection", (reason) => {
  if (reason instanceof Error && reason.message.startsWith("Failed to fetch app config")) {
    return;
  }
  console.error("Unhandled rejection:", reason);
  process.exit(1);
});

function parseCommand() {
  const separatorIndex = process.argv.indexOf("--");
  const command = separatorIndex === -1 ? process.argv.slice(2) : process.argv.slice(separatorIndex + 1);

  if (command.length === 0) {
    throw new Error("Usage: tsx test-server/with-harness.ts -- <command> [args...]");
  }

  return command;
}

async function main() {
  const [command, ...args] = parseCommand();
  let localHub: LocalHub | undefined;
  let harness: AndroidIntegrationHarness | undefined;

  try {
    if (process.env.ANDROID_E2E_LOCAL_HUB === "1") {
      localHub = await startLocalHub();
      process.env.ANDROID_HUB_URL = localHub.androidUrl;
    }

    harness = await startIntegrationHarness();

    console.log("Android harness ready");
    console.log(`  host:    ${harness.serverUrl}`);
    console.log(`  android: ${harness.androidUrl}`);
    console.log(`  public:  ${harness.publicUrl}`);
    console.log(`  hub:     ${harness.hubUrl}`);

    const child = spawn(command, args, {
      stdio: "inherit",
      env: {
        ...process.env,
        HARNESS_URL: process.env.HARNESS_URL || harness.serverUrl,
        ANDROID_HARNESS_URL: process.env.ANDROID_HARNESS_URL || harness.androidUrl,
        ANDROID_API_URL: process.env.ANDROID_API_URL || harness.androidUrl,
        ANDROID_HUB_URL: process.env.ANDROID_HUB_URL || harness.hubUrl,
        ANDROID_APP_KEY: process.env.ANDROID_APP_KEY || harness.appKey,
      },
    });
    let interrupted = false;
    const terminateChild = () => {
      interrupted = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 5_000).unref();
    };
    process.once("SIGINT", terminateChild);
    process.once("SIGTERM", terminateChild);

    const exitCode = await new Promise<number>((resolve, reject) => {
      const deadline = setTimeout(() => {
        console.error("Harness command exceeded 15-minute deadline");
        terminateChild();
      }, 900_000);
      child.on("error", (error) => { clearTimeout(deadline); reject(error); });
      child.on("close", (code, signal) => {
        clearTimeout(deadline);
        if (signal || interrupted) {
          console.error(`Command terminated by signal ${signal}`);
          resolve(1);
          return;
        }

        resolve(code ?? 1);
      });
    }).finally(() => {
      process.removeListener("SIGINT", terminateChild);
      process.removeListener("SIGTERM", terminateChild);
    });

    process.exitCode = exitCode;
  } finally {
    try {
      await harness?.stop();
    } finally {
      await localHub?.stop();
    }
  }
}

main().then(() => process.exit(process.exitCode || 0)).catch((error) => {
  console.error("Failed to run command with Android harness", error);
  process.exit(1);
});
