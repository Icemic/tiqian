// Persistence SDK tests over the real addon (ADR 0052). The render lanes
// must return exactly the JSON batch lanes' entries, the second process must
// resolve from the store without computing, and a tampered store copy must
// demote to the content path instead of returning wrong bytes.

import { readFileSync } from "node:fs";
import { test } from "node:test";
import assert from "node:assert/strict";

import type { CreatePrecomputerOptions } from "../src/precompute.js";
import type { PersistentCacheStore } from "../src/persistence.js";

type PrecomputeModule = typeof import("../src/precompute.js");
type PersistenceModule = typeof import("../src/persistence.js");

let precompute: PrecomputeModule | null = null;
let persistence: PersistenceModule | null = null;
try {
  precompute = (await import("../lib/precompute.js")) as PrecomputeModule;
  persistence = (await import("../lib/persistence.js")) as PersistenceModule;
} catch {
  precompute = null;
  persistence = null;
}

function readLocalFont(fileName: string): Buffer | null {
  try {
    return readFileSync(`${process.env.HOME}/.local/share/fonts/${fileName}`);
  } catch {
    return null;
  }
}

function cjkPrecomputerOptions(): CreatePrecomputerOptions | null {
  const bytes = readLocalFont("chinese.msyh.ttf");
  if (bytes === null) return null;
  return {
    faces: [{ family: "Microsoft YaHei", publicUrl: "/fonts/msyh.ttf", source: bytes }],
    typography: {
      fontFamilies: ["Microsoft YaHei"],
      fontSizePx: 18,
      lineHeightPx: 27,
      locale: "zh-Hans",
      fontWeight: 400,
      italic: false,
      firstLineIndentIc: 0,
      lineLengthGridEnabled: true,
    },
  };
}

/** The plain address-to-bytes store the SDK expects from a host. */
function memoryStore(): PersistentCacheStore & { blobs: Map<string, Uint8Array> } {
  const blobs = new Map<string, Uint8Array>();
  return {
    blobs,
    read(addresses: readonly string[]): Map<string, Uint8Array> {
      const found = new Map<string, Uint8Array>();
      for (const address of addresses) {
        const blob = blobs.get(address);
        if (blob !== undefined) found.set(address, blob);
      }
      return found;
    },
    write(entries: readonly (readonly [string, Uint8Array])[]): void {
      for (const [address, blob] of entries) {
        blobs.set(address, blob);
      }
    },
  };
}

const SNAPSHOTS = [
  { key: "p-0", text: "中文文字排版段落", maxWidthPx: 144 },
  { key: "p-1", text: "第二段正文，含标点。", maxWidthPx: 144 },
  { key: "p-2", text: "带—破折号", maxWidthPx: 360 },
] as const;

test("render results equal the JSON batch lanes", { skip: precompute === null }, async () => {
  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return; // the engine path needs a CJK-covering face
  const precomputer = await precompute.createPrecomputer(options);
  const cache = persistence.createPersistentCache(precomputer, memoryStore());
  const rendered = await cache.renderSnapshots(SNAPSHOTS);
  const lane = await precomputer.prepareParagraphs([...SNAPSHOTS]);
  assert.deepEqual(rendered, lane);
  assert.equal(rendered[2]?.status, "unsupported");
  const contracts = await cache.renderContracts([
    { key: "fc-0", text: "字体样本段落" },
    { key: "fc-1", text: "第二样本段落" },
  ]);
  const contractLane = await precomputer.prepareFontContracts([
    { key: "fc-0", text: "字体样本段落" },
    { key: "fc-1", text: "第二样本段落" },
  ]);
  assert.deepEqual(contracts, contractLane);
  precomputer.close();
});

test("a second precomputer resolves from the store without computing", { skip: precompute === null }, async () => {
  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return;
  const store = memoryStore();
  const first = await precompute.createPrecomputer(options);
  const firstCache = persistence.createPersistentCache(first, store);
  const rendered = await firstCache.renderSnapshots(SNAPSHOTS);
  const written = await firstCache.flush();
  assert.equal(written, SNAPSHOTS.length);
  first.close();

  // A fresh precomputer over the same options shares the context, so every
  // address resolves from the store and nothing reaches the renderer.
  const second = await precompute.createPrecomputer(options);
  const secondCache = persistence.createPersistentCache(second, store);
  const again = await secondCache.renderSnapshots(SNAPSHOTS);
  assert.deepEqual(again, rendered);
  assert.equal(second.cache.drainWrites().length, 0);
  assert.equal(await secondCache.flush(), 0);
  // Warming against the same store reports every item accepted.
  assert.equal(await secondCache.warmSnapshots(SNAPSHOTS), SNAPSHOTS.length);
  second.close();
});

