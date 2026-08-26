import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

// ResizeDestroyTransient: a non-snapshot <tiqian-prose> that has already
// settled must survive a width step change without reverting its paragraphs
// to semantic source. responsive-flicker.test.mjs drives the demo slider in
// small steps on foreground articles; this suite steps the width in one jump,
// on a paragraph with a long progressive-technical URL token, and through
// both the ResizeObserver lane (container) and the window-resize lane
// (viewport), including off-screen and display:none variants. The invariant
// under test is the event sequence, not the converged end state: no
// tiqian:destroy may be dispatched for the root between the width change and
// the next settle, and no sampled animation frame may show the root
// unenhanced with zero lines. The remount variants hold the reconnect
// contract: a same-task disconnect + reconnect adopts the live layout instead
// of restarting from semantic source, whether or not the width changed, and a
// host edit applied while detached still reconciles to the new source.

const webDemoDir = fileURLToPath(new URL("..", import.meta.url));

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.id = 0;
    this.pending = new Map();
  }

  async connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (err) => reject(err);
      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.id && this.pending.has(msg.id)) {
          const { resolve, reject } = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (msg.error) {
            reject(new Error(msg.error.message || JSON.stringify(msg.error)));
          } else {
            resolve(msg.result);
          }
        }
      };
    });
  }

  async send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate(expression) {
    const res = await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (res.exceptionDetails) {
      throw new Error(`Runtime exception: ${JSON.stringify(res.exceptionDetails)}`);
    }
    return res.result?.value;
  }

  close() {
    this.ws?.close();
  }
}

async function ensureServerRunning() {
  try {
    const res = await fetch("http://localhost:8888/", { method: "HEAD" });
    if (res.ok) return null;
  } catch {}

  const serverProc = spawn("bun", ["run", "start"], {
    cwd: webDemoDir,
    stdio: "ignore",
  });

  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch("http://localhost:8888/", { method: "HEAD" });
      if (res.ok) return serverProc;
    } catch {}
    await new Promise((r) => setTimeout(r, 200));
  }

  serverProc.kill();
  throw new Error("Failed to start web demo server on port 8888");
}

