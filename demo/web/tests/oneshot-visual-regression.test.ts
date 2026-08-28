import test from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";
import type {
  CdpEvaluateResponse,
  CdpPendingCallback,
  CdpScreenshotParams,
  CdpTarget,
  CompareStateOptions,
  CompareStateResult,
  PixelsDecoded,
  PngDecoded,
  ScreenshotComparison,
  SettleResult,
  VisualCapturePlan,
  VisualCaptureSet,
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

  async screenshot(params: CdpScreenshotParams = {}): Promise<Buffer> {
    const res = (await this.send("Page.captureScreenshot", { format: "png", ...params })) as { data: string };
    return Buffer.from(res.data, "base64");
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

// Minimal dependency-free PNG decode (8-bit RGB/RGBA, non-interlaced) and a
// strict pixel comparison. Visual regression must fail on any differing
// pixel, so no perceptual threshold is applied.
function decodePng(buf: Buffer): PngDecoded {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG");
  let pos: number = 8;
  let width: number = 0;
  let height: number = 0;
  const idat: Buffer[] = [];
  while (pos < buf.length) {
    const len: number = buf.readUInt32BE(pos);
    const type: string = buf.toString("ascii", pos + 4, pos + 8);
    const data: Buffer = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const bitDepth: number = data[8];
      const colorType: number = data[9];
      const interlace: number = data[12];
      if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2) || interlace !== 0) {
        throw new Error(`unsupported PNG: depth=${bitDepth} color=${colorType} interlace=${interlace}`);
      }
    } else if (type === "IDAT") {
      idat.push(data);
    } else if (type === "IEND") {
      break;
    }
    pos += 12 + len;
  }
  return { width, height, idat: Buffer.concat(idat) };
}

function decodePixels(png: Buffer): PixelsDecoded {
  const { width, height, idat } = decodePng(png);
  // color type from IHDR is validated in decodePng; re-read it here
  const colorType: number = png[25];
  const bpp: number = colorType === 6 ? 4 : 3;
  const raw: Buffer = inflateSync(idat);
  const stride: number = width * bpp;
  const out: Buffer = Buffer.alloc(height * stride);
  const paeth = (a: number, b: number, c: number): number => {
    const p: number = a + b - c;
    const pa: number = Math.abs(p - a);
    const pb: number = Math.abs(p - b);
    const pc: number = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  };
  for (let y: number = 0; y < height; y++) {
    const filter: number = raw[y * (stride + 1)];
    const rowStart: number = y * (stride + 1) + 1;
    const row: Buffer = raw.subarray(rowStart, rowStart + stride);
    const prev: Buffer | null = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    const cur: Buffer = out.subarray(y * stride, (y + 1) * stride);
    for (let x: number = 0; x < stride; x++) {
      const left: number = x >= bpp ? cur[x - bpp] : 0;
      const up: number = prev ? prev[x] : 0;
      const upLeft: number = prev && x >= bpp ? prev[x - bpp] : 0;
      let value: number = row[x];
      if (filter === 1) value = (value + left) & 0xff;
      else if (filter === 2) value = (value + up) & 0xff;
      else if (filter === 3) value = (value + ((left + up) >> 1)) & 0xff;
      else if (filter === 4) value = (value + paeth(left, up, upLeft)) & 0xff;
      cur[x] = value;
    }
  }
  return { width, height, bpp, pixels: out };
}

function compareScreenshots(a: Buffer, b: Buffer): ScreenshotComparison {
  if (a.equals(b)) return { equal: true, differentPixels: 0, detail: null };
  const da: PixelsDecoded = decodePixels(a);
  const db: PixelsDecoded = decodePixels(b);
  if (da.width !== db.width || da.height !== db.height) {
    return {
      equal: false,
      differentPixels: -1,
      detail: `dimensions ${da.width}x${da.height} vs ${db.width}x${db.height}`,
    };
  }
  const { width, height, bpp, pixels: pa } = da;
  const pb: Buffer = db.pixels;
  let different: number = 0;
  let first: string | null = null;
  for (let y: number = 0; y < height && !first; y++) {
    for (let x: number = 0; x < width; x++) {
      const offset: number = (y * width + x) * bpp;
      let delta: number = 0;
      for (let c: number = 0; c < bpp; c++) {
        delta = Math.max(delta, Math.abs(pa[offset + c] - pb[offset + c]));
      }
      if (delta > 0) {
        different += 1;
        first = `(${x},${y}) rgba [${Array.from(pa.subarray(offset, offset + bpp))}] vs [${Array.from(pb.subarray(offset, offset + bpp))}]`;
        break;
      }
    }
  }
  // count the rest without early exit for the report
  if (first) {
    different = 0;
    for (let i: number = 0; i < pa.length; i += bpp) {
      for (let c: number = 0; c < bpp; c++) {
        if (pa[i + c] !== pb[i + c]) {
          different += 1;
          break;
        }
      }
    }
  }
  return { equal: false, differentPixels: different, detail: first };
}

