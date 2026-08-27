import assert from "node:assert/strict";
import test from "node:test";

import {
  loadedSnapshotTablesForRoot,
  prefetchSnapshotTables,
  snapshotTablesForRoot,
  snapshotTablesFromBytes,
} from "../core/sampler/snapshot/snapshot-tables.js";
import type { LoadedSnapshotTable } from "../core/sampler/snapshot/snapshot-tables.js";
import { writeBinaryTable } from "../core/sampler/snapshot/table-binary-writer.mjs";
import type { BinaryTableInput } from "../core/sampler/snapshot/table-binary-writer.d.mts";

interface RootWithAttribute {
  getAttribute: GetAttributeFn;
}

type GetAttributeFn = (name: string) => string | null;

// For testing, we need an Element with just getAttribute. Use a partial mock.
interface MockElement extends Partial<Element> {
  getAttribute: GetAttributeFn;
}

interface FetchStub {
  calls: string[];
  restore: RestoreFn;
}

type RestoreFn = () => void;

interface FetchResponse {
  ok: boolean;
  arrayBuffer: ArrayBufferFn;
}

type ArrayBufferFn = () => Promise<ArrayBuffer>;

type Sha256BytesFn = (bytes: Uint8Array) => Promise<string>;

async function sha256Bytes(bytes: Uint8Array): Promise<string> {
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const digest = await globalThis.crypto.subtle.digest("SHA-256", buffer as ArrayBuffer);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function rootWithTables(attribute: string): MockElement {
  return { getAttribute: (name) => (name === "tq-tables" ? attribute : null) };
}

/** Installs a fetch stub keyed by URL; each entry is [responses...], popped per call. */
function installFetch(responses: Record<string, Array<Uint8Array | Error>>): FetchStub {
  const calls: string[] = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    calls.push(url as string);
    const reply = responses[url as string]?.shift();
    if (reply instanceof Error) throw reply;
    if (reply == null) throw new Error("No response configured");
    const arrayBufferResponse: ArrayBufferFn = async () => {
      return reply.buffer.slice(reply.byteOffset, reply.byteOffset + reply.byteLength) as ArrayBuffer;
    };
    // Build the response object matching Response interface minimally for our test
    const fetchResponse = {
      ok: true,
      arrayBuffer: arrayBufferResponse,
    };
    return fetchResponse as Response;
  };
  return {
    calls,
    restore() {
      globalThis.fetch = previousFetch;
    },
  };
}

const tableInput: BinaryTableInput = {
  replayStrings: [],
  metrics: [],
  probes: [{ text: "中", advancePx: 18, fontSizePx: 18, fontWeight: 400, italic: false, script: "Hani", language: "zh-Hans", features: [] }],
  typographies: [{ sha256: "t".repeat(64), value: { fontFamilies: ["Fixture CJK"] } }],
  faces: [],
  valueStyles: [],
  fontPreloads: [],
  revisions: { backendRevision: "fixture-backend", harfbuzzVersion: "fixture-hb" },
};

const TABLE_BYTES = writeBinaryTable(tableInput);

const otherTableInput: BinaryTableInput = {
  replayStrings: [],
  metrics: [],
  probes: [],
  typographies: [],
  faces: [],
  valueStyles: [".tq-root { line-height: 1.7; }"],
  fontPreloads: [],
  revisions: {},
};

const OTHER_TABLE_BYTES = writeBinaryTable(otherTableInput);

test("table references load by url and dedupe through the global map", async () => {
  const key = "https://tables.test/dedupe-deadbeef.tiqtbl";
  const stub = installFetch({ [key]: [TABLE_BYTES] });
  try {
    const root = rootWithTables(key);
    const first = await snapshotTablesForRoot(root as Element);
    if (first == null) throw new Error("First table load failed");
    const second = await snapshotTablesForRoot(root as Element, first.sha256);
    if (second == null) throw new Error("Second table load failed");
    assert.equal(second.sha256, first.sha256);
    assert.deepEqual([...second.bytes], [...first.bytes]);
    assert.equal(first.view.binary, true);
    assert.equal(first.view.revisions().backendRevision, "fixture-backend");
    assert.equal(first.sha256, await sha256Bytes(TABLE_BYTES));
    assert.deepEqual(stub.calls, [key]);
    const loaded = loadedSnapshotTablesForRoot(root as Element);
    if (loaded == null) throw new Error("Loaded table not found");
    assert.equal(loaded.sha256, first.sha256);
  } finally {
    stub.restore();
  }
});