async function getOrLaunchBrowser() {
  const serverProc = await ensureServerRunning();

  let port = 9222;
  let chromeProc = null;
  try {
    const res = await fetch(`http://127.0.0.1:${port}/json/version`);
    if (!res.ok) throw new Error("not ready");
  } catch {
    port = 9444;
    chromeProc = spawn("chromium", [
      "--headless=new",
      `--remote-debugging-port=${port}`,
      "--no-sandbox",
      "--disable-gpu",
      "--disable-dev-shm-usage",
      "http://localhost:8888/",
    ], { stdio: "ignore" });

    for (let i = 0; i < 30; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/version`);
        if (res.ok) break;
      } catch {}
      await new Promise((r) => setTimeout(r, 200));
    }
  }

  const listRes = await fetch(`http://127.0.0.1:${port}/json/list`);
  const targets = await listRes.json();
  let page = targets.find((t) => t.type === "page" && t.url.includes("localhost:8888"));

  if (!page) {
    const newTargetRes = await fetch(`http://127.0.0.1:${port}/json/new?http://localhost:8888`, { method: "PUT" });
    page = await newTargetRes.json();
  }

  const cdp = new CdpClient(page.webSocketDebuggerUrl);
  await cdp.connect();
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  return { cdp, chromeProc, serverProc };
}

const LONG_URL_PARAGRAPH =
  "这是一段用于复现宽度骤变瞬态的中西混排正文，" +
  "其中嵌入一个很长的统一资源定位符 " +
  "https://example.com/docs/reference/manual/chapter-12/section-03/appendix/very-long-url-token-for-overflow-reproduction " +
  "以及位于地址之后的中文收尾语句，用来观察排版结果在容器宽度骤降时是否先被拆除再重建。";

function installProseExpression(id, hostStyle, paragraphText = LONG_URL_PARAGRAPH) {
  const paragraph = JSON.stringify(paragraphText);
  return `
    (() => {
      const previous = document.getElementById(${JSON.stringify(id + "-host")});
      if (previous) previous.remove();
      const host = document.createElement("div");
      host.id = ${JSON.stringify(id + "-host")};
      host.style.cssText = ${JSON.stringify(hostStyle)};
      const prose = document.createElement("tiqian-prose");
      prose.id = ${JSON.stringify(id)};
      const p = document.createElement("p");
      p.textContent = ${paragraph};
      // DemoWebBreakWordMask: the demo stylesheet guards every prose paragraph
      // with overflow-wrap: break-word, which hides the visible overflow of a
      // destroyed bare paragraph. Reset it so the transient is measurable.
      p.style.overflowWrap = "normal";
      prose.appendChild(p);
      host.appendChild(prose);
      document.body.appendChild(host);
      return true;
    })()
  `;
}

const settledExpression = (id) => `
  (() => {
    const prose = document.getElementById(${JSON.stringify(id)});
    if (!prose || prose.getAttribute("data-tiqian-enhanced") !== "true") return false;
    const ps = Array.from(prose.querySelectorAll("p"));
    if (ps.length === 0) return false;
    if (!ps.every((p) => p.getAttribute("data-tq-rendered") === "true")) return false;
    return prose.querySelectorAll(".tq-line").length > 0;
  })()
`;

const armInstrumentationExpression = (id) => `
  (() => {
    const prose = document.getElementById(${JSON.stringify(id)});
    const events = [];
    const record = (type) => (ev) => {
      // Element-lifecycle events (tiqian:ready, tiqian:relayout-ready) carry
      // counts in their detail and dispatch on the root itself, so the root
      // comes from the event target; document-channel events keep using
      // detail.root.
      const root = (ev.detail && ev.detail.root) ||
        (ev.target && ev.target.closest ? ev.target.closest("tiqian-prose") : null);
      events.push({
        type,
        t: Math.round(performance.now()),
        rootId: root && root.id ? root.id : null,
        rootTag: root && root.tagName ? root.tagName : null,
        mine: root === prose || (root && prose.contains(root)),
      });
    };
    const types = [
      "tiqian:destroy",
      "tiqian:detach",
      "tiqian:relayout",
      "tiqian:relayout-ready",
      "tiqian:ready",
      "tiqian:reconcile-content",
      "tiqian:cancel-layout-work",
    ];
    for (const type of types) document.addEventListener(type, record(type), true);
    // EnhanceOptionsDatasetSignal: the document event channel that used to
    // report each progressive enhance (tiqian:enhance-progressively) is
    // retired (ADR 0053 C1). Its public successor is the root dataset the
    // element writes per coordination run (tiqianEnhanceOptions), so record
    // dataset appearances here instead of listening for the removed event.
    // The element-level ready events above remain the completion signal.
    const optionsObserver = new MutationObserver(() => {
      if (!prose.dataset.tiqianEnhanceOptions) return;
      events.push({
        type: "enhance-options-captured",
        t: Math.round(performance.now()),
        rootId: prose.id || null,
        rootTag: prose.tagName,
        mine: true,
      });
    });
    optionsObserver.observe(prose, {
      attributes: true,
      attributeFilter: ["data-tiqian-enhance-options"],
    });
    const frames = [];
    let sampling = true;
    const sample = () => {
      const ps = prose.querySelectorAll("p");
      let rendered = 0;
      let overflow = 0;
      for (const p of ps) {
        if (p.getAttribute("data-tq-rendered") === "true") rendered += 1;
        overflow = Math.max(overflow, p.scrollWidth - p.clientWidth);
      }
      frames.push({
        t: Math.round(performance.now()),
        enhanced: prose.getAttribute("data-tiqian-enhanced") === "true",
        rendered,
        paragraphs: ps.length,
        lines: prose.querySelectorAll(".tq-line").length,
        overflow,
      });
      if (sampling) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    window.__tqRepro = {
      events,
      frames,
      resizeMarker: () => {
        events.push({ type: "resize-issued", t: Math.round(performance.now()), mine: null });
      },
      stop: () => {
        sampling = false;
        for (const type of types) document.removeEventListener(type, record(type), true);
        optionsObserver.disconnect();
      },
    };
    return true;
  })()
`;

async function waitFor(cdp, expression, timeoutMs = 15000, label = "condition") {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await cdp.evaluate(expression)) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function collectReport(cdp, settleTimeoutMs = 15000) {
  await waitFor(
    cdp,
    `(() => { const r = window.__tqRepro; return r && r.frames.length > 0; })()`,
    5000,
    "sampler start",
  );
  await new Promise((r) => setTimeout(r, 2500));
  return cdp.evaluate(`(() => {
    const r = window.__tqRepro;
    r.stop();
    return { events: r.events, frames: r.frames };
  })()`);
}

function summarizeFailures(report) {
  const destroys = report.events.filter((e) => e.mine && e.type === "tiqian:destroy");
  const detaches = report.events.filter((e) => e.mine && e.type === "tiqian:detach");
  const bareFrames = report.frames.filter((f) => !f.enhanced || f.lines === 0);
  const overflowFrames = report.frames.filter((f) => f.overflow > 1);
  const lines = [
    `events=${JSON.stringify(report.events)}`,
    `frames total=${report.frames.length}`,
    `first frame=${JSON.stringify(report.frames[0])}`,
    `last frame=${JSON.stringify(report.frames[report.frames.length - 1])}`,
  ];
  if (bareFrames.length) lines.push(`bare frames (${bareFrames.length}) first=${JSON.stringify(bareFrames[0])}`);
  if (overflowFrames.length) {
    lines.push(`overflow frames (${overflowFrames.length}) max=${Math.max(...overflowFrames.map((f) => f.overflow))}`);
  }
  return { destroys, detaches, bareFrames, overflowFrames, detail: lines.join("\n") };
}

async function prepareProse(cdp, id, hostStyle, paragraphText) {
  await cdp.evaluate(installProseExpression(id, hostStyle, paragraphText));
  await waitFor(cdp, settledExpression(id), 15000, `initial enhancement of ${id}`);
  // DemoWebSettleFontWave: a legal relayout wave can still land within ~1s
  // after the settle gate; let it pass before arming the instrumentation.
  await new Promise((r) => setTimeout(r, 1200));
  await waitFor(cdp, settledExpression(id), 5000, `re-settle of ${id} after font wave`);
  await cdp.evaluate(armInstrumentationExpression(id));
}

const installMultiParagraphProseExpression = (id, paragraphCount) => {
  const paragraph = JSON.stringify(LONG_URL_PARAGRAPH);
  return `
    (() => {
      const previous = document.getElementById(${JSON.stringify(id + "-host")});
      if (previous) previous.remove();
      const host = document.createElement("div");
      host.id = ${JSON.stringify(id + "-host")};
      host.style.cssText = "width: 900px; margin: 8px auto;";
      const prose = document.createElement("tiqian-prose");
      prose.id = ${JSON.stringify(id)};
      for (let i = 0; i < ${paragraphCount}; i++) {
        const p = document.createElement("p");
        p.textContent = ${paragraph};
        prose.appendChild(p);
      }
      host.appendChild(prose);
      document.body.appendChild(host);
      return true;
    })()
  `;
};

const partiallyRenderedExpression = (id, minRendered) => `
  (() => {
    const prose = document.getElementById(${JSON.stringify(id)});
    if (!prose) return false;
    const rendered = Array.from(prose.querySelectorAll("p"))
      .filter((p) => p.getAttribute("data-tq-rendered") === "true").length;
    return rendered >= ${minRendered};
  })()
`;

test("Tiqian Resize Destroy Transient Reproduction Suite", async (t) => {
  const { cdp, chromeProc, serverProc } = await getOrLaunchBrowser();

  t.after(() => {
    cdp.close();
    chromeProc?.kill();
    serverProc?.kill();
  });

  await t.test("container step resize 900->360 keeps enhancement in place", async () => {
    const id = "tq-repro-container";
    await prepareProse(cdp, id, "width: 900px; margin: 8px auto;");
    const before = await cdp.evaluate(`document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length`);

    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        document.getElementById(${JSON.stringify(id + "-host")}).style.width = "360px";
      })()
    `);
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired during container resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames during container resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
    assert.ok(last.lines !== before, `relayout never happened (${before} -> ${last.lines}):\n${detail}`);
  });

  await t.test("viewport step resize 900->360 keeps enhancement in place", async () => {
    const id = "tq-repro-viewport";
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: 940,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await prepareProse(cdp, id, "width: 100%; margin: 8px auto;");

    await cdp.evaluate(`window.__tqRepro.resizeMarker()`);
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: 400,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired during viewport resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames during viewport resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
  });

  await t.test("container step resize while prose is off-screen", async () => {
    const id = "tq-repro-offscreen";
    await prepareProse(cdp, id, "width: 900px; position: absolute; top: 10000px; left: 0;");
    // Force the root out of the intersection viewport before the resize.
    await cdp.evaluate(`window.scrollTo(0, 0)`);

    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        document.getElementById(${JSON.stringify(id + "-host")}).style.width = "360px";
      })()
    `);
    await new Promise((r) => setTimeout(r, 1500));
    await cdp.evaluate(`
      (() => {
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        host.style.position = "static";
        host.scrollIntoView({ block: "center" });
      })()
    `);
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired during off-screen resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames during off-screen resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
  });

  await t.test("container step resize while enhancement is still in flight", async () => {
    const id = "tq-repro-inflight";
    await cdp.evaluate(installMultiParagraphProseExpression(id, 12));
    // Wait until the progressive job has committed some (not all) paragraphs,
    // then resize mid-flight so the captured-measure cancellation lane runs.
    await waitFor(cdp, partiallyRenderedExpression(id, 2), 15000, "partial enhancement");
    await cdp.evaluate(armInstrumentationExpression(id));

    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        document.getElementById(${JSON.stringify(id + "-host")}).style.width = "360px";
      })()
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-resize settle");
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired during in-flight resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames during in-flight resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
  });

  await t.test("container step resize immediately followed by remount", async () => {
    const id = "tq-repro-remount";
    // The production report measured a 358px overflow on the bare paragraph:
    // a long unbreakable token has no native break opportunity, so only the
    // destroyed (semantic-source) interval can overflow the 360px container.
    const unbreakableParagraph =
      LONG_URL_PARAGRAPH +
      " 另附一个没有任何断行机会的连续令牌 " +
      "https://example.com/download?session=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" +
      " 供裸段落溢出复现使用。";
    await prepareProse(cdp, id, "width: 900px; margin: 8px auto;", unbreakableParagraph);

    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        host.style.width = "360px";
        // Simulate a framework re-render that moves the root in the same task:
        // disconnect + reconnect forces the restart lifecycle path.
        const prose = document.getElementById(${JSON.stringify(id)});
        prose.remove();
        host.appendChild(prose);
      })()
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-remount settle");
    const report = await collectReport(cdp);
    const { destroys, detaches, bareFrames, detail } = summarizeFailures(report);

    // Reconnect adoption: a same-task disconnect + reconnect is a host move,
    // the sequence React and Svelte issue when a re-render
    // relocates the root. The committed LayoutResult stayed valid through the
    // move, so neither teardown lane may fire and the width change must route
    // through the in-place relayout lane. Before the fix this window measured
    // 28 bare frames (~660ms) with ~350px of overflow on the bare paragraph;
    // base b6498412 destroyed too but re-settled within ~2 frames.
    assert.equal(destroys.length, 0, `tiqian:destroy fired across the reconnect move:\n${detail}`);
    assert.equal(detaches.length, 0, `tiqian:detach fired across the reconnect move:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames across the reconnect move:\n${detail}`);
    const relayouts = report.events.filter((e) => e.mine && e.type === "tiqian:relayout-ready");
    assert.ok(relayouts.length > 0, `the 900->360 width change never entered relayout:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
  });

  await t.test("same-task remount without a width change keeps the committed layout", async () => {
    const id = "tq-repro-move";
    await prepareProse(cdp, id, "width: 900px; margin: 8px auto;");

    const linesBefore = await cdp.evaluate(
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length`,
    );
    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        const prose = document.getElementById(${JSON.stringify(id)});
        prose.remove();
        host.appendChild(prose);
      })()
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-move settle");
    const report = await collectReport(cdp);
    const { destroys, detaches, bareFrames, detail } = summarizeFailures(report);

    // The pure reconnect move is the strongest form of the contract: same
    // content, same typography, same width. Nothing may tear down and the
    // committed line count must survive the move untouched.
    assert.equal(destroys.length, 0, `tiqian:destroy fired across the pure move:\n${detail}`);
    assert.equal(detaches.length, 0, `tiqian:detach fired across the pure move:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames across the pure move:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.equal(last.lines, linesBefore, `line count changed across the pure move:\n${detail}`);
    assert.ok(last.enhanced, `final state lost enhancement:\n${detail}`);
  });

  await t.test("same-task remount with a host edit while detached reconciles the new source", async () => {
    const id = "tq-repro-move-edit";
    await prepareProse(cdp, id, "width: 900px; margin: 8px auto;");

    const replacementText =
      LONG_URL_PARAGRAPH +
      " 搬动期间宿主改写了这段内容，重连后必须按新正文重排。";
    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        const prose = document.getElementById(${JSON.stringify(id)});
        prose.remove();
        // React innerHTML-style re-render while detached: the rendered
        // paragraphs are replaced by fresh semantic source.
        prose.innerHTML = "<p>" + ${JSON.stringify(replacementText)} + "</p>";
        host.appendChild(prose);
      })()
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-edit settle");
    const report = await collectReport(cdp);
    const { destroys, detaches, detail } = summarizeFailures(report);

    // Adoption must not swallow a content change. The observe lanes stayed
    // armed through the move, so the edit reaches the reconcile lane and the
    // new source is re-lowered surgically, without a root teardown.
    assert.equal(destroys.length, 0, `tiqian:destroy fired instead of reconcile:\n${detail}`);
    assert.equal(detaches.length, 0, `tiqian:detach fired across the reconnect move:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
    const reconciled = await cdp.evaluate(`
      (() => {
        const prose = document.getElementById(${JSON.stringify(id)});
        const text = prose.textContent;
        return text.includes("搬动期间宿主改写了这段内容") &&
          prose.querySelectorAll(".tq-line").length > 0;
      })()
    `);
    assert.ok(reconciled, `the edited source never reached the rendered layout:\n${detail}`);
  });

  await t.test("container step resize while the panel is display:none", async () => {
    const id = "tq-repro-hidden";
    await prepareProse(cdp, id, "width: 900px; margin: 8px auto;");

    // Hide the committed host itself (no DOM move, so the restart lifecycle
    // lane cannot fire): ResizeObserver reports a 0 inline size for
    // display:none targets, and a hidden panel is exactly how a background
    // dashboard resize arrives.
    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        host.style.display = "none";
        return true;
      })()
    `);
    await new Promise((r) => setTimeout(r, 300));
    await cdp.evaluate(`
      (() => {
        window.__tqRepro.resizeMarker();
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        host.style.display = "";
        host.style.width = "360px";
        return true;
      })()
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-reveal settle");
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired across the hidden resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames across the hidden resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
  });

  await t.test("viewport step resize while the panel is display:none", async () => {
    const id = "tq-repro-hidden-viewport";
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: 940,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await prepareProse(cdp, id, "width: 100%; margin: 8px auto;");

    await cdp.evaluate(`
      (() => {
        const host = document.getElementById(${JSON.stringify(id + "-host")});
        window.__tqRepro.resizeMarker();
        host.style.display = "none";
        return true;
      })()
    `);
    await new Promise((r) => setTimeout(r, 300));
    await cdp.evaluate(`window.__tqRepro.resizeMarker()`);
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: 400,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await new Promise((r) => setTimeout(r, 300));
    await cdp.evaluate(`
      document.getElementById(${JSON.stringify(id + "-host")}).style.display = ""
    `);
    await waitFor(cdp, settledExpression(id), 20000, "post-reveal settle");
    const report = await collectReport(cdp);
    const { destroys, bareFrames, detail } = summarizeFailures(report);

    assert.equal(destroys.length, 0, `tiqian:destroy fired across the hidden viewport resize:\n${detail}`);
    assert.equal(bareFrames.length, 0, `bare-source frames across the hidden viewport resize:\n${detail}`);
    const last = report.frames[report.frames.length - 1];
    assert.ok(last.enhanced && last.lines > 0, `final state lost enhancement:\n${detail}`);
    assert.ok(last.overflow <= 1, `final state overflows by ${last.overflow}px:\n${detail}`);
  });

  await cdp.send("Emulation.clearDeviceMetricsOverride").catch(() => {});
});
