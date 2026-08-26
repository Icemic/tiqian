import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

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

async function waitForServer(url, timeoutMs = 20000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch {
      // retry
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Timeout waiting for demo server at ${url}`);
}

async function waitForCdpEndpoint(port, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return;
    } catch {
      // retry
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timeout waiting for browser remote debugging port on ${port}`);
}

test("HostContentMutation: content changes re-enter layout with correct frame discipline", async () => {
  const demoPort = 8997;
  const cdpPort = 9987;
  const demoUrl = `http://127.0.0.1:${demoPort}/`;

  let parcelProc = null;
  let browserProc = null;
  let client = null;

  try {
    // A leftover service on the port would silently serve a different page
    // build, so require the port to be free before starting parcel.
    const portBusy = await fetch(demoUrl).then(() => true, () => false);
    assert.ok(!portBusy, `Port ${demoPort} must be free before the test starts`);

    parcelProc = spawn("npx", [
      "parcel",
      "index.html",
      "--port",
      String(demoPort),
      "--no-cache",
    ], {
      cwd: webDemoDir,
      stdio: "ignore",
      detached: true,
    });

    await waitForServer(demoUrl, 30000);

    const chromeBin = process.env.CHROME_BIN || "chromium";
    browserProc = spawn(chromeBin, [
      "--headless=new",
      `--remote-debugging-port=${cdpPort}`,
      "--no-sandbox",
      "--disable-gpu",
      "--disable-dev-shm-usage",
      "about:blank",
    ], {
      stdio: "ignore",
      detached: true,
    });

    await waitForCdpEndpoint(cdpPort, 15000);

    const listRes = await fetch(`http://127.0.0.1:${cdpPort}/json/list`);
    const targets = await listRes.json();
    const pageTarget = targets.find(
      (tr) => tr.type === "page" && tr.url === "about:blank",
    );
    assert.ok(pageTarget, "Must find the blank page target");

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();

    await client.send("Page.enable");
    await client.send("Runtime.enable");
    await client.send("Page.navigate", { url: demoUrl });
    await client.evaluate("0");

    // Stay above the 860px sidebar breakpoint the whole run: width nudges
    // then change prose measure without restacking the sidebar, and the
    // 800px height leaves room for in-viewport, edge, and off-screen roots.
    const setViewportWidth = (width) => client.send("Emulation.setDeviceMetricsOverride", {
      width,
      height: 800,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await setViewportWidth(900);

    await client.evaluate(`
      new Promise((resolve) => {
        if (document.readyState === "complete") {
          setTimeout(resolve, 800);
        } else {
          window.addEventListener("load", () => setTimeout(resolve, 800));
        }
      })
    `);

    const initial = await client.evaluate(`
      (async () => {
        const roots = () => Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose)"));
        const collect = () => {
          const paras = roots().flatMap((root) => Array.from(root.querySelectorAll("p")));
          const done = paras.filter((p) => p.getAttribute("data-tq-rendered") === "true").length;
          return { total: paras.length, done };
        };
        const deadline = Date.now() + 45000;
        let lastDone = -1;
        let stalledPolls = 0;
        while (Date.now() < deadline) {
          const { total, done } = collect();
          if (total > 0 && done === total) return { total, done, stalled: false };
          if (done === lastDone) {
            stalledPolls += 1;
          } else {
            stalledPolls = 0;
            lastDone = done;
          }
          if (stalledPolls >= 40) return { ...collect(), stalled: true };
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        return { ...collect(), stalled: true };
      })()
    `);
    assert.ok(
      !initial.stalled && initial.done === initial.total,
      `Initial enhancement must cover every article paragraph: ${JSON.stringify(initial)}`,
    );

    await client.evaluate(`
      (() => {
        globalThis.__roots = () =>
          Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose)"));
        __roots().forEach((root, ri) => { root.dataset.ri = String(ri); });

        globalThis.__log = [];
        globalThis.__mark = performance.now();
        for (const type of ["tiqian:ready", "tiqian:relayout-ready"]) {
          document.addEventListener(type, (event) => {
            __log.push({ type, root: event.target, ri: event.target.dataset.ri ?? "?", t: Math.round(performance.now() - __mark) });
          });
        }
        globalThis.__eventsFor = (roots) =>
          __log.filter((entry) => roots.includes(entry.root))
            .map(({ type, ri, t }) => ({ type, ri, t }));
        globalThis.__eventCount = (root) =>
          __log.filter((entry) => entry.root === root).length;

        globalThis.__state = (p) => ({
          rendered: p.getAttribute("data-tq-rendered"),
          lines: p.querySelectorAll("[data-tq-line-index]").length,
          childCount: p.childNodes.length,
          head: p.textContent.slice(0, 14),
        });

        globalThis.__isRendered = (p) =>
          p.getAttribute("data-tq-rendered") === "true" &&
          p.querySelectorAll("[data-tq-line-index]").length > 0;

        // Viewport-zone classification over article roots. "edge" means the
        // root intersects the viewport without being fully inside it.
        globalThis.__zones = () => __roots().map((root, ri) => {
          const rect = root.getBoundingClientRect();
          const ih = innerHeight;
          const zone = (rect.bottom <= 0 || rect.top >= ih)
            ? "off"
            : (rect.top >= 0 && rect.bottom <= ih) ? "inVp" : "edge";
          return { ri, zone, below: rect.top >= ih, root, top: Math.round(rect.top), bottom: Math.round(rect.bottom) };
        });
        globalThis.__ensureZones = async () => {
          for (const offset of [innerHeight - 120, innerHeight - 60, 240, 360]) {
            const anchor = __zones().find((z) => z.ri >= 3) ?? __zones().at(-1);
            const top = anchor.root.getBoundingClientRect().top + scrollY;
            window.scrollTo(0, Math.max(0, Math.round(top - offset)));
            await new Promise((resolve) => setTimeout(resolve, 500));
            const zones = __zones();
            const pick = {
              inVp: zones.find((z) => z.zone === "inVp"),
              edge: zones.find((z) => z.zone === "edge"),
              off: zones.find((z) => z.zone === "off" && z.below),
            };
            if (pick.inVp && pick.edge && pick.off) return { zones, pick };
          }
          return null;
        };

        // Tier-gated roots only adopt viewport-near items first; scrolling a
        // pending paragraph into view lets the coordinator grant its tier.
        globalThis.__renderedWithScroll = async (paragraphs, timeoutMs) => {
          const deadline = Date.now() + timeoutMs;
          let scrolled = false;
          while (Date.now() < deadline) {
            if (paragraphs.every(__isRendered)) return { ok: true, scrolled };
            const pending = paragraphs.find((p) => !__isRendered(p));
            pending.scrollIntoView({ block: "center" });
            scrolled = true;
            await new Promise((resolve) => setTimeout(resolve, 400));
          }
          return { ok: paragraphs.every(__isRendered), scrolled };
        };

        globalThis.__hostsSnapshot = () => {
          const hosts = new Set();
          for (const root of __roots()) {
            for (const p of root.querySelectorAll("p")) hosts.add(p);
          }
          return hosts;
        };
        globalThis.__hostsReport = (before) => {
          let total = 0;
          const missing = [];
          __roots().forEach((root) => root.querySelectorAll("p").forEach((p, pi) => {
            total += 1;
            if (!before.has(p)) missing.push(root.dataset.ri + ":" + pi);
          }));
          return { total, missing };
        };

        globalThis.__watchRenderedFlags = () => {
          globalThis.__flagHistory = [];
          globalThis.__flagObservers = [];
          for (const root of __roots()) {
            for (const p of root.querySelectorAll("p")) {
              const observer = new MutationObserver(() => {
                __flagHistory.push(p.getAttribute("data-tq-rendered"));
              });
              observer.observe(p, { attributes: true, attributeFilter: ["data-tq-rendered"] });
              __flagObservers.push(observer);
            }
          }
        };
        globalThis.__stopFlagWatchers = () => {
          for (const observer of __flagObservers ?? []) observer.disconnect();
          globalThis.__flagObservers = [];
          return globalThis.__flagHistory.splice(0);
        };

        globalThis.__waitFor = async (predicate, timeoutMs) => {
          const deadline = Date.now() + timeoutMs;
          while (Date.now() < deadline) {
            if (predicate()) return true;
            await new Promise((resolve) => setTimeout(resolve, 150));
          }
          return predicate();
        };
      })()
    `);

    // ------------------------------------------------------------------
    // Phase E (runs first on a quiet page): engine self-observation must not
    // loop, and a single host edit converges to exactly one job.
    // ------------------------------------------------------------------
    const quiet = await client.evaluate(`
      (async () => {
        const root = __roots()[1];
        // The settle gate passes on fallback metrics; webfonts then load and
        // every root legitimately swaps fonts into a relayout. Only a page
        // whose event stream has been silent for a full second is settled.
        let quietFor = 0;
        let lastTotal = -1;
        const deadline = Date.now() + 20000;
        while (Date.now() < deadline) {
          const total = __log.length;
          if (total === lastTotal) {
            quietFor += 200;
            if (quietFor >= 1000) break;
          } else {
            quietFor = 0;
            lastTotal = total;
          }
          await new Promise((resolve) => setTimeout(resolve, 200));
        }
        const before = __eventCount(root);
        await new Promise((resolve) => setTimeout(resolve, 2500));
        return { before, afterIdle: __eventCount(root) };
      })()
    `);
    assert.strictEqual(
      quiet.afterIdle,
      quiet.before,
      `A settled root must not emit further jobs from its own render output: ${quiet.before} -> ${quiet.afterIdle}`,
    );

    const single = await client.evaluate(`
      (async () => {
        const root = __roots()[1];
        const p = root.querySelectorAll("p")[0];
        const before = { state: __state(p), count: __eventCount(root) };
        p.textContent = "单次编辑收敛段。" + "框架在运行期只改一次正文，引擎应当恰好调度一次重排并停止。" .repeat(2);
        const done = await __waitFor(() => __isRendered(p) && __state(p).head.startsWith("单次编辑收敛段"), 20000);
        await new Promise((resolve) => setTimeout(resolve, 2500));
        return {
          done,
          before: before.state,
          after: __state(p),
          newEvents: __eventCount(root) - before.count,
          events: __eventsFor([root]).slice(-4),
        };
      })()
    `);
    // MutationObserverDeliveryIsAsync: the edit is delivered in a microtask,
    // engine output is disproven by identity, and one reconcile job settles
    // the paragraph. The job's own writes must not schedule a second job.
    assert.ok(single.done, `The edited paragraph must re-render: ${JSON.stringify(single)}`);
    assert.strictEqual(
      single.newEvents,
      1,
      `A single host edit must produce exactly one job event (got ${single.newEvents}: ${JSON.stringify(single.events)})`,
    );

    // ------------------------------------------------------------------
    // Phase A: framework-style text edits on rendered paragraphs in each
    // viewport zone re-enter layout and preserve the edited text.
    // ------------------------------------------------------------------
    const zonesA = await client.evaluate("__ensureZones()");
    assert.ok(zonesA, "Must find in-viewport, edge, and off-screen article roots");
    assert.ok(
      zonesA.pick.inVp.ri !== zonesA.pick.edge.ri &&
        zonesA.pick.edge.ri !== zonesA.pick.off.ri &&
        zonesA.pick.inVp.ri !== zonesA.pick.off.ri,
      `Zone roots must be distinct: ${JSON.stringify({
        inVp: zonesA.pick.inVp.ri,
        edge: zonesA.pick.edge.ri,
        off: zonesA.pick.off.ri,
      })}`,
    );

    const mutations = await client.evaluate(`
      (async () => {
        const picks = { inVp: ${zonesA.pick.inVp.ri}, edge: ${zonesA.pick.edge.ri}, off: ${zonesA.pick.off.ri} };
        const edited = {};
        for (const [zone, ri] of Object.entries(picks)) {
          const p = __roots()[ri].querySelectorAll("p")[0];
          edited[zone] = { ri, node: p, before: __state(p) };
          p.textContent = "宿主改写的运行期正文" + zone + "。" + "这段文字由前端框架在增强完成后写进段落，用来检验引擎是否会跟随。" .repeat(2);
        }
        globalThis.__edited = edited;
        __mark = performance.now();
        __log.length = 0;
        const nodes = Object.values(edited).map((e) => e.node);
        const settle = await __renderedWithScroll(nodes, 20000);
        await new Promise((resolve) => setTimeout(resolve, 800));
        const idle = {};
        for (const [zone, e] of Object.entries(edited)) idle[zone] = __state(e.node);
        const roots = Object.values(edited).map((e) => e.node.closest("tiqian-prose"));
        return {
          picks,
          settle,
          before: Object.fromEntries(Object.entries(edited).map(([k, e]) => [k, e.before])),
          idle,
          idleEvents: __eventsFor(roots),
        };
      })()
    `);

    // HostMutationTriggersReconcile: the content observer turns the edit into
    // one reconcile job per root. The edited live text becomes the new custody
    // source, so the paragraph re-renders with the framework's text instead of
    // keeping a stale marker over a raw text node.
    assert.ok(mutations.settle.ok, `Edited paragraphs must re-render: ${JSON.stringify(mutations.idle)}`);
    for (const zone of ["inVp", "edge", "off"]) {
      const idle = mutations.idle[zone];
      assert.strictEqual(idle.rendered, "true", `${zone}: re-rendered paragraph must carry a fresh marker`);
      assert.ok(idle.lines > 0, `${zone}: re-rendered paragraph must have rendered lines`);
      assert.ok(
        idle.head.startsWith("宿主改写的运行期正文"),
        `${zone}: rendered output must use the edited text`,
      );
    }
    for (const zone of ["inVp", "edge", "off"]) {
      const events = mutations.idleEvents.filter((entry) => String(entry.ri) === String(mutations.picks[zone]));
      assert.ok(
        events.length >= 1,
        `${zone}: an edited root must emit at least one job event: ${JSON.stringify(mutations.idleEvents)}`,
      );
    }

    // ------------------------------------------------------------------
    // Phase B: a later width relayout re-renders from the NEW custody source,
    // preserving the host edit in every zone.
    // ------------------------------------------------------------------
    await client.evaluate(`
      (() => {
        __watchRenderedFlags();
        globalThis.__hostsBeforeRelayout = __hostsSnapshot();
        __mark = performance.now();
        __log.length = 0;
      })()
    `);
    await setViewportWidth(920);
    const preserved = await client.evaluate(`
      (async () => {
        const edited = globalThis.__edited;
        const allPreserved = () => Object.values(edited).every((e) => {
          const state = __state(e.node);
          return state.rendered === "true" && state.lines > 0 &&
            state.head.startsWith("宿主改写的运行期正文");
        });
        const ok = await __waitFor(allPreserved, 15000);
        await new Promise((resolve) => setTimeout(resolve, 600));
        const after = {};
        for (const [zone, e] of Object.entries(edited)) after[zone] = __state(e.node);
        return { ok, after, flagHistory: __stopFlagWatchers(), hosts: __hostsReport(__hostsBeforeRelayout) };
      })()
    `);

    // HostEditBecomesCustodySource: after reconcile, the edit is the semantic
    // source, so the width relayout re-breaks the edited text at the new
    // measure instead of reverting to pre-edit content.
    assert.ok(preserved.ok, `Width relayout must preserve host edits: ${JSON.stringify(preserved.after)}`);
    for (const zone of ["inVp", "edge", "off"]) {
      const after = preserved.after[zone];
      assert.ok(
        after.head.startsWith("宿主改写的运行期正文"),
        `${zone}: width relayout must keep the edited text`,
      );
      assert.ok(after.lines > 0, `${zone}: preserved paragraph must stay rendered`);
    }

    // RelayoutKeepsParagraphHosts: relayout swaps paragraph children in
    // place. Host nodes keep their identity, data-tq-rendered never drops,
    // so no reader sees a native-source teardown frame.
    assert.deepStrictEqual(
      preserved.hosts.missing,
      [],
      `Paragraph hosts must survive the width relayout: ${JSON.stringify(preserved.hosts)}`,
    );
    assert.ok(
      preserved.flagHistory.every((value) => value === "true"),
      `data-tq-rendered must never drop during relayout: ${JSON.stringify(preserved.flagHistory)}`,
    );

    // ------------------------------------------------------------------
    // Phase C: paragraphs appended after enhancement are adopted without any
    // geometry signal, in every zone.
    // ------------------------------------------------------------------
    const zonesC = await client.evaluate("__ensureZones()");
    assert.ok(zonesC, "Must re-find in-viewport, edge, and off-screen roots before appends");

    const appends = await client.evaluate(`
      (async () => {
        const picks = { inVp: ${zonesC.pick.inVp.ri}, edge: ${zonesC.pick.edge.ri}, off: ${zonesC.pick.off.ri} };
        for (const [zone, ri] of Object.entries(picks)) {
          const p = document.createElement("p");
          p.setAttribute("data-tq-host-added", zone);
          p.textContent = "新插入段[" + zone + "]。" + "这段正文在增强完成后由宿主框架追加，检验引擎能否发现并接管新段落，长度足以折出多行。" .repeat(2);
          __roots()[ri].appendChild(p);
        }
        __mark = performance.now();
        __log.length = 0;
        const added = () => Array.from(document.querySelectorAll("p[data-tq-host-added]"));
        const settle = await __renderedWithScroll(added(), 20000);
        await new Promise((resolve) => setTimeout(resolve, 800));
        return {
          picks,
          settle,
          states: Object.fromEntries(added().map((p) => [p.getAttribute("data-tq-host-added"), __state(p)])),
          events: __eventsFor(added().map((p) => p.closest("tiqian-prose"))),
        };
      })()
    `);

    // AppendedParagraphAdoptedOnContentSignal: the childList record on the
    // root reaches reconcile; the new paragraph enters the job as a stranded
    // candidate and is adopted at the live width without any width change.
    assert.ok(
      appends.settle.ok,
      `Appended paragraphs must be adopted without a geometry signal: ${JSON.stringify(appends.states)}`,
    );
    for (const zone of ["inVp", "edge", "off"]) {
      const state = appends.states[zone];
      assert.strictEqual(state.rendered, "true", `${zone}: adopted paragraph must be rendered`);
      assert.ok(state.lines >= 2, `${zone}: adopted paragraph must render multiple lines`);
      assert.ok(
        state.head.startsWith("新插入段[" + zone + "]"),
        `${zone}: adopted paragraph must keep its appended text`,
      );
    }

    await client.evaluate(`
      (() => {
        globalThis.__hostsBeforeAdoption = __hostsSnapshot();
        __mark = performance.now();
        __log.length = 0;
      })()
    `);
    await setViewportWidth(900);
    const settled = await client.evaluate(`
      (async () => {
        const allRendered = () => Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose) p"))
          .every((p) => p.getAttribute("data-tq-rendered") === "true");
        const ok = await __waitFor(allRendered, 20000);
        await new Promise((resolve) => setTimeout(resolve, 600));
        return {
          ok,
          hosts: __hostsReport(__hostsBeforeAdoption),
          renderedDrops: Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose) p")).filter((p) => p.getAttribute("data-tq-rendered") !== "true").length,
        };
      })()
    `);
    assert.ok(settled.ok, "Every article paragraph must be rendered after the width change settles");
    assert.deepStrictEqual(
      settled.hosts.missing,
      [],
      `Existing paragraph hosts must survive adoption relayout: ${JSON.stringify(settled.hosts)}`,
    );
    assert.strictEqual(
      settled.renderedDrops,
      0,
      "Every article paragraph must be rendered after adoption settles",
    );

    // ------------------------------------------------------------------
    // Phase D: a Svelte-style {@html} re-projection replaces every host with
    // a clone carrying engine scaffolding. The clone is de-scaffolded and
    // re-rendered from its live content.
    // ------------------------------------------------------------------
    const replaced = await client.evaluate(`
      (async () => {
        const root = __roots()[6];
        const p = root.querySelectorAll("p")[0];
        p.setAttribute("data-tq-host-probe", "1");
        globalThis.__preReplacement = { root, p, before: __state(p) };
        const beforeHtml = root.querySelectorAll("p")[0].outerHTML;
        root.innerHTML = root.innerHTML;
        __mark = performance.now();
        __log.length = 0;
        const clone0 = root.querySelectorAll("p")[0];
        const idleHtml0 = clone0.outerHTML;
        const settle = await __renderedWithScroll([root.querySelectorAll("p")[0]], 20000);
        await new Promise((resolve) => setTimeout(resolve, 800));
        const clone = root.querySelectorAll("p")[0];
        return {
          before: __preReplacement.before,
          beforeHtml,
          idleHtml0,
          oldHostDetached: !__preReplacement.p.isConnected,
          cloneIsNewNode: clone !== __preReplacement.p,
          settle,
          idle: {
            ...__state(clone),
            probe: clone.getAttribute("data-tq-host-probe"),
            html: clone.outerHTML,
          },
          idleEvents: __eventsFor([root]),
        };
      })()
    `);
    assert.ok(replaced.cloneIsNewNode, "innerHTML re-projection must replace paragraph hosts with clones");
    assert.ok(replaced.oldHostDetached, "The original paragraph hosts must be detached by the re-projection");

    // CloneDescaffoldedAndRerendered: the clone keeps stale engine markup at
    // first (its html matches the projection), then reconcile drops the dead
    // originals, strips the engine scaffolding from the clone, and re-renders
    // the live content. The host probe attribute must survive the round trip.
    assert.notStrictEqual(
      replaced.idle.html,
      replaced.idleHtml0,
      "The clone must be rewritten by the engine (de-scaffold plus re-render)",
    );
    assert.strictEqual(
      replaced.idle.probe,
      "1",
      "A host-authored attribute inside the re-projected content must survive re-rendering",
    );
    assert.strictEqual(replaced.idle.rendered, "true", "The re-rendered clone must carry a fresh marker");
    assert.ok(replaced.idle.lines > 0, "The re-rendered clone must have rendered lines");
    assert.strictEqual(
      replaced.idle.head,
      replaced.before.head,
      "The re-rendered clone must keep the projected text",
    );
    assert.ok(
      replaced.idleEvents.length >= 1,
      `The re-projected root must emit a job event: ${JSON.stringify(replaced.idleEvents)}`,
    );

    await setViewportWidth(920);
    const cloneAfterWidth = await client.evaluate(`
      (async () => {
        const root = globalThis.__preReplacement.root;
        const ok = await __waitFor(
          () => Array.from(root.querySelectorAll("p")).every((p) => p.getAttribute("data-tq-rendered") === "true"),
          15000,
        );
        await new Promise((resolve) => setTimeout(resolve, 800));
        const clone = root.querySelectorAll("p")[0];
        return {
          ok,
          state: __state(clone),
          probe: clone.getAttribute("data-tq-host-probe"),
        };
      })()
    `);
    assert.ok(cloneAfterWidth.ok, "The re-projected root must stay fully rendered after a width change");
    assert.strictEqual(
      cloneAfterWidth.state.head,
      replaced.before.head,
      "The re-projected paragraph must keep its text across the width change",
    );
    assert.strictEqual(cloneAfterWidth.probe, "1", "The host probe must survive the width change");

    // ------------------------------------------------------------------
    // Phase F: a content edit and a width change in the same task share one
    // commit; the content path wins and absorbs the width.
    // ------------------------------------------------------------------
    const sameFrame = await client.evaluate(`
      (async () => {
        const root = __roots()[2];
        const p = root.querySelectorAll("p")[0];
        const before = __state(p);
        const countBefore = __eventCount(root);
        // One synchronous task: shrink the root's inline size and rewrite the
        // paragraph. ResizeObserver and MutationObserver both see this task;
        // one commit must reconcile the content at the new live width.
        root.style.maxWidth = "560px";
        p.textContent = "同帧变更段。" + "宿主在同一个任务里改了宽度和正文，引擎必须按新宽度重排新文本。" .repeat(2);
        const done = await __waitFor(() => __isRendered(p) && __state(p).head.startsWith("同帧变更段"), 20000);
        await new Promise((resolve) => setTimeout(resolve, 800));
        root.style.maxWidth = "";
        await new Promise((resolve) => setTimeout(resolve, 800));
        return {
          done,
          before,
          after: __state(p),
          newEvents: __eventCount(root) - countBefore,
        };
      })()
    `);
    // ContentBeforeGeometry: the commit lane checks content first, so the
    // reconciled text can never be replaced by a width relayout of the old
    // custody text in the same frame.
    assert.ok(sameFrame.done, `Content must win the same-frame race: ${JSON.stringify(sameFrame)}`);
    assert.ok(sameFrame.after.head.startsWith("同帧变更段"), "The edited text must survive the same-frame width change");
    assert.ok(sameFrame.after.lines > 0, "The same-frame paragraph must be rendered");
    assert.ok(sameFrame.newEvents >= 1, "The same-frame change must emit at least one job event");

    // ------------------------------------------------------------------
    // Phase G: an in-place characterData edit inside engine output re-renders
    // from custody; the edit on renderer-owned text does not survive.
    // ------------------------------------------------------------------
    const inPlace = await client.evaluate(`
      (async () => {
        const root = __roots()[3];
        const p = root.querySelectorAll("p")[0];
        const before = __state(p);
        const countBefore = __eventCount(root);
        const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
        const textNode = walker.nextNode();
        const beforeData = textNode.data;
        textNode.data = "就地改写" + textNode.data;
        const reacted = await __waitFor(
          () => __isRendered(p) && __state(p).head === before.head,
          20000,
        );
        await new Promise((resolve) => setTimeout(resolve, 600));
        return {
          reacted,
          before,
          after: __state(p),
          newEvents: __eventCount(root) - countBefore,
          beforeData,
        };
      })()
    `);
    // TaintedEngineOutputRerendersFromCustody: a characterData record changes
    // no child list, so the taint set carries it to reconcile. The edited
    // node is renderer-owned; the semantic source in custody wins and the
    // paragraph re-renders with the original text.
    assert.ok(inPlace.reacted, `An in-place text edit must trigger a custody re-render: ${JSON.stringify(inPlace)}`);
    assert.ok(inPlace.after.lines > 0, "The custody re-render must produce lines");
    assert.strictEqual(inPlace.after.head, inPlace.before.head, "Custody text must win over the engine-output edit");
    assert.ok(inPlace.newEvents >= 1, "The characterData edit must emit a job event");

    // ------------------------------------------------------------------
    // Phase H: clear-then-insert in one task delivers both records together;
    // the engine must not wedge on the transient empty state.
    // ------------------------------------------------------------------
    const clearInsert = await client.evaluate(`
      (async () => {
        const root = __roots()[4];
        const p = root.querySelectorAll("p")[0];
        const before = __state(p);
        p.textContent = "";
        p.textContent = "清空重写段。" + "框架先清空再立即写回整段正文，两条记录合并成一次重排。" .repeat(2);
        const done = await __waitFor(() => __isRendered(p) && __state(p).head.startsWith("清空重写段"), 20000);
        await new Promise((resolve) => setTimeout(resolve, 600));
        return { done, before, after: __state(p) };
      })()
    `);
    assert.ok(clearInsert.done, `Clear-then-insert must converge on the new text: ${JSON.stringify(clearInsert)}`);
    assert.ok(clearInsert.after.lines > 0, "The rewritten paragraph must render lines");
    assert.ok(clearInsert.after.head.startsWith("清空重写段"), "The rewritten paragraph must keep the new text");

    // ------------------------------------------------------------------
    // Phase I: a host edit landing while a captured width relayout is in
    // flight cancels the stale job; the final DOM has the edited text at the
    // new width.
    // ------------------------------------------------------------------
    await setViewportWidth(900);
    await client.evaluate(`
      (async () => {
        const allRendered = () => Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose) p"))
          .every((p) => p.getAttribute("data-tq-rendered") === "true");
        await __waitFor(allRendered, 20000);
        await new Promise((resolve) => setTimeout(resolve, 400));
      })()
    `);
    await setViewportWidth(940);
    const midFlight = await client.evaluate(`
      (async () => {
        const root = __roots()[5];
        const p = root.querySelectorAll("p")[0];
        globalThis.__midFlightHosts = __hostsSnapshot();
        __watchRenderedFlags();
        // The whole-page relayout at 940 is still slicing; edit now.
        p.textContent = "飞行中改写段。" + "宿主在宽度重排进行中写入新正文，捕获量度的任务必须让位。" .repeat(2);
        const done = await __waitFor(() => __isRendered(p) && __state(p).head.startsWith("飞行中改写段"), 25000);
        const allRendered = await __waitFor(
          () => Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose) p"))
            .every((paragraph) => paragraph.getAttribute("data-tq-rendered") === "true"),
          25000,
        );
        await new Promise((resolve) => setTimeout(resolve, 800));
        return {
          done,
          allRendered,
          after: __state(p),
          flagHistory: __stopFlagWatchers(),
          hosts: __hostsReport(__midFlightHosts),
        };
      })()
    `);
    // MidFlightHostEditCancelsCapturedJob: the drift probe sees the identity
    // mismatch at the next frame, cancels the captured job, and reconcile
    // re-renders the edited text at the live width. The old behavior kept the
    // pre-edit custody and silently reverted the framework's write.
    assert.ok(midFlight.done, `A mid-flight edit must win over the captured job: ${JSON.stringify(midFlight)}`);
    assert.ok(midFlight.after.head.startsWith("飞行中改写段"), "The mid-flight edit must survive at the new width");
    assert.ok(midFlight.allRendered, "The page must settle fully rendered after the mid-flight edit");
    assert.ok(
      midFlight.flagHistory.every((value) => value === "true"),
      `data-tq-rendered must never observably drop across the mid-flight edit: ${JSON.stringify(midFlight.flagHistory)}`,
    );

    // ------------------------------------------------------------------
    // Phase K: framework-held custody references. React keeps the original
    // Text node and writes .data on it after takeover; Vue and Svelte remove
    // through child.parentNode. Both edits land inside the detached custody
    // fragment, where the live-DOM subtree observer never fires.
    // ------------------------------------------------------------------
    const custodyText = await client.evaluate(`
      (async () => {
        const root = __roots()[__roots().length - 1];
        const p = root.querySelectorAll("p")[0];
        const fragment = __tiqianRawDomFragment(p);
        if (!(fragment instanceof DocumentFragment)) {
          return { ok: false, why: "custody fragment not published", state: __state(p) };
        }
        const walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT);
        const textNode = walker.nextNode();
        if (!textNode) return { ok: false, why: "custody has no text node", state: __state(p) };
        const countBefore = __eventCount(root);
        textNode.data = "持引用改写段。" + "框架拿着原始文本节点的引用，在接管之后直接改写 data，引擎必须从 custody 重新排。" .repeat(2);
        const first = await __waitFor(
          () => __isRendered(p) && __state(p).head.startsWith("持引用改写段"),
          20000,
        );
        // The reconcile round trip restores the very same nodes and re-captures
        // them into a fresh fragment, so the framework-held reference survives.
        const secondFragment = __tiqianRawDomFragment(p);
        const referenceSurvived = secondFragment !== fragment && textNode.parentNode === secondFragment;
        textNode.data = "二次改写段。" + "同一份持有引用在第一轮重排之后再次被框架改写，引擎必须再次进入管线而不是吃掉这次写入。" .repeat(2);
        const second = await __waitFor(
          () => __isRendered(p) && __state(p).head.startsWith("二次改写段"),
          20000,
        );
        const countAfterSecond = __eventCount(root);
        await new Promise((resolve) => setTimeout(resolve, 2500));
        return {
          ok: true,
          first,
          referenceSurvived,
          second,
          after: __state(p),
          newEvents: countAfterSecond - countBefore,
          idleEvents: __eventCount(root) - countAfterSecond,
        };
      })()
    `);
    // CustodyCharacterDataIsHostCertain: the engine only moves whole nodes in
    // and out of custody, so a .data record there is host-authored. Both the
    // first write and the write through the surviving reference must re-render
    // the paragraph from the edited custody text.
    assert.ok(custodyText.ok, `Custody must be published for framework references: ${JSON.stringify(custodyText)}`);
    assert.ok(custodyText.first, `A custody .data write must re-render: ${JSON.stringify(custodyText)}`);
    assert.ok(
      custodyText.referenceSurvived,
      `The framework-held node must survive the reconcile round trip into the new custody: ${JSON.stringify(custodyText)}`,
    );
    assert.ok(custodyText.second, `A second write through the same held reference must re-render: ${JSON.stringify(custodyText.after)}`);
    assert.ok(custodyText.after.head.startsWith("二次改写段"), "The custody edit must become the rendered text");
    assert.ok(custodyText.after.lines > 0, "The custody re-render must produce lines");
    assert.ok(custodyText.newEvents >= 2, `Two custody writes must emit jobs (got ${custodyText.newEvents})`);
    assert.strictEqual(
      custodyText.idleEvents,
      0,
      `The custody lane must not loop on its own output: ${custodyText.idleEvents} events while idle`,
    );

    const custodyRemove = await client.evaluate(`
      (async () => {
        const root = __roots()[__roots().length - 1];
        const p = root.querySelectorAll("p")[1] ?? root.querySelectorAll("p")[0];
        // Build a multi-node paragraph through the live DOM first; after its
        // reconcile the custody fragment holds text, em, text.
        p.innerHTML = "多节点收纳段。" + "<em>强调片段。</em>" + "尾部文本。" + "填充正文长度以折出多行，保证重排可观测。" .repeat(2);
        const prepared = await __waitFor(
          () => __isRendered(p) && __state(p).head.startsWith("多节点收纳段"),
          20000,
        );
        const fragment = __tiqianRawDomFragment(p);
        const em = fragment && fragment.querySelector("em");
        if (!em) return { ok: false, why: "no em in custody", prepared, state: __state(p) };
        const countBefore = __eventCount(root);
        // Vue/Svelte removal shape: the node's live parent is looked up at
        // removal time, which resolves to the custody fragment.
        const removed = em.parentNode.removeChild(em);
        const done = await __waitFor(
          () => __isRendered(p) && !p.textContent.includes("强调片段"),
          20000,
        );
        await new Promise((resolve) => setTimeout(resolve, 800));
        return {
          ok: true,
          prepared,
          removedIsHeld: removed === em,
          done,
          after: __state(p),
          newEvents: __eventCount(root) - countBefore,
        };
      })()
    `);
    // CustodyChildListProvesIdentity: a custody childList record may be the
    // engine's own re-take, so the probe decides by node identity. A removal
    // through a held reference breaks the custody invariant and the paragraph
    // re-renders from the shortened custody content.
    assert.ok(custodyRemove.ok, `Multi-node custody must be reachable: ${JSON.stringify(custodyRemove)}`);
    assert.ok(custodyRemove.prepared, "The multi-node paragraph must render before the custody removal");
    assert.ok(custodyRemove.removedIsHeld, "The removal must resolve against the custody fragment");
    assert.ok(custodyRemove.done, `A custody removal must re-render without the removed node: ${JSON.stringify(custodyRemove.after)}`);
    assert.ok(
      !custodyRemove.after.head.includes("强调"),
      `The rendered text must drop the removed custody node: ${JSON.stringify(custodyRemove.after)}`,
    );
    assert.ok(custodyRemove.newEvents >= 1, "The custody removal must emit a job event");
  } finally {
    client?.close();
    for (const proc of [browserProc, parcelProc]) {
      if (!proc?.pid) continue;
      try { process.kill(-proc.pid, "SIGKILL"); } catch {}
      try { process.kill(proc.pid, "SIGKILL"); } catch {}
    }
  }
});
