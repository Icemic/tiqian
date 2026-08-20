import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

// PrePaintResponsiveCommit: a width-only change observed by ResizeObserver
// on an in-viewport, runtime-active root commits its relayout synchronously
// inside the observer callback — before the browser paints the resized
// frame. The invariant under test is per-frame visual integrity: after the
// width change, no sampled animation frame may show the previous width's
// lines overflowing the new container beyond the hanging-punctuation
// allowance. resize-destroy-transient.test.mjs holds the event-sequence
// contract (no destroy, no bare source); this suite holds the paint
// contract on top of it. The off-screen case documents the designed
// fallback: the immediate lane declines and the scheduled lane converges
// exactly as before.

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
  const proc = spawn("bun", ["run", "start"], {
    cwd: webDemoDir,
    stdio: "ignore",
    detached: false,
  });
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch("http://localhost:8888/", { method: "HEAD" });
      if (res.ok) return proc;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  proc.kill();
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
  // A reused tab may still be running a bundle from before the current
  // build; reload so the suite always exercises the code under test.
  await cdp.send("Page.reload", { ignoreCache: true });
  await new Promise((r) => setTimeout(r, 3000));

  return { cdp, chromeProc, serverProc };
}

const LONG_URL_PARAGRAPH =
  "这是一段用于检验重排提交时机的中西混排正文，" +
  "其中嵌入一个很长的统一资源定位符 " +
  "https://example.com/docs/reference/manual/chapter-12/section-03/appendix/very-long-url-token-for-overflow-reproduction " +
  "以及位于地址之后的中文收尾语句，用来观察旧宽度的行几何是否在新宽度提交之前被绘制出来。";

// Hanging punctuation and the trailing line marker may legitimately paint a
// few pixels outside the paragraph content box; anything beyond one em at
// the demo font size is stale line geometry from the previous width.
const OVERFLOW_ALLOWANCE_PX = 24;

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
      // with overflow-wrap: break-word, which hides the visible overflow of
      // stale line geometry. Reset it so the transient is measurable.
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

