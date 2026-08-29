// Host-side table transport and bundle assembly (ADR 0052 `TableTransport`).
// The native package freezes content-addressed TIQTBL03 bytes; the host
// serves each file under one URL derived from its sha and never parses it.
// This module owns the two orderings a build must not get wrong: a per-item
// bundle freezes its own table, and a build session freezes once, strictly
// after every article's data phase.

import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  absorbSnapshotTables,
  assembleFontContractBundle,
  assembleSnapshotBundle,
  closeSnapshotTables,
  createSnapshotTables,
  finalizeSnapshotTables,
  renderFontContractBundleData,
  renderSnapshotBundleData,
  type FinalizedSnapshotTables,
  type PreparedEntry,
  type SnapshotBundle,
  type SnapshotBundleData,
  type SnapshotTables,
} from "./precompute.js";

const SHA256_PATTERN = /^[0-9a-f]{64}$/u;

export interface SnapshotTableFileTransportOptions {
  /** The directory the table files live in; created on first write. */
  readonly directory: string;
  /** The URL path prefix the host serves the directory under. */
  readonly urlPrefix?: string;
  /** The file and URL extension; the bytes are the TIQTBL03 binary form. */
  readonly extension?: string;
}

export interface SnapshotTableFileTransport {
  readonly directory: string;
  urlFor(sha256: string): string;
  /** The pattern build outputs are scanned with to find referenced tables. */
  urlPattern(): RegExp;
  /** Writes one frozen table; returns the URL the manifest should pin. */
  write(finalized: FinalizedSnapshotTables): string;
  read(sha256: string): Buffer | undefined;
  listShas(): string[];
  /** Deletes files no kept sha pins; returns the deleted file count. */
  sweep(keepShas: ReadonlySet<string>): number;
}

const normalizeDirectory = (directory: string | URL): string =>
  directory instanceof URL ? fileURLToPath(directory) : path.resolve(directory);

/**
 * Serves one directory of frozen table files under `/<urlPrefix>/<sha><extension>`.
 * A table name addresses immutable bytes, so responses cache forever and a
 * name never serves two contents over the file's lifetime.
 */
export function createSnapshotTableFileTransport(
  options: SnapshotTableFileTransportOptions,
): SnapshotTableFileTransport {
  const directory = normalizeDirectory(options.directory);
  const urlPrefix = `/${(options.urlPrefix ?? "tiqian-tables").replace(/^\/+|\/+$/gu, "")}`;
  const extension = options.extension ?? ".tiqtbl";

  return Object.freeze({
    directory,
    urlFor(sha256: string) {
      return `${urlPrefix}/${sha256}${extension}`;
    },
    urlPattern() {
      return new RegExp(
        `${urlPrefix.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&")}/([0-9a-f]{64})${extension.replace(/\./gu, "\\.")}`,
        "gu",
      );
    },
    write(finalized: FinalizedSnapshotTables): string {
      mkdirSync(directory, { recursive: true });
      writeFileSync(path.join(directory, `${finalized.sha256}${extension}`), finalized.bytes);
      return `${urlPrefix}/${finalized.sha256}${extension}`;
    },
    read(sha256: string): Buffer | undefined {
      if (!SHA256_PATTERN.test(sha256)) return undefined;
      try {
        return readFileSync(path.join(directory, `${sha256}${extension}`));
      } catch {
        return undefined;
      }
    },
    listShas(): string[] {
      try {
        return readdirSync(directory)
          .filter((name) => name.endsWith(extension))
          .map((name) => name.slice(0, -extension.length));
      } catch {
        return [];
      }
    },
    sweep(keepShas: ReadonlySet<string>): number {
      let swept = 0;
      let names: string[];
      try {
        names = readdirSync(directory);
      } catch {
        return 0;
      }
      for (const name of names) {
        if (!name.endsWith(extension)) continue;
        if (keepShas.has(name.slice(0, -extension.length))) continue;
        rmSync(path.join(directory, name), { force: true });
        swept += 1;
      }
      return swept;
    },
  });
}

/** The paragraphs one bundle renders; both arrays absorb into one table. */
export interface SnapshotBundlePlan {
  readonly paragraphs: readonly PreparedEntry[];
  readonly fontContractParagraphs: readonly PreparedEntry[];
}

