import { spawn } from "node:child_process";

export function commandTimeout(value = process.env.ANDROID_HARNESS_COMMAND_TIMEOUT_MS): number {
  if (value === undefined) return 2_400_000;
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 2_147_483_647) {
    throw new Error("ANDROID_HARNESS_COMMAND_TIMEOUT_MS must be a positive integer no greater than 2147483647");
  }
  return Number(value);
}

export async function runCommand(command: string, args: string[], env: NodeJS.ProcessEnv): Promise<number> {
  const timeout = commandTimeout(env.ANDROID_HARNESS_COMMAND_TIMEOUT_MS);
  const child = spawn(command, args, { stdio: "inherit", env });
  let interrupted = false;
  const terminateChild = () => {
    interrupted = true;
    child.kill("SIGTERM");
    setTimeout(() => child.kill("SIGKILL"), 5_000).unref();
  };
  process.once("SIGINT", terminateChild);
  process.once("SIGTERM", terminateChild);
  try {
    return await new Promise<number>((resolve, reject) => {
      const deadline = setTimeout(() => {
        console.error(`Harness command exceeded deadline of ${timeout} ms`);
        terminateChild();
      }, timeout);
      child.once("error", (error) => { clearTimeout(deadline); reject(error); });
      child.once("close", (code, signal) => {
        clearTimeout(deadline);
        if (signal || interrupted) {
          console.error(`Command terminated by signal ${signal}`);
          resolve(1);
        } else resolve(code ?? 1);
      });
    });
  } finally {
    process.removeListener("SIGINT", terminateChild);
    process.removeListener("SIGTERM", terminateChild);
  }
}
