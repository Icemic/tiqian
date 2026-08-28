// oneshot-history-harness.diag.mjs — self-contained capture harness for the
// carried fixed demo kit (spec-oneshot-bisect). Ports: demo 8996, CDP 9902.
// Everything is inlined: CdpClient, PNG decode, compareScreenshots, and the
// deep-geometry exports copied verbatim from
// demo/web/tests/helpers/deep-geometry.mjs at 1ad320ce. The comparison
// semantics are frozen by the spec; do not edit the verbatim section.
//
// Usage (inside nix develop):
//   node demo/web-history/oneshot-history-harness.diag.mjs \
//     --era demo/web-history/eras/<label>.json --commit <sha> \
//     --runs N [--run-start K]

import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { createServer } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const repoRoot = fileURLToPath(new URL("../..", import.meta.url));
const kitDir = fileURLToPath(new URL(".", import.meta.url));
export const DEMO_PORT = 8996;
export const CDP_PORT = 9902;
export const VIEWPORT_WIDTH = 900;
export const VIEWPORT_HEIGHT = 800;

// ---------------------------------------------------------------------------
// Verbatim from demo/web/tests/helpers/deep-geometry.mjs (frozen).
// ---------------------------------------------------------------------------
export const DEEP_GEOMETRY_HELPERS = `
  globalThis.__deepGeometry = () => {
    const round = (v) => Math.round(v * 100) / 100;
    const sx = scrollX;
    const sy = scrollY;
    const boxOf = (r) => [round(r.x + sx), round(r.y + sy), round(r.width), round(r.height)];
    const elBox = (el) => boxOf(el.getBoundingClientRect());
    const textBox = (node) => {
      const range = document.createRange();
      range.selectNodeContents(node);
      return boxOf(range.getBoundingClientRect());
    };
    return {
      pageHeight: document.documentElement.scrollHeight,
      roots: Array.from(document.querySelectorAll("tiqian-prose")).map((root, ri) => ({
        root: elBox(root),
        paras: Array.from(root.querySelectorAll("p, li")).map((p, pi) => ({
          key: ri + ":" + pi,
          rect: elBox(p),
          lineMarks: Array.from(p.querySelectorAll("[data-tq-line-index]"), elBox),
          kids: Array.from(p.childNodes)
            .filter((n) => n.nodeType === 1 || (n.nodeType === 3 && n.data.length > 0))
            .map((n) => ({ k: n.nodeType === 3 ? "t" : "e", b: n.nodeType === 3 ? textBox(n) : elBox(n) })),
        })),
      })),
    };
  };
`;

// Compares two __deepGeometry() reports box by box. Returns the compared
// box count (a vacuity guard for callers), the divergent box count, and up
// to ten located examples. Structural mismatches (root, paragraph, or child
// count differences, page height) surface as examples with explicit labels.
export function diffDeepGeometry(a, b) {
  const stats = { equal: false, boxesCompared: 0, divergentBoxes: 0, examples: [] };
  const note = (msg) => {
    if (stats.examples.length < 10) stats.examples.push(msg);
  };
  const cmpBox = (x, y, path) => {
    stats.boxesCompared += 1;
    const same = Array.isArray(x) && Array.isArray(y) &&
      x.length === y.length && x.every((v, i) => v === y[i]);
    if (!same) {
      stats.divergentBoxes += 1;
      note(`${path} [${(x ?? []).join(",")}] vs [${(y ?? []).join(",")}]`);
    }
  };
  if ((a?.pageHeight ?? -1) !== (b?.pageHeight ?? -1)) {
    note(`pageHeight ${a?.pageHeight} vs ${b?.pageHeight}`);
  }
  const rootsA = a?.roots ?? [];
  const rootsB = b?.roots ?? [];
  if (rootsA.length !== rootsB.length) note(`rootCount ${rootsA.length} vs ${rootsB.length}`);
  rootsA.forEach((rootA, ri) => {
    const rootB = rootsB[ri];
    if (!rootB) return;
    cmpBox(rootA.root, rootB.root, `root#${ri}`);
    const parasA = rootA.paras ?? [];
    const parasB = rootB.paras ?? [];
    if (parasA.length !== parasB.length) note(`root#${ri} paraCount ${parasA.length} vs ${parasB.length}`);
    parasA.forEach((paraA, pi) => {
      const paraB = parasB[pi];
      if (!paraB) return;
      const tag = `p${paraA.key ?? pi}`;
      cmpBox(paraA.rect, paraB.rect, `${tag}.rect`);
      const marksA = paraA.lineMarks ?? [];
      const marksB = paraB.lineMarks ?? [];
      if (marksA.length !== marksB.length) note(`${tag} lineMarkCount ${marksA.length} vs ${marksB.length}`);
      marksA.forEach((box, mi) => cmpBox(box, marksB[mi], `${tag}.lineMark[${mi}]`));
      const kidsA = paraA.kids ?? [];
      const kidsB = paraB.kids ?? [];
      if (kidsA.length !== kidsB.length) note(`${tag} childCount ${kidsA.length} vs ${kidsB.length}`);
      kidsA.forEach((kidA, ki) => {
        const kidB = kidsB[ki];
        if (kidB && kidA.k !== kidB.k) note(`${tag}.kids[${ki}] kind ${kidA.k} vs ${kidB.k}`);
        cmpBox(kidA?.b, kidB?.b, `${tag}.kids[${ki}](${kidA?.k ?? "?"})`);
      });
    });
  });
  stats.equal = stats.divergentBoxes === 0 && stats.examples.length === 0;
  return stats;
}

