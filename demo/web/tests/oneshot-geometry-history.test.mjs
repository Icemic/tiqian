// oneshot-geometry-history.test.mjs — ledger anchor for the b649841..HEAD
// box history (spec-oneshot-bisect). Captures the carried fixed demo kit's
// deep geometry on the current tree and asserts zero divergence against the
// frozen final baseline in fixtures/oneshot-geometry-history.json.
//
// Capture semantics reuse the carried kit harness verbatim (kit page, era
// importmap, coordinated enhance, terminal settle, scrollTop=0 capture);
// box comparison uses helpers/deep-geometry.mjs unchanged: every box's
// x/y/width/height compared at 0.01px rounding with zero tolerance, no
// sampling, no dropped boxes.
//
// Complete b649841..HEAD box-change ledger (chain mode, fixed kit corpus):
//
//   Shape S0 187lm/1056el/346tx (baseline):
//     b6498412 2026-08-16 stable  .agent-specs/oneshot-bisect-evidence/b6498412/chain-p0.json
//     876612ef (E1)         stable  .../876612ef/chain-p0.json
//     0835074e              stable  .../0835074e/chain-p0.json
//     3f2996d0              stable  .../3f2996d0/chain-p0.json
//
//   Change 1  S0 -> S1 (172lm/1106el/303tx, 1159/1642 boxes):
//     5c76cf68 2026-08-23 refactor(web): render every paragraph via the
//       prepared bridge and drop the native renderer. p1:1, p2:1, p6:0 lose
//       all 15 line marks. Classification: 重构引发的缺陷 (interval holds
//       only refactor + docs commits). Evidence: .../5c76cf68/chain-p0.json;
//       boundary pair 3f2996d0 -> 5c76cf68.
//     bf506b34 2026-08-23 stable at S1  .../bf506b34/chain-p0.json
//     de926c85              stable at S1  .../de926c85/chain-p0.json
//     88557fca              stable at S1  .../88557fca/chain-p0.json
//
//   Change 2  S1 -> S2 (187lm/1318el/369tx, 539/1634 boxes):
//     6ff37b45 2026-08-23 fix(prepared-dom): keep sub-epsilon justified
//       stretch in the line flow identity. Restores p1:1/p2:1/p6:0 (repairs
//       change 1's defect) and changes justified-line run structure through
//       the spacing epsilon. Interval mixes frontend/web JS with
//       frontend/web-precompute Rust (not an allowed engine path) plus
//       fixture additions -> 归属不明. Evidence: .../6ff37b45/chain-p0.json;
//       boundary pair de926c85 -> 6ff37b45.
//     4370925f, 733d779a, 73449b70 (E2a), 2aafd7f1, 51efc35a, d0c5f50f
//       all stable at S2. Blocked window inside this span: 6f5e0316..11067981
//       has no browser bundle recipe (frontend/web/build.gradle.kts deleted
//       at 6f5e0316, ts-runtime recovery at 73449b70); 9f799c97 recorded
//       blocked, not silently skipped.
//
//   Change 3  S2 -> S3 (159lm/1210el/346tx, 334/1927 boxes):
//     5c9d0a30 2026-08-25 refactor(web): replace the engine global slots
//       with loader state and options. p7:1 and p8:4 lose all line marks.
//       Classification: 重构引发的缺陷 (frontend/web-only interval).
//       Evidence: .../5c9d0a30/chain-p0.json; boundary pair d0c5f50f -> 5c9d0a30.
//     e99c4943, 4818b3f3 (E2b), cd08a2c7 stable at S3.
//
//   Change 4  S3 -> S2 (175/1768 boxes), the repair of change 3:
//     23e36988 2026-08-25 fix(web): stop running the snapshot validator on
//       live commits. Restores p7:1/p8:4. Evidence: .../23e36988/chain-p0.json;
//       boundary pair cd08a2c7 -> 23e36988.
//     05752c8c, b5397a85, 336d1ad7 recorded as transiently unbuildable
//       intermediates (ffi/core signature waves), skipped per spec.
//     efc62a80, af4f310f, ca8eb84a, 4e2b3747, b4f90ec3, 0c135ee3 (E2c),
//       bed4c791 (E3), 9561c747 (E4), acdce952 (E5), 1ad320ce (HEAD) all
//       stable at S2 — S2 persists to HEAD as the final baseline.
//
//   Persistent changes at HEAD: change 2's residual (S2 vs S0 run/text
//   structure: 1056->1318 run els, 346->369 text nodes, justified-line
//   letter-spacing run splits), classification 归属不明. Mid-way repaired:
//   change 1 (introduced 5c76cf68, repaired by change 2), change 3
//   (introduced 5c9d0a30, repaired by change 4 at 23e36988).
//
//   Separate from the chain ledger: the one-shot re-enhance scroll defect
//   (phase 1) first appears at bed4c791 2026-08-26; boundary runs 20/20
//   divergent at bed4c791 vs 20/20 clean at 7e2d1909.
//
// Fixture: fixtures/oneshot-geometry-history.json = the HEAD (1ad320ce)
// chain capture geometry, evidence .agent-specs/oneshot-bisect-evidence/
// 1ad320ce/chain-p0.json.
//
// Post-study extension (2026-08-27, central verification): a fresh chain
// capture at 72e95777 equals this frozen baseline (pair-30, 0/1927
// divergent), so S2 persists through 672f14bc and 72e95777 and the
// baseline remains the current-tree anchor on main. Evidence archives:
// demo/web-history/evidence/.

