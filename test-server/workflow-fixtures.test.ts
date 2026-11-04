import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parse } from "yaml";

const workflow = (name: string) => parse(readFileSync(`.github/workflows/${name}.yaml`, "utf8"));
const pr = workflow("pr-check").jobs.e2e;
const release = workflow("release").jobs.e2e;
const step = (job: typeof pr, name: string) => {
  const found = job.steps.find((candidate: { name: string }) => candidate.name === name);
  assert.ok(found, `Missing step: ${name}`);
  return found;
};

test("release E2E uses the PR-qualified Hub", () => {
  assert.deepEqual(release.strategy.matrix, pr.strategy.matrix);
  const prHub = step(pr, "Checkout Hub");
  assert.equal(prHub.with.ref, "develop");
  assert.deepEqual(step(release, "Checkout Hub").with, prHub.with);
  assert.equal(step(release, "Checkout Hub").with["persist-credentials"], false);
});

test("release E2E uses the PR-qualified emulator fixtures", () => {
  const prTests = step(pr, "Run E2E tests");
  assert.deepEqual(Object.keys(prTests.env).sort(), [
    "ANDROID_ACCESS_TOKEN_VALIDITY_SECONDS",
    "ANDROID_CORE_IMAGE",
    "ANDROID_HOST",
    "ANDROID_HUB_DIR",
    "ANDROID_POSTGRES_IMAGE",
    "ANDROID_REAL_EMAIL_CHANGES",
  ]);
  const releaseTests = step(release, "Run E2E tests");
  assert.deepEqual(releaseTests.env, prTests.env);
  assert.equal(releaseTests.uses, prTests.uses);
  assert.deepEqual(releaseTests.with, prTests.with);
});

test("both E2E workflows record and upload the tested Hub revision", () => {
  for (const job of [pr, release]) {
    const record = step(job, "Record Hub revision");
    assert.equal(record.if, step(job, "Checkout Hub").if);
    assert.equal(record.run, "git -C supertokens-rownd-hub rev-parse HEAD | tee hub-revision.txt");
    const upload = job.steps.find((candidate: { uses?: string }) => candidate.uses?.startsWith("actions/upload-artifact@"));
    assert.ok(upload);
    assert.equal(upload.if, "always()");
    assert.ok(upload.with.path.split("\n").includes("hub-revision.txt"));
  }
});
