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

test("HostContentMutation: partial host edits and late paragraphs across viewport zones", async () => {
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

        globalThis.__state = (p) => ({
          rendered: p.getAttribute("data-tq-rendered"),
          lines: p.querySelectorAll("[data-tq-line-index]").length,
          childCount: p.childNodes.length,
          head: p.textContent.slice(0, 14),
        });

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
    // Phase A: framework-style text edits on rendered paragraphs in each
    // viewport zone are not observed.
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
        await new Promise((resolve) => setTimeout(resolve, 1500));
        const roots = Object.values(edited).map((e) => e.node.closest("tiqian-prose"));
        const idle = {};
        for (const [zone, e] of Object.entries(edited)) idle[zone] = __state(e.node);
        return { picks, before: Object.fromEntries(Object.entries(edited).map(([k, e]) => [k, e.before])), idle, idleEvents: __eventsFor(roots) };
      })()
    `);

    // HostMutationNotObserved: the element watches attribute mutations and
    // inline-size changes, not childList mutations on taken-over
    // paragraphs. A framework text edit lands as a raw text node under a
    // stale data-tq-rendered marker and no root reacts. This pins current
    // behavior; a future host-content signal should flip these assertions.
    for (const zone of ["inVp", "edge", "off"]) {
      const idle = mutations.idle[zone];
      assert.strictEqual(idle.lines, 0, `${zone}: edited paragraph must lose its rendered lines`);
      assert.strictEqual(idle.childCount, 1, `${zone}: edited paragraph must hold a single raw text node`);
      assert.strictEqual(
        idle.rendered,
        "true",
        `${zone}: data-tq-rendered stays stale after the host edit`,
      );
      assert.ok(
        idle.head.startsWith("宿主改写的运行期正文"),
        `${zone}: raw edited text must be the visible content`,
      );
    }
    assert.deepStrictEqual(
      mutations.idleEvents,
      [],
      `No ready/relayout-ready may target an edited root during idle: ${JSON.stringify(mutations.idleEvents)}`,
    );

    // ------------------------------------------------------------------
    // Phase B: the next width relayout re-renders from the cached custody
    // source, reverting the host edit while keeping every paragraph host.
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
    const reverted = await client.evaluate(`
      (async () => {
        const edited = globalThis.__edited;
        const allReverted = () => Object.values(edited).every((e) => {
          const state = __state(e.node);
          return state.rendered === "true" && state.lines > 0 && state.head === e.before.head;
        });
        const ok = await __waitFor(allReverted, 15000);
        await new Promise((resolve) => setTimeout(resolve, 600));
        const after = {};
        for (const [zone, e] of Object.entries(edited)) after[zone] = __state(e.node);
        return { ok, after, flagHistory: __stopFlagWatchers(), hosts: __hostsReport(__hostsBeforeRelayout) };
      })()
    `);

    // CustodySourceWinsOnWidthRelayout: the width path reuses the lowered
    // source held in custody instead of re-reading the live DOM, so the
    // host's edit is silently overwritten with the original text. The
    // reverted text is also the direct evidence that the relayout ran on
    // cached per-paragraph lowering rather than a fresh source scan.
    assert.ok(reverted.ok, `Edited paragraphs must re-render from custody: ${JSON.stringify(reverted.after)}`);
    for (const zone of ["inVp", "edge", "off"]) {
      const before = mutations.before[zone];
      const after = reverted.after[zone];
      assert.strictEqual(
        after.head,
        before.head,
        `${zone}: width relayout must restore the custody text over the host edit`,
      );
      assert.ok(after.lines > 0, `${zone}: reverted paragraph must be rendered again`);
    }

    // RelayoutKeepsParagraphHosts: width relayout swaps paragraph children
    // in place. Host nodes keep their identity, data-tq-rendered never
    // drops, so no reader sees a native-source teardown frame.
    assert.deepStrictEqual(
      reverted.hosts.missing,
      [],
      `Paragraph hosts must survive the width relayout: ${JSON.stringify(reverted.hosts)}`,
    );
    assert.ok(
      reverted.flagHistory.every((value) => value === "true"),
      `data-tq-rendered must never drop during relayout: ${JSON.stringify(reverted.flagHistory)}`,
    );

    // ------------------------------------------------------------------
    // Phase C: paragraphs appended after enhancement stay native until the
    // next geometry signal, then get adopted in every zone.
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
        await new Promise((resolve) => setTimeout(resolve, 1500));
        const added = () => Array.from(document.querySelectorAll("p[data-tq-host-added]"));
        const idle = Object.fromEntries(added().map((p) => [p.getAttribute("data-tq-host-added"), __state(p)]));
        return { picks, idle, idleEvents: __eventsFor(added().map((p) => p.closest("tiqian-prose"))) };
      })()
    `);

    // AppendedParagraphNotTracked: a late paragraph is outside the running
    // job, the tier observer set, and the width observer set, so neither
    // idle time nor scrolling it into view adopts it.
    for (const zone of ["inVp", "edge", "off"]) {
      assert.strictEqual(
        appends.idle[zone].rendered,
        null,
        `${zone}: appended paragraph must stay native after idle`,
      );
      assert.strictEqual(appends.idle[zone].lines, 0, `${zone}: appended paragraph must not render lines`);
    }
    assert.deepStrictEqual(
      appends.idleEvents,
      [],
      `No ready/relayout-ready may target an appended root during idle: ${JSON.stringify(appends.idleEvents)}`,
    );

    const afterScroll = await client.evaluate(`
      (async () => {
        const p = document.querySelector("p[data-tq-host-added=off]");
        p.scrollIntoView({ block: "center" });
        await new Promise((resolve) => setTimeout(resolve, 1500));
        const added = () => Array.from(document.querySelectorAll("p[data-tq-host-added]"));
        return {
          states: Object.fromEntries(added().map((p) => [p.getAttribute("data-tq-host-added"), __state(p)])),
          events: __eventsFor(added().map((p) => p.closest("tiqian-prose"))),
        };
      })()
    `);
    for (const [zone, state] of Object.entries(afterScroll.states)) {
      assert.strictEqual(
        state.rendered,
        null,
        `Scrolling an appended ${zone} paragraph into view must not adopt it by itself`,
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
    const adopted = await client.evaluate(`
      (async () => {
        const added = () => Array.from(document.querySelectorAll("p[data-tq-host-added]"));
        const allAdopted = () => added().length === 3 &&
          added().every((p) => p.getAttribute("data-tq-rendered") === "true");
        const ok = await __waitFor(allAdopted, 20000);
        await new Promise((resolve) => setTimeout(resolve, 600));
        const states = Object.fromEntries(added().map((p) => [p.getAttribute("data-tq-host-added"), __state(p)]));
        return { ok, states, hosts: __hostsReport(__hostsBeforeAdoption), renderedDrops: Array.from(document.querySelectorAll("tiqian-prose:not(.sidebar-prose) p")).filter((p) => p.getAttribute("data-tq-rendered") !== "true").length };
      })()
    `);

    // StrandedParagraphAdoptionOnNextGeometrySignal: the next width relayout
    // folds untracked source paragraphs into the job at the live width in
    // every viewport zone, with their content preserved from the live DOM.
    assert.ok(
      adopted.ok,
      `Appended paragraphs must be adopted after the width signal: ${JSON.stringify(adopted.states)}`,
    );
    for (const zone of ["inVp", "edge", "off"]) {
      const state = adopted.states[zone];
      assert.ok(state.lines >= 2, `${zone}: adopted paragraph must render multiple lines`);
      assert.ok(
        state.head.startsWith("新插入段[" + zone + "]"),
        `${zone}: adopted paragraph must keep its appended text`,
      );
    }
    assert.deepStrictEqual(
      adopted.hosts.missing,
      [],
      `Existing paragraph hosts must survive adoption relayout: ${JSON.stringify(adopted.hosts)}`,
    );
    assert.strictEqual(
      adopted.renderedDrops,
      0,
      "Every article paragraph must be rendered after adoption settles",
    );

    // ------------------------------------------------------------------
    // Phase D: a Svelte-style {@html} re-projection replaces every host
    // with a clone; the visible clones stay stale through a width relayout.
    // ------------------------------------------------------------------
    const replaced = await client.evaluate(`
      (async () => {
        const root = __roots()[6];
        const p = root.querySelectorAll("p")[0];
        if (p.firstElementChild) p.firstElementChild.setAttribute("data-tq-host-probe", "1");
        globalThis.__preReplacement = { root, p, before: __state(p) };
        root.innerHTML = root.innerHTML;
        __mark = performance.now();
        __log.length = 0;
        await new Promise((resolve) => setTimeout(resolve, 1200));
        const clone = root.querySelectorAll("p")[0];
        return {
          before: __preReplacement.before,
          oldHostDetached: !__preReplacement.p.isConnected,
          cloneIsNewNode: clone !== __preReplacement.p,
          idle: {
            ...__state(clone),
            probe: clone.firstElementChild?.getAttribute("data-tq-host-probe") ?? null,
          },
          idleEvents: __eventsFor([root]),
        };
      })()
    `);
    assert.ok(replaced.cloneIsNewNode, "innerHTML re-projection must replace paragraph hosts with clones");
    assert.ok(replaced.oldHostDetached, "The original paragraph hosts must be detached by the re-projection");
    assert.strictEqual(
      replaced.idle.probe,
      "1",
      "The visible clone must carry the copied rendered DOM after re-projection",
    );
    assert.strictEqual(
      replaced.idle.rendered,
      "true",
      "The clone keeps a stale data-tq-rendered marker",
    );

    await setViewportWidth(920);
    const staleClone = await client.evaluate(`
      (async () => {
        const root = globalThis.__preReplacement.root;
        const sawRelayout = await __waitFor(
          () => __log.some((entry) => entry.root === root && entry.type === "tiqian:relayout-ready"),
          15000,
        );
        await new Promise((resolve) => setTimeout(resolve, 800));
        const clone = root.querySelectorAll("p")[0];
        return {
          sawRelayout,
          state: __state(clone),
          probe: clone.firstElementChild?.getAttribute("data-tq-host-probe") ?? null,
        };
      })()
    `);

    // ReplacedRootMarkupStaysStale: the width relayout still reports
    // success, but it operates on the detached original hosts that the
    // runtime tracks. The visible clones are never re-laid-out, so the
    // probe marker survives and the stale geometry stays on screen.
    assert.ok(staleClone.sawRelayout, "The re-projected root must still emit relayout-ready");
    assert.strictEqual(
      staleClone.probe,
      "1",
      "The visible clone must stay untouched by the relayout (stale markup)",
    );
    assert.strictEqual(
      staleClone.state.rendered,
      "true",
      "The stale clone keeps its data-tq-rendered marker after the relayout",
    );
  } finally {
    client?.close();
    for (const proc of [browserProc, parcelProc]) {
      if (!proc?.pid) continue;
      try { process.kill(-proc.pid, "SIGKILL"); } catch {}
      try { process.kill(proc.pid, "SIGKILL"); } catch {}
    }
  }
});
