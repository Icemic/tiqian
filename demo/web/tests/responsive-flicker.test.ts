import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpTarget,
  FlickerReport,
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

interface LaunchedBrowserSession {
  cdp: CdpClient;
  chromeProc: ChildProcess | null;
  serverProc: ChildProcess | null;
}

async function ensureServerRunning(): Promise<ChildProcess | null> {
  try {
    const res: Response = await fetch("http://localhost:8888/", { method: "HEAD" });
    if (res.ok) return null;
  } catch {}

  const serverProc: ChildProcess = spawn("bun", ["run", "start"], {
    cwd: webDemoDir,
    stdio: "ignore",
  });

  for (let i = 0; i < 40; i++) {
    try {
      const res: Response = await fetch("http://localhost:8888/", { method: "HEAD" });
      if (res.ok) return serverProc;
    } catch {}
    await new Promise((r: (val: void) => void) => setTimeout(r, 200));
  }

  serverProc.kill();
  throw new Error("Failed to start web demo server on port 8888");
}

async function getOrLaunchBrowser(): Promise<LaunchedBrowserSession> {
  const serverProc: ChildProcess | null = await ensureServerRunning();

  let port: number = 9222;
  let chromeProc: ChildProcess | null = null;
  try {
    const res: Response = await fetch(`http://127.0.0.1:${port}/json/version`);
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
        const res: Response = await fetch(`http://127.0.0.1:${port}/json/version`);
        if (res.ok) break;
      } catch {}
      await new Promise((r: (val: void) => void) => setTimeout(r, 200));
    }
  }

  const listRes: Response = await fetch(`http://127.0.0.1:${port}/json/list`);
  const targets = (await listRes.json()) as CdpTarget[];
  let page: CdpTarget | undefined = targets.find((t: CdpTarget) => t.type === "page" && t.url.includes("localhost:8888"));

  if (!page) {
    const newTargetRes: Response = await fetch(`http://127.0.0.1:${port}/json/new?http://localhost:8888`, { method: "PUT" });
    page = (await newTargetRes.json()) as CdpTarget;
  }

  const cdp: CdpClient = new CdpClient(page.webSocketDebuggerUrl);
  await cdp.connect();
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  return { cdp, chromeProc, serverProc };
}

test("Tiqian Responsive Relayout In-Place Non-Flicker Test Suite", async (t: TestContext) => {
  const { cdp, chromeProc, serverProc } = await getOrLaunchBrowser();

  t.after((): void => {
    cdp.close();
    chromeProc?.kill();
    serverProc?.kill();
  });

  // Step 1: Wait for initial enhancement to settle completely on foreground articles
  await cdp.evaluate(`
    new Promise((resolve, reject) => {
      const startTime = performance.now();
      const check = () => {
        const proseElements = Array.from(document.querySelectorAll("tiqian-prose"));
        const foregroundSettled = proseElements.length >= 8 && proseElements.slice(0, 8).every((el) => {
          const ps = Array.from(el.querySelectorAll("p, li"));
          return ps.length > 0 && ps.every((p) => p.getAttribute("data-tq-rendered") === "true");
        });

        if (foregroundSettled) {
          resolve();
        } else if (performance.now() - startTime > 10000) {
          reject(new Error("Timed out waiting for initial prose enhancement"));
        } else {
          setTimeout(check, 50);
        }
      };
      check();
    })
  `);

  await t.test("Responsive resizing in place must never revert paragraphs to unrendered bare DOM", async () => {
    const flickerReport = await cdp.evaluate<FlickerReport>(`
      (async () => {
        const art0 = document.querySelector("article tiqian-prose");
        const ps = Array.from(art0.querySelectorAll("p"));
        
        let bareDomFlashes = 0;
        let mutationRecords = [];

        // Install MutationObserver ONLY during responsive resizing
        const observer = new MutationObserver((mutations) => {
          for (const m of mutations) {
            if (m.type === "attributes" && m.attributeName === "data-tq-rendered") {
              const currentVal = m.target.getAttribute("data-tq-rendered");
              if (currentVal !== "true") {
                bareDomFlashes++;
                mutationRecords.push({
                  type: "attribute_lost",
                  element: m.target.tagName,
                  currentVal,
                  text: m.target.textContent.slice(0, 30),
                });
              }
            } else if (m.type === "childList") {
              // If all lines are removed while data-tq-rendered is not true
              const hasLines = m.target.querySelector?.(".tq-line") !== null;
              const hasRenderedAttr = m.target.getAttribute?.("data-tq-rendered") === "true";
              if (!hasLines && !hasRenderedAttr && m.target.tagName === "P") {
                bareDomFlashes++;
                mutationRecords.push({
                  type: "children_reverted_to_plain",
                  element: m.target.tagName,
                  text: m.target.textContent.slice(0, 30),
                });
              }
            }
          }
        });

        for (const p of ps) {
          observer.observe(p, { attributes: true, childList: true });
        }

        // Change width smoothly across multiple values (simulating user dragging)
        const slider = document.getElementById("width-slider");
        const dragSteps = [1100, 950, 800, 670, 550, 420, 360, 480, 670];
        
        for (const w of dragSteps) {
          if (slider) {
            slider.value = String(w);
            slider.dispatchEvent(new Event("input", { bubbles: true }));
          }
          await new Promise((r) => setTimeout(r, 40));
        }

        if (slider) {
          slider.dispatchEvent(new Event("change", { bubbles: true }));
        }

        await new Promise((r) => setTimeout(r, 800));
        observer.disconnect();

        return {
          bareDomFlashes,
          mutationRecords,
        };
      })()
    `);

    assert.equal(
      flickerReport.bareDomFlashes,
      0,
      `Detected ${flickerReport.bareDomFlashes} bare-DOM flicker flashes during resize: ${JSON.stringify(flickerReport.mutationRecords)}`
    );
  });
});
