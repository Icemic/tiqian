import { readdir, readFile, writeFile } from "node:fs/promises";
import type { Dirent } from "node:fs";
import path from "node:path";

const SNAPSHOT_ASSETS =
  /<!--tiqian-astro-assets:([A-Za-z][A-Za-z0-9_-]*)-->([\s\S]*?)<!--\/tiqian-astro-assets:\1-->/gu;
const FONT_PRELOAD =
  /<link rel="preload" as="font" type="font\/woff2" crossorigin href="([^"]+)">/gu;

export interface HoistTiqianAstroAssetsResult {
  readonly html: string;
  readonly count: number;
}

export interface HoistTiqianAstroDirectoryResult {
  readonly pageCount: number;
  readonly snapshotCount: number;
}

export function renderAstroSnapshotAssets(
  id: string | null | undefined,
  html: string | null | undefined,
): string {
  if (!id || !html) return "";
  return `<!--tiqian-astro-assets:${id}-->${html}<!--/tiqian-astro-assets:${id}-->`;
}

function dedupeFontPreloads(html: string): string {
  const seen = new Set<string>();
  return html.replace(FONT_PRELOAD, (link: string, href: string): string => {
    if (seen.has(href)) return "";
    seen.add(href);
    return link;
  });
}

export function hoistTiqianAstroAssets(htmlValue: unknown): HoistTiqianAstroAssetsResult {
  const source = String(htmlValue);
  const snapshots = new Map<string, string>();
  const html = source.replace(
    SNAPSHOT_ASSETS,
    (_whole: string, id: string, assets: string): string => {
      if (snapshots.has(id) && snapshots.get(id) !== assets) {
        throw new Error(`ConflictingTiqianAstroAssets:${id}`);
      }
      snapshots.set(id, assets);
      return "";
    },
  );
  if (snapshots.size === 0) return Object.freeze({ html: source, count: 0 });
  if (!html.includes("</head>")) throw new Error("TiqianAstroHeadUnavailable");
  const assets = dedupeFontPreloads(Array.from(snapshots.values()).join(""));
  return Object.freeze({
    html: html.replace("</head>", (): string => `${assets}\n</head>`),
    count: snapshots.size,
  });
}

async function htmlFiles(directory: string): Promise<string[]> {
  const files: string[] = [];
  const entries: Dirent[] = await readdir(directory, { withFileTypes: true });
  for (const entry of entries) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      const nested = await htmlFiles(file);
      files.push(...nested);
    } else if (entry.name.endsWith(".html")) {
      files.push(file);
    }
  }
  return files;
}

export async function hoistTiqianAstroDirectory(
  directory: string,
): Promise<HoistTiqianAstroDirectoryResult> {
  let pageCount = 0;
  let snapshotCount = 0;
  const files = await htmlFiles(directory);
  for (const file of files) {
    const content = await readFile(file, "utf8");
    const result = hoistTiqianAstroAssets(content);
    if (result.count === 0) continue;
    await writeFile(file, result.html);
    pageCount += 1;
    snapshotCount += result.count;
  }
  return Object.freeze({ pageCount, snapshotCount });
}