test("a tampered store copy demotes to the content path", { skip: precompute === null }, async () => {
  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return;
  const store = memoryStore();
  const precomputer = await precompute.createPrecomputer(options);
  const cache = persistence.createPersistentCache(precomputer, store);
  const rendered = await cache.renderSnapshots(SNAPSHOTS);
  await cache.flush();
  // Corrupt one stored blob: the digest check must fail and the item must
  // come back from a fresh computation with the lane's bytes.
  const target = [...store.blobs.keys()].sort()[0];
  const blob = store.blobs.get(target);
  assert.ok(blob);
  const corrupted = Buffer.from(blob);
  corrupted[corrupted.length - 1] ^= 0xff;
  store.blobs.set(target, corrupted);
  const again = await cache.renderSnapshots(SNAPSHOTS);
  assert.deepEqual(again, rendered);
  precomputer.close();
});

test("prefill lanes queue work the render lanes then resolve", { skip: precompute === null }, async () => {
  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return;
  const precomputer = await precompute.createPrecomputer(options);
  const cache = persistence.createPersistentCache(precomputer, memoryStore());
  // The background egress queues without waiting; the render lane over the
  // same content then resolves through the pool and matches the JSON lane.
  assert.equal(cache.prefillSnapshots(SNAPSHOTS), SNAPSHOTS.length);
  const rendered = await cache.renderSnapshots(SNAPSHOTS);
  assert.deepEqual(rendered, await precomputer.prepareParagraphs([...SNAPSHOTS]));
  assert.equal(cache.prefillContracts([{ key: "fc-0", text: "字体样本段落" }]), 1);
  assert.deepEqual(
    await cache.renderContracts([{ key: "fc-0", text: "字体样本段落" }]),
    await precomputer.prepareFontContracts([{ key: "fc-0", text: "字体样本段落" }]),
  );
  const written = await cache.flush();
  assert.equal(written, SNAPSHOTS.length + 1);
  precomputer.close();
});

test("addresses stay apart across typography contexts", { skip: precompute === null }, async () => {
  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return;
  const store = memoryStore();
  const base = await precompute.createPrecomputer(options);
  const baseCache = persistence.createPersistentCache(base, store);
  const changed = await precompute.createPrecomputer({
    ...options,
    typography: { ...options.typography, lineHeightPx: 30 },
  });
  const changedCache = persistence.createPersistentCache(changed, store);
  const input = [{ key: "p-0", text: "中文文字排版段落", maxWidthPx: 144 }] as const;
  const baseEntry = await baseCache.renderSnapshots(input);
  const changedEntry = await changedCache.renderSnapshots(input);
  // Same content under two contexts: two addresses, two artifacts.
  await baseCache.flush();
  await changedCache.flush();
  assert.equal(store.blobs.size, 2);
  if (baseEntry[0]?.status === "prepared" && changedEntry[0]?.status === "prepared") {
    assert.notEqual(baseEntry[0].typographySha256, changedEntry[0].typographySha256);
  }
  base.close();
  changed.close();
});

// Article-layer tests (ADR 0052): recordArticle, whole-article warming and
// bucket-intersection eviction run against a stub bridge, so the index
// behavior is observable without the native engine.

import { createHash } from "node:crypto";

import type { ArticleIndexRecord, PersistentCache } from "../src/persistence.js";
import type { CacheBridge, CacheRecord, CacheTier } from "../src/cache.js";

type CacheModule = typeof import("../src/cache.js");
type CanonicalModule = typeof import("../src/canonical.js");
let cacheModule: CacheModule | null = null;
let canonical: CanonicalModule | null = null;
try {
  cacheModule = (await import("../lib/cache.js")) as CacheModule;
  canonical = (await import("../lib/canonical.js")) as CanonicalModule;
} catch {
  cacheModule = null;
  canonical = null;
}

