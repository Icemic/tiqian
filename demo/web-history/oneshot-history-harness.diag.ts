// oneshot-history-harness.diag.ts — self-contained capture harness for the
// carried fixed demo kit (spec-oneshot-bisect). Ports: demo 8996, CDP 9902.
// Everything is inlined: CdpClient, PNG decode, compareScreenshots, and the
// deep-geometry exports copied verbatim from
// demo/web/tests/helpers/deep-geometry.mjs at 1ad320ce. The comparison
// semantics are frozen by the spec; do not edit the verbatim section.
//
// Usage (inside nix develop):
//   node demo/web-history/oneshot-history-harness.diag.ts \
//     --era demo/web-history/eras/<label>.json --commit <sha> \
//     --runs N [--run-start K]

import { spawn, execFileSync, type ChildProcess } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { createServer, type Server, type IncomingMessage, type ServerResponse } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const repoRoot: string = fileURLToPath(new URL("../..", import.meta.url));
const kitDir: string = fileURLToPath(new URL(".", import.meta.url));
const adaptersDistDir: string = path.join(kitDir, ".dist-adapters");
export const DEMO_PORT: number = 8996;
export const CDP_PORT: number = 9902;
export const VIEWPORT_WIDTH: number = 900;
export const VIEWPORT_HEIGHT: number = 800;

export function compileAdapters(): void {
  mkdirSync(adaptersDistDir, { recursive: true });
  execFileSync("npx", [
    "tsc",
    "-p",
    path.join(kitDir, "tsconfig.json"),
  ], { cwd: repoRoot, stdio: "pipe" });
}

export type Box = [x: number, y: number, width: number, height: number];

export interface KidGeometry {
  k: string;
  b?: Box;
}

export interface ParaGeometry {
  key?: string;
  rect?: Box;
  lineMarks?: Box[];
  kids?: KidGeometry[];
}

export interface RootGeometry {
  root?: Box;
  paras?: ParaGeometry[];
}

export interface DeepGeometryReport {
  pageHeight?: number;
  roots?: RootGeometry[];
}

export interface DeepGeometryStats {
  equal: boolean;
  boxesCompared: number;
  divergentBoxes: number;
  examples: string[];
}

export interface DeepGeometryCounts {
  lineMarks: number;
  runEls: number;
  textNodes: number;
}

export interface CdpWsPendingHandler {
  resolve: (val: unknown) => void;
  reject: (err: unknown) => void;
}

export interface CdpWsMessageError {
  message?: string;
  data?: string;
}

export interface CdpWsMessage {
  id?: number;
  method?: string;
  params?: Record<string, unknown>;
  error?: CdpWsMessageError;
  result?: unknown;
}

export interface CdpEvaluateExceptionDetails {
  text?: string;
  exception?: {
    description?: string;
  };
}

export interface CdpEvaluateResult<T = unknown> {
  exceptionDetails?: CdpEvaluateExceptionDetails;
  result?: {
    value?: T;
  };
}

export interface CdpScreenshotResult {
  data: string;
}

export interface DecodedPng {
  width: number;
  height: number;
  idat: Buffer;
}

export interface DecodedPixels {
  width: number;
  height: number;
  bpp: number;
  pixels: Buffer;
}

export interface ScreenshotComparison {
  equal: boolean;
  differentPixels: number;
  detail: string | null;
}

export interface ParsedArgs {
  era?: string;
  commit?: string;
  runs?: string;
  "run-start"?: string;
  chain?: string;
  [key: string]: string | undefined;
}

export interface EraConfig {
  label: string;
  adapter: string;
  stylesheet: string;
  importMap: Record<string, unknown>;
  static?: Record<string, string>;
}

export interface StaticMount {
  prefix: string;
  dir: string;
}

export interface SettleProbeResult {
  settled: boolean;
  pageHeight: number;
}

export interface ScrollPlan {
  offsets: number[];
  viewportHeight: number;
  pageHeight: number;
}

