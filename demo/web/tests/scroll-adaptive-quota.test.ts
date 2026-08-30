import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpTarget,
  ScrollMetrics,
  CdpWsMessage,
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
    return new Promise<void>((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = (): void => resolve();
      this.ws.onerror = (err: Event): void => reject(err);
      this.ws.onmessage = (event: MessageEvent): void => {
        const msg = JSON.parse(String(event.data)) as CdpWsMessage;
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
    return new Promise((resolve, reject) => {
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
    await new Promise<void>((resolve) => setTimeout(resolve, 250));
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
    await new Promise<void>((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timeout waiting for browser remote debugging port on ${port}`);
}

test("Tiqian Scroll Adaptive Quota Test Suite", async (t: TestContext) => {
  const demoPort: number = 8993;
  const cdpPort: number = 9983;
  const demoUrl: string = `http://127.0.0.1:${demoPort}/`;

  let parcelProc: ChildProcess | null = null;
  let browserProc: ChildProcess | null = null;
  let client: CdpClient | null = null;

  try {
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
      demoUrl,
    ], {
      stdio: "ignore",
      detached: true,
    });

    await waitForCdpEndpoint(cdpPort, 15000);

    const listRes: Response = await fetch(`http://127.0.0.1:${cdpPort}/json/list`);
    const targets = (await listRes.json()) as CdpTarget[];
    const pageTarget: CdpTarget | undefined = targets.find((tr: CdpTarget) => tr.type === "page") || targets[0];
    assert.ok(pageTarget, "Must find an active browser page target");

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();

    await client.send("Page.enable");
    await client.send("Runtime.enable");

    // Scroll while the initial enhancement is still in flight, mirroring the
    // recorded hover-and-scroll session: visibility tier flips grant large
    // pending batches while the page keeps moving.
    await client.evaluate(`
      new Promise((resolve) => {
        if (document.readyState === "complete") {
          setTimeout(resolve, 50);
        } else {
          window.addEventListener("load", () => setTimeout(resolve, 50));
        }
      })
    `);

    await t.test("full-page scroll pass keeps frames and long tasks bounded while coverage completes", async () => {
      const metrics = await client!.evaluate<ScrollMetrics>(`
        (async () => {
          const proseElements = Array.from(document.querySelectorAll("tiqian-prose"));

          // 1. LongTask observer: a scroll pass flips visibility tiers, which
          // grants large pending batches. AdaptiveGrantQuota must keep the
          // committed batches small enough that native follow-up work stays
          // inside frame-sized windows.
          const longTasks = [];
          let longTaskObserver = null;
          try {
            longTaskObserver = new PerformanceObserver((list) => {
              for (const entry of list.getEntries()) {
                longTasks.push({
                  startTime: entry.startTime,
                  duration: entry.duration,
                });
              }
            });
            longTaskObserver.observe({ entryTypes: ["longtask"] });
          } catch (e) {
            // longtask observer not supported in this runtime
          }

          // 2. rAF frame monitor.
          const frameDeltas = [];
          let lastFrameTime = performance.now();
          let frameMonitoring = true;
          function onFrame(now) {
            if (!frameMonitoring) return;
            frameDeltas.push(now - lastFrameTime);
            lastFrameTime = now;
            requestAnimationFrame(onFrame);
          }
          requestAnimationFrame(onFrame);

          // 3. Event loop delay prober.
          const eventLoopDelays = [];
          let probing = true;
          const probeInterval = 8;
          let expectedProbeTime = performance.now() + probeInterval;
          function probeEventLoop() {
            if (!probing) return;
            const now = performance.now();
            eventLoopDelays.push(Math.max(0, now - expectedProbeTime));
            expectedProbeTime = now + probeInterval;
            setTimeout(probeEventLoop, probeInterval);
          }
          setTimeout(probeEventLoop, probeInterval);

          // 4. Scroll the full page down and up twice at wheel speed. Each
          // step crosses visibility boundaries, so roots keep flipping tiers
          // and the coordinator keeps granting while the page moves.
          const maxScroll = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
          const step = Math.max(40, Math.round(maxScroll / 48));
          const startTime = performance.now();
          let scrollEvents = 0;
          const scrollPass = (dir) => new Promise((resolve) => {
            let y = dir > 0 ? 0 : maxScroll;
            const advance = () => {
              y += dir * step;
              const done = dir > 0 ? y >= maxScroll : y <= 0;
              window.scrollTo(0, Math.min(maxScroll, Math.max(0, y)));
              scrollEvents += 1;
              if (done) {
                resolve();
                return;
              }
              setTimeout(advance, 16);
            };
            advance();
          });
          await scrollPass(1);
          await scrollPass(-1);
          await scrollPass(1);
          await scrollPass(-1);

          // Settle: deferred off-screen roots finish their trailing work.
          await new Promise((r) => setTimeout(r, 1500));
          const totalDuration = performance.now() - startTime;

          frameMonitoring = false;
          probing = false;
          longTaskObserver?.disconnect();

          const maxFrameDuration = frameDeltas.length > 0 ? Math.max(...frameDeltas) : 0;
          const meanFrameDuration = frameDeltas.length > 0
            ? frameDeltas.reduce((a, b) => a + b, 0) / frameDeltas.length
            : 0;
          const totalBlockingTime = longTasks.reduce((sum, tk) => sum + Math.max(0, tk.duration - 50), 0);
          const maxLongTaskDuration = longTasks.length > 0 ? Math.max(...longTasks.map((tk) => tk.duration)) : 0;
          const maxEventLoopDelay = eventLoopDelays.length > 0 ? Math.max(...eventLoopDelays) : 0;

          let totalParagraphs = 0;
          let enhancedParagraphs = 0;
          for (const prose of proseElements) {
            const ps = prose.querySelectorAll("p");
            totalParagraphs += ps.length;
            for (const p of ps) {
              if (p.getAttribute("data-tq-rendered") === "true") enhancedParagraphs += 1;
            }
          }

          return {
            scrollEvents,
            totalDuration,
            totalFrames: frameDeltas.length,
            meanFrameDuration,
            maxFrameDuration,
            longTaskCount: longTasks.length,
            maxLongTaskDuration,
            totalBlockingTime,
            maxEventLoopDelay,
            totalParagraphs,
            enhancedParagraphs,
          };
        })()
      `);

      console.log("\n=======================================================");
      console.log("   TIQIAN SCROLL ADAPTIVE QUOTA METRICS");
      console.log("=======================================================");
      console.log(`Scroll Steps                 : ${metrics.scrollEvents}`);
      console.log(`Total Scroll Duration        : ${metrics.totalDuration.toFixed(2)} ms`);
      console.log(`Rendered Frames Count        : ${metrics.totalFrames}`);
      console.log(`Mean Frame Interval          : ${metrics.meanFrameDuration.toFixed(2)} ms`);
      console.log(`Max Frame Stall Duration     : ${metrics.maxFrameDuration.toFixed(2)} ms`);
      console.log(`Long Tasks (>50ms) Count     : ${metrics.longTaskCount}`);
      console.log(`Max Long Task Duration       : ${metrics.maxLongTaskDuration.toFixed(2)} ms`);
      console.log(`Total Blocking Time (TBT)    : ${metrics.totalBlockingTime.toFixed(2)} ms`);
      console.log(`Max Event Loop Latency       : ${metrics.maxEventLoopDelay.toFixed(2)} ms`);
      console.log(`Enhanced Paragraph Coverage  : ${metrics.enhancedParagraphs}/${metrics.totalParagraphs} (100%)`);
      console.log("=======================================================\n");

      // ScrollAdaptiveQuotaGuard: scrolling flips visibility tiers and grants
      // large pending batches, the load where the recorded Firefox session
      // produced 5.4s of native follow-up long tasks (Gecko's per-node
      // accessibility accounting). Headless Chromium books that follow-up
      // far cheaper, so this suite is a coarse disaster gate, not a
      // quota-regulation probe: with the adaptive quota disabled the scroll
      // still measures only ~33ms stalls, which no honest threshold here
      // could separate from noise. The regulation itself is verified
      // frame-by-frame in platforms/web/client/web-component/coordinator.test.mjs. Measured
      // headless baseline with the adaptive quota active: 16.8-33.4ms max
      // frame stall, 25-46ms max event-loop latency.
      assert.ok(metrics.scrollEvents >= 120, "Must simulate a full multi-pass scroll");
      assert.strictEqual(
        metrics.enhancedParagraphs,
        metrics.totalParagraphs,
        "All paragraphs must reach 100% enhancement after scrolling settles",
      );
      assert.ok(
        metrics.maxFrameDuration < 250,
        `Max frame stall (${metrics.maxFrameDuration.toFixed(1)}ms) must stay bounded during scroll-driven enhancement`,
      );
      assert.ok(
        metrics.maxLongTaskDuration < 250,
        `Max long task (${metrics.maxLongTaskDuration.toFixed(1)}ms) must stay bounded during scroll-driven enhancement`,
      );
      assert.ok(
        metrics.totalBlockingTime < 1500,
        `Total blocking time (${metrics.totalBlockingTime.toFixed(1)}ms) must stay bounded across the scroll session`,
      );
      assert.ok(
        metrics.maxEventLoopDelay < 250,
        `Max event loop starvation (${metrics.maxEventLoopDelay.toFixed(1)}ms) must remain responsive while scrolling`,
      );
    });
  } finally {
    client?.close();
    if (browserProc?.pid) {
      try { process.kill(-browserProc.pid, "SIGKILL"); } catch {}
    }
    if (parcelProc?.pid) {
      try { process.kill(-parcelProc.pid, "SIGKILL"); } catch {}
    }
  }
});
