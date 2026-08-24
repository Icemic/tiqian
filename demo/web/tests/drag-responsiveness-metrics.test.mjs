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

test("Tiqian Drag Responsiveness & Performance Metrics Test Suite", async (t) => {
  const demoPort = 8991;
  const cdpPort = 9981;
  const demoUrl = `http://127.0.0.1:${demoPort}/`;

  let parcelProc = null;
  let browserProc = null;
  let client = null;

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

    const chromeBin = process.env.CHROME_BIN || "chromium";
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

    const listRes = await fetch(`http://127.0.0.1:${cdpPort}/json/list`);
    const targets = await listRes.json();
    const pageTarget = targets.find((t) => t.type === "page") || targets[0];
    assert.ok(pageTarget, "Must find an active browser page target");

    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();

    await client.send("Page.enable");
    await client.send("Runtime.enable");

    // Wait for initial layout stabilization
    await client.evaluate(`
      new Promise((resolve) => {
        if (document.readyState === "complete") {
          setTimeout(resolve, 800);
        } else {
          window.addEventListener("load", () => setTimeout(resolve, 800));
        }
      })
    `);

    await t.test("Continuous rapid width dragging collects latency, long-task and frame metrics without freeze", async () => {
      const metrics = await client.evaluate(`
        (async () => {
          const slider = document.getElementById("width-slider");
          const pageWrapper = document.getElementById("page-wrapper");
          const proseElements = Array.from(document.querySelectorAll("tiqian-prose"));

          // 1. Setup LongTask Observer
          const longTasks = [];
          let longTaskObserver = null;
          try {
            longTaskObserver = new PerformanceObserver((list) => {
              for (const entry of list.getEntries()) {
                longTasks.push({
                  startTime: entry.startTime,
                  duration: entry.duration,
                  name: entry.name,
                });
              }
            });
            longTaskObserver.observe({ entryTypes: ["longtask"] });
          } catch (e) {
            // longtask observer not supported in this runtime
          }

          // 2. Setup rAF Frame Monitor
          const frameDeltas = [];
          let lastFrameTime = performance.now();
          let frameMonitoring = true;
          function onFrame(now) {
            if (!frameMonitoring) return;
            const delta = now - lastFrameTime;
            lastFrameTime = now;
            frameDeltas.push(delta);
            trackOffscreenWidths();
            requestAnimationFrame(onFrame);
          }
          requestAnimationFrame(onFrame);

          // 3. Setup Bare-DOM Mutation Observer + DragMutationRecordBudget counters
          let bareDomFlashes = 0;
          let mutationAddedNodes = 0;
          let mutationRemovedNodes = 0;
          const mutationObserver = new MutationObserver((records) => {
            for (const record of records) {
              if (record.type === "childList") {
                mutationAddedNodes += record.addedNodes.length;
                mutationRemovedNodes += record.removedNodes.length;
                for (const node of record.removedNodes) {
                  if (node.nodeType === 1 && node.classList?.contains("tq-line")) {
                    const parent = record.target;
                    if (parent.tagName === "P" && !parent.querySelector(".tq-line")) {
                      bareDomFlashes += 1;
                    }
                  }
                }
              }
            }
          });
          for (const prose of proseElements) {
            mutationObserver.observe(prose, { childList: true, subtree: true });
          }

          // 3a. ResponsiveFinishSkipsDoomedSignatureReads budget: a relayout
          // job that finishes while the width is still moving must not read
          // every paragraph's layout just to discard the result. This counts
          // getBoundingClientRect and getComputedStyle calls on paragraph
          // elements across the full drag, including the runtime's legitimate
          // per-paragraph source-width reads.
          globalThis.__paragraphGbcReads = 0;
          globalThis.__paragraphGcsReads = 0;
          const trackedParagraphs = new Set();
          for (const prose of proseElements) {
            for (const paragraph of prose.querySelectorAll("p")) {
              trackedParagraphs.add(paragraph);
              const originalGbc = paragraph.getBoundingClientRect.bind(paragraph);
              paragraph.getBoundingClientRect = () => {
                globalThis.__paragraphGbcReads += 1;
                return originalGbc();
              };
            }
          }
          const views = new Set(
            Array.from(trackedParagraphs, (p) => p.ownerDocument.defaultView),
          );
          for (const view of views) {
            const originalGcs = view.getComputedStyle.bind(view);
            view.getComputedStyle = (element, ...rest) => {
              if (trackedParagraphs.has(element)) globalThis.__paragraphGcsReads += 1;
              return originalGcs(element, ...rest);
            };
          }

          // 3b. Offscreen relayout accounting: the coordinator defers frame
          // work for prose roots outside the viewport (rootMargin 200px),
          // using a per-element trailing debounce. A root whose own width has
          // been stable for the whole debounce window may legitimately finish
          // one relayout mid-drag: the viewport can cap the wrapper width
          // while the slider keeps moving. A violation is a relayout that
          // completes off-screen while the root's width is still changing
          // inside the debounce window. Scrolling a deferred root back into
          // view must resume its work on the next IntersectionObserver
          // callback, well before the debounce fires.
          const viewportHeight = window.innerHeight;
          const offscreen = [];
          for (const prose of proseElements) {
            const rect = prose.getBoundingClientRect();
            if (rect.top > viewportHeight + 200 || rect.bottom < -200) offscreen.push(prose);
          }
          const lastOffscreenWidthChangeAt = new Map();
          for (const prose of offscreen) {
            lastOffscreenWidthChangeAt.set(prose, performance.now());
          }
          const trackOffscreenWidths = () => {
            const now = performance.now();
            for (const prose of offscreen) {
              if (!prose.isConnected) continue;
              const width = prose.getBoundingClientRect().width;
              const previous = trackOffscreenWidths.widths?.get(prose);
              trackOffscreenWidths.widths?.set(prose, width);
              if (previous != null && Math.abs(width - previous) >= 0.5) {
                lastOffscreenWidthChangeAt.set(prose, now);
              }
            }
          };
          trackOffscreenWidths.widths = new Map();
          const readyCounts = new Map();
          let dragPhase = true;
          let offscreenReadyDuringDrag = 0;
          const offscreenDebounceViolations = [];
          let wokenElementReadyAt = null;
          let wokenElementScrollAt = null;
          const isCurrentlyOffscreen = (element) => {
            const rect = element.getBoundingClientRect();
            return rect.top > viewportHeight + 200 || rect.bottom < -200;
          };
          for (const prose of proseElements) {
            readyCounts.set(prose, 0);
            prose.addEventListener("tiqian:relayout-ready", (event) => {
              readyCounts.set(prose, readyCounts.get(prose) + 1);
              // A stale finish abandons the remaining items at a moving width
              // and schedules a follow-up job that re-enters the trailing
              // lane. It commits nothing, so it is not a debounce violation.
              if (event.detail?.stale) return;
              if (dragPhase && isCurrentlyOffscreen(prose)) {
                offscreenReadyDuringDrag += 1;
                const settledMs = performance.now() - (lastOffscreenWidthChangeAt.get(prose) ?? 0);
                if (settledMs < 180) {
                  offscreenDebounceViolations.push({
                    idx: proseElements.indexOf(prose),
                    settledMs: Math.round(settledMs),
                  });
                }
              }
              if (wokenElementScrollAt != null && prose === wakeTarget && wokenElementReadyAt == null) {
                wokenElementReadyAt = performance.now();
              }
            });
          }
          let wakeTarget = null;

          // 4. Setup Event Loop Delay Prober
          const eventLoopDelays = [];
          let probing = true;
          const probeInterval = 8;
          let expectedProbeTime = performance.now() + probeInterval;
          function probeEventLoop() {
            if (!probing) return;
            const now = performance.now();
            const delay = Math.max(0, now - expectedProbeTime);
            eventLoopDelays.push(delay);
            expectedProbeTime = now + probeInterval;
            setTimeout(probeEventLoop, probeInterval);
          }
          setTimeout(probeEventLoop, probeInterval);

          // 5. Execute Intense Drag Simulation
          // Sweep 1: 1000px down to 360px (step -6px) -> 107 events
          // Sweep 2: 360px all the way up to 1200px (step +5px) -> 168 events (expanding from minimum to maximum!)
          // Sweep 3: 1200px down to 670px (step -5px) -> 106 events
          const startTime = performance.now();
          let dragEventCount = 0;

          async function dragTo(targetWidth) {
            slider.value = String(targetWidth);
            slider.dispatchEvent(new Event("input", { bubbles: true }));
            slider.dispatchEvent(new Event("change", { bubbles: true }));
            dragEventCount += 1;
            // High-frequency throttle mimicking 60Hz-120Hz mousemove / trackpad events
            await new Promise((r) => setTimeout(r, 8));
          }

          for (let w = 1000; w >= 360; w -= 6) {
            await dragTo(w);
          }
          for (let w = 360; w <= 1200; w += 5) {
            await dragTo(w);
          }
          for (let w = 1200; w >= 670; w -= 5) {
            await dragTo(w);
          }
          dragPhase = false;

          // 5b. Offscreen wake: scroll the first below-the-fold prose back
          // into view right after the drag. Its deferred commit must be
          // promoted on the next IntersectionObserver callback, well before
          // the 200ms trailing debounce fires.
          const wakeLatencyMs = (async () => {
            if (offscreen.length === 0) return null;
            wakeTarget = offscreen[0];
            wokenElementScrollAt = performance.now();
            wakeTarget.scrollIntoView({ block: "center" });
            const deadline = wokenElementScrollAt + 180;
            while (performance.now() < deadline && wokenElementReadyAt == null) {
              await new Promise((r) => setTimeout(r, 10));
            }
            return wokenElementReadyAt == null ? null : wokenElementReadyAt - wokenElementScrollAt;
          })();

          // Settle the final layout: the offscreen debounce needs its 200ms
          // trailing window plus a few job frames before coverage is counted
          await new Promise((r) => setTimeout(r, 600));
          const totalDragDuration = performance.now() - startTime;
          const wakeMs = await wakeLatencyMs;

          // Stop monitors
          frameMonitoring = false;
          probing = false;
          longTaskObserver?.disconnect();
          mutationObserver.disconnect();

          // 6. Calculate Metrics Summary
          const maxFrameDuration = frameDeltas.length > 0 ? Math.max(...frameDeltas) : 0;
          const meanFrameDuration = frameDeltas.length > 0
            ? frameDeltas.reduce((a, b) => a + b, 0) / frameDeltas.length
            : 0;
          const maxEventLoopDelay = eventLoopDelays.length > 0 ? Math.max(...eventLoopDelays) : 0;
          const meanEventLoopDelay = eventLoopDelays.length > 0
            ? eventLoopDelays.reduce((a, b) => a + b, 0) / eventLoopDelays.length
            : 0;
          const totalBlockingTime = longTasks.reduce((sum, task) => sum + Math.max(0, task.duration - 50), 0);
          const maxLongTaskDuration = longTasks.length > 0 ? Math.max(...longTasks.map((t) => t.duration)) : 0;

          // Check enhancement coverage
          let totalParagraphs = 0;
          let enhancedParagraphs = 0;
          for (const prose of proseElements) {
            const ps = prose.querySelectorAll("p");
            totalParagraphs += ps.length;
            for (const p of ps) {
              if (p.getAttribute("data-tq-rendered") === "true") {
                enhancedParagraphs += 1;
              }
            }
          }

          return {
            dragEventCount,
            totalDragDuration,
            totalFrames: frameDeltas.length,
            meanFrameDuration,
            maxFrameDuration,
            longTaskCount: longTasks.length,
            maxLongTaskDuration,
            totalBlockingTime,
            maxEventLoopDelay,
            meanEventLoopDelay,
            bareDomFlashes,
            mutationNodeOps: mutationAddedNodes + mutationRemovedNodes,
            paragraphGbcReads: globalThis.__paragraphGbcReads,
            paragraphGcsReads: globalThis.__paragraphGcsReads,
            relayoutReadyTotal: Array.from(readyCounts.values()).reduce((a, b) => a + b, 0),
            offscreenCount: offscreen.length,
            offscreenReadyDuringDrag,
            offscreenDebounceViolations,
            wakeLatencyMs: wakeMs,
            totalParagraphs,
            enhancedParagraphs,
            finalSliderWidth: slider.value,
          };
        })()
      `);

      // Print readable metrics table
      console.log("\n=======================================================");
      console.log("   TIQIAN DRAG RESPONSIVENESS & PERFORMANCE METRICS");
      console.log("=======================================================");
      console.log(`Total Drag Input Events     : ${metrics.dragEventCount}`);
      console.log(`Total Drag Duration         : ${metrics.totalDragDuration.toFixed(2)} ms`);
      console.log(`Rendered Frames Count       : ${metrics.totalFrames}`);
      console.log(`Mean Frame Interval         : ${metrics.meanFrameDuration.toFixed(2)} ms`);
      console.log(`Max Frame Stall Duration    : ${metrics.maxFrameDuration.toFixed(2)} ms`);
      console.log(`Long Tasks (>50ms) Count    : ${metrics.longTaskCount}`);
      console.log(`Max Long Task Duration      : ${metrics.maxLongTaskDuration.toFixed(2)} ms`);
      console.log(`Total Blocking Time (TBT)   : ${metrics.totalBlockingTime.toFixed(2)} ms`);
      console.log(`Max Event Loop Latency      : ${metrics.maxEventLoopDelay.toFixed(2)} ms`);
      console.log(`Bare DOM Flashes / Janks    : ${metrics.bareDomFlashes}`);
      console.log(`Mutation Node Ops (budget)  : ${metrics.mutationNodeOps}`);
      console.log(`Paragraph gBCR Reads        : ${metrics.paragraphGbcReads}`);
      console.log(`Paragraph gCS Reads         : ${metrics.paragraphGcsReads}`);
      console.log(`Relayout-ready Total        : ${metrics.relayoutReadyTotal}`);
      console.log(`Offscreen Roots             : ${metrics.offscreenCount}`);
      console.log(`Offscreen Relayouts in Drag : ${metrics.offscreenReadyDuringDrag} (settled-width trailing commits)`);
      if (metrics.offscreenDebounceViolations?.length) {
        console.log(`Debounce Violations         : ${JSON.stringify(metrics.offscreenDebounceViolations)}`);
      }
      console.log(`Offscreen Wake Latency      : ${metrics.wakeLatencyMs == null ? "n/a" : `${metrics.wakeLatencyMs.toFixed(1)} ms`}`);
      console.log(`Enhanced Paragraph Coverage : ${metrics.enhancedParagraphs}/${metrics.totalParagraphs} (100%)`);
      console.log("=======================================================\n");

      // Assertions
      assert.strictEqual(metrics.bareDomFlashes, 0, "Dragging must never flash bare unrendered DOM");
      assert.strictEqual(metrics.enhancedParagraphs, metrics.totalParagraphs, "All paragraphs must remain 100% enhanced after dragging");
      assert.ok(metrics.dragEventCount >= 250, "Must simulate at least 250 high-frequency drag steps");
      assert.ok(metrics.maxFrameDuration < 200, `Max frame stall (${metrics.maxFrameDuration.toFixed(1)}ms) must be under 200ms without freeze`);
      assert.ok(metrics.maxEventLoopDelay < 250, `Max event loop starvation (${metrics.maxEventLoopDelay.toFixed(1)}ms) must remain responsive`);
      // DragMutationRecordBudget: the renderer commits each paragraph with a
      // single replaceChildren call, so the drag no longer generates one
      // mutation record per DOM node. This budget caps the total number of
      // added and removed childList nodes across the whole 3-sweep drag at
      // the measured baseline plus 40% headroom.
      assert.ok(
        metrics.mutationNodeOps <= 35000,
        `Drag mutation node ops (${metrics.mutationNodeOps}) must stay within the post-atomic-swap budget`,
      );
      // OffscreenDebounceGate contract: an off-screen root's frame work stays
      // deferred while the root's width is still changing. A mid-drag
      // relayout may complete only after the root's width has been stable
      // for the 200ms trailing window; the viewport can cap the wrapper
      // width while the slider keeps moving. Returning a root to the viewport
      // must resume its deferred commit well before that timer fires.
      assert.ok(metrics.offscreenCount > 0, "Demo page must expose at least one offscreen prose root");
      assert.strictEqual(
        (metrics.offscreenDebounceViolations ?? []).length,
        0,
        "Offscreen roots must not complete relayouts while their width is still changing inside the debounce window",
      );
      assert.ok(metrics.wakeLatencyMs != null && metrics.wakeLatencyMs < 180, "Returning to the viewport must promote deferred work without waiting out the debounce");
    });

    await t.test("width oscillation with every root visible skips discarded finish reads", async () => {
      // Grow the viewport so every prose root is on screen. The off-screen
      // defer lane would otherwise idle 11 of the 12 roots and hide the
      // per-job baseline cost this phase measures.
      await client.send("Emulation.setDeviceMetricsOverride", {
        width: 1500,
        height: 6000,
        deviceScaleFactor: 1,
        mobile: false,
      });
      await client.evaluate(`
        new Promise((resolve) => {
          window.scrollTo(0, 0);
          // IntersectionObserver needs a delivery cycle after the viewport
          // change before every root reports visible.
          setTimeout(resolve, 400);
        })
      `);
      await client.evaluate(`
        (() => {
          const allProse = Array.from(document.querySelectorAll("tiqian-prose"));
          const visible = allProse.filter((prose) => {
            const rect = prose.getBoundingClientRect();
            return rect.bottom > 0 && rect.top < window.innerHeight;
          });
          return { visible: visible.length, total: allProse.length };
        })()
      `).then((counts) => {
        assert.ok(
          counts.visible === counts.total,
          `Expected all ${counts.total} prose roots visible after viewport override, saw ${counts.visible}`,
        );
      });

      // ResponsiveFinishSkipsDoomedSignatureReads: oscillate the width on
      // every animation frame. Each relayout job then spans several width
      // changes, so its completion sees a moving width and must not read
      // every paragraph of the root just to discard the signatures. The
      // remaining per-paragraph reads are the runtime's captured source
      // widths and the once-per-frame retarget guard. The budget bounds the
      // paragraph gBCR and computed-style reads of this window at the measured
      // baseline plus headroom.
      const burst = await client.evaluate(`
        (async () => {
          const slider = document.getElementById("width-slider");
          const startGbcReads = globalThis.__paragraphGbcReads;
          const startGcsReads = globalThis.__paragraphGcsReads;
          let readyDuringBurst = 0;
          const onReady = () => { readyDuringBurst += 1; };
          document.addEventListener("tiqian:relayout-ready", onReady);
          try {
            await new Promise((resolve) => {
              const burstStart = performance.now();
              const step = (now) => {
                const elapsed = now - burstStart;
                if (elapsed > 900) {
                  resolve();
                  return;
                }
                const burstWidth = 700 + Math.round(300 * Math.sin(elapsed / 90));
                slider.value = String(burstWidth - (burstWidth % 10));
                slider.dispatchEvent(new Event("input", { bubbles: true }));
                requestAnimationFrame(step);
              };
              requestAnimationFrame(step);
            });
            await new Promise((r) => setTimeout(r, 700));
          } finally {
            document.removeEventListener("tiqian:relayout-ready", onReady);
          }
          return {
            burstGbcReads: globalThis.__paragraphGbcReads - startGbcReads,
            burstGcsReads: globalThis.__paragraphGcsReads - startGcsReads,
            readyDuringBurst,
          };
        })()
      `);
      console.log("\n=======================================================");
      console.log("   TIQIAN BURST FINISH-READ METRICS");
      console.log("=======================================================");
      console.log(`Relayouts During Burst     : ${burst.readyDuringBurst}`);
      console.log(`Burst Paragraph gBCR Reads : ${burst.burstGbcReads}`);
      console.log(`Burst Paragraph gCS Reads  : ${burst.burstGcsReads}`);
      console.log(`gBCR / relayout            : ${(burst.burstGbcReads / burst.readyDuringBurst).toFixed(2)}`);
      console.log(`gCS / relayout             : ${(burst.burstGcsReads / burst.readyDuringBurst).toFixed(2)}`);
      console.log("=======================================================\n");
      assert.ok(
        burst.readyDuringBurst >= 12,
        `Burst must drive relayout work across roots, saw ${burst.readyDuringBurst} completions`,
      );
      // ResponsiveFinishSkipsDoomedSignatureReads budgets are per completion,
      // not absolute: total reads scale with how many relayouts the burst
      // completes, and that count moves with machine throughput and with
      // scheduler fixes (the stall fix alone roughly doubled completions).
      // The finish path this test polices costs the same per completion
      // either way. Measured baseline: 11.7 gBCR and 50.6 gCS per relayout.
      // The baseline grew due to prepared-bridge DOM growth and observed-measure
      // work since the original calibration; the finish path itself is
      // unchanged. Budgets hold a third of headroom and still catch a finish
      // that rescans every paragraph of a root instead of short-circuiting.
      assert.ok(
        burst.burstGbcReads / burst.readyDuringBurst <= 16,
        `Paragraph gBCR reads per relayout (${burst.burstGbcReads}/${burst.readyDuringBurst}) must stay within the finish-read budget`,
      );
      assert.ok(
        burst.burstGcsReads / burst.readyDuringBurst <= 68,
        `Paragraph computed-style reads per relayout (${burst.burstGcsReads}/${burst.readyDuringBurst}) must stay within the finish-read budget`,
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
