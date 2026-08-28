// oneshot-geometry-history.test.mjs — ledger anchor for the b649841..HEAD
// box history (spec-oneshot-bisect). Two subtests:
//
// 1. Chain anchor: captures the carried fixed demo kit's deep geometry on
//    the current tree and asserts zero divergence against the frozen final
//    baseline in fixtures/oneshot-geometry-history.json.
// 2. One-shot re-enhance probe: sweeps the page twice (coordinated enhance,
//    then a fresh one-shot re-enhance over the settled roots) and compares
//    every box across the two sweeps and against the same frozen baseline
//    snapshot. This reproduces the still-live defect introduced at
//    bed4c791 (2026-08-26): the one-shot re-enhance relayouts boxes at the
//    mid-scroll offsets. The assertion stays at zero tolerance; a red run
//    is the honest signal that the defect is present. The failure message
//    pinpoints the affected scroll offsets, the affected paragraphs with
//    per-box deltas, and which side moved away from the snapshot, so the
//    blast site localizes without a manual bisect.
//
// Capture semantics reuse the carried kit harness verbatim (kit page, era
// importmap, coordinated enhance, terminal settle, scroll-sweep captures);
// box comparison uses helpers/deep-geometry.mjs unchanged: every box's
// x/y/width/height compared at 0.01px rounding with zero tolerance, no
// sampling, no dropped boxes. Each offset capture keeps the kit's
// screenshot pair (the frame pump that flushes rAF-scheduled relayouts in
// a headless page) and adds a 400ms geometry re-read as the
// self-consistency probe on the measured surface itself.
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
//   divergent at bed4c791 vs 20/20 clean at 7e2d1909. Still live at the
//   2026-08-27 central measurement (8/10 runs divergent at 6bda6d05, offsets
//   1280-3200; evidence .agent-specs/oneshot-bisect-evidence/6bda6d05/ in
//   the bisect worktree).
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
  SETTLE_HELPERS,
  DEEP_GEOMETRY_HELPERS,
} from "../../web-history/oneshot-history-harness.diag.mjs";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Kit scaffolding shared by both subtests: static server for the era, one
// headless chromium on the CDP port, one connected client with the page
// diagnostics collected into pageLog. Errors inside the setup clean up
// every half they already created.
async function openKitPage(era) {
  const server = await startKitServer(era);
  let browserProc = null;
  let client = null;
  try {
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
    return { server, browserProc, client, pageLog };
  } catch (error) {
    client?.close();
    server.close();
    if (browserProc?.pid) {
      try { process.kill(-browserProc.pid, "SIGKILL"); } catch {}
      try { process.kill(browserProc.pid, "SIGKILL"); } catch {}
    }
    throw error;
  }
}

function closeKitPage(kit) {
  if (!kit) return;
  kit.client?.close();
  kit.server?.close();
  if (kit.browserProc?.pid) {
    try { process.kill(-kit.browserProc.pid, "SIGKILL"); } catch {}
    try { process.kill(kit.browserProc.pid, "SIGKILL"); } catch {}
  }
}

// Kit page load plus the __historyReady wait, mirroring the harness
// chainCapture/runOnce preamble.
async function navigateAndWaitReady(client) {
  await client.send("Page.navigate", { url: "about:blank" });
  await client.evaluate("0");
  await client.send("Page.navigate", { url: `http://127.0.0.1:${DEMO_PORT}/` });
  await client.evaluate("new Promise((r) => { if (document.readyState === 'complete') setTimeout(r, 300); else window.addEventListener('load', () => setTimeout(r, 300)); })");
  const deadline = Date.now() + 30000;
  let lastError = null;
  while (Date.now() < deadline) {
    const ready = await client.evaluate("globalThis.__historyReady === true")
      .catch((e) => { lastError = String(e); return false; });
    if (ready) return;
    await sleep(250);
  }
  throw new Error(`adapter never became ready${lastError ? `: ${lastError}` : ""}`);
}

