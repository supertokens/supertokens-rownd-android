import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

function run(value: string | undefined, command: string) {
  const env = { ...process.env, ANDROID_HARNESS_COMMAND_TIMEOUT_MS: value };
  return spawnSync(process.execPath, ["--import", "tsx", "-e", "require('./test-server/command-deadline.ts').runCommand(process.execPath, ['-e', process.argv[1]], process.env).then(code => process.exit(code)).catch(error => { console.error(error.message); process.exit(1); })", command], { env, encoding: "utf8", timeout: 5000 });
}

test("deadline terminates a long-running command and returns failure", () => {
  const result = run("100", "setTimeout(() => {}, 10000)");
  assert.equal(result.status, 1);
  assert.match(result.stderr, /exceeded.*100 ms/);
});

test("command exit status is preserved", () => {
  assert.equal(run("2000", "process.exit(7)").status, 7);
});

test("invalid timeout is rejected before starting the command", () => {
  const result = run("0", "console.log('should not run')");
  assert.equal(result.status, 1);
  assert.match(result.stderr, /positive integer/);
  assert.doesNotMatch(result.stdout, /should not run/);
});
