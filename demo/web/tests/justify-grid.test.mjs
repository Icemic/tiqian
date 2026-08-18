import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const webDemoDir = fileURLToPath(new URL("..", import.meta.url));
const TOTAL_EXPECTED_PROSE_ELEMENTS = 12;

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

test("Tiqian Justify and LineLengthGrid Quantization Test Suite", async (t) => {
  const { cdp, chromeProc, serverProc } = await getOrLaunchBrowser();

  t.after(() => {
    cdp.close();
    chromeProc?.kill();
    serverProc?.kill();
  });

  // Helper to wait for all prose elements to settle to current container width
  const waitForAllSettled = async () => {
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
    const data = await cdp.evaluate(`
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

    const result = await cdp.evaluate(`
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
    const article1 = result[0];
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

    const result = await cdp.evaluate(`
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
