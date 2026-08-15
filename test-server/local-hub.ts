import { spawn, type ChildProcess } from "node:child_process";
import { access } from "node:fs/promises";
import path from "node:path";

export type LocalHub = {
  androidUrl: string;
  stop: () => Promise<void>;
};

function run(command: string, args: string[], cwd: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: "inherit" });
    child.once("error", reject);
    child.once("close", (code, signal) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`${command} exited with ${signal ? `signal ${signal}` : `code ${code}`}`));
    });
  });
}

async function waitUntilReady(url: string, child: ChildProcess, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;

  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`Local Hub exited with code ${child.exitCode} before becoming ready`);
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1_000) });
      if (response.ok) return;
      lastError = new Error(`Health check returned ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  throw new Error(`Local Hub did not become ready at ${url}`, { cause: lastError });
}

export async function startLocalHub(): Promise<LocalHub> {
  const hubDirectory = path.resolve(
    process.cwd(),
    process.env.ANDROID_HUB_DIR || "../supertokens-rownd-hub",
  );
  await access(path.join(hubDirectory, "package.json"));

  const port = Number(process.env.ANDROID_HUB_PORT || 8787);
  await run("npm", ["run", "build"], hubDirectory);

  const child = spawn(
    "npm",
    ["exec", "--", "tsx", "./test/e2e/harness/hub-server.ts"],
    {
      cwd: hubDirectory,
      env: { ...process.env, E2E_HUB_PORT: String(port) },
      stdio: "inherit",
    },
  );
  try {
    await waitUntilReady(`http://127.0.0.1:${port}/health`, child);
  } catch (error) {
    child.kill("SIGTERM");
    throw error;
  }

  return {
    androidUrl: `http://${process.env.ANDROID_HOST || "10.0.2.2"}:${port}`,
    stop: async () => {
      if (child.exitCode !== null) return;
      child.kill("SIGTERM");
      await new Promise<void>((resolve) => child.once("close", () => resolve()));
    },
  };
}