/** The data phase of one bundle, held until the table froze. */
export interface RenderedSnapshotData {
  readonly data: SnapshotBundleData;
  readonly fontContractOnly: boolean;
}

export interface SnapshotTableSession {
  /**
   * Absorbs one batch of prepared entries into the shared table. Absorb and
   * data-phase calls share one mutable table until `finish`; both throw
   * afterwards. Font-contract corpora never intern through their data phase,
   * so both arrays of a plan absorb here.
   */
  absorb(prepared: readonly PreparedEntry[]): void;
  /** The data phase of one article; `undefined` when the plan holds nothing. */
  renderData(plan: SnapshotBundlePlan, id: string): RenderedSnapshotData | undefined;
  /** Freezes the union table once; every article's data phase must have run. */
  finish(): FinalizedSnapshotTables;
  /** Expands one held data phase against the frozen table. */
  assemble(rendered: RenderedSnapshotData): SnapshotBundle;
  /** Drops the table handle; safe to call more than once. */
  close(): void;
}

/**
 * One build's table session (ADR 0052 `BundleLayering`): absorbs and data
 * phases interleave article by article, the table freezes once after the
 * last one, and every bundle assembles against the same frozen rows. A
 * per-article freeze instead would pin every intermediate union and grow
 * the shipped bytes quadratically.
 */
export function createSnapshotTableSession(): SnapshotTableSession {
  const tables: SnapshotTables = createSnapshotTables();
  let finalized: FinalizedSnapshotTables | null = null;
  let closed = false;

  const guardOpen = (call: string) => {
    if (closed) throw new Error(`SnapshotTableSessionClosed:${call}`);
    if (finalized !== null) throw new Error(`SnapshotTableSessionFrozen:${call}`);
  };

  return Object.freeze({
    absorb(prepared: readonly PreparedEntry[]): void {
      guardOpen("absorb");
      absorbSnapshotTables(tables, prepared);
    },
    renderData(plan: SnapshotBundlePlan, id: string): RenderedSnapshotData | undefined {
      guardOpen("renderData");
      if (plan.paragraphs.length > 0) {
        return Object.freeze({
          data: renderSnapshotBundleData(plan.paragraphs, {
            id,
            fontContractParagraphs: plan.fontContractParagraphs,
            snapshotTables: tables,
          }),
          fontContractOnly: false,
        });
      }
      if (plan.fontContractParagraphs.length === 0) return undefined;
      return Object.freeze({
        data: renderFontContractBundleData(plan.fontContractParagraphs, {
          id,
          snapshotTables: tables,
        }),
        fontContractOnly: true,
      });
    },
    finish(): FinalizedSnapshotTables {
      guardOpen("finish");
      finalized = finalizeSnapshotTables(tables);
      return finalized;
    },
    assemble(rendered: RenderedSnapshotData): SnapshotBundle {
      if (closed) throw new Error("SnapshotTableSessionClosed:assemble");
      if (finalized === null) throw new Error("SnapshotTableSessionUnfrozen:assemble");
      return rendered.fontContractOnly
        ? assembleFontContractBundle(rendered.data, tables)
        : assembleSnapshotBundle(rendered.data, tables);
    },
    close(): void {
      if (closed) return;
      closed = true;
      closeSnapshotTables(tables);
    },
  });
}

export interface StandaloneSnapshotBundle {
  readonly bundle: SnapshotBundle;
  /** The item's own frozen table; the host writes it through a transport. */
  readonly tableFile: FinalizedSnapshotTables;
  readonly fontContractOnly: boolean;
}

/**
 * The per-item fallback: the item absorbs into and freezes its own table,
 * written beside the session's so the manifest's pin stays servable. Returns
 * `undefined` when the item produced neither snapshots nor font contracts.
 */
export function renderStandaloneSnapshotBundle(
  plan: SnapshotBundlePlan,
  id: string,
): StandaloneSnapshotBundle | undefined {
  const session = createSnapshotTableSession();
  try {
    session.absorb(plan.paragraphs);
    session.absorb(plan.fontContractParagraphs);
    const rendered = session.renderData(plan, id);
    if (!rendered) return undefined;
    const tableFile = session.finish();
    return Object.freeze({
      bundle: session.assemble(rendered),
      tableFile,
      fontContractOnly: rendered.fontContractOnly,
    });
  } finally {
    session.close();
  }
}
