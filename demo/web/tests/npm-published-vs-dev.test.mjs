import test from "node:test";
import assert from "node:assert/strict";
import { spawn, execFile } from "node:child_process";
import { createServer } from "node:http";
import { readFile, readdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const webDemoDir = fileURLToPath(new URL("..", import.meta.url));
const repoRoot = fileURLToPath(new URL("../../../", import.meta.url));
const devPkgDir = join(repoRoot, "frontend/web/npm");

// The published release and the working tree are served through the same
// static server and import map, so the only variable between the two pages is
// which @tiqian/prose directory /frontend/web/npm/ resolves to. Both sides run
// as native ESM; neither goes through parcel.
const devPort = 9002;
const pubPort = 9004;
const cdpPort = 9902;
const devUrl = `http://127.0.0.1:${devPort}/`;
const pubUrl = `http://127.0.0.1:${pubPort}/`;

// A CDP response can be dropped silently when the page's execution context is
// destroyed mid-evaluate, wedging the suite. Every remote call has a deadline.
const withTimeout = (promise, ms, label) =>
  Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms),
    ),
  ]);

const run = (cmd, args, opts = {}) =>
  withTimeout(new Promise((resolve, reject) => {
    execFile(cmd, args, opts, (err, stdout, stderr) => {
      if (err) reject(new Error(`${cmd} ${args.join(" ")} failed: ${stderr || err.message}`));
      else resolve(stdout);
    });
  }), 120000, `${cmd} ${args[0]}`);

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.id = 0;
    this.pending = new Map();
    this.console = [];
  }

  async connect() {
    return withTimeout(new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (err) => reject(err);
      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.method === "Runtime.consoleAPICalled" && ["error", "warning"].includes(msg.params.type)) {
          this.console.push(`${msg.params.type}: ${msg.params.args.map((a) => a.value ?? a.description ?? "").join(" ")}`);
        }
        if (msg.method === "Runtime.exceptionThrown") {
          this.console.push(`exception: ${msg.params.exceptionDetails?.text} ${msg.params.exceptionDetails?.exception?.description ?? ""}`);
        }
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
    }), 15000, "cdp connect");
  }

  async send(method, params = {}) {
    const id = ++this.id;
    return withTimeout(new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    }), 60000, `cdp ${method}`);
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

// Serves the demo page with an import map so main.js's bare specifier
// "@tiqian/prose/element" resolves to the chosen package directory. The
// stylesheet link "../../frontend/web/npm/styles.css" resolves from the page
// root to the same directory, so published CSS pairs with published JS.
function startDemoServer(port, pkgDir) {
  const notFound = [];
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
      notFound.push(path);
      res.statusCode = 404;
      res.end("not found");
    } catch (err) {
      notFound.push(`${path} (${err})`);
      res.statusCode = 500;
      res.end(String(err));
    }
  });
  return withTimeout(new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve({ server, notFound }))), 10000, `listen ${port}`);
}

// In-page helpers shared by both sides. The settle check is attribute-agnostic:
// the published release predates current markers, so quiescence is defined by
// the full rendered-subtree fingerprint and page height alone.
const PAGE_HELPERS = `
  (() => {
    globalThis.__roots = () => Array.from(document.querySelectorAll("tiqian-prose"));
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
      __roots().map((r) => Array.from(r.childNodes, serialize).join("#")).join("##") +
      "||" + document.documentElement.scrollHeight;
    globalThis.__settle = async (timeoutMs) => {
      await document.fonts.ready;
      const deadline = Date.now() + timeoutMs;
      // A raw page before enhancement starts is also perfectly stable, so
      // quiescence additionally requires at least one taken-over paragraph;
      // both the published release and the working tree mark them with
      // data-tq-rendered.
      const enhanced = () => document.querySelectorAll("tiqian-prose [data-tq-rendered]").length;
      let prev = __fingerprint();
      let stable = 0;
      let pageHeight = document.documentElement.scrollHeight;
      while (Date.now() < deadline && stable < 3) {
        await new Promise((resolve) => setTimeout(resolve, 350));
        const cur = __fingerprint();
        stable = cur === prev && enhanced() > 0 ? stable + 1 : 0;
        prev = cur;
        pageHeight = document.documentElement.scrollHeight;
      }
      return { settled: stable >= 3, pageHeight, enhanced: enhanced() };
    };
    const round = (v) => Math.round(v * 100) / 100;
    const rect4 = (el) => {
      const r = el.getBoundingClientRect();
      return [round(r.x + scrollX), round(r.y + scrollY), round(r.width), round(r.height)];
    };
    globalThis.__geometry = () =>
      __roots().map((root) => ({
        root: rect4(root),
        paras: Array.from(root.querySelectorAll("p, li")).map((p) => ({
          rect: rect4(p),
          kids: Array.from(p.children).map(rect4),
        })),
      }));
    const hud = document.querySelector(".floating-benchmark-hud");
    if (hud) hud.style.display = "none";
  })()
`;