// Counts of the measured surfaces, used to prove a comparison was not
// vacuous (a page that failed to enhance measures zero line markers).
export function deepGeometryCounts(report) {
  let lineMarks = 0;
  let runEls = 0;
  let textNodes = 0;
  for (const root of report?.roots ?? []) {
    for (const para of root.paras ?? []) {
      lineMarks += (para.lineMarks ?? []).length;
      for (const kid of para.kids ?? []) {
        if (kid.k === "t") textNodes += 1;
        else runEls += 1;
      }
    }
  }
  return { lineMarks, runEls, textNodes };
}
// ---------------------------------------------------------------------------
// End of verbatim section.
// ---------------------------------------------------------------------------

export class CdpClient {
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

  async screenshot(params) {
    const res = await this.send("Page.captureScreenshot", { format: "png", ...params });
    return Buffer.from(res.data, "base64");
  }

  close() {
    this.ws?.close();
  }
}

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
  for (let i = 0; i < pa.length; i += bpp) {
    for (let c = 0; c < bpp; c++) {
      if (pa[i + c] !== pb[i + c]) {
        different += 1;
        break;
      }
    }
  }
  return { equal: false, differentPixels: different, detail: null };
}

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i++) {
    if (argv[i].startsWith("--")) {
      args[argv[i].slice(2)] = argv[i + 1];
      i++;
    }
  }
  return args;
}

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".map": "application/json",
  ".png": "image/png",
  ".woff2": "font/woff2",
};

export function startKitServer(era) {
  const indexTemplate = readFileSync(path.join(kitDir, "index.html"), "utf8");
  const indexHtml = indexTemplate.replace(
    /(<script type="importmap" id="era-importmap">)[\s\S]*?(<\/script>)/,
    (_, open, close) => open + JSON.stringify(era.importMap) + close,
  );
  const adapterJs = readFileSync(path.join(kitDir, era.adapter), "utf8");
  const stylesheetAbs = path.resolve(repoRoot, era.stylesheet);
  const mounts = Object.entries(era.static ?? {}).map(([urlPrefix, dir]) => ({
    prefix: urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/",
    dir: path.resolve(repoRoot, dir),
  }));

  const server = createServer((req, res) => {
    const urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);
    const send = (status, body, type) => {
      res.writeHead(status, { "content-type": type, "cache-control": "no-store" });
      res.end(body);
    };
    try {
      if (urlPath === "/" || urlPath === "/index.html") {
        return send(200, indexHtml, MIME[".html"]);
      }
      if (urlPath === "/era-adapter.js") {
        return send(200, adapterJs, MIME[".js"]);
      }
      if (urlPath === "/tiqian.css") {
        return send(200, readFileSync(stylesheetAbs), MIME[".css"]);
      }
      for (const mount of mounts) {
        if (urlPath.startsWith(mount.prefix)) {
          const rel = urlPath.slice(mount.prefix.length);
          const abs = path.resolve(mount.dir, rel);
          if (!abs.startsWith(mount.dir)) return send(403, "forbidden", "text/plain");
          const ext = path.extname(abs);
          return send(200, readFileSync(abs), MIME[ext] ?? "application/octet-stream");
        }
      }
      return send(404, "not found: " + urlPath, "text/plain");
    } catch (error) {
      return send(500, String(error), "text/plain");
    }
  });
  return new Promise((resolve) => server.listen(DEMO_PORT, "127.0.0.1", () => resolve(server)));
}