export interface OffsetCapture {
  offset: number;
  pageHeight?: number;
  selfConsistent?: boolean;
  selfDifferentPixels?: number;
  geometry?: DeepGeometryReport;
  boxesCompared?: number;
  divergentBoxes?: number;
  examples?: string[];
  equal?: boolean;
  offsetDivergent?: boolean;
  selfConsistentCoordinated?: boolean;
  selfConsistentOneShot?: boolean;
  selfDifferentPixelsCoordinated?: number;
  selfDifferentPixelsOneShot?: number;
  countsCoordinated?: DeepGeometryCounts;
  countsOneShot?: DeepGeometryCounts;
  pageHeightCoordinated?: number;
  pageHeightOneShot?: number;
  note?: string;
}

export interface SideCaptureResult {
  plan: ScrollPlan;
  captures: OffsetCapture[];
  endHeight: number;
}

export interface DiagReport {
  terminal: unknown;
  rendered: number;
  renderedValues: (string | null)[];
  issues: number;
  paras: number;
  roots: number;
  firstLineMarks: number;
}

export interface PageStateReport {
  title: string;
  hasRoots: boolean;
  bodyLen: number;
}

export interface RunOnceResult {
  commit: string;
  run: number;
  era: string;
  valid: boolean;
  reason?: string;
  pageState?: PageStateReport | null;
  pageLog?: string[];
  pageHeight?: number;
  diag?: DiagReport | null;
  pageHeightCoordinated?: number;
  pageHeightAfterOneShot?: number;
  pageHeightOneShot?: number;
  divergent?: boolean;
  divergentReasons?: string[];
  offsets?: OffsetCapture[];
  fontsStatus?: string;
  viewport?: { width: number; height: number };
}

export interface ChainCaptureResult {
  commit: string;
  era: string;
  valid: boolean;
  reason?: string;
  geometry?: DeepGeometryReport;
  counts?: DeepGeometryCounts;
  pageHeight?: number;
  selfEqual?: boolean;
  selfDivergentBoxes?: number;
  fontsStatus?: string;
  viewport?: { width: number; height: number };
  pageLog?: string[];
}

export interface CdpTarget {
  type: string;
  url: string;
  webSocketDebuggerUrl: string;
  id?: string;
  title?: string;
}

export interface CdpMessageArg {
  value?: unknown;
  description?: string;
  type?: string;
}

export interface HistoryLogEntry {
  level?: string;
  text?: string;
}

export interface HistoryHttpResponse {
  status?: number;
  url?: string;
}

export interface CdpMessageParams {
  type?: string;
  args?: CdpMessageArg[];
  exceptionDetails?: CdpEvaluateExceptionDetails;
  entry?: HistoryLogEntry;
  response?: HistoryHttpResponse;
  errorText?: string;
  requestId?: string;
}

export interface CdpMessageEventPayload {
  id?: number;
  method?: string;
  params?: CdpMessageParams;
  error?: CdpWsMessageError;
  result?: unknown;
}

