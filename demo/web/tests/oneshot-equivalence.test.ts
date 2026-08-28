import test from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  DEEP_GEOMETRY_HELPERS,
  deepGeometryCounts,
  diffDeepGeometry,
} from "./helpers/deep-geometry.js";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpTarget,
  CompareRoundResult,
  CoverageResult,
  DeepGeometryReport,
  EvaluateCompareResult,
  SettleResult,
} from "./types.js";

const webDemoDir: string = fileURLToPath(new URL("..", import.meta.url));

class CdpClient {
  wsUrl: string;
  ws: WebSocket | null = null;
  id: number = 0;
  pending: Map<number, CdpPendingCallback> = new Map();

  constructor(wsUrl: string) {
    this.wsUrl = wsUrl;
  }

  async connect(): Promise<void> {
    return new Promise((resolve: () => void, reject: (err: unknown) => void) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = (): void => resolve();
      this.ws.onerror = (err: Event): void => reject(err);
      this.ws.onmessage = (event: MessageEvent): void => {
        const msg = JSON.parse(String(event.data)) as { id?: number; error?: { message?: string }; result?: unknown };
        if (msg.id && this.pending.has(msg.id)) {
          const { resolve: res, reject: rej } = this.pending.get(msg.id)!;
          this.pending.delete(msg.id);
          if (msg.error) {
            rej(new Error(msg.error.message || JSON.stringify(msg.error)));
          } else {
            res(msg.result);
          }
        }
      };
    });
  }

  async send(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
    const id = ++this.id;
    return new Promise((resolve: (val: unknown) => void, reject: (err: unknown) => void) => {
      this.pending.set(id, { resolve, reject });
      this.ws!.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate<T = unknown>(expression: string): Promise<T> {
    const res = (await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    })) as CdpEvaluateResponse<T>;
    if (res.exceptionDetails) {
      throw new Error(`Runtime exception: ${JSON.stringify(res.exceptionDetails)}`);
    }
    return res.result?.value as T;
  }

  close(): void {
    this.ws?.close();
  }
}

async function waitForServer(url: string, timeoutMs: number = 20000): Promise<void> {
  const start: number = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res: Response = await fetch(url);
      if (res.ok) return;
    } catch {
      // retry
    }
    await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 250));
  }
  throw new Error(`Timeout waiting for demo server at ${url}`);
}

async function waitForCdpEndpoint(port: number, timeoutMs: number = 15000): Promise<void> {
  const start: number = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res: Response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return;
    } catch {
      // retry
    }
    await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 200));
  }
  throw new Error(`Timeout waiting for browser remote debugging port on ${port}`);
}