export async function waitForCdpEndpoint(port, timeoutMs = 20000) {
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
  throw new Error(`Timeout waiting for CDP on ${port}`);
}

// Settle gate: terminal flag plus a stable rendered-subtree fingerprint
// (three consecutive 350ms checks), the same quiescence protocol the HEAD
// test uses before every capture.
export const SETTLE_HELPERS = `
  (() => {
    if (globalThis.__historyFingerprint) return;
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
    globalThis.__historyFingerprint = () =>
      Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"))
        .flatMap((root) => Array.from(root.querySelectorAll("p, li")))
        .map((p) =>
          p.getAttribute("data-tq-rendered") + ";" + p.hasAttribute("data-tiqian-capability-issue") + ";" +
          Array.from(p.childNodes, serialize).join("#")).join("##") +
      "||" + document.documentElement.scrollHeight;
    globalThis.__historySettle = async (timeoutMs) => {
      const deadline = Date.now() + timeoutMs;
      let prev = __historyFingerprint();
      let stable = 0;
      let pageHeight = document.documentElement.scrollHeight;
      while (Date.now() < deadline && stable < 3) {
        await new Promise((resolve) => setTimeout(resolve, 350));
        const cur = __historyFingerprint();
        const terminal = globalThis.__historyTerminal ? __historyTerminal() : true;
        stable = terminal && cur === prev ? stable + 1 : 0;
        prev = cur;
        pageHeight = document.documentElement.scrollHeight;
      }
      await document.fonts.ready;
      return { settled: stable >= 3, pageHeight };
    };
  })()
`;

async function captureSide(client, label) {
  // Returns per-offset captures: two screenshots (self-consistency probe)
  // plus one deep geometry report each, at the top and every scroll step.
  await client.evaluate("window.scrollTo(0, 0)");
  const topSettle = await client.evaluate("__historySettle(20000)");
  if (!topSettle.settled) throw new Error(`${label}: page did not settle at top`);
  const plan = await client.evaluate(`
    (() => {
      const viewportHeight = innerHeight;
      const pageHeight = document.documentElement.scrollHeight;
      const step = Math.floor(viewportHeight * 0.8);
      const maxScroll = Math.max(0, pageHeight - viewportHeight);
      const offsets = [0];
      for (let y = step; y < maxScroll; y += step) offsets.push(y);
      if (offsets[offsets.length - 1] !== maxScroll) offsets.push(maxScroll);
      return { offsets, viewportHeight, pageHeight };
    })()
  `);
  const captures = [];
  for (const offset of plan.offsets) {
    await client.evaluate(`window.scrollTo(0, ${offset})`);
    await new Promise((resolve) => setTimeout(resolve, 500));
    const settled = await client.evaluate("__historySettle(20000)");
    if (!settled.settled) throw new Error(`${label}: page did not settle at scroll ${offset}`);
    const clip = {
      x: 0,
      y: offset,
      width: VIEWPORT_WIDTH,
      height: Math.min(plan.viewportHeight, plan.pageHeight - offset),
      scale: 1,
    };
    const shot1 = await client.screenshot({ clip, captureBeyondViewport: true });
    const shot2 = await client.screenshot({ clip, captureBeyondViewport: true });
    const geometry = await client.evaluate("__deepGeometry()");
    const self = compareScreenshots(shot1, shot2);
    captures.push({
      offset,
      pageHeight: settled.pageHeight,
      selfConsistent: self.equal,
      selfDifferentPixels: self.equal ? 0 : self.differentPixels,
      geometry,
    });
  }
  await client.evaluate("window.scrollTo(0, 0)");
  const endHeight = await client.evaluate("document.documentElement.scrollHeight");
  return { plan, captures, endHeight };
}