const captureSet = async (client, plan) => {
  const shots = {};
  // Capturing a background tab stalls in headless chromium; each capture pass
  // runs sequentially, so the page being photographed is brought to front.
  await client.send("Page.bringToFront");
  await client.evaluate("window.scrollTo(0, 0)");
  const topSettled = await client.evaluate("__settle(20000)");
  assert.ok(topSettled.settled, "Must settle at the top before the full-page capture");
  shots["full"] = await client.screenshot({
    clip: { x: plan.rect.x, y: plan.rect.y, width: plan.rect.width, height: plan.rect.height, scale: 1 },
    captureBeyondViewport: true,
  });
  for (const scroll of plan.scrolls) {
    await client.evaluate(`window.scrollTo(0, ${scroll})`);
    await new Promise((resolve) => setTimeout(resolve, 500));
    const settled = await client.evaluate("__settle(20000)");
    assert.ok(settled.settled, `Must settle at scroll offset ${scroll}`);
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
  const endHeight = await client.evaluate("document.documentElement.scrollHeight");
  return { shots, pageHeight: endHeight };
};

// Full scan of both geometry reports: first divergence for the assertion, plus
// counts, worst delta, and examples for the diagnostic record.
function geometryReport(dev, pub) {
  if (dev.length !== pub.length) {
    return { firstDiff: `root count ${dev.length} vs ${pub.length}`, childDiffs: 0, paraRectDiffs: 0, maxDelta: 0, examples: [] };
  }
  let firstDiff = null;
  let childDiffs = 0;
  let paraRectDiffs = 0;
  let maxDelta = 0;
  const examples = [];
  const note = (msg, delta) => {
    if (!firstDiff) firstDiff = msg;
    if (examples.length < 8) examples.push(msg);
    maxDelta = Math.max(maxDelta, delta ?? 0);
  };
  for (let r = 0; r < dev.length; r++) {
    if (JSON.stringify(dev[r].root) !== JSON.stringify(pub[r].root)) {
      note(`root#${r} rect ${JSON.stringify(dev[r].root)} vs ${JSON.stringify(pub[r].root)})`,
        Math.max(...dev[r].root.map((v, i) => Math.abs(v - pub[r].root[i]))));
    }
    if (dev[r].paras.length !== pub[r].paras.length) {
      note(`root#${r} paragraph count ${dev[r].paras.length} vs ${pub[r].paras.length}`, 1);
      continue;
    }
    for (let p = 0; p < dev[r].paras.length; p++) {
      const dp = dev[r].paras[p];
      const pp = pub[r].paras[p];
      if (JSON.stringify(dp.rect) !== JSON.stringify(pp.rect)) {
        paraRectDiffs += 1;
        note(`root#${r} p#${p} rect ${JSON.stringify(dp.rect)} vs ${JSON.stringify(pp.rect)}`,
          Math.max(...dp.rect.map((v, i) => Math.abs(v - pp.rect[i]))));
      }
      if (dp.kids.length !== pp.kids.length) {
        note(`root#${r} p#${p} child count ${dp.kids.length} vs ${pp.kids.length}`, 1);
        continue;
      }
      for (let k = 0; k < dp.kids.length; k++) {
        if (JSON.stringify(dp.kids[k]) !== JSON.stringify(pp.kids[k])) {
          childDiffs += 1;
          note(`root#${r} p#${p} line#${k} ${JSON.stringify(dp.kids[k])} vs ${JSON.stringify(pp.kids[k])}`,
            Math.max(...dp.kids[k].map((v, i) => Math.abs(v - pp.kids[k][i]))));
        }
      }
    }
  }
  return { firstDiff, childDiffs, paraRectDiffs, maxDelta, examples };
}

test("NpmPublishedVsDev: published @tiqian/prose matches the working tree visually and geometrically", async () => {
  const tmpBase = await mkdtemp(join(tmpdir(), "tq-npm-pub-"));
  let chromeProc = null;
  let dev = null;
  let pub = null;
  const servers = [];

  try {
    await run("npm", ["pack", "@tiqian/prose@latest", "--pack-destination", tmpBase]);
    const tarball = (await readdir(tmpBase)).find((f) => f.endsWith(".tgz"));
    assert.ok(tarball, "npm pack must produce a tarball");
    await run("tar", ["xzf", join(tmpBase, tarball), "-C", tmpBase]);
    const pubPkgDir = join(tmpBase, "package");
    const pubVersion = JSON.parse(await readFile(join(pubPkgDir, "package.json"), "utf8")).version;
    const devVersion = JSON.parse(await readFile(join(devPkgDir, "package.json"), "utf8")).version;
    console.log(`published @tiqian/prose@${pubVersion} vs working tree ${devVersion}`);

    servers.push(await startDemoServer(devPort, devPkgDir));
    servers.push(await startDemoServer(pubPort, pubPkgDir));

    chromeProc = spawn("chromium", [
      "--headless=new",
      `--remote-debugging-port=${cdpPort}`,
      "--no-sandbox",
      "--disable-gpu",
      "--disable-dev-shm-usage",
      "--force-device-scale-factor=1",
      "--hide-scrollbars",
      "about:blank",
    ], { stdio: "ignore", detached: true });

    let cdpUp = false;
    for (let i = 0; i < 75 && !cdpUp; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${cdpPort}/json/version`);
        cdpUp = res.ok;
      } catch {}
      if (!cdpUp) await new Promise((r) => setTimeout(r, 200));
    }
    assert.ok(cdpUp, "browser remote debugging port must come up");

    const openPage = async (url) => {
      const res = await withTimeout(fetch(`http://127.0.0.1:${cdpPort}/json/new?${url}`, { method: "PUT" }), 10000, "json/new");
      const target = await res.json();
      const client = new CdpClient(target.webSocketDebuggerUrl);
      await client.connect();
      await client.send("Page.enable");
      await client.send("Runtime.enable");
      return client;
    };
    dev = await openPage(devUrl);
    pub = await openPage(pubUrl);

    const both = async (fn) => Promise.all([fn(dev, "dev"), fn(pub, "published")]);
    const setViewportWidth = (width) =>
      both((client) => client.send("Emulation.setDeviceMetricsOverride", {
        width,
        height: 800,
        deviceScaleFactor: 1,
        mobile: false,
      }));
    await setViewportWidth(900);

    await both(async (client) => {
      await client.evaluate(`
        new Promise((resolve) => {
          if (document.readyState === "complete") setTimeout(resolve, 800);
          else window.addEventListener("load", () => setTimeout(resolve, 800));
        })
      `);
      await client.evaluate(PAGE_HELPERS);
    });

    // Timing instrumentation attributes differ per run (enhance-ms, load-ms,
    // relayout-ms) and their position in the attribute order shifts; they are
    // performance telemetry, not layout truth, so they are stripped for the
    // DOM identity check. Capability detail records which code path produced
    // the verdict (reconcile reuse vs relayout re-enhance), so it is equally
    // timing-dependent; the capability issue name itself stays compared.
    const normalizedMainHtml = `(
      document.querySelector("main") ?? document.body
    ).outerHTML
      .replace(/data-tiqian-(enhance|max-slice|load|relayout|relayout-max-slice)-ms="[^"]*"/g, "")
      .replace(/data-tiqian-capability-detail="[^"]*"/g, "")
      .replace(/\\s+/g, " ")
      .replace(/\\s>/g, ">")`;

    const compareState = async (label, { assertPixels }) => {
      // Settling is sequential with bringToFront: enhancement scheduling on a
      // background tab is throttled, and a hidden page never reaches its first
      // taken-over paragraph within the settle budget.
      const settled = [];
      for (const client of [dev, pub]) {
        await client.send("Page.bringToFront");
        settled.push(await client.evaluate("__settle(45000)"));
      }
      assert.ok(settled[0].settled, `${label}: dev page must settle`);
      assert.ok(settled[1].settled, `${label}: published page must settle`);

      // Both versions mark taken-over paragraphs with data-tq-rendered. If a
      // side has none, its element module failed to load and every comparison
      // below would pass against a raw unenhanced page.
      const renderedCounts = await both((client, side) =>
        client.evaluate(`document.querySelectorAll("tiqian-prose [data-tq-rendered]").length`));
      assert.ok(renderedCounts[0] > 0, `${label}: dev page must show enhanced paragraphs`);
      assert.ok(renderedCounts[1] > 0, `${label}: published page must show enhanced paragraphs (module load failure produces a raw page)`);

      const heights = await both((client) => client.evaluate("document.documentElement.scrollHeight"));
      assert.strictEqual(
        heights[1],
        heights[0],
        `${label}: page heights differ between published and dev (${heights[0]} vs ${heights[1]}); layout output has diverged`,
      );

      // Capture plan is computed once on the dev side and replayed on the
      // published side, so both photographs cover the same regions.
      const plan = await dev.evaluate(`
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

      const devPass = await captureSet(dev, plan);
      const pubPass = await captureSet(pub, plan);
      assert.strictEqual(
        pubPass.pageHeight,
        devPass.pageHeight,
        `${label}: page height changed across capture passes (${devPass.pageHeight} vs ${pubPass.pageHeight})`,
      );

      const failures = [];
      for (const key of Object.keys(devPass.shots)) {
        const result = compareScreenshots(devPass.shots[key], pubPass.shots[key]);
        if (!result.equal) {
          failures.push(`${key}: ${result.differentPixels} differing pixels, first ${result.detail}`);
        }
      }

      const geometry = await both((client) => client.evaluate("__geometry()"));
      const report = geometryReport(geometry[0], geometry[1]);
      const geometryDiff = report.firstDiff;
      const domHtml = await both((client) => client.evaluate(normalizedMainHtml));
      // Diagnostic record of where the two versions diverge; the assertions
      // below turn any divergence into a failure with this context.
      console.log(`[${label}] rendered dev=${settled[0].enhanced} published=${settled[1].enhanced}`);
      if (geometryDiff) {
        console.log(`[${label}] geometry divergence: ${report.childDiffs} line rects, ${report.paraRectDiffs} paragraph rects, max delta ${report.maxDelta}px`);
        console.log(`[${label}] examples:\n  ${report.examples.join("\n  ")}`);
      }
      if (failures.length) console.log(`[${label}] pixel diffs:\n  ${failures.join("\n  ")}`);

      // On a freshly loaded page rasterization is deterministic, so pixel
      // identity is asserted. After host mutations, scroll-triggered re-renders
      // make the same page differ from its own repeated capture (measured:
      // 139k pixels self-noise), so post-mutation states assert engine truth
      // (DOM + geometry) and only record pixel deltas.
      if (assertPixels) {
        assert.deepStrictEqual(
          failures,
          [],
          `${label}: published and dev screenshots must be pixel-identical (full page + every scrolled viewport):\n${failures.join("\n")}`,
        );
      }
      if (domHtml[1] !== domHtml[0] && process.env.TIQIAN_DOM_DUMP_DIR) {
        const { writeFile } = await import("node:fs/promises");
        const slug = label.replace(/[^a-z0-9]+/gi, "-");
        await writeFile(join(process.env.TIQIAN_DOM_DUMP_DIR, `${slug}-dev.html`), domHtml[0]);
        await writeFile(join(process.env.TIQIAN_DOM_DUMP_DIR, `${slug}-pub.html`), domHtml[1]);
      }
      assert.strictEqual(
        domHtml[1],
        domHtml[0],
        `${label}: engine DOM output differs between published and dev (timing attributes stripped)`,
      );
      assert.ok(
        geometryDiff === null,
        `${label}: paragraph geometry diverges between published and dev: ${geometryDiff}`,
      );

      return { shots: Object.keys(devPass.shots).length, pageHeight: heights[0] };
    };

    const results = [];
    for (const width of [900, 700]) {
      await setViewportWidth(width);
      results.push({ phase: `initial@${width}`, ...(await compareState(`initial@${width}`, { assertPixels: true })) });
    }

    // Supported mutations only: appended paragraphs and a removal, applied
    // identically to both sides. A host textContent rewrite has no observation
    // contract in either version and is pinned separately.
    await both((client) => client.evaluate(`
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
        append(6, "mixed",
          "追加混排段。Chrome 的 Canvas API 提供了 measureText() 方法，HarfBuzz 则负责 shaping；中西文之间需要 autospace，数字 3.14 与 95% 保持 Latin 字体。" .repeat(2));
        roots[3].querySelectorAll("p")[1].remove();
      })()
    `));
    for (const width of [940, 700]) {
      await setViewportWidth(width);
      results.push({ phase: `after-dom-change@${width}`, ...(await compareState(`after-dom-change@${width}`, { assertPixels: false })) });
    }

    assert.ok(
      results.every((r) => r.shots >= 5),
      `Each state must compare the full page plus multiple scrolled viewports: ${JSON.stringify(results)}`,
    );

    for (const [i, side] of servers.entries()) {
      assert.deepStrictEqual(
        side.notFound,
        [],
        `server #${i} must not serve any 404; missing assets would silently change rendering: ${side.notFound.join(", ")}`,
      );
    }
  } catch (err) {
    for (const [name, client] of [["dev", dev], ["published", pub]]) {
      if (client?.console?.length) {
        console.log(`${name} console:\n${client.console.join("\n")}`);
      }
    }
    throw err;
  } finally {
    dev?.close();
    pub?.close();
    if (chromeProc?.pid) {
      try { process.kill(-chromeProc.pid, "SIGKILL"); } catch {}
      try { process.kill(chromeProc.pid, "SIGKILL"); } catch {}
    }
    for (const side of servers) side.server.close();
    await rm(tmpBase, { recursive: true, force: true });
  }
});