test("failed loads stay uncached so a later root can retry", async () => {
  const key = "https://tables.test/retry-deadbeef.tiqtbl";
  const stub = installFetch({
    [key]: [new Error("offline"), TABLE_BYTES],
  });
  try {
    const failing = rootWithTables(key) as Element;
    const failingResult = await snapshotTablesForRoot(failing);
    assert.equal(failingResult, null);
    const loadedAfterFail = loadedSnapshotTablesForRoot(failing);
    assert.equal(loadedAfterFail, null);
    const retryingRoot = rootWithTables(key) as Element;
    const retrying = await snapshotTablesForRoot(retryingRoot);
    if (retrying == null) throw new Error("Retry failed");
    assert.deepEqual(retrying.view.valueStyles(), []);
    assert.deepEqual(stub.calls, [key, key]);
  } finally {
    stub.restore();
  }
});

test("a digest mismatch walks to the next reference of the attribute", async () => {
  const stale = "https://tables.test/stale-cafe.tiqtbl";
  const fresh = "https://tables.test/fresh-beef.tiqtbl";
  const stub = installFetch({ [stale]: [TABLE_BYTES], [fresh]: [OTHER_TABLE_BYTES] });
  try {
    const expected = await sha256Bytes(OTHER_TABLE_BYTES);
    const fallbackRoot = rootWithTables(`${stale} ${fresh}`) as Element;
    const table = await snapshotTablesForRoot(
      fallbackRoot,
      expected,
    );
    if (table == null) throw new Error("Table load with fallback failed");
    assert.deepEqual([...table.bytes], [...OTHER_TABLE_BYTES]);
    assert.deepEqual(stub.calls, [stale, fresh]);
    const staleOnlyRoot = rootWithTables(stale) as Element;
    const staleOnly = await snapshotTablesForRoot(staleOnlyRoot, expected);
    assert.equal(staleOnly, null);
  } finally {
    stub.restore();
  }
});

test("bytes without the snapshot-table magic fail closed", () => {
  assert.throws(
    () => snapshotTablesFromBytes(new TextEncoder().encode("{\"schema\":2}")),
    /SnapshotTablesInvalid/u,
  );
  assert.throws(
    () => snapshotTablesFromBytes(new Uint8Array([0x7b, 0x22, 0x61, 0xff, 0xff])),
    /SnapshotTablesInvalid/u,
  );
  assert.throws(
    () => snapshotTablesFromBytes(TABLE_BYTES.subarray(0, TABLE_BYTES.length - 1)),
    /SnapshotTablesInvalid/u,
  );
});

interface MockDocument {
  querySelectorAll: DocumentQuerySelectorAllFn;
}

type DocumentQuerySelectorAllFn = (selector: string) => Element[];

test("the document pre-scan starts loading every referenced table", async () => {
  const key = "https://tables.test/prefetch-deadbeef.tiqtbl";
  const stub = installFetch({ [key]: [TABLE_BYTES] });
  const previousDocument = globalThis.document;
  // Create minimal document mock for testing
  const querySelectorAllFn = (selector: string) => {
    return selector === "[tq-tables]" ? [rootWithTables(key) as Element] : [];
  };
  // Split the double assertion into two separate statements per ADR 0053 StrictTsDiscipline
  const intermediate = Object.assign({}, { querySelectorAll: querySelectorAllFn }) as unknown;
  const mockDocument = intermediate as Document;
  globalThis.document = mockDocument;
  try {
    prefetchSnapshotTables();
    assert.deepEqual(stub.calls, [key]);
  } finally {
    globalThis.document = previousDocument;
    stub.restore();
  }
});
