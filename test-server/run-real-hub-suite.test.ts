import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

test("real Hub runner removes each reverse once and exits successfully on Bash 3", () => {
  const work = mkdtempSync(path.join(os.tmpdir(), "rownd-suite-test-"));
  try {
    const log = path.join(work, "adb.log");
    const executable = (name: string, body: string) => writeFileSync(path.join(work, name), `#!/bin/bash\n${body}\n`, { mode: 0o755 });
    executable("adb", 'printf "%s\\n" "$*" >> "$ADB_LOG"');
    executable("node", "exit 0");
    executable("python3", "exit 0");
    executable("gradlew", "exit 0");
    const script = process.env.REAL_HUB_SUITE_SCRIPT || path.resolve("test-server/run-real-hub-suite.sh");
    const result = spawnSync("/bin/bash", [script], {
      cwd: work,
      env: { ...process.env, PATH: `${work}:${process.env.PATH}`, ADB_LOG: log, ANDROID_HOST: "127.0.0.1", ANDROID_SERIAL: "emulator-test", ANDROID_HARNESS_PORT: "3158", ANDROID_HUB_PORT: "8858", ANDROID_HARNESS_URL: "http://127.0.0.1:3158" },
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
    const calls = readFileSync(log, "utf8").trim().split("\n");
    assert.equal(calls.filter((line) => line.includes("reverse --remove tcp:3158")).length, 1);
    assert.equal(calls.filter((line) => line.includes("reverse --remove tcp:8858")).length, 1);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
});