// ---------------------------------------------------------------------------
// Verbatim from demo/web/tests/helpers/deep-geometry.mjs (frozen).
// ---------------------------------------------------------------------------
export const DEEP_GEOMETRY_HELPERS: string = `
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
export function diffDeepGeometry(
  a: DeepGeometryReport | null | undefined,
  b: DeepGeometryReport | null | undefined,
): DeepGeometryStats {
  const stats: DeepGeometryStats = { equal: false, boxesCompared: 0, divergentBoxes: 0, examples: [] };
  const note = (msg: string): void => {
    if (stats.examples.length < 10) stats.examples.push(msg);
  };
  const cmpBox = (x: Box | undefined, y: Box | undefined, pathStr: string): void => {
    stats.boxesCompared += 1;
    const same: boolean = Array.isArray(x) && Array.isArray(y) &&
      x.length === y.length && x.every((v: number, i: number): boolean => v === y[i]);
    if (!same) {
      stats.divergentBoxes += 1;
      note(`${pathStr} [${(x ?? []).join(",")}] vs [${(y ?? []).join(",")}]`);
    }
  };
  if ((a?.pageHeight ?? -1) !== (b?.pageHeight ?? -1)) {
    note(`pageHeight ${a?.pageHeight} vs ${b?.pageHeight}`);
  }
  const rootsA: RootGeometry[] = a?.roots ?? [];
  const rootsB: RootGeometry[] = b?.roots ?? [];
  if (rootsA.length !== rootsB.length) note(`rootCount ${rootsA.length} vs ${rootsB.length}`);
  rootsA.forEach((rootA: RootGeometry, ri: number): void => {
    const rootB: RootGeometry | undefined = rootsB[ri];
    if (!rootB) return;
    cmpBox(rootA.root, rootB.root, `root#${ri}`);
    const parasA: ParaGeometry[] = rootA.paras ?? [];
    const parasB: ParaGeometry[] = rootB.paras ?? [];
    if (parasA.length !== parasB.length) note(`root#${ri} paraCount ${parasA.length} vs ${parasB.length}`);
    parasA.forEach((paraA: ParaGeometry, pi: number): void => {
      const paraB: ParaGeometry | undefined = parasB[pi];
      if (!paraB) return;
      const tag: string = `p${paraA.key ?? pi}`;
      cmpBox(paraA.rect, paraB.rect, `${tag}.rect`);
      const marksA: Box[] = paraA.lineMarks ?? [];
      const marksB: Box[] = paraB.lineMarks ?? [];
      if (marksA.length !== marksB.length) note(`${tag} lineMarkCount ${marksA.length} vs ${marksB.length}`);
      marksA.forEach((box: Box, mi: number): void => cmpBox(box, marksB[mi], `${tag}.lineMark[${mi}]`));
      const kidsA: KidGeometry[] = paraA.kids ?? [];
      const kidsB: KidGeometry[] = paraB.kids ?? [];
      if (kidsA.length !== kidsB.length) note(`${tag} childCount ${kidsA.length} vs ${kidsB.length}`);
      kidsA.forEach((kidA: KidGeometry, ki: number): void => {
        const kidB: KidGeometry | undefined = kidsB[ki];
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
export function deepGeometryCounts(report: DeepGeometryReport | null | undefined): DeepGeometryCounts {
  let lineMarks: number = 0;
  let runEls: number = 0;
  let textNodes: number = 0;
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
  readonly wsUrl: string;
  ws: WebSocket | null = null;
  id: number = 0;
  readonly pending: Map<number, CdpWsPendingHandler> = new Map();

  constructor(wsUrl: string) {
    this.wsUrl = wsUrl;
  }

  async connect(): Promise<void> {
    return new Promise<void>((resolve: () => void, reject: (err: unknown) => void): void => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = (): void => resolve();
      this.ws.onerror = (err: unknown): void => reject(err);
      this.ws.onmessage = (event: MessageEvent): void => {
        const msg: CdpWsMessage = JSON.parse(event.data as string) as CdpWsMessage;
        if (msg.id && this.pending.has(msg.id)) {
          const handler: CdpWsPendingHandler | undefined = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (handler) {
            if (msg.error) {
              handler.reject(new Error(msg.error.message || JSON.stringify(msg.error)));
            } else {
              handler.resolve(msg.result);
            }
          }
        }
      };
    });
  }

  async send(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
    const id: number = ++this.id;
    return new Promise<unknown>((resolve: (val: unknown) => void, reject: (err: unknown) => void): void => {
      this.pending.set(id, { resolve, reject });
      this.ws?.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate<T = unknown>(expression: string): Promise<T> {
    const res: CdpEvaluateResult<T> = (await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    })) as CdpEvaluateResult<T>;
    if (res.exceptionDetails) {
      throw new Error(`Runtime exception: ${JSON.stringify(res.exceptionDetails)}`);
    }
    return res.result?.value as T;
  }

  async screenshot(params: Record<string, unknown> = {}): Promise<Buffer> {
    const res: CdpScreenshotResult = (await this.send("Page.captureScreenshot", {
      format: "png",
      ...params,
    })) as CdpScreenshotResult;
    return Buffer.from(res.data, "base64");
  }

  close(): void {
    this.ws?.close();
  }
}

function decodePng(buf: Buffer): DecodedPng {
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

function decodePixels(png: Buffer): DecodedPixels {
  const { width, height, idat } = decodePng(png);
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
  const da: DecodedPixels = decodePixels(a);
  const db: DecodedPixels = decodePixels(b);
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
  for (let i: number = 0; i < pa.length; i += bpp) {
    for (let c: number = 0; c < bpp; c++) {
      if (pa[i + c] !== pb[i + c]) {
        different += 1;
        break;
      }
    }
  }
  return { equal: false, differentPixels: different, detail: null };
}

function parseArgs(argv: string[]): ParsedArgs {
  const args: ParsedArgs = {};
  for (let i: number = 2; i < argv.length; i++) {
    if (argv[i].startsWith("--")) {
      args[argv[i].slice(2)] = argv[i + 1];
      i++;
    }
  }
  return args;
}

const MIME: Record<string, string> = {
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

export function startKitServer(era: EraConfig): Promise<Server> {
  compileAdapters();
  const indexTemplate: string = readFileSync(path.join(kitDir, "index.html"), "utf8");
  const indexHtml: string = indexTemplate.replace(
    /(<script type="importmap" id="era-importmap">)[\s\S]*?(<\/script>)/,
    (_: string, open: string, close: string): string => open + JSON.stringify(era.importMap) + close,
  );
  const adapterBaseName: string = path.basename(era.adapter, path.extname(era.adapter)) + ".js";
  const adapterJsPath: string = path.join(adaptersDistDir, adapterBaseName);
  const adapterJs: string = readFileSync(adapterJsPath, "utf8");
  const stylesheetAbs: string = path.resolve(repoRoot, era.stylesheet);
  const mounts: StaticMount[] = Object.entries(era.static ?? {}).map(([urlPrefix, dir]: [string, string]): StaticMount => ({
    prefix: urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/",
    dir: path.resolve(repoRoot, dir),
  }));

  const server: Server = createServer((req: IncomingMessage, res: ServerResponse): void => {
    const urlPath: string = decodeURIComponent(new URL(req.url ?? "/", "http://x").pathname);
    const send = (status: number, body: string | Buffer, type: string): void => {
      res.writeHead(status, { "content-type": type, "cache-control": "no-store" });
      res.end(body);
    };
    try {
      if (urlPath === "/" || urlPath === "/index.html") {
        send(200, indexHtml, MIME[".html"]);
        return;
      }
      if (urlPath === "/era-adapter.js") {
        send(200, adapterJs, MIME[".js"]);
        return;
      }
      if (urlPath === "/tiqian.css") {
        send(200, readFileSync(stylesheetAbs), MIME[".css"]);
        return;
      }
      for (const mount of mounts) {
        if (urlPath.startsWith(mount.prefix)) {
          const rel: string = urlPath.slice(mount.prefix.length);
          const abs: string = path.resolve(mount.dir, rel);
          if (!abs.startsWith(mount.dir)) {
            send(403, "forbidden", "text/plain");
            return;
          }
          const ext: string = path.extname(abs);
          send(200, readFileSync(abs), MIME[ext] ?? "application/octet-stream");
          return;
        }
      }
      send(404, "not found: " + urlPath, "text/plain");
    } catch (error: unknown) {
      send(500, String(error), "text/plain");
    }
  });
  return new Promise<Server>((resolve: (server: Server) => void): void => {
    server.listen(DEMO_PORT, "127.0.0.1", (): void => resolve(server));
  });
}

export async function waitForCdpEndpoint(port: number, timeoutMs: number = 20000): Promise<void> {
  const start: number = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res: Response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return;
    } catch {
      // retry
    }
    await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 200));
  }
  throw new Error(`Timeout waiting for CDP on ${port}`);
}

// Settle gate: terminal flag plus a stable rendered-subtree fingerprint
// (three consecutive 350ms checks), the same quiescence protocol the HEAD
// test uses before every capture.
export const SETTLE_HELPERS: string = `
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

async function captureSide(client: CdpClient, label: string): Promise<SideCaptureResult> {
  // Returns per-offset captures: two screenshots (self-consistency probe)
  // plus one deep geometry report each, at the top and every scroll step.
  await client.evaluate("window.scrollTo(0, 0)");
  const topSettle: SettleProbeResult = await client.evaluate<SettleProbeResult>("__historySettle(20000)");
  if (!topSettle.settled) throw new Error(`${label}: page did not settle at top`);
  const plan: ScrollPlan = await client.evaluate<ScrollPlan>(`
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
  const captures: OffsetCapture[] = [];
  for (const offset of plan.offsets) {
    await client.evaluate(`window.scrollTo(0, ${offset})`);
    await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 500));
    const settled: SettleProbeResult = await client.evaluate<SettleProbeResult>("__historySettle(20000)");
    if (!settled.settled) throw new Error(`${label}: page did not settle at scroll ${offset}`);
    const clip = {
      x: 0,
      y: offset,
      width: VIEWPORT_WIDTH,
      height: Math.min(plan.viewportHeight, plan.pageHeight - offset),
      scale: 1,
    };
    const shot1: Buffer = await client.screenshot({ clip, captureBeyondViewport: true });
    const shot2: Buffer = await client.screenshot({ clip, captureBeyondViewport: true });
    const geometry: DeepGeometryReport = await client.evaluate<DeepGeometryReport>("__deepGeometry()");
    const self: ScreenshotComparison = compareScreenshots(shot1, shot2);
    captures.push({
      offset,
      pageHeight: settled.pageHeight,
      selfConsistent: self.equal,
      selfDifferentPixels: self.equal ? 0 : self.differentPixels,
      geometry,
    });
  }
  await client.evaluate("window.scrollTo(0, 0)");
  const endHeight: number = await client.evaluate<number>("document.documentElement.scrollHeight");
  return { plan, captures, endHeight };
}

