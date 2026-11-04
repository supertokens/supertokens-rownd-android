import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

test("emulator runner executes the integration workflow in one shell", () => {
  const workflow = readFileSync(".github/workflows/pr-check.yaml", "utf8");
  assert.match(workflow, /script: bash test-server\/run-ci-integration\.sh/);
});

for (const [preflight, integration] of [[0, 0], [1, 0], [0, 1]]) {
  test(`integration wrapper preserves preflight=${preflight} integration=${integration}`, () => {
    const work = mkdtempSync(path.join(os.tmpdir(), "rownd-ci-test-"));
    try {
      mkdirSync(path.join(work, "test-server"));
      const executable = (name: string, body: string) => writeFileSync(path.join(work, name), `#!/bin/bash\n${body}\n`, { mode: 0o755 });
      executable("test-server/provision-ci-webview.sh", "exit 0");
      executable("gradlew", `exit ${preflight}`);
      executable("npm", `exit ${integration}`);
      executable("adb", "exit 0");
      const result = spawnSync("/bin/bash", [path.resolve("test-server/run-ci-integration.sh")], {
        cwd: work, env: { ...process.env, PATH: `${work}:${process.env.PATH}` }, encoding: "utf8",
      });
      assert.equal(result.status, preflight || integration, result.stderr);
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });
}
