// SQLite reference store tests (ADR 0052). The plain store behavior runs on
// whatever sqlite backend the host runtime offers; the SDK integration also
// needs the engine, so it follows the shared skip pattern.

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import assert from "node:assert/strict";

import type { CreatePrecomputerOptions } from "../src/precompute.js";
import type { SqliteCacheStore } from "../src/sqlite-store.js";

type PrecomputeModule = typeof import("../src/precompute.js");
type PersistenceModule = typeof import("../src/persistence.js");
type SqliteStoreModule = typeof import("../src/sqlite-store.js");

let precompute: PrecomputeModule | null = null;
let persistence: PersistenceModule | null = null;
let sqliteStore: SqliteStoreModule | null = null;
try {
  precompute = (await import("../lib/precompute.js")) as PrecomputeModule;
  persistence = (await import("../lib/persistence.js")) as PersistenceModule;
  sqliteStore = (await import("../lib/sqlite-store.js")) as SqliteStoreModule;
} catch {
  precompute = null;
  persistence = null;
  sqliteStore = null;
}

const available = sqliteStore !== null;
async function openStore(directory: string): Promise<SqliteCacheStore> {
  assert.ok(sqliteStore);
  try {
    return await sqliteStore.createSqliteCacheStore(join(directory, "cache.db"));
  } catch (error) {
    if (error instanceof Error && error.message === "SqliteCacheStoreUnavailable") {
      throw new Error("no sqlite backend in this runtime", { cause: error });
    }
    throw error;
  }
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

const SNAPSHOTS = [
  { key: "p-0", text: "中文文字排版段落", maxWidthPx: 144 },
  { key: "p-1", text: "第二段正文，含标点。", maxWidthPx: 144 },
  { key: "p-2", text: "带—破折号", maxWidthPx: 360 },
] as const;

test("store round-trips entries and reports closed by name", { skip: !available }, async () => {
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const store = await openStore(directory);
    const first = new Uint8Array([1, 2, 3]);
    const second = new Uint8Array([4, 5]);
    store.write([
      ["aaa:01", first],
      ["aaa:02", second],
      ["bbb:01", first],
    ]);
    const found = await store.read(["aaa:01", "aaa:02", "aaa:03"]);
    assert.equal(found.size, 2);
    assert.deepEqual(found.get("aaa:01"), first);
    assert.deepEqual(found.get("aaa:02"), second);
    // Rewriting one address replaces the row instead of failing the key.
    store.write([["aaa:01", second]]);
    assert.deepEqual((await store.read(["aaa:01"])).get("aaa:01"), second);

    // Pruning one context keeps its listed rows and every other context.
    const removed = store.prune("aaa", ["aaa:01"]);
    assert.equal(removed, 1);
    const afterPrune = await store.read(["aaa:01", "aaa:02", "bbb:01"]);
    assert.equal(afterPrune.size, 2);
    assert.ok(afterPrune.has("aaa:01"));
    assert.ok(afterPrune.has("bbb:01"));

    store.close();
    assert.throws(() => store.read(["aaa:01"]), /SqliteCacheStoreClosed/);
    assert.throws(() => store.write([["aaa:01", first]]), /SqliteCacheStoreClosed/);
    assert.throws(() => store.prune("aaa", []), /SqliteCacheStoreClosed/);

    // A reopened database still holds the surviving rows.
    const reopened = await openStore(directory);
    const survived = await reopened.read(["aaa:01", "aaa:02", "bbb:01"]);
    assert.equal(survived.size, 2);
    reopened.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("a database stamped with a newer structure version refuses by name", {
  skip: !available,
}, async () => {
  let raw: typeof import("node:sqlite") | null = null;
  try {
    raw = await import("node:sqlite");
  } catch {
    // Forcing a version stamp needs a direct handle this runtime lacks.
  }
  if (raw === null) return;
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const path = join(directory, "cache.db");
    const stamp = new raw.DatabaseSync(path);
    stamp.exec("PRAGMA user_version = 99;");
    stamp.close();
    await assert.rejects(openStore(directory), /SqliteCacheStoreSchemaNewer/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("a structure-1 database migrates in place with its entries", {
  skip: !available,
}, async () => {
  let raw: typeof import("node:sqlite") | null = null;
  try {
    raw = await import("node:sqlite");
  } catch {
    // Writing a version-1 file needs a direct handle this runtime lacks.
  }
  if (raw === null) return;
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const path = join(directory, "cache.db");
    const stamp = new raw.DatabaseSync(path);
    stamp.exec(
      "CREATE TABLE tiqian_cache_entries (address TEXT PRIMARY KEY, bytes BLOB NOT NULL);",
    );
    stamp.exec("INSERT INTO tiqian_cache_entries VALUES ('aaa:01', x'010203');");
    stamp.exec("PRAGMA user_version = 1;");
    stamp.close();
    const store = await openStore(directory);
    const found = await store.read(["aaa:01"]);
    assert.equal(found.size, 1);
    assert.deepEqual(found.get("aaa:01"), new Uint8Array([1, 2, 3]));
    store.recordArticles("aaa", [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    store.close();
    const check = new raw.DatabaseSync(path);
    const versionRow = check.prepare("PRAGMA user_version;").all()[0];
    check.close();
    assert.equal((versionRow as { user_version?: unknown }).user_version, 2);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("article rows round-trip, replace shrinks, and deletes count", {
  skip: !available,
}, async () => {
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const store = await openStore(directory);
    store.recordArticles("aaa", [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01", "02"] },
      { articleKey: "note-b", bucket: "note", contentHashes: ["02", "03"] },
    ]);
    // Rows come back ordered by article key with their full hash sets.
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "note-b", bucket: "note", contentHashes: ["02", "03"] },
      { articleKey: "post-a", bucket: "post", contentHashes: ["01", "02"] },
    ]);
    // Re-recording one article replaces its set: a hash the article dropped
    // no longer stays pinned in the index.
    store.recordArticles("aaa", [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "note-b", bucket: "note", contentHashes: ["02", "03"] },
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    // Deleting removes both the row and its hashes; unknown keys add nothing.
    assert.equal(await store.deleteArticles("aaa", ["note-b", "missing"]), 1);
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    // Another context's rows never surface in this context's read.
    store.recordArticles("bbb", [
      { articleKey: "post-a", bucket: "post", contentHashes: ["07"] },
    ]);
    assert.deepEqual(await store.readArticles("bbb"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["07"] },
    ]);
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    // Rows survive a reopen; an entry prune leaves index rows alone.
    store.write([["aaa:01", new Uint8Array([9])]]);
    assert.equal(store.prune("aaa", []), 1);
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    store.close();
    const reopened = await openStore(directory);
    assert.deepEqual(await reopened.readArticles("aaa"), [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    reopened.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("bucket prune keeps the intersection inside one bucket's scope", {
  skip: !available,
}, async () => {
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const store = await openStore(directory);
    store.recordArticles("aaa", [
      { articleKey: "post-a", bucket: "post", contentHashes: ["01", "02"] },
      { articleKey: "post-c", bucket: "post", contentHashes: ["03"] },
      { articleKey: "note-b", bucket: "note", contentHashes: ["02", "04"] },
    ]);
    for (const [address, blob] of [
      ["aaa:01", new Uint8Array([1])],
      ["aaa:02", new Uint8Array([2])],
      ["aaa:03", new Uint8Array([3])],
      ["aaa:04", new Uint8Array([4])],
      // No article row attributes this address.
      ["aaa:99", new Uint8Array([5])],
      ["bbb:01", new Uint8Array([6])],
    ] as const) {
      store.write([[address, blob]]);
    }
    // The post bucket keeps only hash 01: its other attributed entry goes,
    // the shared 02 survives on the note rows, and 99 (no row) plus the
    // other context stay for their own sweeps.
    assert.equal(store.pruneBucket("aaa", "post", ["01"]), 1);
    const after = await store.read([
      "aaa:01",
      "aaa:02",
      "aaa:03",
      "aaa:04",
      "aaa:99",
      "bbb:01",
    ]);
    assert.equal(after.size, 5);
    assert.equal(after.has("aaa:03"), false);
    // Rows follow the list: post-a shrinks, emptied post-c goes, notes keep
    // their full sets.
    assert.deepEqual(await store.readArticles("aaa"), [
      { articleKey: "note-b", bucket: "note", contentHashes: ["02", "04"] },
      { articleKey: "post-a", bucket: "post", contentHashes: ["01"] },
    ]);
    // Sweeping the last bucket leaves exactly the union of the two lists.
    assert.equal(store.pruneBucket("aaa", "note", ["02", "04"]), 0);
    assert.equal((await store.read(["aaa:99"])).size, 1);
    assert.equal(store.prune("aaa", ["aaa:01", "aaa:02", "aaa:04"]), 1);
    assert.equal((await store.read(["aaa:99"])).size, 0);
    store.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("a persistent cache over sqlite resolves the second pass from the file", {
  skip: !available || precompute === null,
}, async () => {  assert.ok(precompute);
  assert.ok(persistence);
  const options = cjkPrecomputerOptions();
  if (options === null) return;
  const directory = mkdtempSync(join(tmpdir(), "tiqian-sqlite-"));
  try {
    const store = await openStore(directory);
    const first = await precompute.createPrecomputer(options);
    const firstCache = persistence.createPersistentCache(first, store);
    const rendered = await firstCache.renderSnapshots(SNAPSHOTS);
    assert.equal(await firstCache.flush(), SNAPSHOTS.length);
    first.close();
    store.close();

    // A fresh precomputer and a fresh store connection over the same file:
    // every entry resolves from disk and the renderer stays idle.
    const reopened = await openStore(directory);
    const second = await precompute.createPrecomputer(options);
    const secondCache = persistence.createPersistentCache(second, reopened);
    const again = await secondCache.renderSnapshots(SNAPSHOTS);
    assert.deepEqual(again, rendered);
    assert.equal(second.cache.drainWrites().length, 0);
    assert.equal(await secondCache.flush(), 0);
    second.close();
    reopened.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
