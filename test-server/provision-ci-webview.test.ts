import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

test("a tampered provider is rejected before installation", () => {
  const work = mkdtempSync(path.join(os.tmpdir(), "rownd-provider-test-"));
  try {
    const tools = path.join(work, "sdk/build-tools/35.0.0");
    mkdirSync(tools, { recursive: true });
    const executable = (name: string, body: string) => writeFileSync(name, `#!/bin/bash\n${body}\n`, { mode: 0o755 });
    executable(path.join(tools, "apksigner"), "exit 0");
    executable(path.join(work, "curl"), 'while [ "$1" != "-o" ]; do shift; done; printf tampered > "$2"');
    executable(path.join(work, "adb"), 'printf "%s\\n" "$*" >> "$ADB_LOG"\ncase "$*" in *ro.build.version.sdk*) echo 26;; *ro.product.cpu.abilist*) echo x86;; esac');
    const log = path.join(work, "adb.log");
    const result = spawnSync("/bin/bash", [path.resolve("test-server/provision-ci-webview.sh")], {
      env: { ...process.env, PATH: `${work}:${process.env.PATH}`, ANDROID_HOME: path.join(work, "sdk"), ADB_LOG: log },
      encoding: "utf8",
    });
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(readFileSync(log, "utf8"), /install|set-webview-implementation/);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
});