/** An in-memory store with the Article index and prune on board. */
function articleMemoryStore(withPrune: boolean, withBucketPrune = true) {
  const blobs = new Map<string, Uint8Array>();
  const rows = new Map<string, ArticleIndexRecord>();
  const rowKey = (context: string, articleKey: string): string =>
    `${context}\0${articleKey}`;
  const store: PersistentCacheStore & {
    blobs: Map<string, Uint8Array>;
    rows: Map<string, ArticleIndexRecord>;
  } = {
    blobs,
    rows,
    read(addresses: readonly string[]): Map<string, Uint8Array> {
      const found = new Map<string, Uint8Array>();
      for (const address of addresses) {
        const blob = blobs.get(address);
        if (blob !== undefined) found.set(address, blob);
      }
      return found;
    },
    write(entries: readonly (readonly [string, Uint8Array])[]): void {
      for (const [address, blob] of entries) {
        blobs.set(address, blob);
      }
    },
    recordArticles(context: string, articles: readonly ArticleIndexRecord[]): void {
      for (const article of articles) {
        rows.set(rowKey(context, article.articleKey), article);
      }
    },
    readArticles(context: string): ArticleIndexRecord[] {
      return [...rows.entries()]
        .filter(([key]) => key.startsWith(`${context}\0`))
        .map(([, row]) => row)
        .sort((left, right) => (left.articleKey < right.articleKey ? -1 : 1));
    },
    deleteArticles(context: string, articleKeys: readonly string[]): number {
      let removed = 0;
      for (const articleKey of articleKeys) {
        if (rows.delete(rowKey(context, articleKey))) removed += 1;
      }
      return removed;
    },
  };
  if (withPrune) {
    store.prune = (context: string, keep: readonly string[]): number => {
      const keepSet = new Set(keep);
      let removed = 0;
      for (const address of [...blobs.keys()]) {
        if (address.startsWith(`${context}:`) && !keepSet.has(address)) {
          blobs.delete(address);
          removed += 1;
        }
      }
      return removed;
    };
  }
  if (withBucketPrune) {
    store.pruneBucket = (
      context: string,
      bucket: string,
      keep: readonly string[],
    ): number => {
      const keepSet = new Set(keep);
      const bucketRows = [...rows.entries()].filter(
        ([key, row]) => key.startsWith(`${context}\0`) && row.bucket === bucket,
      );
      const otherHashes = new Set(
        [...rows.entries()]
          .filter(([key, row]) => key.startsWith(`${context}\0`) && row.bucket !== bucket)
          .flatMap(([, row]) => row.contentHashes),
      );
      let removed = 0;
      for (const hash of bucketRows.flatMap(([, row]) => row.contentHashes)) {
        const address = `${context}:${hash}`;
        if (!keepSet.has(hash) && !otherHashes.has(hash) && blobs.has(address)) {
          blobs.delete(address);
          removed += 1;
        }
      }
      for (const [key, row] of bucketRows) {
        const trimmed = row.contentHashes.filter((hash) => keepSet.has(hash));
        if (trimmed.length === 0) {
          rows.delete(key);
        } else {
          rows.set(key, { ...row, contentHashes: trimmed });
        }
      }
      return removed;
    };
  }
  return store;
}

/** A bridge that only answers context and prefetch: any other use fails
 * the test by name. Article-layer calls touch nothing else. */
function stubBridge(context: string, prefetched?: number[]): CacheBridge {
  const fail = (name: string): never => {
    throw new Error(`unexpected ${name} on the stub bridge`);
  };
  return {
    context: () => context,
    submitHashes: () => fail("submitHashes"),
    submitContents: () => fail("submitContents"),
    prefillContents: () => fail("prefillContents"),
    prefetch: (records: readonly CacheRecord[]) => {
      if (prefetched !== undefined) prefetched.push(records.length);
      return records.length;
    },
    drainWrites: () => [],
    evictExcept: () => {},
  };
}

/** A lane over the stub bridge of one context. */
function stubLane(store: PersistentCacheStore): {
  lane: PersistentCache;
  prefetched: number[];
} {
  assert.ok(persistence);
  const prefetched: number[] = [];
  return {
    lane: persistence.createPersistentCache(
      { cache: stubBridge("a".repeat(64), prefetched) },
      store,
    ),
    prefetched,
  };
}