// Scroll sweep with per-offset captures: scrollTo, settle, one screenshot
// pair, then geometry read twice 400ms apart. The screenshots are the frame
// pump: a headless page produces no compositor frames on its own, so
// rAF-scheduled work never runs until Page.captureScreenshot requests a
// fresh frame. The one-shot defect relayouts through exactly such deferred
// work — measured 2026-08-27, same tree and build: with the pump the study
// protocol reproduces the divergence 3/3, without it four consecutive
// sweeps read the pre-relayout state on both sides and the defect is
// invisible. The screenshot bytes are discarded; the 400ms geometry re-read
// is the self-consistency probe on the measured surface itself. Boxes are
// document-absolute (the collector adds scrollX/scrollY), so captures at
// different offsets stay comparable with each other and with the
// scrollTop=0 fixture.
async function sweep(client, label) {
  await client.evaluate("window.scrollTo(0, 0)");
  const topSettle = await client.evaluate("__historySettle(20000)");
  assert.ok(topSettle.settled, `${label}: page did not settle at top`);
  const plan = await client.evaluate(`
    (() => {
      const viewportHeight = innerHeight;
      const pageHeight = document.documentElement.scrollHeight;
      const step = Math.floor(viewportHeight * 0.8);
      const maxScroll = Math.max(0, pageHeight - viewportHeight);
      const offsets = [0];
      for (let y = step; y < maxScroll; y += step) offsets.push(y);
      if (offsets[offsets.length - 1] !== maxScroll) offsets.push(maxScroll);
      return { offsets, viewportHeight, pageHeight };
    })()
  `);
  const captures = [];
  for (const offset of plan.offsets) {
    await client.evaluate(`window.scrollTo(0, ${offset})`);
    await sleep(500);
    const settled = await client.evaluate("__historySettle(20000)");
    assert.ok(settled.settled, `${label}: page did not settle at scroll ${offset}`);
    await client.screenshot({ clip: { x: 0, y: offset, width: VIEWPORT_WIDTH, height: Math.min(plan.viewportHeight, plan.pageHeight - offset), scale: 1 }, captureBeyondViewport: true });
    await client.screenshot({ clip: { x: 0, y: offset, width: VIEWPORT_WIDTH, height: Math.min(plan.viewportHeight, plan.pageHeight - offset), scale: 1 }, captureBeyondViewport: true });
    const geometryA = await client.evaluate("__deepGeometry()");
    await sleep(400);
    const geometryB = await client.evaluate("__deepGeometry()");
    const self = diffDeepGeometry(geometryA, geometryB);
    captures.push({
      offset,
      pageHeight: settled.pageHeight,
      selfEqual: self.equal,
      selfDivergentBoxes: self.divergentBoxes,
      geometry: geometryA,
    });
  }
  await client.evaluate("window.scrollTo(0, 0)");
  return { plan, captures };
}

// Signed per-edge delta between two rounded boxes, e.g.
// "y3142.16->3100.57(dy=-41.59)". Values are already rounded to 0.01 by the
// collector, so string array equality matches the diffDeepGeometry box
// comparison semantics.
function fmtDelta(from, to) {
  const a = from ?? [];
  const b = to ?? [];
  const parts = [];
  for (let i = 0; i < 4; i++) {
    if (a[i] === b[i]) continue;
    const d = a[i] == null || b[i] == null ? "?" : (b[i] - a[i]).toFixed(2);
    parts.push(`${"xywh"[i]}${a[i] ?? "?"}->${b[i] ?? "?"}(d${"xywh"[i]}=${d})`);
  }
  return parts.length ? parts.join(" ") : "identical";
}

// Per-paragraph localization of every box difference between two geometry
// reports: paragraph rect delta, how many line marks moved and the first
// moved one, how many direct children changed and the first changed one.
// Structural count mismatches (page height, roots, paragraphs, marks,
// children) get their own lines. Returns a summary line plus at most
// maxLines detail lines.
function paragraphDigest(from, to, maxLines = 8) {
  const same = (x, y) => JSON.stringify(x) === JSON.stringify(y);
  const lines = [];
  const push = (line) => lines.push(line);
  if ((from?.pageHeight ?? -1) !== (to?.pageHeight ?? -1)) {
    push(`pageHeight ${from?.pageHeight} -> ${to?.pageHeight}`);
  }
  const rootsFrom = from?.roots ?? [];
  const rootsTo = to?.roots ?? [];
  if (rootsFrom.length !== rootsTo.length) push(`rootCount ${rootsFrom.length} -> ${rootsTo.length}`);
  rootsFrom.forEach((rootFrom, ri) => {
    const rootTo = rootsTo[ri];
    if (!rootTo) return;
    if (!same(rootFrom.root, rootTo.root)) push(`root#${ri}: ${fmtDelta(rootFrom.root, rootTo.root)}`);
    const parasFrom = rootFrom.paras ?? [];
    const parasTo = rootTo?.paras ?? [];
    if (parasFrom.length !== parasTo.length) push(`root#${ri} paraCount ${parasFrom.length} -> ${parasTo.length}`);
    parasFrom.forEach((paraFrom, pi) => {
      const paraTo = parasTo[pi];
      if (!paraTo) return;
      const parts = [];
      if (!same(paraFrom.rect, paraTo.rect)) parts.push(`rect ${fmtDelta(paraFrom.rect, paraTo.rect)}`);
      const marksFrom = paraFrom.lineMarks ?? [];
      const marksTo = paraTo?.lineMarks ?? [];
      const movedMarks = [];
      marksFrom.forEach((box, mi) => { if (!same(box, marksTo[mi])) movedMarks.push(mi); });
      if (marksFrom.length !== marksTo.length || movedMarks.length) {
        const first = movedMarks[0];
        parts.push(`lineMarks ${marksFrom.length}->${marksTo.length}, ${movedMarks.length} moved` +
          (first != null ? `, first [${first}] ${fmtDelta(marksFrom[first], marksTo[first])}` : ""));
      }
      const kidsFrom = paraFrom.kids ?? [];
      const kidsTo = paraTo?.kids ?? [];
      const movedKids = [];
      kidsFrom.forEach((kidFrom, ki) => {
        const kidTo = kidsTo[ki];
        if (!kidTo || kidFrom.k !== kidTo.k || !same(kidFrom.b, kidTo.b)) movedKids.push(ki);
      });
      if (kidsFrom.length !== kidsTo.length || movedKids.length) {
        const first = movedKids[0];
        parts.push(`kids ${kidsFrom.length}->${kidsTo.length}, ${movedKids.length} differ` +
          (first != null ? `, first kids[${first}](${kidsFrom[first]?.k ?? "?"}) ${fmtDelta(kidsFrom[first]?.b, kidsTo[first]?.b)}` : ""));
      }
      if (parts.length) push(`p${paraFrom.key ?? pi}: ${parts.join(" | ")}`);
    });
  });
  if (!lines.length) return ["no located differences (unexpected: the caller saw divergence)"];
  return [
    `${lines.length} affected paragraph/structure entries:`,
    ...lines.slice(0, maxLines),
    ...(lines.length > maxLines ? [`...and ${lines.length - maxLines} more`] : []),
  ];
}