test("OneShotEquivalence: coordinated output equals a fresh one-shot enhance at settled states", async () => {
  const demoPort: number = 8999;
  const cdpPort: number = 9989;
  const demoUrl: string = `http://127.0.0.1:${demoPort}/`;

  let parcelProc: ChildProcess | null = null;
  let browserProc: ChildProcess | null = null;
  let client: CdpClient | null = null;

  try {
    // A leftover service on the port would silently serve a different page
    // build, so require the port to be free before starting parcel.
    const portBusy: boolean = await fetch(demoUrl).then(() => true, () => false);
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

    const chromeBin: string = process.env.CHROME_BIN || "chromium";
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

    const listRes: Response = await fetch(`http://127.0.0.1:${cdpPort}/json/list`);
    const targets = (await listRes.json()) as CdpTarget[];
    const pageTarget: CdpTarget | undefined = targets.find(
      (tr: CdpTarget) => tr.type === "page" && tr.url === "about:blank",
    );
    assert.ok(pageTarget, "Must find the blank page target");

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();

    await client.send("Page.enable");
    await client.send("Runtime.enable");

    // FaithfulOptionsOracle: the one-shot must replay the exact options each
    // tiqian-prose element resolved for the coordinated run, including
    // cjkDashCapability. Calling TiqianWeb.enhance with empty options changes
    // the dash capability evidence and produces different capability markers.
    // The element publishes the resolved options on each root's dataset
    // (tiqianEnhanceOptions) per coordination run, the public successor of the
    // retired document event channel (ADR 0053 C1); the capture is read back
    // from the live DOM instead of a listener.

    await client.send("Page.navigate", { url: demoUrl });
    await client.evaluate("0");

    const setViewportWidth = (width: number): Promise<unknown> => client!.send("Emulation.setDeviceMetricsOverride", {
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

    // Helpers injected once. The fingerprint serializes, for every paragraph
    // host in every tiqian-prose root, the host's full attribute set and the
    // complete rendered subtree: element tags, every attribute, sorted inline
    // style declarations, and text nodes. This covers everything the engine
    // applies to the DOM: per-line geometry attributes (range, advance width,
    // break reason), the --tq-line-height / --tq-line-baseline-offset custom
    // properties, per-run justification micro-adjustments (letter-spacing and
    // margin values, including negative half-width punctuation compression),
    // spacing-carrier runs, dash replay evidence attributes, punctuation
    // ink-floor/body-width evidence, display substitutions with data-tq-src,
    // cloned semantic spans, engine <br> elements, hard-break markers, the
    // selection-end sentinel, SVG interlinear decorations, and the
    // capability-issue markers on gated paragraphs. Root-level counters
    // (data-tiqian-issue-count and friends) accumulate across the session's
    // history by design and stay outside the per-paragraph comparison.
    await client.evaluate(`
      (() => {
        globalThis.__roots = () => Array.from(document.querySelectorAll("tiqian-prose"));

        // Options oracle read back from the element dataset that each
        // coordination run writes (tiqianEnhanceOptions). Roots without a
        // captured record are skipped.
        globalThis.__optionsByRoot = () => {
          const map = new Map();
          for (const el of document.querySelectorAll("tiqian-prose")) {
            const raw = el.dataset.tiqianEnhanceOptions;
            if (!raw) continue;
            map.set(el, JSON.parse(raw));
          }
          return map;
        };

        globalThis.__paras = () =>
          __roots().flatMap((root) => Array.from(root.querySelectorAll("p, li")));

        const styleOf = (el) => {
          const out = [];
          for (let i = 0; i < el.style.length; i++) {
            const prop = el.style[i];
            out.push([prop, el.style.getPropertyValue(prop), el.style.getPropertyPriority(prop)]);
          }
          out.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
          return out;
        };

        const serialize = (node) => {
          if (node.nodeType === 3) return { t: node.data };
          if (node.nodeType !== 1) return { other: node.nodeType };
          const el = node;
          const attrs = {};
          for (const attribute of el.attributes) attrs[attribute.name] = attribute.value;
          return { tag: el.tagName, at: attrs, st: styleOf(el), ch: Array.from(el.childNodes, serialize) };
        };

        globalThis.__fingerprint = () => {
          const out = {};
          __roots().forEach((root, ri) => {
            Array.from(root.querySelectorAll("p, li")).forEach((p, pi) => {
              const key = ri + ":" + pi + (p.hasAttribute("data-tq-host-added") ? ":" + p.getAttribute("data-tq-host-added") : "");
              out[key] = {
                host: { tag: p.tagName, at: Object.fromEntries(Array.from(p.attributes, (a) => [a.name, a.value])) },
                dom: Array.from(p.childNodes, serialize),
                lineCount: p.querySelectorAll("[data-tq-line-index]").length,
              };
            });
          });
          return out;
        };

        const firstPath = (a, b, path) => {
          if (JSON.stringify(a) === JSON.stringify(b)) return null;
          const nodeLike = (x) => x && typeof x === "object" && (x.tag !== undefined || x.t !== undefined);
          if (nodeLike(a) && nodeLike(b)) {
            if (a.tag !== b.tag || a.t !== b.t) return path + "<" + (a.tag ?? a.t) + " vs " + (b.tag ?? b.t) + ">";
            if (JSON.stringify(a.at) !== JSON.stringify(b.at)) {
              for (const k of new Set([...Object.keys(a.at ?? {}), ...Object.keys(b.at ?? {})])) {
                if ((a.at?.[k] ?? null) !== (b.at?.[k] ?? null)) {
                  return path + "<" + a.tag + "@" + k + ": " + String(a.at?.[k]).slice(0, 50) + " vs " + String(b.at?.[k]).slice(0, 50) + ">";
                }
              }
            }
            if (JSON.stringify(a.st) !== JSON.stringify(b.st)) {
              const flat = (st) => Object.fromEntries((st ?? []).map(([k, v, pri]) => [k, v + (pri ? "!" : "")]));
              const sa = flat(a.st);
              const sb = flat(b.st);
              for (const k of new Set([...Object.keys(sa), ...Object.keys(sb)])) {
                if (sa[k] !== sb[k]) return path + "<" + a.tag + "!" + k + ": " + sa[k] + " vs " + sb[k] + ">";
              }
            }
            const count = Math.max(a.ch?.length ?? 0, b.ch?.length ?? 0);
            for (let i = 0; i < count; i++) {
              const divergence = firstPath(a.ch?.[i], b.ch?.[i], path + a.tag + "[" + i + "]/");
              if (divergence) return divergence;
            }
            return path + "<childCount " + (a.ch?.length ?? 0) + " vs " + (b.ch?.length ?? 0) + ">";
          }
          return path + "<value " + JSON.stringify(a)?.slice(0, 50) + " vs " + JSON.stringify(b)?.slice(0, 50) + ">";
        };

        globalThis.__diff = (a, b) => {
          const issues = [];
          for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
            const x = a[k];
            const y = b[k];
            if (!x || !y) {
              issues.push({ k, kind: "missing", coordinated: !!x, oneshot: !!y });
              continue;
            }
            if (JSON.stringify(x) === JSON.stringify(y)) continue;
            issues.push({
              k,
              kind: JSON.stringify(x.host) === JSON.stringify(y.host) ? "dom" : "host",
              linesCoordinated: x.lineCount,
              linesOneshot: y.lineCount,
              hostPath: firstPath(x.host, y.host, "host/"),
              domPath: firstPath(x.dom, y.dom, ""),
            });
            if (issues.length >= 10) break;
          }
          return issues;
        };

        // Terminal state: every paragraph is either rendered or carries a
        // capability-issue marker (dash and ellipsis paragraphs are gated
        // native in the browser metrics path). Layout is settled when the
        // terminal state holds and the fingerprint stopped changing.
        globalThis.__settle = async (timeoutMs) => {
          const deadline = Date.now() + timeoutMs;
          let prev = JSON.stringify(__fingerprint());
          let stable = 0;
          let pending = [];
          while (Date.now() < deadline && stable < 3) {
            await new Promise((resolve) => setTimeout(resolve, 350));
            pending = __paras().filter((p) =>
              p.getAttribute("data-tq-rendered") !== "true" &&
              !p.hasAttribute("data-tiqian-capability-issue"));
            const cur = JSON.stringify(__fingerprint());
            stable = pending.length === 0 && cur === prev ? stable + 1 : 0;
            prev = cur;
          }
          globalThis.__settledFp = JSON.parse(prev);
          return {
            settled: stable >= 3,
            pending: pending.slice(0, 4).map((p) => p.textContent.slice(0, 10)),
            fpCount: Object.keys(__settledFp).length,
            liveCount: __paras().length,
          };
        };

        globalThis.__oneshot = () => {
          const t0 = performance.now();
          for (const [root, options] of __optionsByRoot()) {
            __tiqianOneShot(root, options);
          }
          return Math.round(performance.now() - t0);
        };

        globalThis.__relayoutErrors = () =>
          Array.from(document.querySelectorAll("[data-tq-relayout-error]"))
            .map((el) => el.getAttribute("data-tq-relayout-error"));

        ${DEEP_GEOMETRY_HELPERS}
      })()
    `);

    // One comparison round: settle at the current width, serialize the
    // coordinated output, replay a one-shot enhance with each root's captured
    // options, then require identical serializations and a quiet page.
    const compareRound = async (label: string, settleMs: number): Promise<CompareRoundResult> => {
      const settled = await client!.evaluate<SettleResult>(`__settle(${settleMs})`);
      assert.ok(
        settled.settled,
        `${label}: state must settle before comparing (pending=${JSON.stringify(settled.pending)} fp=${settled.fpCount} live=${settled.liveCount})`,
      );
      assert.strictEqual(
        settled.fpCount,
        settled.liveCount,
        `${label}: fingerprint must cover every live paragraph host`,
      );

      const geoBefore = await client!.evaluate<DeepGeometryReport>("__deepGeometry()");
      const ms = await client!.evaluate<number>("__oneshot()");
      const { issues, quiet, errors, geoAfter } = await client!.evaluate<EvaluateCompareResult>(`
        (async () => {
          const issues = __diff(__settledFp, __fingerprint());
          const before = JSON.stringify(__fingerprint());
          await new Promise((resolve) => setTimeout(resolve, 800));
          return {
            issues,
            quiet: JSON.stringify(__fingerprint()) === before,
            errors: __relayoutErrors(),
            geoAfter: __deepGeometry(),
          };
        })()
      `);

      // CoordinatedStateMatchesOneShotOracle: the incremental pipeline
      // (coordinator grants, viewport tiers, raw-dom records, width relayout)
      // and a fresh one-shot enhance over the same roots with the same
      // resolved options must produce the same paragraph DOM: line geometry,
      // justification spacing, evidence attributes, substitutions,
      // decorations, text, and capability markers all agree exactly at every
      // settled width, for initial content and after host DOM changes.
      assert.deepStrictEqual(
        issues,
        [],
        `${label}: one-shot output must equal coordinated output (${ms}ms): ${JSON.stringify(issues.slice(0, 2))}`,
      );
      assert.deepStrictEqual(
        errors,
        [],
        `${label}: no relayout errors may be recorded`,
      );

      // OneShotLeavesNoCoordinatorAftershock: the one-shot replaces the
      // coordination state, so no deferred grant or observer callback may
      // rewrite any paragraph after it returns.
      assert.ok(quiet, `${label}: output must stay unchanged after the one-shot`);

      // MeasuredRunBoxesMatchOneShotOracle: the fingerprint compares what the
      // engine wrote into the DOM; this check measures the physical boxes
      // with getBoundingClientRect and Range. Root, paragraph, line, run
      // element, and text-node boxes must agree between the coordinated and
      // one-shot outputs, so an attribute-correct render that lands on the
      // wrong physical geometry still fails.
      const geoDiff = diffDeepGeometry(geoBefore, geoAfter);
      const geoCounts = deepGeometryCounts(geoBefore);
      assert.deepStrictEqual(
        geoDiff.examples,
        [],
        `${label}: measured boxes must equal one-shot output (${geoDiff.boxesCompared} boxes, ${geoDiff.divergentBoxes} divergent): ${JSON.stringify(geoDiff.examples.slice(0, 3))}`,
      );
      assert.ok(
        geoCounts.lineMarks > 0 && geoCounts.runEls > 0 && geoCounts.textNodes > 0,
        `${label}: box comparison must cover real line markers, runs, and text nodes: ${JSON.stringify(geoCounts)}`,
      );
      return { count: settled.fpCount!, ms, boxes: geoDiff.boxesCompared };
    };

    // ------------------------------------------------------------------
    // Phase 1: initial content, all elements, across widths that cross the
    // sidebar breakpoint in both directions (900 > 860 > 700, then 1400).
    // ------------------------------------------------------------------
    const phase1: (CompareRoundResult & { width: number })[] = [];
    for (const width of [900, 700, 1400]) {
      await setViewportWidth(width);
      phase1.push({ width, ...(await compareRound(`initial@${width}`, 45000)) });
    }

    // ------------------------------------------------------------------
    // Phase 2: host DOM changes, then the same comparison at further
    // widths. The appended paragraphs deliberately cover three pipelines:
    // dash/ellipsis capability gating (stays native with markers), pair
    // punctuation compression with bracket kinsoku (renders with negative
    // letter-spacing), and CJK/Latin autospace (renders with carriers).
    // Edits stay to supported operations: appending paragraphs and removing
    // them. A textContent rewrite of a taken-over paragraph is not observed
    // by the element (pinned by host-content-mutation Phase A), so it has no
    // equality contract between the coordinated and one-shot paths.
    // ------------------------------------------------------------------
    await client.evaluate(`
      (() => {
        const roots = __roots();
        const append = (ri, name, text) => {
          const p = document.createElement("p");
          p.setAttribute("data-tq-host-added", name);
          p.textContent = text;
          roots[ri].appendChild(p);
        };
        append(0, "dash",
          "追加破折段。「这怎么可能？！」他失声道——《规范》里从未写过这样的结局……可文件末尾分明盖着「不予受理」的印章。" .repeat(2));
        append(3, "compress",
          "追加挤压段。「什么？！！不对。」她连连摇头：『怎么会这样？？你确定？？』众人面面相觑；谁也不敢先开口：「我不知道！真的不知道！」（毕竟，《规则》写得清清楚楚。）" .repeat(2));
        append(6, "mixed",
          "追加混排段。Chrome 的 Canvas API 提供了 measureText() 方法，HarfBuzz 则负责 shaping；中西文之间需要 autospace，数字 3.14 与 95% 保持 Latin 字体。" .repeat(2));
        append(2, "quote",
          "追加引文段。白居易《琵琶行》：『千呼万唤始出来，犹抱琵琶半遮面。』转轴拨弦三两声，未成曲调先有情；弦弦掩抑声声思，似诉平生不得志。" .repeat(2));
        roots[5].querySelectorAll("p")[1].remove();
      })()
    `);

    const phase2: (CompareRoundResult & { width: number })[] = [];
    for (const width of [940, 700, 1400]) {
      await setViewportWidth(width);
      phase2.push({ width, ...(await compareRound(`after-dom-change@${width}`, 45000)) });
    }

    // Phase 2 coverage: the compared surface must actually exercise the
    // targeted pipelines, otherwise the equality above is vacuous.
    const coverage = await client.evaluate<CoverageResult>(`
      (() => {
        const byName = (name) => document.querySelector("p[data-tq-host-added=" + name + "]");
        const dash = byName("dash");
        const compress = byName("compress");
        const mixed = byName("mixed");
        let negativeSpacing = 0;
        let positiveSpacing = 0;
        // Spacing declarations reach the DOM either as inline styles or as
        // scoped value-style classes (tqvr-*) backed by a shared style
        // element; resolved styles cover both carriers.
        for (const el of document.querySelectorAll("tiqian-prose span")) {
          const value = parseFloat(getComputedStyle(el).letterSpacing);
          if (!Number.isFinite(value) || value === 0) continue;
          if (value < 0) negativeSpacing += 1; else positiveSpacing += 1;
        }
        return {
          optionsCaptured: __optionsByRoot().size,
          roots: __roots().length,
          dashIssue: dash?.getAttribute("data-tiqian-capability-issue") ?? null,
          dashNative: dash?.getAttribute("data-tq-rendered") === null,
          compressRendered: compress?.getAttribute("data-tq-rendered") === "true",
          compressLines: compress?.querySelectorAll("[data-tq-line-index]").length ?? 0,
          mixedRendered: mixed?.getAttribute("data-tq-rendered") === "true",
          mixedLines: mixed?.querySelectorAll("[data-tq-line-index]").length ?? 0,
          pageCarriers: document.querySelectorAll("tiqian-prose [data-tq-spacing-carrier]").length,
          negativeSpacing,
          positiveSpacing,
        };
      })()
    `);

    // PunctuationPipelineCoverage: the dash paragraph must end in the named
    // capability-gated native state with identical markers on both paths,
    // the compression and autospace paragraphs must render real geometry,
    // and compression runs must exist page-wide as compared micro-adjustments.
    assert.strictEqual(coverage.optionsCaptured, coverage.roots,
      "Every root must have captured enhance options for the oracle");
    assert.ok(coverage.dashNative, "Dash paragraph must stay native (capability-gated)");
    assert.strictEqual(coverage.dashIssue, "NoConformingCjkDashGlyph",
      `Dash paragraph must carry the named capability issue: ${coverage.dashIssue}`);
    assert.ok(coverage.compressRendered && coverage.compressLines >= 2,
      `Compression paragraph must render multiple lines: ${JSON.stringify(coverage)}`);
    assert.ok(coverage.mixedRendered && coverage.mixedLines >= 2,
      `Autospace paragraph must render multiple lines: ${JSON.stringify(coverage)}`);
    assert.ok(coverage.pageCarriers >= 1,
      `Selectable spacing carriers must exist page-wide: ${JSON.stringify(coverage)}`);
    assert.ok(coverage.negativeSpacing > 0,
      `Punctuation compression runs (negative letter-spacing) must exist: ${JSON.stringify(coverage)}`);
    assert.ok(coverage.positiveSpacing > 0,
      `Justification stretch runs (positive letter-spacing) must exist: ${JSON.stringify(coverage)}`);
    assert.strictEqual(
      phase2[0].count,
      phase1[0].count + 3,
      `Paragraph count must reflect -1 removal +4 appends: initial ${phase1[0].count}, after changes ${phase2[0].count}`,
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
