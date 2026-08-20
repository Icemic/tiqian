// RefVsRefComparison: build the web npm package at two git refs and compare
// the demo pages they render, box by box and pixel by pixel. This lands the
// manual "checkout base, rebuild, swap artifacts, eyeball" workflow as a
// repeatable tool. Each ref builds inside its own git worktree, so the
// working tree is never touched and each side's artifact cannot leak into
// the other (the artifact under frontend/web/npm/runtime/tiqian-web.js is
// git-ignored, which made manual swapping produce a false green once).
//
// Usage (from demo/web, inside nix develop):
//   node tests/ab/compare-refs.mjs --base <git-ref> [--head <git-ref>]
//
// --head defaults to the current working tree, uncommitted changes included.
// Both refs must know the :frontend:web:assembleNpmPackage task. The demo
// page, viewport, fonts, and capture plan are identical constants for both
// sides, so the only variable is the engine build.
//
// Every width lane below re-settles, recomputes its capture plan, and
// compares the FULL measured surface (every root, every paragraph, every
// line marker, every direct child element and text node of every
// paragraph — no sampling) plus pixel-identical screenshots. The lanes are
// driven through the demo's own width axis: the page-wrapper maxWidth
// slider (360..1600, default 1280), so a divergence report speaks the same
// widths designers and the perf HUD see. Screenshots cover the full page
// plus up to MAX_SCROLL_STOPS evenly spaced scroll positions per lane;
// box coverage is complete regardless through the geometry lanes.
//
// Exit code 0 means, at every width: identical page height, identical
// measured boxes, and pixel-identical screenshots.

const WIDTH_LANES = [1600, 1440, 1280, 1120, 1024, 960, 900, 820, 720, 620, 500, 360];
const MAX_SCROLL_STOPS = 6;

import { spawn, execFile } from "node:child_process";
import { createServer } from "node:http";
import { readFile, mkdtemp, rm, stat } from "node:fs/promises";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";
import {
  DEEP_GEOMETRY_HELPERS,
  deepGeometryCounts,
  diffDeepGeometry,
} from "../helpers/deep-geometry.mjs";

const webDemoDir = fileURLToPath(new URL("../..", import.meta.url));
const repoRoot = fileURLToPath(new URL("../../../..", import.meta.url));

const args = process.argv.slice(2);
const readArg = (name) => {
  const prefix = "--" + name + "=";
  const inline = args.find((a) => a.startsWith(prefix));
  if (inline) return inline.slice(prefix.length);
  const at = args.indexOf("--" + name);
  return at >= 0 ? args[at + 1] : null;
};
const baseRef = readArg("base");
const headRef = readArg("head"); // null means the current working tree
if (!baseRef) {
  console.error("usage: node tests/ab/compare-refs.mjs --base <git-ref> [--head <git-ref>]");
  console.error("--head defaults to the current working tree including uncommitted changes");
  process.exit(2);
}

const withTimeout = (promise, ms, label) =>
  Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms),
    ),
  ]);

