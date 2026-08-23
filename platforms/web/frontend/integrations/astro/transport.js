import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const SNAPSHOT_ASSETS =
  /<!--tiqian-astro-assets:([A-Za-z][A-Za-z0-9_-]*)-->([\s\S]*?)<!--\/tiqian-astro-assets:\1-->/gu;
const FONT_PRELOAD =
  /<link rel="preload" as="font" type="font\/woff2" crossorigin href="([^"]+)">/gu;

export function renderAstroSnapshotAssets(id, html) {
  if (!id || !html) return "";
  return `<!--tiqian-astro-assets:${id}-->${html}<!--/tiqian-astro-assets:${id}-->`;
}

function dedupeFontPreloads(html) {
  const seen = new Set();
  return html.replace(FONT_PRELOAD, (link, href) => {
    if (seen.has(href)) return "";
    seen.add(href);
    return link;
  });
}

export function hoistTiqianAstroAssets(htmlValue) {
  const source = String(htmlValue);
  const snapshots = new Map();
  const html = source.replace(SNAPSHOT_ASSETS, (_whole, id, assets) => {
    if (snapshots.has(id) && snapshots.get(id) !== assets) {
      throw new Error(`ConflictingTiqianAstroAssets:${id}`);
    }
    snapshots.set(id, assets);
    return "";
  });
  if (snapshots.size === 0) return Object.freeze({ html: source, count: 0 });
  if (!html.includes("</head>")) throw new Error("TiqianAstroHeadUnavailable");
  const assets = dedupeFontPreloads(Array.from(snapshots.values()).join(""));
  return Object.freeze({
    html: html.replace("</head>", () => `${assets}\n</head>`),
    count: snapshots.size,
  });
}

async function htmlFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await htmlFiles(file));
    else if (entry.name.endsWith(".html")) files.push(file);
  }
  return files;
}

export async function hoistTiqianAstroDirectory(directory) {
  let pageCount = 0;
  let snapshotCount = 0;
  for (const file of await htmlFiles(directory)) {
    const result = hoistTiqianAstroAssets(await readFile(file, "utf8"));
    if (result.count === 0) continue;
    await writeFile(file, result.html);
    pageCount += 1;
    snapshotCount += result.count;
  }
  return Object.freeze({ pageCount, snapshotCount });
}