async function runOnce(client, era, commit, runIndex, pageLog) {
  pageLog.length = 0;
  await client.send("Page.navigate", { url: "about:blank" });
  await client.evaluate("0");
  await client.send("Page.navigate", { url: `http://127.0.0.1:${DEMO_PORT}/` });
  await client.evaluate("new Promise((r) => { if (document.readyState === 'complete') setTimeout(r, 300); else window.addEventListener('load', () => setTimeout(r, 300)); })");

  const readyDeadline = Date.now() + 30000;
  let ready = false;
  let pageError = null;
  while (Date.now() < readyDeadline) {
    ready = await client.evaluate("globalThis.__historyReady === true").catch((e) => {
      pageError = String(e);
      return false;
    });
    if (ready) break;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  if (!ready) {
    const consoleErrors = await client.evaluate(`(() => {
      const el = document.querySelector("tiqian-prose");
      return { title: document.title, hasRoots: !!el, bodyLen: document.body?.innerText?.length ?? 0 };
    })()`).catch(() => null);
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: "adapter never became ready" + (pageError ? `: ${pageError}` : ""),
      pageState: consoleErrors,
      pageLog: pageLog.slice(0, 40),
    };
  }

  await client.evaluate(SETTLE_HELPERS);
  await client.evaluate("document.fonts.ready");
  await client.evaluate("globalThis.__historyEnhance()");
  const enhanced = await client.evaluate("__historySettle(60000)");
  if (!enhanced.settled) {
    const diag = await client.evaluate(`(() => ({
      terminal: globalThis.__historyTerminal ? __historyTerminal() : null,
      rendered: Array.from(document.querySelectorAll("[data-tq-rendered]")).length,
      renderedValues: Array.from(new Set(Array.from(document.querySelectorAll("[data-tq-rendered]"), (p) => p.getAttribute("data-tq-rendered")))),
      issues: Array.from(document.querySelectorAll("[data-tiqian-capability-issue]")).length,
      paras: document.querySelectorAll("tiqian-prose p, tiqian-prose li").length,
      roots: document.querySelectorAll("tiqian-prose").length,
      firstLineMarks: document.querySelectorAll("[data-tq-line-index]").length,
    }))()`).catch(() => null);
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: "coordinated enhance did not reach the terminal settle",
      pageHeight: enhanced.pageHeight,
      diag,
      pageLog: pageLog.slice(0, 40),
    };
  }
  await client.evaluate(DEEP_GEOMETRY_HELPERS);

  let coordinated;
  try {
    coordinated = await captureSide(client, "coordinated");
  } catch (error) {
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: `coordinated capture failed: ${error.message}`,
      pageLog: pageLog.slice(0, 40),
    };
  }

  await client.evaluate("globalThis.__historyOneShot()");
  await new Promise((resolve) => setTimeout(resolve, 800));
  const afterOneShot = await client.evaluate("__historySettle(60000)");
  if (!afterOneShot.settled) {
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: "one-shot did not reach the terminal settle",
      pageHeightCoordinated: enhanced.pageHeight,
      pageHeightAfterOneShot: afterOneShot.pageHeight,
      pageLog: pageLog.slice(0, 40),
    };
  }

  let oneshot;
  try {
    oneshot = await captureSide(client, "one-shot");
  } catch (error) {
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: `one-shot capture failed: ${error.message}`,
      pageHeightCoordinated: coordinated.endHeight,
      pageLog: pageLog.slice(0, 40),
    };
  }

  const offsets = [];
  let divergent = false;
  let divergentReasons = [];
  const count = coordinated.captures.length;
  for (let i = 0; i < count; i++) {
    const a = coordinated.captures[i];
    const b = oneshot.captures[i];
    const offset = a.offset;
    if (b?.offset !== offset) {
      divergent = true;
      divergentReasons.push(`scroll plan mismatch at index ${i}: ${a.offset} vs ${b?.offset}`);
      offsets.push({ offset, note: "plan mismatch" });
      continue;
    }
    const diff = diffDeepGeometry(a.geometry, b.geometry);
    const countsA = deepGeometryCounts(a.geometry);
    const countsB = deepGeometryCounts(b.geometry);
    const vacuous =
      countsA.lineMarks === 0 && countsA.runEls === 0 && countsA.textNodes === 0;
    let offsetDivergent = false;
    let why = [];
    if (!diff.equal) {
      if (a.selfConsistent && b.selfConsistent && !vacuous) {
        offsetDivergent = true;
        why.push("box divergence with self-consistent captures");
      } else if (vacuous) {
        why.push("vacuous geometry (page not enhanced)");
      } else {
        why.push(`box divergence but self-consistency probe failed (coordinated ${a.selfDifferentPixels}px, one-shot ${b.selfDifferentPixels}px)`);
      }
    }
    if (offsetDivergent) {
      divergent = true;
      divergentReasons.push(`scroll${offset}: ${why.join("; ")}`);
    }
    offsets.push({
      offset,
      boxesCompared: diff.boxesCompared,
      divergentBoxes: diff.divergentBoxes,
      examples: diff.examples,
      equal: diff.equal,
      offsetDivergent,
      selfConsistentCoordinated: a.selfConsistent,
      selfConsistentOneShot: b.selfConsistent,
      selfDifferentPixelsCoordinated: a.selfDifferentPixels,
      selfDifferentPixelsOneShot: b.selfDifferentPixels,
      countsCoordinated: countsA,
      countsOneShot: countsB,
      pageHeightCoordinated: a.pageHeight,
      pageHeightOneShot: b.pageHeight,
    });
  }

  const fontsStatus = await client.evaluate("document.fonts.status");
  return {
    commit,
    run: runIndex,
    era: era.label,
    valid: true,
    divergent,
    divergentReasons,
    offsets,
    pageHeightCoordinated: coordinated.endHeight,
    pageHeightOneShot: oneshot.endHeight,
    pageHeightAfterOneShot: afterOneShot.pageHeight,
    fontsStatus,
    viewport: { width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT },
  };
}