const run = (cmd, runArgs, opts = {}) =>
  withTimeout(new Promise((resolve, reject) => {
    execFile(cmd, runArgs, opts, (err, stdout, stderr) => {
      if (err) reject(new Error(`${cmd} ${runArgs.join(" ")} failed: ${stderr || err.message}`));
      else resolve(stdout);
    });
  }), 120000, `${cmd} ${runArgs[0]}`);

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.id = 0;
    this.pending = new Map();
  }

  async connect() {
    return withTimeout(new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (err) => reject(err);
      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.id && this.pending.has(msg.id)) {
          const { resolve, reject } = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (msg.error) {
            const detail = msg.error.data ? ` (${msg.error.data})` : "";
            reject(new Error(msg.error.message + detail));
          } else {
            resolve(msg.result);
          }
        }
      };
    }), 15000, "cdp connect");
  }

  async send(method, params) {
    const id = ++this.id;
    return withTimeout(new Promise((resolve, reject) => {
      this.pending.set(id, {
        resolve,
        reject: (err) => reject(new Error(`${method}: ${err.message}`)),
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    }), 90000, `cdp ${method}`);
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

  async screenshot(params) {
    const res = await this.send("Page.captureScreenshot", { format: "png", ...params });
    return Buffer.from(res.data, "base64");
  }

  close() {
    this.ws?.close();
  }
}

// Minimal dependency-free PNG decode (8-bit RGB/RGBA, non-interlaced) and a
// strict pixel comparison: any differing pixel fails the comparison.
function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG");
  let pos = 8;
  let width = 0;
  let height = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString("ascii", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const bitDepth = data[8];
      const colorType = data[9];
      const interlace = data[12];
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

function decodePixels(png) {
  const { width, height, idat } = decodePng(png);
  const colorType = png[25];
  const bpp = colorType === 6 ? 4 : 3;
  const raw = inflateSync(idat);
  const stride = width * bpp;
  const out = Buffer.alloc(height * stride);
  const paeth = (a, b, c) => {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  };
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const rowStart = y * (stride + 1) + 1;
    const row = raw.subarray(rowStart, rowStart + stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    for (let x = 0; x < stride; x++) {
      const left = x >= bpp ? cur[x - bpp] : 0;
      const up = prev ? prev[x] : 0;
      const upLeft = prev && x >= bpp ? prev[x - bpp] : 0;
      let value = row[x];
      if (filter === 1) value = (value + left) & 0xff;
      else if (filter === 2) value = (value + up) & 0xff;
      else if (filter === 3) value = (value + ((left + up) >> 1)) & 0xff;
      else if (filter === 4) value = (value + paeth(left, up, upLeft)) & 0xff;
      cur[x] = value;
    }
  }
  return { width, height, bpp, pixels: out };
}

function compareScreenshots(a, b) {
  if (a.equals(b)) return { equal: true, differentPixels: 0, detail: null };
  const da = decodePixels(a);
  const db = decodePixels(b);
  if (da.width !== db.width || da.height !== db.height) {
    return {
      equal: false,
      differentPixels: -1,
      detail: `dimensions ${da.width}x${da.height} vs ${db.width}x${db.height}`,
    };
  }
  const { width, height, bpp, pixels: pa } = da;
  const pb = db.pixels;
  let different = 0;
  let first = null;
  for (let y = 0; y < height && !first; y++) {
    for (let x = 0; x < width; x++) {
      const offset = (y * width + x) * bpp;
      let delta = 0;
      for (let c = 0; c < bpp; c++) {
        delta = Math.max(delta, Math.abs(pa[offset + c] - pb[offset + c]));
      }
      if (delta > 0) {
        first = `(${x},${y}) rgba [${Array.from(pa.subarray(offset, offset + bpp))}] vs [${Array.from(pb.subarray(offset, offset + bpp))}]`;
        break;
      }
    }
  }
  for (let i = 0; i < pa.length; i += bpp) {
    for (let c = 0; c < bpp; c++) {
      if (pa[i + c] !== pb[i + c]) {
        different += 1;
        break;
      }
    }
  }
  return { equal: false, differentPixels: different, detail: first };
}

// Serves the current demo page with an import map that resolves
// @tiqian/prose inside the given package directory, so the page chrome is a
// constant and the engine build is the only variable.
function startDemoServer(port, pkgDir, label) {
  const server = createServer(async (req, res) => {
    const path = decodeURIComponent(new URL(req.url, "http://x").pathname);
    try {
      if (path === "/") {
        const html = (await readFile(join(webDemoDir, "index.html"), "utf8")).replace(
          "</head>",
          `<script type="importmap">{"imports":{"@tiqian/prose/element":"/frontend/web/npm/element.js","@tiqian/prose/":"/frontend/web/npm/","@tiqian/prose":"/frontend/web/npm/api.js"}}</script></head>`,
        );
        res.setHeader("content-type", "text/html; charset=utf-8");
        res.end(html);
        return;
      }
      let file = null;
      let type = "text/javascript";
      if (path === "/main.js" || path === "/index.css") {
        file = join(webDemoDir, path.slice(1));
        if (path.endsWith(".css")) type = "text/css";
      } else if (path.startsWith("/frontend/web/npm/")) {
        const rest = path.slice("/frontend/web/npm/".length);
        file = join(pkgDir, rest);
        if (rest.endsWith(".css")) type = "text/css";
      }
      const data = file ? await readFile(file).catch(() => null) : null;
      if (data) {
        res.setHeader("content-type", type);
        res.end(data);
        return;
      }
      if (path === "/favicon.ico") {
        res.statusCode = 204;
        res.end();
        return;
      }
      res.statusCode = 404;
      res.end("not found");
    } catch (err) {
      console.error(`[${label}] server error on ${path}: ${err}`);
      res.statusCode = 500;
      res.end(String(err));
    }
  });
  return withTimeout(
    new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve(server))),
    10000,
    `listen ${port}`,
  );
}

// Attribute-agnostic quiescence: the base ref may predate current markers,
// so settle is defined by a full rendered-subtree fingerprint and page
// height, with at least one taken-over paragraph as a liveness proof.
const PAGE_HELPERS = `
  (() => {
    globalThis.__roots = () => Array.from(document.querySelectorAll("tiqian-prose"));
    const serialize = (node) => {
      if (node.nodeType === 3) return "t" + node.data.length + ":" + node.data;
      if (node.nodeType !== 1) return "o" + node.nodeType;
      const el = node;
      const attrs = Array.from(el.attributes, (a) => a.name + "=" + a.value).sort().join("|");
      return el.tagName + "[" + attrs + "]" + Array.from(el.childNodes, serialize).join(",");
    };
    globalThis.__fingerprint = () =>
      __roots().map((r) => Array.from(r.childNodes, serialize).join("#")).join("##") +
      "||" + document.documentElement.scrollHeight;
    globalThis.__settle = async (timeoutMs) => {
      await document.fonts.ready;
      const deadline = Date.now() + timeoutMs;
      const enhanced = () => document.querySelectorAll("tiqian-prose [data-tq-rendered]").length;
      let prev = __fingerprint();
      let stable = 0;
      let pageHeight = document.documentElement.scrollHeight;
      while (Date.now() < deadline && stable < 3) {
        await new Promise((resolve) => setTimeout(resolve, 350));
        pageHeight = document.documentElement.scrollHeight;
        const cur = __fingerprint();
        stable = cur === prev && enhanced() > 0 ? stable + 1 : 0;
        prev = cur;
      }
      return { settled: stable >= 3, pageHeight, enhanced: enhanced() };
    };
    const hud = document.querySelector(".floating-benchmark-hud");
    if (hud) hud.style.display = "none";
    globalThis.__setLaneWidth = (w) => {
      const wrapper = document.querySelector(".page-wrapper");
      if (wrapper) wrapper.style.maxWidth = w + "px";
      const slider = document.getElementById("width-slider");
      if (slider) slider.value = String(w);
    };
    ${DEEP_GEOMETRY_HELPERS}
  })()
`;

async function captureSide({ label, pkgDir, port, cdpPort, plans }) {
  const url = `http://127.0.0.1:${port}/`;
  const busy = await fetch(url).then(() => true, () => false);
  if (busy) throw new Error(`[${label}] port ${port} is busy`);
  const server = await startDemoServer(port, pkgDir, label);
  const browser = spawn(process.env.CHROME_BIN || "chromium", [
    "--headless=new",
    `--remote-debugging-port=${cdpPort}`,
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "about:blank",
  ], { stdio: "ignore", detached: true });
  try {
    for (let i = 0; i < 75; i++) {
      try {
        const res = await withTimeout(fetch(`http://127.0.0.1:${cdpPort}/json/version`), 3000, "v");
        if (res.ok) break;
      } catch {}
      await new Promise((r) => setTimeout(r, 200));
    }
    const targets = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
    const pageTarget = targets.find((t) => t.type === "page");
    const client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    await client.send("Page.navigate", { url });
    await client.evaluate("0");
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 1600,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await client.evaluate(`
      new Promise((resolve) => {
        if (document.readyState === "complete") setTimeout(resolve, 500);
        else window.addEventListener("load", () => setTimeout(resolve, 500));
      })
    `);
    await client.evaluate(PAGE_HELPERS);
    await client.send("Page.bringToFront");
    const settled = await client.evaluate("__settle(45000)");
    if (!settled.settled || settled.enhanced === 0) {
      throw new Error(`[${label}] page did not settle (settled=${settled.settled} enhanced=${settled.enhanced})`);
    }
    // Each width is an independent comparison lane: resize, re-settle at the
    // new measure, recompute or replay the capture plan, then photograph and
    // measure the full surface. Plans travel from the base side so both
    // sides photograph identical regions.
    const lanes = {};
    for (const width of WIDTH_LANES) {
      await client.evaluate(`__setLaneWidth(${width})`);
      await client.evaluate("window.scrollTo(0, 0)");
      const laneSettled = await client.evaluate("__settle(30000)");
      if (!laneSettled.settled) {
        throw new Error(`[${label}] page did not settle at width ${width}`);
      }
      let plan = plans?.[width];
      if (!plan) {
        plan = await client.evaluate(`
          (() => {
            const main = document.querySelector("main") ?? document.body;
            const rect = main.getBoundingClientRect();
            const viewportHeight = innerHeight;
            const pageHeight = document.documentElement.scrollHeight;
            const maxScroll = Math.max(0, pageHeight - viewportHeight);
            const stops = Math.max(1, Math.min(${MAX_SCROLL_STOPS}, Math.floor(maxScroll / viewportHeight) + 1));
            const scrolls = [];
            for (let i = 0; i < stops; i++) {
              scrolls.push(stops === 1 ? 0 : Math.round((maxScroll * i) / (stops - 1)));
            }
            return {
              rect: { x: rect.left + scrollX, y: rect.top + scrollY, width: rect.width, height: rect.height },
              viewportHeight,
              pageHeight,
              scrolls,
            };
          })()
        `);
      }
      const shots = {};
      console.log(`[${label}] @${width} full clip=${JSON.stringify(plan.rect)}`);
      shots.full = await client.screenshot({
        clip: { x: plan.rect.x, y: plan.rect.y, width: plan.rect.width, height: plan.rect.height, scale: 1 },
        captureBeyondViewport: true,
      });
      for (const scroll of plan.scrolls) {
        await client.evaluate(`window.scrollTo(0, ${scroll})`);
        await new Promise((r) => setTimeout(r, 500));
        const at = await client.evaluate("__settle(20000)");
        if (!at.settled) throw new Error(`[${label}] page did not settle at width ${width} scroll ${scroll}`);
        console.log(`[${label}] @${width} scroll=${scroll} clip y=${scroll} h=${Math.min(plan.viewportHeight, plan.pageHeight - scroll)}`);
        shots["scroll" + scroll] = await client.screenshot({
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
      await client.evaluate("window.scrollTo(0, 0)");
      lanes[width] = {
        shots,
        plan,
        geometry: await client.evaluate("__deepGeometry()"),
        pageHeight: await client.evaluate("document.documentElement.scrollHeight"),
      };
    }
    client.close();
    return { label, lanes };
  } finally {
    try { process.kill(-browser.pid, "SIGKILL"); } catch {}
    try { process.kill(browser.pid, "SIGKILL"); } catch {}
    server.close();
  }
}

async function buildSide(label, sideDir) {
  console.log(`[${label}] building :frontend:web:assembleNpmPackage in ${sideDir}`);
  await new Promise((resolve, reject) => {
    const proc = spawn("./gradlew", [
      ":frontend:web:assembleNpmPackage",
      "--no-build-cache",
    ], { cwd: sideDir, stdio: ["ignore", "pipe", "pipe"] });
    let stderr = "";
    proc.stdout.on("data", (d) => process.stdout.write(`[${label}] ${d}`));
    proc.stderr.on("data", (d) => {
      stderr += d;
      process.stderr.write(`[${label}] ${d}`);
    });
    const timer = setTimeout(() => {
      proc.kill("SIGKILL");
      reject(new Error(`[${label}] gradle build timed out`));
    }, 15 * 60 * 1000);
    proc.on("exit", (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`[${label}] gradle build failed (${code})`));
    });
  });
  const artifact = join(sideDir, "frontend/web/npm/runtime/tiqian-web.js");
  const info = await stat(artifact);
  const bytes = await readFile(artifact);
  const hash = createHash("md5").update(bytes).digest("hex");
  console.log(`[${label}] artifact ${info.size} bytes md5=${hash}`);
  return { artifact, hash };
}

const worktrees = [];
const cleanup = async () => {
  for (const dir of worktrees.splice(0)) {
    await run("git", ["worktree", "remove", "--force", dir], { cwd: repoRoot }).catch(() => {});
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
};

try {
  // A stale chromium from an aborted run would silently own a debug port and
  // answer /json/list with a foreign page, so ports are checked up front.
  for (const port of [9321, 9323, 9931, 9933]) {
    const taken = await fetch(`http://127.0.0.1:${port}/json/version`).then(() => true, () => false);
    if (taken) throw new Error(`port ${port} is already serving CDP; kill the stale chromium first`);
  }
  const baseDir = await mkdtemp(join(tmpdir(), "tq-ab-base-"));
  worktrees.push(baseDir);
  console.log(`[setup] git worktree for base ${baseRef} at ${baseDir}`);
  await run("git", ["worktree", "add", "--detach", baseDir, baseRef], { cwd: repoRoot });
  const baseBuild = await buildSide("base", baseDir);

  let headDir = repoRoot;
  if (headRef) {
    headDir = await mkdtemp(join(tmpdir(), "tq-ab-head-"));
    worktrees.push(headDir);
    console.log(`[setup] git worktree for head ${headRef} at ${headDir}`);
    await run("git", ["worktree", "add", "--detach", headDir, headRef], { cwd: repoRoot });
  } else {
    console.log("[setup] head side is the current working tree (uncommitted changes included)");
  }
  const headBuild = await buildSide("head", headDir);

  if (baseBuild.hash === headBuild.hash) {
    console.warn("[warn] both sides produced identical artifacts; the comparison below is a no-op");
  }

  const base = await captureSide({
    label: "base",
    pkgDir: join(baseDir, "frontend/web/npm"),
    port: 9321,
    cdpPort: 9931,
    plans: null,
  });
  const head = await captureSide({
    label: "head",
    pkgDir: join(headDir, "frontend/web/npm"),
    port: 9323,
    cdpPort: 9933,
    plans: base.lanes && Object.fromEntries(
      WIDTH_LANES.map((width) => [width, base.lanes[width]?.plan]),
    ),
  });

  const failures = [];
  console.log("\n=== ref-vs-ref report ===");
  console.log(`base: ${baseRef} (${baseBuild.hash})`);
  console.log(`head: ${headRef ?? "working tree"} (${headBuild.hash})`);
  for (const width of WIDTH_LANES) {
    const laneBase = base.lanes[width];
    const laneHead = head.lanes[width];
    if (laneBase.pageHeight !== laneHead.pageHeight) {
      failures.push(`@${width}: pageHeight ${laneBase.pageHeight} vs ${laneHead.pageHeight}`);
    }
    const geoDiff = diffDeepGeometry(laneBase.geometry, laneHead.geometry);
    const geoCounts = deepGeometryCounts(laneBase.geometry);
    if (!geoDiff.equal) {
      failures.push(
        `@${width}: geometry ${geoDiff.divergentBoxes} divergent boxes of ${geoDiff.boxesCompared} compared`,
      );
    }
    const shotFailures = [];
    for (const key of Object.keys(laneBase.shots)) {
      const result = compareScreenshots(laneBase.shots[key], laneHead.shots[key]);
      if (!result.equal) {
        shotFailures.push(`${key}: ${result.differentPixels} differing pixels, first ${result.detail}`);
      }
    }
    console.log(
      `@${width}: boxes=${geoDiff.boxesCompared} ` +
      `(lineMarks=${geoCounts.lineMarks} runEls=${geoCounts.runEls} texts=${geoCounts.textNodes}) ` +
      `pageHeight=${laneBase.pageHeight}${laneBase.pageHeight === laneHead.pageHeight ? "" : " vs " + laneHead.pageHeight} ` +
      `shots=${Object.keys(laneBase.shots).length}`,
    );
    if (geoDiff.examples.length) {
      console.log(`@${width}: geometry divergences:`);
      for (const example of geoDiff.examples) console.log("  " + example);
    }
    for (const failure of shotFailures) {
      failures.push(`@${width}: ${failure}`);
    }
  }
  if (failures.length) {
    console.log("FAILURES:");
    for (const failure of failures) console.log("  " + failure);
    process.exitCode = 1;
  } else {
    console.log(`RESULT: identical at all ${WIDTH_LANES.length} width lanes ${WIDTH_LANES[0]}..${WIDTH_LANES[WIDTH_LANES.length - 1]} (full boxes and pixels)`);
  }
} finally {
  await cleanup();
}
