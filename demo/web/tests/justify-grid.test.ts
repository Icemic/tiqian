import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpTarget,
  DragStepProseInfo,
  ProseElementInfo,
  Width670ProseInfo,
  CdpWsMessage,
} from "./types.js";

const webDemoDir: string = fileURLToPath(new URL("..", import.meta.url));
const TOTAL_EXPECTED_PROSE_ELEMENTS: number = 12;

// A CDP response can be dropped silently when the page's execution context
// is destroyed mid-evaluate, leaving the caller pending forever and hanging
// the whole suite. Every remote call gets a hard deadline; a timeout fails
// the test with the culprit method instead of wedging.
const withTimeout = <T>(promise: Promise<T>, ms: number, label: string): Promise<T> =>
  Promise.race([
    promise,
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms),
    ),
  ]);

class CdpClient {
  wsUrl: string;
  ws: WebSocket | null = null;
  id: number = 0;
  pending: Map<number, CdpPendingCallback> = new Map();

  constructor(wsUrl: string) {
    this.wsUrl = wsUrl;
  }

  async connect(): Promise<void> {
    return withTimeout(new Promise<void>((resolve, reject) => {
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
    }), 15000, "cdp connect");
  }

  async send(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
    const id = ++this.id;
    return withTimeout(new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws!.send(JSON.stringify({ id, method, params }));
    }), 30000, `cdp ${method}`);
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

interface LaunchedBrowserSession {
  cdp: CdpClient;
  chromeProc: ChildProcess | null;
  serverProc: ChildProcess | null;
}

// Hermetic setup on dedicated ports, matching the other suite files. The old
// discovery form (reuse any server on 8888, reuse any browser on 9222, else
// spawn on 9444) let orphans from earlier runs poison this file: a stale
// server was reused with unknown build state, and an orphan holding the IPv4
// debug port made Chromium fall back to binding ::1 only, wedging the test.
const demoPort: number = 8990;
const cdpPort: number = 9980;
const demoUrl: string = `http://127.0.0.1:${demoPort}/`;

async function startDemoServer(): Promise<ChildProcess> {
  const portBusy: boolean = await fetch(demoUrl).then(() => true, () => false);
  if (portBusy) {
    throw new Error(`Port ${demoPort} is already in use; a leftover server must be stopped first`);
  }

  // --no-hmr: the suite server is cold when the page first loads, and a late
  // HMR push reloads the page mid-test, dropping in-flight CDP evaluate
  // responses. The test needs a static dev server, not live reloading.
  const serverProc: ChildProcess = spawn("npx", [
    "parcel",
    "index.html",
    "--port",
    String(demoPort),
    "--no-hmr",
    "--no-cache",
  ], {
    cwd: webDemoDir,
    stdio: "ignore",
    detached: true,
  });

  for (let i: number = 0; i < 120; i++) {
    try {
      const res: Response = await fetch(demoUrl, { method: "HEAD" });
      if (res.ok) return serverProc;
    } catch {}
    await new Promise<void>((r) => setTimeout(r, 250));
  }

  try { if (serverProc.pid) process.kill(-serverProc.pid, "SIGKILL"); } catch {}
  serverProc.kill();
  throw new Error(`Failed to start web demo server on port ${demoPort}`);
}

async function launchBrowserAndGetPage(): Promise<LaunchedBrowserSession> {
  const serverProc: ChildProcess = await startDemoServer();

  const chromeProc: ChildProcess = spawn("chromium", [
    "--headless=new",
    `--remote-debugging-port=${cdpPort}`,
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    demoUrl,
  ], { stdio: "ignore", detached: true });

  for (let i: number = 0; i < 75; i++) {
    try {
      const res: Response = await fetch(`http://127.0.0.1:${cdpPort}/json/version`);
      if (res.ok) break;
    } catch {}
    await new Promise<void>((r) => setTimeout(r, 200));
  }

  const listRes: Response = await withTimeout(fetch(`http://127.0.0.1:${cdpPort}/json/list`), 10000, "json/list");
  const targets = (await listRes.json()) as CdpTarget[];
  let page: CdpTarget | undefined = targets.find((t: CdpTarget) => t.type === "page" && t.url.includes(`127.0.0.1:${demoPort}`));

  if (!page) {
    const newTargetRes: Response = await withTimeout(
      fetch(`http://127.0.0.1:${cdpPort}/json/new?${demoUrl}`, { method: "PUT" }),
      10000,
      "json/new",
    );
    page = (await newTargetRes.json()) as CdpTarget;
  }

  const cdp: CdpClient = new CdpClient(page.webSocketDebuggerUrl);
  await cdp.connect();
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  return { cdp, chromeProc, serverProc };
}

