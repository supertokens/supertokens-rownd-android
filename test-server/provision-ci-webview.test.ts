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
    assert.match(result.stderr, /SHA-256 verification failed/);
    assert.doesNotMatch(readFileSync(log, "utf8"), /install|set-webview-implementation/);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
});

for (const { signerPrefix, digest, accepted } of [
  { signerPrefix: "Signer #1", digest: "6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5", accepted: true },
  { signerPrefix: "V3.0 Signer:", digest: "6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5", accepted: true },
  { signerPrefix: "V3.0 Signer:", digest: "wrong", accepted: false },
  { signerPrefix: "V3.0 Signer:", digest: "6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5\nV3.0 Signer: certificate SHA-256 digest: wrong", accepted: false },
]) {
  test(`${accepted ? "accepts" : "rejects"} ${signerPrefix} certificate ${accepted ? "matching" : "not matching"} the trusted signer`, () => {
    const work = mkdtempSync(path.join(os.tmpdir(), "rownd-provider-test-"));
    try {
      const tools = path.join(work, "sdk/build-tools/37.0.0");
      mkdirSync(tools, { recursive: true });
      const executable = (name: string, body: string) => writeFileSync(name, `#!/bin/bash\n${body}\n`, { mode: 0o755 });
      executable(path.join(tools, "apksigner"), `echo '${signerPrefix} certificate SHA-256 digest: ${digest}'`);
      executable(path.join(tools, "aapt"), 'echo "package: name=\x27com.google.android.webview\x27"');
      executable(path.join(work, "curl"), 'while [ "$1" != "-o" ]; do shift; done; printf fixture > "$2"');
      executable(path.join(work, "shasum"), "exit 0");
      executable(path.join(work, "adb"), 'printf "%s\\n" "$*" >> "$ADB_LOG"\ncase "$*" in *ro.build.version.sdk*) echo 29;; *ro.product.cpu.abilist*) echo x86;; esac');
      const log = path.join(work, "adb.log");
      const result = spawnSync("/bin/bash", [path.resolve("test-server/provision-ci-webview.sh")], {
        env: { ...process.env, PATH: `${work}:${process.env.PATH}`, ANDROID_HOME: path.join(work, "sdk"), ADB_LOG: log },
        encoding: "utf8",
      });
      if (accepted) {
        assert.equal(result.status, 0, result.stderr);
        assert.match(readFileSync(log, "utf8"), /install -r/);
      } else {
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /signer verification failed/);
        assert.doesNotMatch(readFileSync(log, "utf8"), /install -r/);
      }
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });
}