const ARTICLE_SNAPSHOTS = [
  { key: "p-0", text: "中文文字排版段落", maxWidthPx: 144 },
  { key: "p-1", text: "第二段正文，含标点。", maxWidthPx: 144 },
  { key: "p-2", text: "带—破折号", maxWidthPx: 360 },
] as const;

test("recordArticle drives bucket-intersection eviction", {
  skip: persistence === null || cacheModule === null || canonical === null,
}, async () => {
  assert.ok(persistence);
  assert.ok(cacheModule);
  assert.ok(canonical);
  const store = articleMemoryStore(true);
  const { lane } = stubLane(store);
  const context = lane.context();
  const hashOf = (input: (typeof ARTICLE_SNAPSHOTS)[number]): string =>
    Buffer.from(
      cacheModule.submissionItem(input, canonical.KIND_SNAPSHOT).hash,
    ).toString("hex");
  const [first, second, third] = ARTICLE_SNAPSHOTS;
  assert.ok(first && second && third);
  // Two articles share the second paragraph; one orphan entry exists too.
  store.write([
    [`${context}:${hashOf(first)}`, new Uint8Array([1])],
    [`${context}:${hashOf(second)}`, new Uint8Array([2])],
    [`${context}:${hashOf(third)}`, new Uint8Array([3])],
    [`${context}:${"f".repeat(64)}`, new Uint8Array([4])],
  ]);
  await lane.recordArticle("post-a", "post", { snapshots: [first, second] });
  await lane.recordArticle("note-b", "note", { snapshots: [second, third] });
  assert.deepEqual(await lane.articleHashes("post-a"), [
    hashOf(first),
    hashOf(second),
  ]);
  // The union of both articles keeps every referenced entry and drops the
  // orphan.
  assert.equal(await lane.pruneUnreferencedEntries(), 1);
  assert.equal(store.blobs.has(`${context}:${"f".repeat(64)}`), false);
  assert.equal(store.blobs.size, 3);
  // Dropping one article removes its exclusive entry; the shared paragraph
  // survives because the other article still references it.
  assert.equal(await lane.pruneUnreferencedEntries(["note-b"]), 1);
  assert.equal(store.blobs.has(`${context}:${hashOf(third)}`), false);
  assert.equal(store.blobs.has(`${context}:${hashOf(second)}`), true);
  assert.deepEqual([...store.rows.keys()], [`${context}\0post-a`]);
  assert.equal(await lane.articleHashes("note-b"), null);
});

test("pruneBucket keeps the intersection inside one bucket's scope", {
  skip: persistence === null || cacheModule === null || canonical === null,
}, async () => {
  assert.ok(persistence);
  assert.ok(cacheModule);
  assert.ok(canonical);
  const store = articleMemoryStore(true);
  const { lane } = stubLane(store);
  const context = lane.context();
  const hashOf = (input: (typeof ARTICLE_SNAPSHOTS)[number]): string =>
    Buffer.from(
      cacheModule.submissionItem(input, canonical.KIND_SNAPSHOT).hash,
    ).toString("hex");
  const [first, second, third] = ARTICLE_SNAPSHOTS;
  assert.ok(first && second && third);
  const exclusive = "0".repeat(64);
  const noteOnly = "e".repeat(64);
  // Recording returns the hash list, so a host assembles bucket keep lists
  // from its own record calls instead of re-deriving them.
  const recorded = await lane.recordArticle("post-a", "post", {
    snapshots: [first, second],
  });
  assert.deepEqual(recorded, [hashOf(first), hashOf(second)]);
  assert.ok(store.recordArticles);
  store.recordArticles(context, [
    { articleKey: "post-c", bucket: "post", contentHashes: [exclusive] },
    { articleKey: "note-b", bucket: "note", contentHashes: [hashOf(second), noteOnly] },
  ]);
  store.write([
    [`${context}:${hashOf(first)}`, new Uint8Array([1])],
    [`${context}:${hashOf(second)}`, new Uint8Array([2])],
    [`${context}:${exclusive}`, new Uint8Array([3])],
    [`${context}:${noteOnly}`, new Uint8Array([4])],
    // No article row attributes the third paragraph: it is out of every
    // bucket's scope.
    [`${context}:${hashOf(third)}`, new Uint8Array([5])],
  ]);
  // The post bucket now retains only the first paragraph: its other entries
  // go, the shared paragraph survives on the note bucket's reference, and
  // the unattributed entry stays for the whole-context prune.
  assert.equal(await lane.pruneBucket("post", [hashOf(first)]), 1);
  assert.equal(store.blobs.has(`${context}:${exclusive}`), false);
  assert.equal(store.blobs.has(`${context}:${hashOf(second)}`), true);
  assert.equal(store.blobs.has(`${context}:${hashOf(third)}`), true);
  // Rows follow the list: post-a shrinks to the kept hash, the emptied
  // post-c row is gone, and the note rows keep their full sets.
  assert.deepEqual(await lane.articleHashes("post-a"), [hashOf(first)]);
  assert.equal(await lane.articleHashes("post-c"), null);
  assert.deepEqual(await lane.articleHashes("note-b"), [
    hashOf(second),
    noteOnly,
  ]);
});

