// The SQLite reference store (ADR 0052): one ready-made PersistentCacheStore
// over the host runtime's built-in SQLite. node:sqlite is probed first, then
// bun:sqlite; runtimes without either (including Deno, which ships no
// built-in sqlite module) reject the factory by name and their hosts bring
// their own store. Entry bytes stay opaque rows addressed by the SDK's
// address strings; write batches land in one transaction. The Article index
// (articles and their content-hash sets) lives in two tables of its own, so
// an entry prune never touches index rows.

import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

import type { ArticleIndexRecord, PersistentCacheStore } from "./persistence.js";

/** The shared surface of the probed sqlite modules. */
interface SqliteStatement {
  run(...parameters: unknown[]): { changes: number | bigint };
  all(...parameters: unknown[]): unknown[];
}

interface SqliteDatabase {
  prepare(sql: string): SqliteStatement;
  exec(sql: string): unknown;
  close(): void;
}

/** One sqlite-backed store plus its maintenance entries. */
export interface SqliteCacheStore extends PersistentCacheStore {
  /**
   * Drops entries of one context that are not in the keep list. Returns the
   * number of rows removed; other contexts are untouched.
   */
  prune(context: string, keep: readonly string[]): number;
  /**
   * Bucket-scoped eviction (ADR 0052 BucketIntersectionEviction): within the
   * entries this bucket's article rows attribute, keeps the keep list and
   * everything other buckets' rows reference; trims the bucket's hash rows
   * to the list and drops emptied article rows. Returns the entry count
   * removed; other buckets, unattributed entries and other contexts stay.
   */
  pruneBucket(context: string, bucket: string, keep: readonly string[]): number;
  /**
   * Records article-index rows (ADR 0052 Article layer), replacing the rows
   * of the same article keys.
   */
  recordArticles(context: string, articles: readonly ArticleIndexRecord[]): void;
  /** Reads every article row of one context, ordered by article key. */
  readArticles(context: string): ArticleIndexRecord[];
  /** Deletes article rows by key; returns the row count removed. */
  deleteArticles(context: string, articleKeys: readonly string[]): number;
  /** Closes the database; later calls report by name. */
  close(): void;
}

async function openDatabase(path: string): Promise<SqliteDatabase> {
  try {
    const mod = await import("node:sqlite");
    return new mod.DatabaseSync(path);
  } catch {
    // Fall through to the next runtime's module.
  }
  // The bun specifier rides in a variable so TypeScript does not resolve it
  // as a module this package cannot type; the probed module is structural.
  const bunSpecifier = "bun:sqlite";
  try {
    const mod = await import(bunSpecifier);
    return new mod.DatabaseSync(path);
  } catch {
    // No probed module loaded.
  }
  throw new Error("SqliteCacheStoreUnavailable");
}

const isBytes = (value: unknown): value is Uint8Array => value instanceof Uint8Array;

// The file's structure version, kept in SQLite's user_version. Content
// versioning does not live here: record bytes are opaque and invalidate
// through the context fingerprint. A structure mismatch refuses by name;
// the file is a cache, so the host may delete it and rebuild.
const SCHEMA_VERSION = 2;

const CREATE_ENTRIES =
  "CREATE TABLE IF NOT EXISTS tiqian_cache_entries (" +
  "address TEXT PRIMARY KEY, bytes BLOB NOT NULL);";
// Structure 2 adds the Article index tables (ADR 0052): articles tag keys
// to buckets, article hashes hold each article's full content-hash set.
// Contexts stay in their own columns because one file serves every lane.
const CREATE_ARTICLES =
  "CREATE TABLE IF NOT EXISTS tiqian_cache_articles (" +
  "context TEXT NOT NULL, article_key TEXT NOT NULL, bucket TEXT NOT NULL, " +
  "PRIMARY KEY (context, article_key));";
const CREATE_ARTICLE_HASHES =
  "CREATE TABLE IF NOT EXISTS tiqian_cache_article_hashes (" +
  "context TEXT NOT NULL, article_key TEXT NOT NULL, content_hash TEXT NOT NULL, " +
  "PRIMARY KEY (context, article_key, content_hash));";

function migrate(db: SqliteDatabase): void {
  const row = db.prepare("PRAGMA user_version;").all()[0];
  const version = (row as { user_version?: unknown }).user_version;
  const current = typeof version === "number" ? version : 0;
  if (current === SCHEMA_VERSION) {
    // Idempotent: a versioned file from this store keeps its rows.
    db.exec(CREATE_ENTRIES + CREATE_ARTICLES + CREATE_ARTICLE_HASHES);
    return;
  }
  if (current === 0) {
    // A fresh file (or one written before versioning): create and stamp.
    db.exec(CREATE_ENTRIES + CREATE_ARTICLES + CREATE_ARTICLE_HASHES);
    db.exec(`PRAGMA user_version = ${SCHEMA_VERSION};`);
    return;
  }
  if (current === 1) {
    // Structure 2 only adds the article tables; entry rows survive in
    // place, so the migration is a pair of creates away from version 1.
    db.exec(CREATE_ARTICLES + CREATE_ARTICLE_HASHES);
    db.exec(`PRAGMA user_version = ${SCHEMA_VERSION};`);
    return;
  }
  // A future version means a newer host wrote this file; an unknown past
  // version has no migration yet. Neither is read on guesswork.
  throw new Error(
    current > SCHEMA_VERSION
      ? "SqliteCacheStoreSchemaNewer"
      : "SqliteCacheStoreSchemaUnknown",
  );
}