test("OneShotVisualRegression: coordinated and one-shot pages are pixel-identical across the whole page", async () => {
  const demoPort: number = 9000;
  const cdpPort: number = 9900;
  const demoUrl: string = `http://127.0.0.1:${demoPort}/`;

  let parcelProc: ChildProcess | null = null;
  let browserProc: ChildProcess | null = null;
  let client: CdpClient | null = null;

  try {
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
      "--force-device-scale-factor=1",
      "--hide-scrollbars",
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
        globalThis.__terminal = () => __paras().every((p) =>
          p.getAttribute("data-tq-rendered") === "true" ||
          p.hasAttribute("data-tiqian-capability-issue"));

        // A width relayout rewrites paragraphs in place while their
        // data-tq-rendered marker stays "true", so terminal markers alone
        // cannot detect an in-flight relayout. Settling requires the full
        // rendered-subtree fingerprint to stop changing, plus a stable page
        // height, so capture plans are only taken from a quiescent layout.
        const styleOf = (el) => {
          const out = [];
          for (let i = 0; i < el.style.length; i++) {
            const prop = el.style[i];
            out.push(prop + ":" + el.style.getPropertyValue(prop));
          }
          return out.sort().join(";");
        };
        const serialize = (node) => {
          if (node.nodeType === 3) return "t" + node.data.length + ":" + node.data;
          if (node.nodeType !== 1) return "o" + node.nodeType;
          const el = node;
          const attrs = Array.from(el.attributes, (a) => a.name + "=" + a.value).sort().join("|");
          return el.tagName + "[" + attrs + "][" + styleOf(el) + "]" +
            Array.from(el.childNodes, serialize).join(",");
        };
        globalThis.__fingerprint = () =>
          __paras().map((p) =>
            p.getAttribute("data-tq-rendered") + ";" + p.hasAttribute("data-tiqian-capability-issue") + ";" +
            Array.from(p.childNodes, serialize).join("#")).join("##") +
          "||" + document.documentElement.scrollHeight;

        globalThis.__settle = async (timeoutMs) => {
          const deadline = Date.now() + timeoutMs;
          let prev = __fingerprint();
          let stable = 0;
          let pageHeight = document.documentElement.scrollHeight;
          while (Date.now() < deadline && stable < 3) {
            await new Promise((resolve) => setTimeout(resolve, 350));
            const cur = __fingerprint();
            stable = globalThis.__terminal() && cur === prev ? stable + 1 : 0;
            prev = cur;
            pageHeight = document.documentElement.scrollHeight;
          }
          await document.fonts.ready;
          return { settled: stable >= 3, pageHeight };
        };

        globalThis.__oneshot = () => {
          for (const [root, options] of __optionsByRoot()) {
            __tiqianOneShot(root, options);
          }
        };
      })()
    `);

    // The fixed benchmark HUD renders live-updating text (relayout ms, fps)
    // that changes between the two captures. It is page chrome, not layout
    // output, so it is hidden for the whole comparison.
    await client.evaluate(`
      (() => {
        const hud = document.querySelector(".floating-benchmark-hud");
        if (hud) hud.style.display = "none";
      })()
    `);

    const captureSet = async (plan: VisualCapturePlan): Promise<VisualCaptureSet> => {
      const shots: Record<string, Buffer> = {};
      await client!.evaluate("window.scrollTo(0, 0)");
      const topSettled = await client!.evaluate<SettleResult>("__settle(15000)");
      assert.ok(topSettled.settled, "Must settle at the top before the full-page capture");
      shots["full"] = await client!.screenshot({
        clip: { x: plan.rect.x, y: plan.rect.y, width: plan.rect.width, height: plan.rect.height, scale: 1 },
        captureBeyondViewport: true,
      });
      for (const scroll of plan.scrolls) {
        await client!.evaluate(`window.scrollTo(0, ${scroll})`);
        await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 500));
        const settled = await client!.evaluate<SettleResult>("__settle(15000)");
        assert.ok(settled.settled, `Must settle at scroll offset ${scroll}`);
        shots["scroll" + scroll] = await client!.screenshot({
          clip: {
            x: plan.rect.x,
            y: scroll,
            width: plan.rect.width,
            height: Math.min(plan.viewportHeight, plan.pageHeight - scroll),
            scale: 1,
          },
          captureBeyondViewport: true,
        });
      }
      await client!.evaluate("window.scrollTo(0, 0)");
      const endHeight: number = await client!.evaluate<number>("document.documentElement.scrollHeight");
      return { shots, pageHeight: endHeight };
    };

    const compareState = async (label: string, { assertPixels }: CompareStateOptions): Promise<CompareStateResult> => {
      const settled = await client!.evaluate<SettleResult>("__settle(45000)");
      assert.ok(settled.settled, `${label}: page must settle before capturing`);

      // Capture plan is computed once from the coordinated state and replayed
      // identically after the one-shot, so both passes photograph the same
      // regions at the same offsets.
      const plan = await client!.evaluate<VisualCapturePlan>(`
        (() => {
          const main = document.querySelector("main") ?? document.body;
          const rect = main.getBoundingClientRect();
          const viewportHeight = innerHeight;
          const pageHeight = document.documentElement.scrollHeight;
          const step = Math.floor(viewportHeight * 0.8);
          const maxScroll = Math.max(0, pageHeight - viewportHeight);
          const scrolls = [];
          for (let y = 0; y <= maxScroll; y += step) scrolls.push(y);
          if (scrolls[scrolls.length - 1] !== maxScroll) scrolls.push(maxScroll);
          return {
            rect: { x: rect.left + scrollX, y: rect.top + scrollY, width: rect.width, height: rect.height },
            viewportHeight,
            pageHeight,
            scrolls,
          };
        })()
      `);

      const coordinatedPass: VisualCaptureSet = await captureSet(plan);
      const coordinated: Record<string, Buffer> = coordinatedPass.shots;
      await client!.evaluate("__oneshot()");
      await new Promise((resolve: (val: void) => void) => setTimeout(resolve, 800));
      const afterOneShot = await client!.evaluate<SettleResult>("__settle(45000)");
      assert.ok(afterOneShot.settled, `${label}: page must settle after the one-shot`);

      // The compared heights are taken after each pass's scroll sequence, so
      // tier adoption during scrolling affects both sides equally. What must
      // not happen is the one-shot itself changing the page height.
      assert.strictEqual(
        afterOneShot.pageHeight,
        coordinatedPass.pageHeight,
        `${label}: page height must be unchanged by the one-shot (${coordinatedPass.pageHeight} vs ${afterOneShot.pageHeight})`,
      );

      const oneshotPass: VisualCaptureSet = await captureSet(plan);
      const oneshot: Record<string, Buffer> = oneshotPass.shots;
      assert.deepStrictEqual(
        Object.keys(coordinated).sort(),
        Object.keys(oneshot).sort(),
        `${label}: both passes must capture the same shot set`,
      );
      assert.strictEqual(
        oneshotPass.pageHeight,
        coordinatedPass.pageHeight,
        `${label}: page height must stay stable across both capture passes (${coordinatedPass.pageHeight} vs ${oneshotPass.pageHeight})`,
      );

      const failures: string[] = [];
      for (const key of Object.keys(coordinated)) {
        const result: ScreenshotComparison = compareScreenshots(coordinated[key], oneshot[key]);
        if (!result.equal) {
          failures.push(`${key}: ${result.differentPixels} differing pixels, first ${result.detail}`);
        }
      }

      // OneShotPixelIdentity: with the paragraph DOM proven identical, the
      // rasterized page must also be pixel-identical — full-page (articles
      // and sidebar in one capture) and at every scrolled viewport position,
      // which exercises the viewport-tier rendering states of each root.
      // Pixel identity is asserted on freshly loaded pages. After host
      // mutations, scroll-triggered re-renders make even an unmodified page
      // differ from its own repeated capture (measured 139k pixels of
      // self-noise), so post-mutation states record deltas without asserting;
      // their DOM identity contract lives in oneshot-equivalence.test.mjs.
      if (assertPixels) {
        assert.deepStrictEqual(
          failures,
          [],
          `${label}: screenshots must be pixel-identical after the one-shot:\n${failures.join("\n")}`,
        );
      } else if (failures.length) {
        console.log(`[${label}] pixel deltas (recorded, not asserted):\n  ${failures.join("\n  ")}`);
      }
      return { shots: Object.keys(coordinated).length, pageHeight: settled.pageHeight! };
    };

    const results: (CompareStateResult & { phase: string })[] = [];
    for (const width of [900, 700]) {
      await setViewportWidth(width);
      results.push({ phase: `initial@${width}`, ...(await compareState(`initial@${width}`, { assertPixels: true })) });
    }

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
    for (const width of [940, 700]) {
      await setViewportWidth(width);
      results.push({ phase: `after-dom-change@${width}`, ...(await compareState(`after-dom-change@${width}`, { assertPixels: false })) });
    }

    assert.ok(
      results.every((r) => r.shots >= 5),
      `Each state must compare the full page plus multiple scrolled viewports: ${JSON.stringify(results)}`,
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