async function runOnce(
  client: CdpClient,
  era: EraConfig,
  commit: string,
  runIndex: number,
  pageLog: string[],
): Promise<RunOnceResult> {
  pageLog.length = 0;
  await client.send("Page.navigate", { url: "about:blank" });
  await client.evaluate("0");
  await client.send("Page.navigate", { url: `http://127.0.0.1:${DEMO_PORT}/` });
  await client.evaluate(
    "new Promise((r) => { if (document.readyState === 'complete') setTimeout(r, 300); else window.addEventListener('load', () => setTimeout(r, 300)); })",
  );

  const readyDeadline: number = Date.now() + 30000;
  let ready: boolean = false;
  let pageError: string | null = null;
  while (Date.now() < readyDeadline) {
    ready = await client.evaluate<boolean>("globalThis.__historyReady === true").catch((e: unknown): boolean => {
      pageError = String(e);
      return false;
    });
    if (ready) break;
    await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 250));
  }
  if (!ready) {
    const consoleErrors: PageStateReport | null = await client.evaluate<PageStateReport>(`(() => {
      const el = document.querySelector("tiqian-prose");
      return { title: document.title, hasRoots: !!el, bodyLen: document.body?.innerText?.length ?? 0 };
    })()`).catch((): null => null);
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
  const enhanced: SettleProbeResult = await client.evaluate<SettleProbeResult>("__historySettle(60000)");
  if (!enhanced.settled) {
    const diag: DiagReport | null = await client.evaluate<DiagReport>(`(() => ({
      terminal: globalThis.__historyTerminal ? __historyTerminal() : null,
      rendered: Array.from(document.querySelectorAll("[data-tq-rendered]")).length,
      renderedValues: Array.from(new Set(Array.from(document.querySelectorAll("[data-tq-rendered]"), (p) => p.getAttribute("data-tq-rendered")))),
      issues: Array.from(document.querySelectorAll("[data-tiqian-capability-issue]")).length,
      paras: document.querySelectorAll("tiqian-prose p, tiqian-prose li").length,
      roots: document.querySelectorAll("tiqian-prose").length,
      firstLineMarks: document.querySelectorAll("[data-tq-line-index]").length,
    }))()`).catch((): null => null);
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: "coordinated enhance did not reach the terminal settle",
      pageHeight: enhanced.pageHeight,
      diag,
      pageLog: pageLog.slice(0, 40),
    };
  }
  await client.evaluate(DEEP_GEOMETRY_HELPERS);

  let coordinated: SideCaptureResult;
  try {
    coordinated = await captureSide(client, "coordinated");
  } catch (error: unknown) {
    const err: Error = error instanceof Error ? error : new Error(String(error));
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: `coordinated capture failed: ${err.message}`,
      pageLog: pageLog.slice(0, 40),
    };
  }

  await client.evaluate("globalThis.__historyOneShot()");
  await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 800));
  const afterOneShot: SettleProbeResult = await client.evaluate<SettleProbeResult>("__historySettle(60000)");
  if (!afterOneShot.settled) {
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: "one-shot did not reach the terminal settle",
      pageHeightCoordinated: enhanced.pageHeight,
      pageHeightAfterOneShot: afterOneShot.pageHeight,
      pageLog: pageLog.slice(0, 40),
    };
  }

  let oneshot: SideCaptureResult;
  try {
    oneshot = await captureSide(client, "one-shot");
  } catch (error: unknown) {
    const err: Error = error instanceof Error ? error : new Error(String(error));
    return {
      commit, run: runIndex, era: era.label, valid: false,
      reason: `one-shot capture failed: ${err.message}`,
      pageHeightCoordinated: coordinated.endHeight,
      pageLog: pageLog.slice(0, 40),
    };
  }

  const offsets: OffsetCapture[] = [];
  let divergent: boolean = false;
  const divergentReasons: string[] = [];
  const count: number = coordinated.captures.length;
  for (let i: number = 0; i < count; i++) {
    const a: OffsetCapture = coordinated.captures[i];
    const b: OffsetCapture | undefined = oneshot.captures[i];
    const offset: number = a.offset;
    if (b?.offset !== offset) {
      divergent = true;
      divergentReasons.push(`scroll plan mismatch at index ${i}: ${a.offset} vs ${b?.offset}`);
      offsets.push({ offset, note: "plan mismatch" });
      continue;
    }
    const diff: DeepGeometryStats = diffDeepGeometry(a.geometry, b.geometry);
    const countsA: DeepGeometryCounts = deepGeometryCounts(a.geometry);
    const countsB: DeepGeometryCounts = deepGeometryCounts(b.geometry);
    const vacuous: boolean =
      countsA.lineMarks === 0 && countsA.runEls === 0 && countsA.textNodes === 0;
    let offsetDivergent: boolean = false;
    const why: string[] = [];
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

  const fontsStatus: string = await client.evaluate<string>("document.fonts.status");
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

export async function chainCapture(
  client: CdpClient,
  era: EraConfig,
  commit: string,
  pageLog: string[],
): Promise<ChainCaptureResult> {
  pageLog.length = 0;
  await client.send("Page.navigate", { url: "about:blank" });
  await client.evaluate("0");
  await client.send("Page.navigate", { url: `http://127.0.0.1:${DEMO_PORT}/` });
  await client.evaluate(
    "new Promise((r) => { if (document.readyState === 'complete') setTimeout(r, 300); else window.addEventListener('load', () => setTimeout(r, 300)); })",
  );

  const readyDeadline: number = Date.now() + 30000;
  let ready: boolean = false;
  while (Date.now() < readyDeadline) {
    ready = await client.evaluate<boolean>("globalThis.__historyReady === true").catch((): boolean => false);
    if (ready) break;
    await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 250));
  }
  if (!ready) {
    return { commit, era: era.label, valid: false, reason: "adapter never became ready", pageLog: pageLog.slice(0, 40) };
  }
  await client.evaluate(SETTLE_HELPERS);
  await client.evaluate("document.fonts.ready");
  await client.evaluate("globalThis.__historyEnhance()");
  const enhanced: SettleProbeResult = await client.evaluate<SettleProbeResult>("__historySettle(60000)");
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
  const geometryA: DeepGeometryReport = await client.evaluate<DeepGeometryReport>("__deepGeometry()");
  await new Promise<void>((resolve: () => void): NodeJS.Timeout => setTimeout(resolve, 400));
  const geometryB: DeepGeometryReport = await client.evaluate<DeepGeometryReport>("__deepGeometry()");
  const selfDiff: DeepGeometryStats = diffDeepGeometry(geometryA, geometryB);
  const fontsStatus: string = await client.evaluate<string>("document.fonts.status");
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