import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { diffDeepGeometry, deepGeometryCounts } from "./helpers/deep-geometry.mjs";
import {
  CdpClient,
  startKitServer,
  waitForCdpEndpoint,
  chainCapture,
  DEMO_PORT,
  CDP_PORT,
  VIEWPORT_WIDTH,
  VIEWPORT_HEIGHT,
} from "../../web-history/oneshot-history-harness.diag.mjs";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));

test("OneShotGeometryHistory: current tree capture equals the frozen b649841..HEAD final baseline", async () => {
  const demoUrl = `http://127.0.0.1:${DEMO_PORT}/`;
  const portBusy = await fetch(demoUrl).then(() => true, () => false);
  assert.ok(!portBusy, `Port ${DEMO_PORT} must be free before the test starts`);

  const era = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web-history/eras/e8-context.json"), "utf8"));
  const baseline = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web/tests/fixtures/oneshot-geometry-history.json"), "utf8"));

  let browserProc = null;
  let client = null;
  let server = null;
  try {
    server = await startKitServer(era);

    const chromeBin = process.env.CHROME_BIN || "chromium";
    browserProc = spawn(chromeBin, [
      "--headless=new",
      `--remote-debugging-port=${CDP_PORT}`,
      "--no-sandbox",
      "--disable-gpu",
      "--disable-dev-shm-usage",
      "--force-device-scale-factor=1",
      "--hide-scrollbars",
      "about:blank",
    ], { stdio: "ignore", detached: true });

    await waitForCdpEndpoint(CDP_PORT);
    const targets = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json();
    const pageTarget = targets.find((tr) => tr.type === "page" && tr.url === "about:blank");
    assert.ok(pageTarget, "Must find the blank page target");

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    await client.send("Log.enable");
    await client.send("Network.enable");
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT, deviceScaleFactor: 1, mobile: false,
    });

    const pageLog = [];
    client.ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.method === "Runtime.consoleAPICalled") {
        const text = (msg.params.args ?? []).map((a) => a.value ?? a.description ?? a.type).join(" ");
        pageLog.push(`console.${msg.params.type}: ${text}`.slice(0, 400));
      } else if (msg.method === "Runtime.exceptionThrown") {
        const d = msg.params.exceptionDetails;
        pageLog.push(`exception: ${d?.exception?.description ?? d?.text ?? "?"}`.slice(0, 400));
      } else if (msg.method === "Log.entryAdded") {
        pageLog.push(`log.${msg.params.entry.level}: ${msg.params.entry.text}`.slice(0, 400));
      } else if (msg.method === "Network.responseReceived" && msg.params.response?.status >= 400) {
        pageLog.push(`http ${msg.params.response.status}: ${msg.params.response.url}`.slice(0, 400));
      } else if (msg.method === "Network.loadingFailed") {
        pageLog.push(`net-failed: ${msg.params.errorText} (${msg.params.requestId})`.slice(0, 400));
      }
    });

    const record = await chainCapture(client, era, "current-tree", pageLog);
    assert.ok(record.valid, `kit capture must be valid; reason=${record.reason} log=${JSON.stringify(record.pageLog ?? pageLog.slice(0, 20))}`);
    assert.ok(record.selfEqual, "capture must be self-consistent across the 400ms re-capture");

    // Vacuity gate: zero counts mean an unenhanced page, not a zero-divergence pass.
    const counts = deepGeometryCounts(record.geometry);
    assert.ok(counts.lineMarks > 0 && counts.runEls > 0 && counts.textNodes > 0,
      `page must be enhanced; counts=${JSON.stringify(counts)}`);
    assert.deepEqual(counts, baseline.counts, "geometry counts must equal the baseline counts");
    assert.equal(record.pageHeight, baseline.pageHeight, "page height must equal the baseline");

    const diff = diffDeepGeometry(record.geometry, baseline.geometry);
    assert.ok(diff.equal,
      `current tree diverges from the frozen baseline: ${diff.divergentBoxes}/${diff.boxesCompared} boxes; examples: ${diff.examples.join(" | ")}`);
    assert.equal(diff.divergentBoxes, 0);
  } finally {
    client?.close();
    server?.close();
    if (browserProc?.pid) {
      try { process.kill(-browserProc.pid, "SIGKILL"); } catch {}
      try { process.kill(browserProc.pid, "SIGKILL"); } catch {}
    }
  }
});