test("Tiqian Justify and LineLengthGrid Quantization Test Suite", async (t: TestContext) => {
  const { cdp, chromeProc, serverProc } = await launchBrowserAndGetPage();

  t.after((): void => {
    cdp.close();
    // Group SIGKILL: a plain SIGTERM to the wrapper pid leaves the browser
    // alive holding its debug port, which poisons later runs.
    for (const proc of [chromeProc, serverProc]) {
      if (!proc?.pid) continue;
      try { process.kill(-proc.pid, "SIGKILL"); } catch {}
      try { process.kill(proc.pid, "SIGKILL"); } catch {}
    }
  });

  // Helper to wait for all prose elements to settle to current container width
  const waitForAllSettled = async (): Promise<void> => {
    await cdp.evaluate(`
      new Promise((resolve, reject) => {
        const startTime = performance.now();
        const check = () => {
          const proseElements = Array.from(document.querySelectorAll("tiqian-prose"));
          const allEnhanced = proseElements.length === ${TOTAL_EXPECTED_PROSE_ELEMENTS} &&
            proseElements.every((el) => el.getAttribute("data-tiqian-enhanced") === "true");
          
          const allRendered = allEnhanced && proseElements.every((el) => {
            const ps = Array.from(el.querySelectorAll("p, li"));
            return ps.length > 0 && ps.every((p) => p.getAttribute("data-tq-rendered") === "true");
          });

          // Check that every element's lines have adapted to its current container bounds
          const allAdapted = allRendered && proseElements.every((el) => {
            const containerWidth = el.getBoundingClientRect().width;
            const lines = Array.from(el.querySelectorAll(".tq-line"));
            return lines.every((l) => {
              const w = parseFloat(l.getAttribute("data-tq-line-width") || "0");
              return w <= containerWidth + 1.0;
            });
          });

          if (allAdapted) {
            resolve();
          } else if (performance.now() - startTime > 12000) {
            reject(new Error(\`Timed out waiting for all prose elements to settle to bounds\`));
          } else {
            setTimeout(check, 100);
          }
        };
        check();
      })
    `);
  };

  await waitForAllSettled();

  await t.test("All 12 prose elements and candidate paragraphs are 100% enhanced", async () => {
    const data = await cdp.evaluate<ProseElementInfo[]>(`
      Array.from(document.querySelectorAll("tiqian-prose")).map((el, i) => {
        const paragraphs = Array.from(el.querySelectorAll("p, li"));
        return {
          index: i,
          title: el.closest("article")?.querySelector("h2")?.textContent?.trim() ||
                 el.closest(".sidebar-box")?.querySelector("h3")?.textContent?.trim() || "unknown",
          enhanced: el.getAttribute("data-tiqian-enhanced"),
          paragraphTotal: paragraphs.length,
          renderedParagraphs: paragraphs.filter((p) => p.getAttribute("data-tq-rendered") === "true").length,
          paragraphsDetail: paragraphs.map((p) => {
            const lines = Array.from(p.querySelectorAll(".tq-line"));
            return {
              isRendered: p.getAttribute("data-tq-rendered") === "true",
              lineCount: lines.length,
              firstLineWidth: lines[0] ? parseFloat(lines[0].getAttribute("data-tq-line-width") || "0") : 0,
            };
          }),
        };
      })
    `);

    assert.equal(data.length, TOTAL_EXPECTED_PROSE_ELEMENTS, `Expected exactly ${TOTAL_EXPECTED_PROSE_ELEMENTS} prose elements`);
    
    for (const item of data) {
      assert.equal(item.enhanced, "true", `Element "${item.title}" (#${item.index}) must have data-tiqian-enhanced='true'`);
      assert.ok(item.paragraphTotal > 0, `Element "${item.title}" (#${item.index}) must contain paragraphs`);
      assert.equal(
        item.renderedParagraphs,
        item.paragraphTotal,
        `Element "${item.title}" (#${item.index}) must have 100% paragraphs rendered (got ${item.renderedParagraphs}/${item.paragraphTotal})`
      );

      for (const [pIdx, pDetail] of item.paragraphsDetail.entries()) {
        assert.equal(pDetail.isRendered, true, `Paragraph #${pIdx} in "${item.title}" must have data-tq-rendered='true'`);
        assert.ok(pDetail.lineCount >= 1, `Paragraph #${pIdx} in "${item.title}" must have at least 1 line box`);
        assert.ok(pDetail.firstLineWidth > 20, `Paragraph #${pIdx} in "${item.title}" first line width must be substantial, got ${pDetail.firstLineWidth}`);
      }
    }
  });

  await t.test("Rapid continuous multi-step dragging retains 100% enhancement and correct grid line widths", async () => {
    await cdp.evaluate(`
      (async () => {
        const slider = document.getElementById("width-slider");
        const steps = [1200, 1050, 900, 750, 600, 480, 360, 500, 700, 950, 1280, 1100, 850, 620, 360];
        
        for (const w of steps) {
          if (slider) {
            slider.value = String(w);
            slider.dispatchEvent(new Event("input", { bubbles: true }));
          }
          await new Promise((r) => setTimeout(r, 25));
        }
        
        if (slider) {
          slider.dispatchEvent(new Event("change", { bubbles: true }));
        }
      })()
    `);

    await waitForAllSettled();

    const result = await cdp.evaluate<DragStepProseInfo[]>(`
      Array.from(document.querySelectorAll("tiqian-prose")).map((el, i) => {
        const paragraphs = Array.from(el.querySelectorAll("p, li"));
        return {
          index: i,
          title: el.closest("article")?.querySelector("h2")?.textContent?.trim() ||
                 el.closest(".sidebar-box")?.querySelector("h3")?.textContent?.trim() || "unknown",
          enhanced: el.getAttribute("data-tiqian-enhanced"),
          paragraphCount: paragraphs.length,
          renderedCount: paragraphs.filter((p) => p.getAttribute("data-tq-rendered") === "true").length,
          containerWidth: el.getBoundingClientRect().width,
          paragraphsDetail: paragraphs.map((p) => {
            const lines = Array.from(p.querySelectorAll(".tq-line"));
            const computed = window.getComputedStyle(p);
            return {
              fontSize: parseFloat(computed.fontSize || "16"),
              isRendered: p.getAttribute("data-tq-rendered") === "true",
              lineCount: lines.length,
              lines: lines.map((l) => ({
                width: parseFloat(l.getAttribute("data-tq-line-width") || "0"),
                endReason: l.getAttribute("data-tq-line-end") || "AutoWrap",
              })),
            };
          }),
        };
      })
    `);

    assert.equal(result.length, TOTAL_EXPECTED_PROSE_ELEMENTS, `Must have all ${TOTAL_EXPECTED_PROSE_ELEMENTS} prose elements`);

    for (const item of result) {
      // 1. Unconditional enhancement check: must NEVER drop enhancement state on resize
      assert.equal(
        item.enhanced,
        "true",
        `Element "${item.title}" (#${item.index}) dropped data-tiqian-enhanced state after rapid dragging!`
      );

      // 2. Unconditional paragraph rendering check
      assert.equal(
        item.renderedCount,
        item.paragraphCount,
        `Element "${item.title}" (#${item.index}) lost rendered paragraphs (got ${item.renderedCount}/${item.paragraphCount})`
      );

      // 3. Line width bounds check: every line must fit within its current container width
      for (const [pIdx, pDetail] of item.paragraphsDetail.entries()) {
        assert.equal(pDetail.isRendered, true, `Paragraph #${pIdx} in "${item.title}" is not rendered`);
        assert.ok(pDetail.lineCount >= 1, `Paragraph #${pIdx} in "${item.title}" has 0 lines`);
        
        for (const [lIdx, line] of pDetail.lines.entries()) {
          assert.ok(
            line.width <= item.containerWidth + 1.0,
            `Paragraph #${pIdx} Line #${lIdx} in "${item.title}" line width (${line.width}px) exceeds container width (${item.containerWidth}px)`
          );
        }
      }
    }

    // Article 1 first line (pure CJK) must quantize to 272px (17 * 16px) inside ~278px content box
    const article1: DragStepProseInfo = result[0];
    assert.equal(
      Math.round(article1.paragraphsDetail[0].lines[0].width),
      272,
      `Article 1 first line must settle to 272px after rapid dragging, got ${article1.paragraphsDetail[0].lines[0].width}`
    );
  });

  await t.test("Width 670 dynamically recalculates line length grid across all cards", async () => {
    await cdp.evaluate(`
      (() => {
        const slider = document.getElementById("width-slider");
        if (slider) {
          slider.value = "670";
          slider.dispatchEvent(new Event("input", { bubbles: true }));
          slider.dispatchEvent(new Event("change", { bubbles: true }));
        }
      })()
    `);

    await waitForAllSettled();

    const result = await cdp.evaluate<Width670ProseInfo[]>(`
      Array.from(document.querySelectorAll("tiqian-prose")).map((el, i) => {
        const p = el.querySelector("p, li");
        const line = p?.querySelector(".tq-line");
        const computed = p ? window.getComputedStyle(p) : null;
        return {
          index: i,
          title: el.closest("article")?.querySelector("h2")?.textContent?.trim() ||
                 el.closest(".sidebar-box")?.querySelector("h3")?.textContent?.trim() || "unknown",
          containerWidth: el.getBoundingClientRect().width,
          enhanced: el.getAttribute("data-tiqian-enhanced"),
          isRendered: p?.getAttribute("data-tq-rendered") === "true",
          fontSize: parseFloat(computed?.fontSize || "16"),
          lineWidth: line ? parseFloat(line.getAttribute("data-tq-line-width") || "0") : 0,
          endReason: line?.getAttribute("data-tq-line-end") || "AutoWrap",
        };
      })
    `);

    assert.equal(result.length, TOTAL_EXPECTED_PROSE_ELEMENTS);
    for (const item of result) {
      assert.equal(item.enhanced, "true", `Element "${item.title}" (#${item.index}) must be enhanced at width 670`);
      assert.equal(item.isRendered, true, `Element "${item.title}" (#${item.index}) paragraph must be rendered at width 670`);
      assert.ok(item.lineWidth > 50, `Line width at 670px should be > 50px, got ${item.lineWidth}`);
      assert.ok(
        item.lineWidth <= item.containerWidth + 1.0,
        `Line width (${item.lineWidth}) in "${item.title}" must fit within container (${item.containerWidth})`
      );
    }
  });
});