test("warmArticle verifies stored copies before warming", {
  skip: persistence === null || cacheModule === null || canonical === null,
}, async () => {
  assert.ok(persistence);
  assert.ok(cacheModule);
  assert.ok(canonical);
  const store = articleMemoryStore(true);
  const { lane, prefetched } = stubLane(store);
  const context = lane.context();
  const input = ARTICLE_SNAPSHOTS[0];
  assert.ok(input);
  const hash = cacheModule.submissionItem(input, canonical.KIND_SNAPSHOT).hash;
  const artifact = Buffer.from('{"status":"prepared"}', "utf8");
  const tier: CacheTier = "snapshot";
  const record: CacheRecord = {
    tier,
    key: new Uint8Array(32),
    contentHash: hash,
    artifact,
    artifactSha: createHash("sha256").update(artifact).digest(),
  };
  await lane.recordArticle("post-a", "post", { snapshots: [input] });
  // An unrecorded address warms nothing.
  assert.equal(await lane.warmArticle("missing"), 0);
  // The verified copy warms the memory tier once.
  store.write([
    [`${context}:${Buffer.from(hash).toString("hex")}`, cacheModule.packRecords([record])],
  ]);
  assert.equal(await lane.warmArticle("post-a"), 1);
  assert.deepEqual(prefetched, [1]);
  // A tampered digest is a skip, not poison.
  const tampered: CacheRecord = { ...record, artifactSha: new Uint8Array(32) };
  store.write([
    [`${context}:${Buffer.from(hash).toString("hex")}`, cacheModule.packRecords([tampered])],
  ]);
  prefetched.length = 0;
  assert.equal(await lane.warmArticle("post-a"), 0);
  assert.deepEqual(prefetched, []);
});

test("stores without the article surface or prune report by name", {
  skip: persistence === null,
}, async () => {
  assert.ok(persistence);
  const plain = memoryStore();
  const plainLane = persistence.createPersistentCache(
    { cache: stubBridge("b".repeat(64)) },
    plain,
  );
  await assert.rejects(
    plainLane.recordArticle("post-a", "post", { snapshots: [] }),
    /ArticleIndexUnavailable/,
  );
  await assert.rejects(plainLane.articleHashes("post-a"), /ArticleIndexUnavailable/);
  await assert.rejects(plainLane.warmArticle("post-a"), /ArticleIndexUnavailable/);
  await assert.rejects(plainLane.pruneUnreferencedEntries(), /ArticleIndexUnavailable/);
  const unprunable = articleMemoryStore(false);
  const unprunableLane = persistence.createPersistentCache(
    { cache: stubBridge("c".repeat(64)) },
    unprunable,
  );
  await unprunableLane.recordArticle("post-a", "post", { snapshots: [] });
  await assert.rejects(
    unprunableLane.pruneUnreferencedEntries(),
    /EntryPruneUnavailable/,
  );
  const unbucketed = articleMemoryStore(false, false);
  const unbucketedLane = persistence.createPersistentCache(
    { cache: stubBridge("d".repeat(64)) },
    unbucketed,
  );
  await assert.rejects(
    unbucketedLane.pruneBucket("post", []),
    /BucketPruneUnavailable/,
  );
});