// Paint-accurate stale detector. A requestAnimationFrame sampler cannot
// tell the two implementations apart: it runs before the frame's
// ResizeObserver phase, so it records the pre-commit state under both the
// immediate lane and the scheduled lane, while the paint at the end of the
// frame differs. ResizeObserver broadcast order is observer creation order,
// and this probe observer is created after the runtime's, so its callback
// runs in the same broadcast, after any PrePaintResponsiveCommit work, and
// before the frame paints. Stale geometry it records is exactly what the
// frame paints; geometry the immediate lane already committed shows up
// clean here.
const armSamplerExpression = (id) => `
  (() => {
    const prose = document.getElementById(${JSON.stringify(id)});
    const host = document.getElementById(${JSON.stringify(id + "-host")});
    const deliveries = [];
    const events = { destroy: 0, relayoutReady: 0 };
    const onDestroy = (ev) => {
      const root = ev.detail && ev.detail.root;
      if (root === prose || (root && prose.contains(root))) events.destroy += 1;
    };
    const onRelayoutReady = () => { events.relayoutReady += 1; };
    document.addEventListener("tiqian:destroy", onDestroy, true);
    document.addEventListener("tiqian:relayout-ready", onRelayoutReady, true);
    const measure = () => {
      const hostRect = host.getBoundingClientRect();
      let maxRight = 0;
      let lines = 0;
      for (const p of prose.querySelectorAll("p")) {
        for (const child of p.querySelectorAll("*")) {
          const r = child.getBoundingClientRect();
          if (r.width > 0 && r.right > maxRight) maxRight = r.right;
        }
        lines += p.querySelectorAll(".tq-line").length;
      }
      return {
        t: Math.round(performance.now()),
        hostWidth: Math.round(hostRect.width),
        overflow: Math.round(Math.max(0, maxRight - hostRect.right)),
        lines,
      };
    };
    const probe = new ResizeObserver(() => { deliveries.push(measure()); });
    probe.observe(host, { box: "border-box" });
    window.__tqPrePaint = {
      deliveries,
      events,
      stop: () => {
        probe.disconnect();
        document.removeEventListener("tiqian:destroy", onDestroy, true);
        document.removeEventListener("tiqian:relayout-ready", onRelayoutReady, true);
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

async function collectReport(cdp) {
  await new Promise((r) => setTimeout(r, 1500));
  return cdp.evaluate(`(() => {
    const r = window.__tqPrePaint;
    r.stop();
    return { deliveries: r.deliveries, events: r.events };
  })()`);
}

function stalePaintedFrames(report) {
  return report.deliveries.filter((f) => f.overflow > OVERFLOW_ALLOWANCE_PX);
}

test("Tiqian PrePaint Responsive Commit Suite", async (t) => {
  const { cdp, chromeProc, serverProc } = await getOrLaunchBrowser();

  t.after(() => {
    cdp.close();
    chromeProc?.kill();
    serverProc?.kill();
  });

  await t.test("step resize 900->360 paints no frame with stale overflow", async () => {
    const id = "prepaint-step";
    // position: fixed keeps the root inside the viewport regardless of the
    // demo page's own scroll height, so the immediate lane is eligible.
    await cdp.evaluate(installProseExpression(
      id,
      "position: fixed; top: 8px; left: 8px; width: 900px; background: #fff; z-index: 99;",
    ));
    await waitFor(cdp, settledExpression(id), 20000, "initial enhancement");
    await cdp.evaluate(armSamplerExpression(id));
    await new Promise((r) => setTimeout(r, 300));

    const linesBefore = await cdp.evaluate(
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length`,
    );
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).style.width = "360px"; return true; })()`,
    );
    await waitFor(
      cdp,
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length !== ${linesBefore}`,
      15000,
      "relayout at 360px",
    );
    const report = await collectReport(cdp);

    const stale = stalePaintedFrames(report);
    assert.equal(
      stale.length,
      0,
      `no painted frame may show stale overflow; got ${JSON.stringify(stale.slice(0, 5))}`,
    );
    assert.equal(report.events.destroy, 0, "step resize must not destroy the root");
    assert.ok(report.events.relayoutReady > 0, "width-only path must relayout in place");
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).remove(); return true; })()`,
    );
  });

  await t.test("fast drag keeps stale overflow off the screen", async () => {
    const id = "prepaint-drag";
    await cdp.evaluate(installProseExpression(
      id,
      "position: fixed; top: 8px; left: 8px; width: 900px; background: #fff; z-index: 99;",
    ));
    await waitFor(cdp, settledExpression(id), 20000, "initial enhancement");
    await cdp.evaluate(armSamplerExpression(id));
    await new Promise((r) => setTimeout(r, 300));

    // 12 steps of 90px mirror a fast drag, where each animation frame moves
    // the width by far more than the hanging-punctuation allowance; a
    // 40-step fine drag moves 13.5px per step and cannot distinguish stale
    // geometry from the allowance.
    await cdp.evaluate(`(async () => {
      const host = document.getElementById(${JSON.stringify(id + "-host")});
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      for (let i = 0; i <= 12; i++) {
        const t = i / 12;
        const w = t <= 0.5 ? 900 - (900 - 360) * (t * 2) : 360 + (900 - 360) * ((t - 0.5) * 2);
        host.style.width = Math.round(w) + "px";
        await wait(40);
      }
      return true;
    })()`);
    const report = await collectReport(cdp);

    // The immediate allowance is a budget, not a promise: a slice that
    // overruns it drops that step back to the scheduled lane, whose single
    // transient frame is today's behavior. A stray fallback across 13 steps
    // stays acceptable; every step regressing does not.
    const stale = stalePaintedFrames(report);
    assert.ok(
      stale.length <= 2,
      `at most 2 of 13 drag steps may fall back to the scheduled lane; got ${stale.length}: ` +
        JSON.stringify(stale.slice(0, 6)),
    );
    assert.equal(report.events.destroy, 0, "drag must not destroy the root");
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).remove(); return true; })()`,
    );
  });

  await t.test("rapid round-trip resize always converges", async () => {
    // CommittedMeasureLedger poisoning guard: a job that dies mid-flight
    // during a narrow pass leaves mixed-measure paragraphs; if the width
    // then returns to the previously committed cell, a ledger that still
    // holds that cell's signature would let the forced convergence pass
    // skip and strand the mix forever. The oscillation below re-creates the
    // round-trip; the assertion is about the settled end state only.
    const id = "prepaint-roundtrip";
    await cdp.evaluate(installProseExpression(
      id,
      "position: fixed; top: 8px; left: 8px; width: 900px; background: #fff; z-index: 99;",
    ));
    await waitFor(cdp, settledExpression(id), 20000, "initial enhancement");
    await cdp.evaluate(`(async () => {
      const host = document.getElementById(${JSON.stringify(id + "-host")});
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      for (let i = 0; i < 8; i++) {
        host.style.width = (i % 2 === 0) ? "360px" : "900px";
        await wait(30);
      }
      host.style.width = "900px";
      return true;
    })()`);
    await waitFor(
      cdp,
      `(() => {
        const prose = document.getElementById(${JSON.stringify(id)});
        const p = prose.querySelector("p");
        const ms = [...p.querySelectorAll(".tq-line")];
        if (ms.length < 2) return false;
        const cs = getComputedStyle(p);
        const avail = p.getBoundingClientRect().width -
          parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
        const engine = parseFloat(ms[0].dataset.tqLineWidth);
        return Math.abs(engine - Math.floor(avail / parseFloat(cs.fontSize)) * parseFloat(cs.fontSize)) <= 1;
      })()`,
      15000,
      "settled engine measure matching the final cell",
    );
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).remove(); return true; })()`,
    );
  });

  await t.test("off-screen root declines the immediate lane and still converges", async () => {
    const id = "prepaint-offscreen";
    await cdp.evaluate(installProseExpression(
      id,
      "width: 900px; margin: 16px auto; border: 1px solid transparent; position: absolute; top: 20000px;",
    ));
    await waitFor(cdp, settledExpression(id), 20000, "initial enhancement");
    await cdp.evaluate(armSamplerExpression(id));

    const linesBefore = await cdp.evaluate(
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length`,
    );
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).style.width = "360px"; return true; })()`,
    );
    // OffscreenDebounceGate holds the commit for its debounce window first.
    await waitFor(
      cdp,
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length !== ${linesBefore}`,
      20000,
      "deferred relayout of the off-screen root",
    );
    const report = await collectReport(cdp);
    assert.equal(report.events.destroy, 0, "off-screen fallback must not destroy the root");
    const lines = await cdp.evaluate(
      `document.getElementById(${JSON.stringify(id)}).querySelectorAll(".tq-line").length`,
    );
    assert.ok(lines > linesBefore, "narrower width must produce more lines");
    await cdp.evaluate(
      `(() => { document.getElementById(${JSON.stringify(id + "-host")}).remove(); return true; })()`,
    );
  });
});
