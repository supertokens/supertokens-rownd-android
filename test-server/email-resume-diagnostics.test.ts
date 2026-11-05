import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

const script = readFileSync("app/src/androidTest/assets/email-resume-diagnostics.js", "utf8");
const secret = "private-fixture@example.invalid?token=private-credential";

function capture(pending: boolean, hidden = false, y = 30) {
  const element = {
    textContent: pending ? "Verify your new email" : secret,
    tagName: "H2",
    getBoundingClientRect: () => ({ x: 12, y, width: 320, height: 40 }),
    getClientRects: () => hidden ? [] : [{}],
    get value(): never { throw new Error("Input values must not be read"); },
    get outerHTML(): never { throw new Error("Raw DOM must not be read"); },
  };
  const document = {
    readyState: "complete",
    visibilityState: "visible",
    hasFocus: () => true,
    activeElement: element,
    querySelectorAll: (selector: string) => selector === "h1,h2,h3,p" ? [element] : [],
    get title(): never { throw new Error("Page titles must not be read"); },
    get URL(): never { throw new Error("URLs must not be read"); },
  };
  return JSON.parse(JSON.stringify(runInNewContext(script, {
    document,
    window: { innerWidth: 393, innerHeight: 600, scrollY: 0, devicePixelRatio: 2.75 },
    getComputedStyle: () => ({ display: "block", visibility: hidden ? "hidden" : "visible", opacity: "1" }),
    location: { href: secret },
  })));
}

test("resume diagnostics distinguish missing, rendered, and hidden fixed headings", () => {
  assert.equal(capture(false).text.pending.present, false);
  const rendered = capture(true).text.pending;
  assert.equal(rendered.present, true);
  assert.equal(rendered.rendered, true);
  assert.equal(rendered.intersectsViewport, true);
  assert.deepEqual(rendered.bounds, [12, 30, 320, 40]);
  const hidden = capture(true, true).text.pending;
  assert.equal(hidden.present, true);
  assert.equal(hidden.rendered, false);
  const offscreen = capture(true, false, 700).text.pending;
  assert.equal(offscreen.rendered, true);
  assert.equal(offscreen.intersectsViewport, false);
});

test("resume diagnostics never serialize page text, URLs, DOM, or input values", () => {
  const result = capture(false);
  const serialized = JSON.stringify(result);
  assert.ok(!serialized.includes(secret));
  assert.ok(!serialized.includes("private-fixture"));
  assert.ok(!serialized.includes("private-credential"));
  assert.deepEqual(Object.keys(result).sort(), ["activeTag", "focused", "pixelRatio", "ready", "selectors", "text", "viewport", "visible", "visualViewport"]);
  assert.ok(Object.values(result.text).every((value) => (value as { present: boolean }).present === false));
});