async function main(): Promise<void> {
  const args: ParsedArgs = parseArgs(process.argv);
  if (!args.era || !args.commit || (!args.runs && !args.chain)) {
    throw new Error("required: --era <json> --commit <sha> (--runs N [--run-start K] | --chain <label>)");
  }
  const era: EraConfig = JSON.parse(readFileSync(path.resolve(repoRoot, args.era), "utf8")) as EraConfig;
  const runs: number = args.runs ? Number(args.runs) : 0;
  const runStart: number = Number(args["run-start"] ?? 1);

  const outDir: string = path.resolve(repoRoot, ".agent-specs/oneshot-bisect-evidence", args.commit);
  mkdirSync(outDir, { recursive: true });

  const server: Server = await startKitServer(era);
  const chromeBin: string = process.env.CHROME_BIN || "chromium";
  const browserProc: ChildProcess = spawn(chromeBin, [
    "--headless=new",
    `--remote-debugging-port=${CDP_PORT}`,
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--force-device-scale-factor=1",
    "--hide-scrollbars",
    "about:blank",
  ], { stdio: "ignore", detached: true });

  let client: CdpClient | null = null;
  try {
    await waitForCdpEndpoint(CDP_PORT);
    const listRes: Response = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`);
    const targets: CdpTarget[] = (await listRes.json()) as CdpTarget[];
    const pageTarget: CdpTarget | undefined = targets.find((tr: CdpTarget): boolean => tr.type === "page" && tr.url === "about:blank");
    if (!pageTarget) throw new Error("no blank page target");
    client = new CdpClient(pageTarget.webSocketDebuggerUrl);
    await client.connect();
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    const pageLog: string[] = [];
    client.ws?.addEventListener("message", (event: MessageEvent): void => {
      const msg: CdpMessageEventPayload = JSON.parse(event.data as string) as CdpMessageEventPayload;
      if (msg.method === "Runtime.consoleAPICalled") {
        const text: string = (msg.params?.args ?? []).map((a: CdpMessageArg): string => String(a.value ?? a.description ?? a.type)).join(" ");
        pageLog.push(`console.${msg.params?.type}: ${text}`.slice(0, 400));
      } else if (msg.method === "Runtime.exceptionThrown") {
        const d: CdpEvaluateExceptionDetails | undefined = msg.params?.exceptionDetails;
        pageLog.push(`exception: ${d?.exception?.description ?? d?.text ?? "?"}`.slice(0, 400));
      } else if (msg.method === "Log.entryAdded") {
        pageLog.push(`log.${msg.params?.entry?.level}: ${msg.params?.entry?.text}`.slice(0, 400));
      } else if (msg.method === "Network.responseReceived" && (msg.params?.response?.status ?? 0) >= 400) {
        pageLog.push(`http ${msg.params?.response?.status}: ${msg.params?.response?.url}`.slice(0, 400));
      } else if (msg.method === "Network.loadingFailed") {
        pageLog.push(`net-failed: ${msg.params?.errorText} (${msg.params?.requestId})`.slice(0, 400));
      }
    });
    await client.send("Runtime.setAsyncCallStackDepth", { maxDepth: 0 }).catch((): void => {});
    await client.send("Log.enable").catch((): void => {});
    await client.send("Network.enable").catch((): void => {});
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: VIEWPORT_WIDTH,
      height: VIEWPORT_HEIGHT,
      deviceScaleFactor: 1,
      mobile: false,
    });

    for (let i: number = 0; i < runs; i++) {
      const runIndex: number = runStart + i;
      const record: RunOnceResult = await runOnce(client, era, args.commit, runIndex, pageLog);
      const file: string = path.join(outDir, `${runIndex}.json`);
      writeFileSync(file, JSON.stringify(record, null, 2));
      const summary: string = record.valid
        ? `divergent=${record.divergent} boxes=${record.offsets?.[0]?.boxesCompared} pageH=${record.pageHeightCoordinated}/${record.pageHeightOneShot}`
        : `INVALID: ${record.reason}`;
      console.log(`[${era.label} ${args.commit.slice(0, 8)} run ${runIndex}] ${summary} -> ${path.relative(repoRoot, file)}`);
    }

    if (args.chain) {
      const record: ChainCaptureResult = await chainCapture(client, era, args.commit, pageLog);
      const file: string = path.join(outDir, `chain-${args.chain}.json`);
      writeFileSync(file, JSON.stringify(record, null, 2));
      const summary: string = record.valid
        ? `chain counts=${record.counts?.lineMarks}lm/${record.counts?.runEls}el/${record.counts?.textNodes}tx pageH=${record.pageHeight} selfEqual=${record.selfEqual}`
        : `INVALID: ${record.reason}`;
      console.log(`[${era.label} ${args.commit.slice(0, 8)} chain ${args.chain}] ${summary} -> ${path.relative(repoRoot, file)}`);
    }
  } finally {
    client?.close();
    server.close();
    if (browserProc.pid) {
      try { process.kill(-browserProc.pid, "SIGKILL"); } catch {}
      try { process.kill(browserProc.pid, "SIGKILL"); } catch {}
    }
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error: unknown): void => {
    console.error(error);
    process.exit(1);
  });
}