/**
 * Opens (or creates) the database at `path`. The parent directory is created
 * when missing; the file belongs to the caller's cache directory policy.
 */
export async function createSqliteCacheStore(path: string): Promise<SqliteCacheStore> {
  mkdirSync(dirname(path), { recursive: true });
  const db = await openDatabase(path);
  // A failed open must release the handle: an open sqlite file blocks
  // deleting the cache directory on Windows.
  try {
    db.exec("PRAGMA journal_mode = WAL;");
    // Hosts that prerender with worker processes open several connections to
    // one file; a busy timeout turns their write contention into waiting
    // instead of a failed build.
    db.exec("PRAGMA busy_timeout = 5000;");
    migrate(db);
    db.exec("CREATE TEMP TABLE IF NOT EXISTS keep_list (address TEXT PRIMARY KEY);");
    db.exec(
      "CREATE TEMP TABLE IF NOT EXISTS keep_hashes (content_hash TEXT PRIMARY KEY);",
    );
  } catch (error) {
    db.close();
    throw error;
  }
  const readStatement = db.prepare("SELECT bytes FROM tiqian_cache_entries WHERE address = ?;");
  const writeStatement = db.prepare(
    "INSERT OR REPLACE INTO tiqian_cache_entries (address, bytes) VALUES (?, ?);",
  );
  const clearKeepStatement = db.prepare("DELETE FROM keep_list;");
  const keepStatement = db.prepare(
    "INSERT OR REPLACE INTO keep_list (address) VALUES (?);",
  );
  const clearKeepHashesStatement = db.prepare("DELETE FROM keep_hashes;");
  const keepHashStatement = db.prepare(
    "INSERT OR REPLACE INTO keep_hashes (content_hash) VALUES (?);",
  );
  const pruneStatement = db.prepare(
    "DELETE FROM tiqian_cache_entries WHERE address LIKE ? AND address NOT IN " +
      "(SELECT address FROM keep_list);",
  );
  // Bucket-scoped eviction in one pass over the index: the entry must sit
  // under this bucket's rows (scope), miss the keep list and miss every
  // other bucket's rows (cross-bucket shares survive). The row trim and the
  // emptied-article sweep run after the entry delete, inside the same
  // transaction, so the scope stays readable while it is computed from.
  const pruneBucketEntriesStatement = db.prepare(
    "DELETE FROM tiqian_cache_entries WHERE address LIKE ? AND address IN " +
      "(SELECT ? || ':' || h.content_hash FROM tiqian_cache_article_hashes h " +
      "JOIN tiqian_cache_articles a ON a.context = h.context " +
      "AND a.article_key = h.article_key WHERE h.context = ? AND a.bucket = ?) " +
      "AND address NOT IN (SELECT address FROM keep_list) AND address NOT IN " +
      "(SELECT ? || ':' || h.content_hash FROM tiqian_cache_article_hashes h " +
      "JOIN tiqian_cache_articles a ON a.context = h.context " +
      "AND a.article_key = h.article_key WHERE h.context = ? AND a.bucket <> ?);",
  );
  const trimBucketHashesStatement = db.prepare(
    "DELETE FROM tiqian_cache_article_hashes WHERE context = ? AND " +
      "content_hash NOT IN (SELECT content_hash FROM keep_hashes) AND " +
      "article_key IN (SELECT article_key FROM tiqian_cache_articles " +
      "WHERE context = ? AND bucket = ?);",
  );
  const dropEmptiedArticlesStatement = db.prepare(
    "DELETE FROM tiqian_cache_articles WHERE context = ? AND bucket = ? AND " +
      "article_key NOT IN (SELECT article_key FROM tiqian_cache_article_hashes " +
      "WHERE context = ?);",
  );
  const deleteArticleStatement = db.prepare(
    "DELETE FROM tiqian_cache_articles WHERE context = ? AND article_key = ?;",
  );
  const deleteArticleHashesStatement = db.prepare(
    "DELETE FROM tiqian_cache_article_hashes WHERE context = ? AND article_key = ?;",
  );
  const insertArticleStatement = db.prepare(
    "INSERT INTO tiqian_cache_articles (context, article_key, bucket) VALUES (?, ?, ?);",
  );
  const insertArticleHashStatement = db.prepare(
    "INSERT INTO tiqian_cache_article_hashes (context, article_key, content_hash) " +
      "VALUES (?, ?, ?);",
  );
  const readArticlesStatement = db.prepare(
    "SELECT article_key, bucket FROM tiqian_cache_articles WHERE context = ? " +
      "ORDER BY article_key;",
  );
  const readArticleHashesStatement = db.prepare(
    "SELECT article_key, content_hash FROM tiqian_cache_article_hashes " +
      "WHERE context = ? ORDER BY article_key, content_hash;",
  );
  let closed = false;
  const guard = (): void => {
    if (closed) {
      throw new Error("SqliteCacheStoreClosed");
    }
  };
  return {
    read(addresses: readonly string[]): Map<string, Uint8Array> {
      guard();
      const found = new Map<string, Uint8Array>();
      for (const address of addresses) {
        const rows = readStatement.all(address);
        const row = rows[0];
        if (row === undefined) continue;
        // One row per primary key; a row without bytes cannot occur through
        // this store's writes, and an external edit is skipped rather than
        // trusted.
        const value = (row as { bytes?: unknown }).bytes;
        if (isBytes(value)) {
          found.set(address, value);
        }
      }
      return found;
    },
    write(entries: readonly (readonly [string, Uint8Array])[]): void {
      guard();
      db.exec("BEGIN IMMEDIATE;");
      try {
        for (const [address, blob] of entries) {
          writeStatement.run(address, blob);
        }
        db.exec("COMMIT;");
      } catch (error) {
        db.exec("ROLLBACK;");
        throw error;
      }
    },
    prune(context: string, keep: readonly string[]): number {
      guard();
      db.exec("BEGIN IMMEDIATE;");
      try {
        clearKeepStatement.run();
        for (const address of keep) {
          keepStatement.run(address);
        }
        // Context fingerprints are hex, so the prefix match carries no LIKE
        // wildcards of its own.
        const outcome = pruneStatement.run(`${context}:%`);
        db.exec("COMMIT;");
        return Number(outcome.changes);
      } catch (error) {
        db.exec("ROLLBACK;");
        throw error;
      }
    },
    pruneBucket(context: string, bucket: string, keep: readonly string[]): number {
      guard();
      db.exec("BEGIN IMMEDIATE;");
      try {
        clearKeepStatement.run();
        clearKeepHashesStatement.run();
        for (const contentHash of keep) {
          keepStatement.run(`${context}:${contentHash}`);
          keepHashStatement.run(contentHash);
        }
        const outcome = pruneBucketEntriesStatement.run(
          `${context}:%`,
          context,
          context,
          bucket,
          context,
          context,
          bucket,
        );
        trimBucketHashesStatement.run(context, context, bucket);
        dropEmptiedArticlesStatement.run(context, bucket, context);
        db.exec("COMMIT;");
        return Number(outcome.changes);
      } catch (error) {
        db.exec("ROLLBACK;");
        throw error;
      }
    },
    close(): void {
      guard();
      closed = true;
      db.close();
    },
    recordArticles(context: string, articles: readonly ArticleIndexRecord[]): void {
      guard();
      db.exec("BEGIN IMMEDIATE;");
      try {
        for (const article of articles) {
          // The replacement drops the article's previous hashes first, so a
          // shrunken article does not keep pins on entries it no longer
          // references.
          deleteArticleStatement.run(context, article.articleKey);
          deleteArticleHashesStatement.run(context, article.articleKey);
          insertArticleStatement.run(context, article.articleKey, article.bucket);
          for (const contentHash of article.contentHashes) {
            insertArticleHashStatement.run(context, article.articleKey, contentHash);
          }
        }
        db.exec("COMMIT;");
      } catch (error) {
        db.exec("ROLLBACK;");
        throw error;
      }
    },
    readArticles(context: string): ArticleIndexRecord[] {
      guard();
      const hashes = new Map<string, string[]>();
      for (const row of readArticleHashesStatement.all(context)) {
        const { article_key: articleKey, content_hash: contentHash } =
          row as { article_key: string; content_hash: string };
        const list = hashes.get(articleKey);
        if (list === undefined) {
          hashes.set(articleKey, [contentHash]);
        } else {
          list.push(contentHash);
        }
      }
      return readArticlesStatement.all(context).map(
        (row) => {
          const { article_key: articleKey, bucket } =
            row as { article_key: string; bucket: string };
          return {
            articleKey,
            bucket,
            contentHashes: hashes.get(articleKey) ?? [],
          };
        },
      );
    },
    deleteArticles(context: string, articleKeys: readonly string[]): number {
      guard();
      db.exec("BEGIN IMMEDIATE;");
      try {
        let removed = 0;
        for (const articleKey of articleKeys) {
          deleteArticleHashesStatement.run(context, articleKey);
          const outcome = deleteArticleStatement.run(context, articleKey);
          removed += Number(outcome.changes);
        }
        db.exec("COMMIT;");
        return removed;
      } catch (error) {
        db.exec("ROLLBACK;");
        throw error;
      }
    },
  };
}