test("OneShotGeometryHistory: current tree capture equals the frozen b649841..HEAD final baseline", async () => {
  const demoUrl = `http://127.0.0.1:${DEMO_PORT}/`;
  const portBusy = await fetch(demoUrl).then(() => true, () => false);
  assert.ok(!portBusy, `Port ${DEMO_PORT} must be free before the test starts`);

  const era = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web-history/eras/e8-context.json"), "utf8"));
  const baseline = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web/tests/fixtures/oneshot-geometry-history.json"), "utf8"));

  let kit = null;
  try {
    kit = await openKitPage(era);

    const record = await chainCapture(kit.client, era, "current-tree", kit.pageLog);
    assert.ok(record.valid, `kit capture must be valid; reason=${record.reason} log=${JSON.stringify(record.pageLog ?? kit.pageLog.slice(0, 20))}`);
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
    closeKitPage(kit);
  }
});

test("OneShotReEnhanceGeometry: a one-shot re-enhance over settled roots keeps every box at every scroll offset", async () => {
  const demoUrl = `http://127.0.0.1:${DEMO_PORT}/`;
  const portBusy = await fetch(demoUrl).then(() => true, () => false);
  assert.ok(!portBusy, `Port ${DEMO_PORT} must be free before the test starts`);

  const era = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web-history/eras/e8-context.json"), "utf8"));
  const baseline = JSON.parse(readFileSync(
    path.join(repoRoot, "demo/web/tests/fixtures/oneshot-geometry-history.json"), "utf8"));

  let kit = null;
  try {
    kit = await openKitPage(era);
    const { client, pageLog } = kit;

    await navigateAndWaitReady(client);
    await client.evaluate(SETTLE_HELPERS);
    await client.evaluate("document.fonts.ready");
    await client.evaluate("globalThis.__historyEnhance()");
    const enhanced = await client.evaluate("__historySettle(60000)");
    assert.ok(enhanced.settled,
      `coordinated enhance must reach the terminal settle; pageLog=${JSON.stringify(pageLog.slice(0, 20))}`);
    await client.evaluate(DEEP_GEOMETRY_HELPERS);

    const coordinated = await sweep(client, "coordinated");

    // Vacuity gate plus the snapshot anchor at scroll 0: the same semantics
    // as the chain subtest, so a clean cross comparison below cannot hide a
    // starting state that already drifted from the frozen fixture.
    assert.equal(coordinated.captures[0].offset, 0, "first sweep capture must be at scroll 0");
    const countsTop = deepGeometryCounts(coordinated.captures[0].geometry);
    assert.ok(countsTop.lineMarks > 0 && countsTop.runEls > 0 && countsTop.textNodes > 0,
      `page must be enhanced; counts=${JSON.stringify(countsTop)}`);
    assert.deepEqual(countsTop, baseline.counts, "coordinated top counts must equal the baseline counts");
    assert.equal(coordinated.captures[0].pageHeight, baseline.pageHeight, "coordinated top page height must equal the baseline");
    const topBaseDiff = diffDeepGeometry(coordinated.captures[0].geometry, baseline.geometry);
    assert.ok(topBaseDiff.equal,
      `coordinated top capture diverges from the frozen baseline: ${topBaseDiff.divergentBoxes}/${topBaseDiff.boxesCompared} boxes; examples: ${topBaseDiff.examples.join(" | ")}`);

    // The one-shot re-enhance, exactly as the bisect study drives it: fresh
    // createEnhanceContext(...).mount() over every settled root.
    await client.evaluate("globalThis.__historyOneShot()");
    await sleep(800);
    const afterOneShot = await client.evaluate("__historySettle(60000)");
    assert.ok(afterOneShot.settled,
      `one-shot re-enhance must reach the terminal settle; pageLog=${JSON.stringify(pageLog.slice(0, 20))}`);

    const oneshot = await sweep(client, "one-shot");
    const countsOneshotTop = deepGeometryCounts(oneshot.captures[0].geometry);
    assert.ok(countsOneshotTop.lineMarks > 0 && countsOneshotTop.runEls > 0 && countsOneshotTop.textNodes > 0,
      `page must stay enhanced after the one-shot re-enhance; counts=${JSON.stringify(countsOneshotTop)}`);

    assert.deepEqual(oneshot.plan.offsets, coordinated.plan.offsets,
      `scroll plans must match (page height changed after the one-shot re-enhance: ` +
      `coordinated ${coordinated.plan.pageHeight} vs one-shot ${oneshot.plan.pageHeight})`);

    const failures = [];
    const sideFailures = [];
    for (let i = 0; i < coordinated.captures.length; i++) {
      const a = coordinated.captures[i];
      const b = oneshot.captures[i];
      const countsA = deepGeometryCounts(a.geometry);
      const countsB = deepGeometryCounts(b.geometry);
      const vacuousA = countsA.lineMarks === 0 && countsA.runEls === 0 && countsA.textNodes === 0;
      const vacuousB = countsB.lineMarks === 0 && countsB.runEls === 0 && countsB.textNodes === 0;
      if (vacuousA || vacuousB) {
        sideFailures.push(`scroll ${a.offset}: enhancement collapsed mid-sweep ` +
          `(coordinated counts=${JSON.stringify(countsA)}, one-shot counts=${JSON.stringify(countsB)})`);
      }
      if (!a.selfEqual) {
        sideFailures.push(`scroll ${a.offset}: coordinated side still moving during capture ` +
          `(${a.selfDivergentBoxes} boxes changed across the 400ms re-read)`);
      }
      if (!b.selfEqual) {
        sideFailures.push(`scroll ${a.offset}: one-shot side still moving during capture ` +
          `(${b.selfDivergentBoxes} boxes changed across the 400ms re-read)`);
      }

      const cross = diffDeepGeometry(a.geometry, b.geometry);
      if (cross.equal) continue;

      // Which side moved: compare each side at this offset against the same
      // frozen snapshot the chain subtest anchors to.
      const baseA = diffDeepGeometry(a.geometry, baseline.geometry);
      const baseB = diffDeepGeometry(b.geometry, baseline.geometry);
      const parts = [
        `scroll ${a.offset}: ${cross.divergentBoxes}/${cross.boxesCompared} boxes diverge between coordinated and one-shot`,
        `  cross examples: ${cross.examples.slice(0, 4).join(" | ")}`,
      ];
      if (baseA.equal) {
        parts.push("  coordinated side equals the frozen snapshot; the one-shot side moved. one-shot vs snapshot:");
        paragraphDigest(baseline.geometry, b.geometry).forEach((line) => parts.push("    " + line));
      } else if (baseB.equal) {
        parts.push("  one-shot side equals the frozen snapshot; the coordinated side moved. coordinated vs snapshot:");
        paragraphDigest(baseline.geometry, a.geometry).forEach((line) => parts.push("    " + line));
      } else {
        parts.push("  both sides differ from the frozen snapshot. coordinated vs snapshot:");
        paragraphDigest(baseline.geometry, a.geometry).forEach((line) => parts.push("    " + line));
        parts.push("  one-shot vs snapshot:");
        paragraphDigest(baseline.geometry, b.geometry).forEach((line) => parts.push("    " + line));
      }
      failures.push(parts.join("\n"));
    }

    const messages = [];
    if (sideFailures.length) messages.push(sideFailures.join("\n"));
    if (failures.length) {
      messages.push(
        `one-shot re-enhance diverges from the coordinated capture at ${failures.length}/${coordinated.captures.length} scroll offsets ` +
        "(defect first appeared at bed4c791 2026-08-26, see the ledger above):\n" +
        failures.join("\n"));
    }
    assert.ok(messages.length === 0, messages.join("\n\n"));
  } finally {
    closeKitPage(kit);
  }
});
