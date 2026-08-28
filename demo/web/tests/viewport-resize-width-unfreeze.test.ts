import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpTarget,
  EnhancementWaitResult,
  FinalCheckRoot,
  UnfreezeRootReport,
  UnfreezeSettleReport,
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

test("ViewportResizeWidthUnfreeze: rapid viewport widening keeps every prose root catching up", async (t: TestContext) => {
  const demoPort: number = 8995;
  const cdpPort: number = 9985;
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
    assert.ok(
      pageTarget,
      `Must find the blank page target among: ${targets.map((tr: CdpTarget) => `${tr.type}:${tr.url}`).join(", ")}`,
    );

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();

    await client.send("Page.enable");
    await client.send("Runtime.enable");

    // FrameTraceDiagnostics: the trace switch must exist before the first
    // tiqian frame, so it rides in ahead of every navigation.
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
      source: "globalThis.__tqTrace = { maxEntries: 800 };",
    });
    await client.send("Page.navigate", { url: demoUrl });
    await client.evaluate("0");

    // Start below the 860px sidebar breakpoint so widening reflows the
    // sidebar between stacked and column layouts and pushes prose roots
    // across viewport edges. A tall first viewport keeps every root on
    // screen for the initial enhancement pass.
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 700,
      height: 4000,
      deviceScaleFactor: 1,
      mobile: false,
    });

    await client.evaluate(`
      new Promise((resolve) => {
        if (document.readyState === "complete") {
          setTimeout(resolve, 800);
        } else {
          window.addEventListener("load", () => setTimeout(resolve, 800));
        }
      })
    `);

    const installInstrumentation = (): Promise<unknown> => client!.evaluate(`
      (() => {
        globalThis.__lastReadyAt = new Map();
        globalThis.__lastReadyWidth = new Map();
        globalThis.__readyTimeline = [];
        globalThis.__roundStartAt = 0;
        document.addEventListener("tiqian:relayout-ready", (event) => {
          const root = event.target;
          if (!root || !root.matches?.("tiqian-prose")) return;
          const at = performance.now();
          __lastReadyAt.set(root, at);
          __readyTimeline.push(at);
          __lastReadyWidth.set(root, root.getBoundingClientRect().width);
        });
      })()
    `);

    await installInstrumentation();

    const waitForEnhancement = async (): Promise<EnhancementWaitResult> => client!.evaluate<EnhancementWaitResult>(`
      (async () => {
        const collect = () => {
          const prose = Array.from(document.querySelectorAll("tiqian-prose"));
          const total = prose.reduce((acc, root) =>
            acc + root.querySelectorAll("p, li").length, 0);
          const done = prose.reduce((acc, root) =>
            acc + root.querySelectorAll("p[data-tq-rendered=true], li[data-tq-rendered=true]").length, 0);
          return { total, done };
        };
        const deadline = Date.now() + 45000;
        let lastDone = -1;
        let stalledPolls = 0;
        while (Date.now() < deadline) {
          const { total, done } = collect();
          if (total > 0 && done === total) return { total, done };
          if (done === lastDone) {
            stalledPolls += 1;
          } else {
            stalledPolls = 0;
            lastDone = done;
          }
          if (stalledPolls >= 40) {
            const prose = Array.from(document.querySelectorAll("tiqian-prose"));
            const detail = prose.slice(0, 4).map((root, i) => ({
              i,
              rendered: root.querySelectorAll("[data-tq-rendered=true]").length,
              paragraphs: root.querySelectorAll("p, li").length,
              capability: root.dataset.tiqianCapabilityIssue ?? null,
              loadMs: root.dataset.tiqianLoadMs ?? null,
            }));
            return { ...collect(), stalled: true, detail };
          }
          await new Promise((r) => setTimeout(r, 250));
        }
        return collect();
      })()
    `);

    // The enhancement queue used to drop every root below the fold when a
    // resize re-queued a responsive commit over the pending initial enhance
    // in the coordinator's single-slot deferred lane (OffscreenRequestQueue
    // fixed it). A reload restarts the pipeline and keeps this regression
    // test usable if another stall source ever appears.
    let initial: EnhancementWaitResult = await waitForEnhancement();
    let stallReports: number = 0;
    for (let attempt: number = 0; attempt < 2 && initial.stalled; attempt += 1) {
      stallReports += 1;
      // FrameTraceDiagnostics: capture the scheduling evidence while the
      // stalled page is still alive, before the reload resets it.
      const stallDump: unknown = await client.evaluate(`
        (() => {
          const prose = Array.from(document.querySelectorAll("tiqian-prose"));
          const capabilityMarks = Array.from(
            document.querySelectorAll("[data-tiqian-capability-issue]"),
          ).map((el) => {
            const root = el.closest("tiqian-prose");
            return {
              issue: el.getAttribute("data-tiqian-capability-issue"),
              rootIndex: root ? prose.indexOf(root) : -1,
            };
          });
          const trace = globalThis.__tqFrameTrace ?? [];
          return {
            roots: prose.map((root, i) => ({
              i,
              rendered: root.querySelectorAll("[data-tq-rendered=true]").length,
              paragraphs: root.querySelectorAll("p, li").length,
              dataset: { ...root.dataset },
            })),
            capabilityMarks,
            frameTraceTail: trace.slice(-40),
            frameTraceCount: trace.length,
          };
        })()
      `);
      console.log(`enhancement stall ${JSON.stringify(initial.detail)}`);
      console.log(`stall dump ${JSON.stringify(stallDump)}`);
      await client.send("Page.navigate", { url: demoUrl });
      await client.send("Emulation.setDeviceMetricsOverride", {
        width: 700,
        height: 4000,
        deviceScaleFactor: 1,
        mobile: false,
      });
      await client.evaluate(`
        new Promise((resolve) => {
          if (document.readyState === "complete") setTimeout(resolve, 800);
          else window.addEventListener("load", () => setTimeout(resolve, 800));
        })
      `);
      await installInstrumentation();
      initial = await waitForEnhancement();
    }
    assert.ok(
      !initial.stalled,
      `Initial enhancement stalled at ${initial.done}/${initial.total} across reloads: ` +
      JSON.stringify(initial.detail),
    );
    assert.strictEqual(
      initial.done,
      initial.total,
      `Initial enhancement must cover every paragraph (${initial.done}/${initial.total})`,
    );

    // Shrink the viewport height so later widening moves roots across the
    // viewport edge, then let the trailing relayout settle before dragging.
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 700,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 600));

    // Rapid widening at frame cadence with no settle pause between steps.
    // Each step changes real viewport width, which resizes prose roots
    // directly.
    const widenRapidly = async (fromWidth: number, toWidth: number, steps: number): Promise<void> => {
      for (let i: number = 1; i <= steps; i++) {
        const width: number = Math.round(fromWidth + (toWidth - fromWidth) * (i / steps));
        await client!.send("Emulation.setDeviceMetricsOverride", {
          width,
          height: 900,
          deviceScaleFactor: 1,
          mobile: false,
        });
        await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 16));
      }
    };

    const markRoundStart = (): Promise<unknown> => client!.evaluate(`
      (() => {
        globalThis.__roundStartAt = performance.now();
        globalThis.__readyTimeline = [];
      })()
    `);

    const settleAndReport = (label: string, dragEndAt: number): Promise<UnfreezeSettleReport> => client!.evaluate<UnfreezeSettleReport>(`
      (async (label, dragEndAt) => {
        await new Promise((r) => setTimeout(r, 2000));
        const prose = Array.from(document.querySelectorAll("tiqian-prose"));
        const report = prose.map((root, index) => ({
          index,
          sidebar: root.classList.contains("sidebar-prose"),
          width: root.getBoundingClientRect().width,
          readyDelta: (__lastReadyAt.get(root) ?? 0) - __roundStartAt,
          lastReadyWidth: __lastReadyWidth.get(root) ?? 0,
          capabilityIssue: root.dataset.tiqianCapabilityIssue ?? null,
        }));
        const windowEnd = dragEndAt + 500;
        let maxGap = 0;
        let prev = __roundStartAt;
        for (const at of globalThis.__readyTimeline) {
          if (at > windowEnd) break;
          maxGap = Math.max(maxGap, at - prev);
          prev = at;
        }
        maxGap = Math.max(maxGap, Math.min(windowEnd, performance.now()) - prev);
        return {
          label,
          report,
          eventCount: globalThis.__readyTimeline.length,
          maxEventGapMs: maxGap,
        };
      })(${JSON.stringify(label)}, ${dragEndAt})
    `);

    const dragEndClock = (): Promise<number> => client!.evaluate<number>("performance.now()");

    // Round 1: rapid widen across the breakpoint, then settle.
    await markRoundStart();
    await widenRapidly(700, 1400, 12);
    const round1: UnfreezeSettleReport = await settleAndReport("widen-700-1400", await dragEndClock());

    // Round 2: squeeze back under the breakpoint and widen again without
    // pauses. Roots flip between stacked and column placement, which swaps
    // them across viewport edges mid-flight.
    await markRoundStart();
    await widenRapidly(1400, 700, 8);
    await widenRapidly(700, 1400, 8);
    const round2: UnfreezeSettleReport = await settleAndReport("squeeze-widen-cycle", await dragEndClock());

    // Round 3: scroll to the bottom so lower roots re-enter the viewport,
    // then cross the 860px breakpoint both ways. The container caps at
    // 1280px, so widening past it must still cross the breakpoint on the
    // way back down for widths to keep moving.
    await client.evaluate(`
      (async () => {
        window.scrollTo(0, document.body.scrollHeight);
        await new Promise((r) => setTimeout(r, 300));
      })()
    `);
    await markRoundStart();
    await widenRapidly(1400, 820, 8);
    await widenRapidly(820, 1500, 10);
    const round3: UnfreezeSettleReport = await settleAndReport("scrolled-bottom-widen", await dragEndClock());

    for (const round of [round1, round2, round3]) {
      assert.ok(
        round.report.length > 0,
        `${round.label}: page must expose prose roots`,
      );
      // A root counts as caught up when it either finished a relayout inside
      // the round or its last committed width already matches the settled
      // width. LineLengthGridResponsiveInvalidation skips dispatching a
      // relayout when a squeeze-widen cycle returns to the committed measure,
      // so those roots correctly fire no event; a root frozen at its pre-drag
      // width fails the width comparison and is still flagged.
      const stalled: UnfreezeRootReport[] = round.report.filter(
        (r: UnfreezeRootReport) => r.readyDelta < 0
          && Math.abs(r.width - r.lastReadyWidth) >= 0.5
          && !r.capabilityIssue,
      );
      assert.strictEqual(
        stalled.length,
        0,
        `${round.label}: roots without a relayout-ready after the resize window began: ` +
          JSON.stringify(stalled),
      );
      const broken: UnfreezeRootReport[] = round.report.filter((r: UnfreezeRootReport) => r.capabilityIssue);
      assert.strictEqual(
        broken.length,
        0,
        `${round.label}: roots with capability issues: ` +
          JSON.stringify(broken),
      );
      console.log(`${round.label}: ${round.report.length} roots all caught up, ` +
        `${round.eventCount} events, max gap ${round.maxEventGapMs.toFixed(0)}ms`);
      // A mid-drag stall freezes every root at once, which shows up as one
      // long gap in the event timeline. Legitimate pauses (offscreen
      // debounce, job restarts) stay an order of magnitude shorter.
      assert.ok(
        round.maxEventGapMs < 1200,
        `${round.label}: relayout-ready gap of ${round.maxEventGapMs.toFixed(0)}ms ` +
        `during the round indicates a mid-drag stall`,
      );
    }

    // Width follow-through: after settling, every root's last relayout-ready
    // width must equal its current width. A frozen root keeps the width it
    // had before the drag.
    const finalCheck: FinalCheckRoot[] = await client.evaluate<FinalCheckRoot[]>(`
      (() => {
        const prose = Array.from(document.querySelectorAll("tiqian-prose"));
        return prose.map((root, index) => ({
          index,
          sidebar: root.classList.contains("sidebar-prose"),
          currentWidth: root.getBoundingClientRect().width,
          lastReadyWidth: __lastReadyWidth.get(root) ?? 0,
        }));
      })()
    `);
    const mismatched: FinalCheckRoot[] = finalCheck.filter(
      (r: FinalCheckRoot) => Math.abs(r.currentWidth - r.lastReadyWidth) >= 0.5,
    );
    assert.strictEqual(
      mismatched.length,
      0,
      `Roots whose last relayout width disagrees with the settled width: ` +
        JSON.stringify(mismatched),
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