export async function chainCapture(client, era, commit, pageLog) {
  pageLog.length = 0;
  await client.send("Page.navigate", { url: "about:blank" });
  await client.evaluate("0");
  await client.send("Page.navigate", { url: `http://127.0.0.1:${DEMO_PORT}/` });
  await client.evaluate("new Promise((r) => { if (document.readyState === 'complete') setTimeout(r, 300); else window.addEventListener('load', () => setTimeout(r, 300)); })");

  const readyDeadline = Date.now() + 30000;
  let ready = false;
  while (Date.now() < readyDeadline) {
    ready = await client.evaluate("globalThis.__historyReady === true").catch(() => false);
    if (ready) break;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  if (!ready) {
    return { commit, era: era.label, valid: false, reason: "adapter never became ready", pageLog: pageLog.slice(0, 40) };
  }
  await client.evaluate(SETTLE_HELPERS);
  await client.evaluate("document.fonts.ready");
  await client.evaluate("globalThis.__historyEnhance()");
  const enhanced = await client.evaluate("__historySettle(60000)");
  if (!enhanced.settled) {
    return {
      commit, era: era.label, valid: false,
      reason: "coordinated enhance did not reach the terminal settle",
      pageHeight: enhanced.pageHeight, pageLog: pageLog.slice(0, 40),
    };
  }
  await client.evaluate(DEEP_GEOMETRY_HELPERS);
  await client.evaluate("window.scrollTo(0, 0)");
  await client.evaluate("__historySettle(20000)");
  const geometryA = await client.evaluate("__deepGeometry()");
  await new Promise((resolve) => setTimeout(resolve, 400));
  const geometryB = await client.evaluate("__deepGeometry()");
  const selfDiff = diffDeepGeometry(geometryA, geometryB);
  const fontsStatus = await client.evaluate("document.fonts.status");
  return {
    commit,
    era: era.label,
    valid: true,
    geometry: geometryA,
    counts: deepGeometryCounts(geometryA),
    pageHeight: geometryA.pageHeight,
    selfEqual: selfDiff.equal,
    selfDivergentBoxes: selfDiff.divergentBoxes,
    fontsStatus,
    viewport: { width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT },
    pageLog: pageLog.slice(0, 40),
  };
}

async function main() {
  const args = parseArgs(process.argv);
  if (!args.era || !args.commit || (!args.runs && !args.chain)) {
    throw new Error("required: --era <json> --commit <sha> (--runs N [--run-start K] | --chain <label>)");
  }
  const era = JSON.parse(readFileSync(path.resolve(repoRoot, args.era), "utf8"));
  const runs = args.runs ? Number(args.runs) : 0;
  const runStart = Number(args["run-start"] ?? 1);

  const outDir = path.resolve(repoRoot, ".agent-specs/oneshot-bisect-evidence", args.commit);
  mkdirSync(outDir, { recursive: true });

  const server = await startKitServer(era);
  const chromeBin = process.env.CHROME_BIN || "chromium";
  const browserProc = spawn(chromeBin, [
    "--headless=new",
    `--remote-debugging-port=${CDP_PORT}`,
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--force-device-scale-factor=1",
    "--hide-scrollbars",
    "about:blank",
  ], { stdio: "ignore", detached: true });

  let client = null;
  try {
    await waitForCdpEndpoint(CDP_PORT);
    const listRes = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`);
    const targets = await listRes.json();
    const pageTarget = targets.find((tr) => tr.type === "page" && tr.url === "about:blank");
    if (!pageTarget) throw new Error("no blank page target");
    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    const pageLog = [];
    client.ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.method === "Runtime.consoleAPICalled") {
        const text = (msg.params.args ?? []).map((a) => a.value ?? a.description ?? a.type).join(" ");
        pageLog.push(`console.${msg.params.type}: ${text}`.slice(0, 400));
      } else if (msg.method === "Runtime.exceptionThrown") {
        const d = msg.params.exceptionDetails;
        pageLog.push(`exception: ${d?.exception?.description ?? d?.text ?? "?"}`.slice(0, 400));
      } else if (msg.method === "Log.entryAdded") {
        pageLog.push(`log.${msg.params.entry.level}: ${msg.params.entry.text}`.slice(0, 400));
      } else if (msg.method === "Network.responseReceived" && msg.params.response?.status >= 400) {
        pageLog.push(`http ${msg.params.response.status}: ${msg.params.response.url}`.slice(0, 400));
      } else if (msg.method === "Network.loadingFailed") {
        pageLog.push(`net-failed: ${msg.params.errorText} (${msg.params.requestId})`.slice(0, 400));
      }
    });
    await client.send("Runtime.setAsyncCallStackDepth", { maxDepth: 0 }).catch(() => {});
    await client.send("Log.enable").catch(() => {});
    await client.send("Network.enable").catch(() => {});
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: VIEWPORT_WIDTH,
      height: VIEWPORT_HEIGHT,
      deviceScaleFactor: 1,
      mobile: false,
    });

    for (let i = 0; i < runs; i++) {
      const runIndex = runStart + i;
      const record = await runOnce(client, era, args.commit, runIndex, pageLog);
      const file = path.join(outDir, `${runIndex}.json`);
      writeFileSync(file, JSON.stringify(record, null, 2));
      const summary = record.valid
        ? `divergent=${record.divergent} boxes=${record.offsets?.[0]?.boxesCompared} pageH=${record.pageHeightCoordinated}/${record.pageHeightOneShot}`
        : `INVALID: ${record.reason}`;
      console.log(`[${era.label} ${args.commit.slice(0, 8)} run ${runIndex}] ${summary} -> ${path.relative(repoRoot, file)}`);
    }

    if (args.chain) {
      const record = await chainCapture(client, era, args.commit, pageLog);
      const file = path.join(outDir, `chain-${args.chain}.json`);
      writeFileSync(file, JSON.stringify(record, null, 2));
      const summary = record.valid
        ? `chain counts=${record.counts?.lineMarks}lm/${record.counts?.runEls}el/${record.counts?.textNodes}tx pageH=${record.pageHeight} selfEqual=${record.selfEqual}`
        : `INVALID: ${record.reason}`;
      console.log(`[${era.label} ${args.commit.slice(0, 8)} chain ${args.chain}] ${summary} -> ${path.relative(repoRoot, file)}`);
    }
  } finally {
    client?.close();
    server.close();
    if (browserProc?.pid) {
      try { process.kill(-browserProc.pid, "SIGKILL"); } catch {}
      try { process.kill(browserProc.pid, "SIGKILL"); } catch {}
    }
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
