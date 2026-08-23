// Snapshot-table delivery for the Astro integration (ADR 0052
// `TableTransport`). The preparer freezes per-item table bytes; this module
// owns the three delivery points a static Astro host needs: the dev-server
// middleware, the end-of-build shipping pass, and the option validation the
// integration entry applies.

import { copyFile, mkdir, readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { createSnapshotTableFileTransport } from "@tiqian/precompute/transport";

/**
 * Validates the integration's `tables` option into transport inputs: an
 * absolute directory plus the URL shape. Returns null when the host
 * configured none, so the preparer lane stays on inline manifests.
 */
export function normalizeTiqianAstroTablesOptions(options) {
  if (options == null) return null;
  if (typeof options !== "object") throw new Error("TiqianAstroTablesOptionsInvalid");
  const directory = options.directory;
  if (typeof directory !== "string" && !(directory instanceof URL)) {
    throw new Error("TiqianAstroTablesDirectoryRequired");
  }
  return {
    directory: directory instanceof URL ? fileURLToPath(directory) : path.resolve(directory),
    urlPrefix: options.urlPrefix ?? "tiqian-tables",
    extension: options.extension ?? ".tiqtbl",
  };
}

/** Builds the file transport of one normalized `tables` option. */
export function tiqianAstroTables(normalized) {
  return normalized == null ? null : createSnapshotTableFileTransport(normalized);
}

/** Parses the sha out of one transport URL; null when the URL is not one. */
export function shaOfTiqianAstroTableUrl(transport, url) {
  const match = transport.urlPattern().exec(String(url));
  return match === null ? null : match[1];
}

/**
 * The dev-server middleware: serves `GET <urlPrefix>/<sha><extension>` from
 * the transport directory under the same URL the built output ships.
 */
export function tiqianAstroTableMiddleware(transport) {
  return (request, response, next) => {
    if (request.method !== "GET" || typeof request.url !== "string") {
      next();
      return;
    }
    const sha = shaOfTiqianAstroTableUrl(transport, request.url.split("?")[0]);
    const bytes = sha === null ? undefined : transport.read(sha);
    if (bytes === undefined) {
      next();
      return;
    }
    response.setHeader("content-type", "application/octet-stream");
    // A table name addresses immutable bytes, so one name never serves two
    // contents over the file's lifetime.
    response.setHeader("cache-control", "public, max-age=31536000, immutable");
    response.end(bytes);
  };
}

async function htmlFilesUnder(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await htmlFilesUnder(file)));
    else if (entry.name.endsWith(".html")) files.push(file);
  }
  return files;
}

/**
 * The end-of-build shipping pass: every table the built pages reference is
 * copied into the output verbatim under the transport URL prefix; the
 * content-addressed cache directory then keeps only what some build pins.
 */
export async function shipTiqianAstroTables(transport, outputDirectory, logger) {
  const pattern = transport.urlPattern();
  const referenced = new Set();
  for (const file of await htmlFilesUnder(outputDirectory)) {
    const html = await readFile(file, "utf8");
    for (const match of html.matchAll(pattern)) {
      referenced.add(match[1]);
    }
  }
  const sample = transport.urlFor("0".repeat(64));
  const outputTables = path.join(outputDirectory, path.dirname(sample));
  await mkdir(outputTables, { recursive: true });
  const missing = [];
  for (const sha of referenced) {
    try {
      await copyFile(
        path.join(transport.directory, path.basename(transport.urlFor(sha))),
        path.join(outputTables, path.basename(transport.urlFor(sha))),
      );
    } catch (error) {
      missing.push(sha);
      const detail = error instanceof Error ? `: ${error.message}` : "";
      logger?.error(`missing snapshot table ${sha}${detail}`);
    }
  }
  const swept = transport.sweep(referenced);
  if (referenced.size > 0 || swept > 0) {
    logger?.info(`snapshot tables: ${referenced.size - missing.length} shipped, ${swept} swept`);
  }
  return { shipped: referenced.size - missing.length, swept, missing };
}
