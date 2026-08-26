// Snapshot-table transport of ADR 0052 `TableTransport`. Roots carry a
// `tq-tables` attribute with space-separated references; each reference is a
// URL of a content-addressed `TIQTBL03` binary table file. One global map
// deduplicates loads per reference, so every root of a page shares one table
// instance. The decoded view travels on so the layout worker rebuilds the
// table in its own context.

import { decodeSnapshotTableBinary, isSnapshotTableBinary } from "./snapshot-table-binary.js";
import type { SnapshotTableBinaryView } from "./snapshot-table-binary.js";
import { globalServices } from "../../services/global-services.js";

const TABLES_ATTRIBUTE = "tq-tables";

export interface LoadedSnapshotTable {
  bytes: Uint8Array;
  view: SnapshotTableBinaryView;
  sha256: string;
}

/** Reference key to loaded table; successes stay for the page lifetime. */
const loadedTables = (): Map<string, Promise<LoadedSnapshotTable>> =>
  globalServices().snapshotTables!.loadedTables as Map<string, Promise<LoadedSnapshotTable>>;
/** Reference key to the resolved, verified table, readable synchronously. */
const resolvedTables = (): Map<string, LoadedSnapshotTable> =>
  globalServices().snapshotTables!.resolvedTables as Map<string, LoadedSnapshotTable>;

/**
 * Builds the accessor surface of one table file. The binary form is the only
 * file form this build reads; any other bytes fail closed.
 */
export function snapshotTablesFromBytes(bytes: Uint8Array): SnapshotTableBinaryView {
  if (!(bytes instanceof Uint8Array)) throw new Error("SnapshotTablesInvalid");
  if (!isSnapshotTableBinary(bytes)) throw new Error("SnapshotTablesInvalid");
  return decodeSnapshotTableBinary(bytes);
}

async function digestBytes(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function loadTableBytes(key: string): Promise<Uint8Array<ArrayBuffer>> {
  if (typeof globalThis.fetch !== "function") {
    throw new Error("SnapshotTablesFetchUnavailable");
  }
  const response = await globalThis.fetch(key);
  if (!response?.ok) throw new Error("SnapshotTablesFetchFailed");
  const buffer = await response.arrayBuffer();
  return new Uint8Array(buffer);
}

function tablePromiseFor(key: string): Promise<LoadedSnapshotTable> {
  const tables = loadedTables();
  let promise = tables.get(key);
  if (promise) return promise;
  // Failed loads stay uncached so a later root can retry after a transient
  // network failure; the map only memoizes verified tables.
  promise = (async (): Promise<LoadedSnapshotTable> => {
    const bytes = await loadTableBytes(key);
    const table: LoadedSnapshotTable = {
      bytes,
      view: snapshotTablesFromBytes(bytes),
      sha256: await digestBytes(bytes),
    };
    resolvedTables().set(key, table);
    return table;
  })();
  promise.catch(() => tables.delete(key));
  tables.set(key, promise);
  return promise;
}

function tableReferences(root: Element | null): string[] {
  const raw = root?.getAttribute?.(TABLES_ATTRIBUTE);
  if (typeof raw !== "string" || raw.trim() === "") return [];
  return raw.split(/\s+/u).filter((value) => value.length > 0);
}

/**
 * Resolves the snapshot tables of one root. Every reference of the attribute
 * loads in order until one verifies against the sha the manifest pins; a
 * mismatch keeps walking the ladder so a stale file degrades the same way a
 * missing one does.
 */
export async function snapshotTablesForRoot(
  root: Element | null,
  expectedSha256: string | null = null,
): Promise<LoadedSnapshotTable | null> {
  for (const key of tableReferences(root)) {
    let table: LoadedSnapshotTable;
    try {
      table = await tablePromiseFor(key);
    } catch {
      continue;
    }
    if (expectedSha256 == null || table.sha256 === expectedSha256) return table;
  }
  return null;
}

/**
 * The already-verified table of one root, or null when no reference of the
 * attribute has finished loading. Synchronous callers use this as a
 * best-effort cache read; adoption paths resolve tables through the async
 * map instead.
 */
export function loadedSnapshotTablesForRoot(
  root: Element | null,
  expectedSha256: string | null = null,
): LoadedSnapshotTable | null {
  const resolved = resolvedTables();
  for (const key of tableReferences(root)) {
    const table = resolved.get(key);
    if (table == null) continue;
    if (expectedSha256 == null || table.sha256 === expectedSha256) return table;
  }
  return null;
}

/**
 * Pre-scans the document for table references and starts the loads before
 * the first root hydrates; ADR 0052 keeps this free of ordering contracts,
 * so a reference no root claims simply stays unused.
 */
export function prefetchSnapshotTables(): void {
  const documentObject = globalThis.document;
  if (!documentObject?.querySelectorAll) return;
  for (const element of documentObject.querySelectorAll(`[${TABLES_ATTRIBUTE}]`)) {
    for (const key of tableReferences(element)) {
      tablePromiseFor(key).catch(() => {});
    }
  }
}
