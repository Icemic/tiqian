import { AsyncLocalStorage } from "node:async_hooks";

import {
  createHtmlPreparer,
  renderSnapshotServerAssets,
} from "@tiqian/precompute/precompute-html";
import { createSnapshotTableFileTransport } from "@tiqian/precompute/transport";

const SNAPSHOT_REFERENCE = /<tiqian-prose\b[^>]*\bsnapshot-ref=(["'])([A-Za-z][A-Za-z0-9_-]*)\1[^>]*>/giu;

/**
 * Builds the snapshot-table file transport of a `tables` option (ADR 0052
 * `TableTransport`) for hosts that own their preparation pipeline and call
 * the transport directly. Only a production build writes `directory`; dev,
 * test, and an unset NODE_ENV write `devDirectory` when it is configured.
 * The build directory therefore only changes through a build.
 */
export function createTiqianTables(options) {
  if (typeof options !== "object" || options === null) {
    throw new Error("TiqianSvelteKitTablesOptionsInvalid");
  }
  const directory = options.directory;
  if (typeof directory !== "string" && !(directory instanceof URL)) {
    throw new Error("TiqianSvelteKitTablesDirectoryRequired");
  }
  const devDirectory = options.devDirectory;
  if (devDirectory !== undefined && typeof devDirectory !== "string" && !(devDirectory instanceof URL)) {
    throw new Error("TiqianSvelteKitTablesOptionsInvalid");
  }
  return createSnapshotTableFileTransport({
    directory: process.env.NODE_ENV === "production" ? directory : (devDirectory ?? directory),
    urlPrefix: options.urlPrefix,
    extension: options.extension,
  });
}

export function injectTiqianSsrAssets(htmlValue, resolveAssets) {
  const html = String(htmlValue);
  const ids = new Set(Array.from(html.matchAll(SNAPSHOT_REFERENCE), (match) => match[2]));
  const assets = Array.from(ids, (id) => resolveAssets(id)).filter(Boolean);
  if (assets.length === 0) return html;
  if (!html.includes("</head>")) throw new Error("TiqianSvelteKitHeadUnavailable");
  const rendered = assets.map(renderSnapshotServerAssets).join("");
  return html.replace("</head>", () => `${rendered}\n</head>`);
}

function sameServerAssets(left, right) {
  return left.id === right.id &&
    left.initialStyle === right.initialStyle &&
    left.inertTemplate === right.inertTemplate &&
    left.fontPreloads.length === right.fontPreloads.length &&
    left.fontPreloads.every((href, index) => href === right.fontPreloads[index]);
}

/**
 * One SvelteKit server boundary owns exact-font preparation, SSR head assets,
 * and the compact object serialized through route data for client navigation.
 * A `tables` option adds snapshot-table delivery: `prepare` writes each
 * per-item table the preparer freezes and stamps the root's `tq-tables`
 * attribute with the served URL; the exposed transport backs a prerendered
 * route so the built output ships the bytes the manifests pin.
 * `createTiqianTables` exports the same transport on its own for hosts that
 * run their own preparation pipeline.
 */
export function createTiqianSvelteKit(options = {}) {
  const retainedAssets = new Map();
  const requestAssets = new AsyncLocalStorage();
  const maximumRetainedBundles = Number(options.maximumRetainedBundles ?? 256);
  if (!Number.isSafeInteger(maximumRetainedBundles) || maximumRetainedBundles <= 0) {
    throw new Error("InvalidMaximumRetainedTiqianBundles");
  }
  const tablesOptions = options.tables ?? null;
  const tableTransport = tablesOptions == null ? null : createTiqianTables(tablesOptions);
  const preparerPromise = options.htmlPreparer == null
    ? createHtmlPreparer(options)
    : Promise.resolve(options.htmlPreparer);

  const retain = (assets, target = requestAssets.getStore() ?? retainedAssets) => {
    if (!assets) return;
    const existing = target.get(assets.id);
    if (existing && !sameServerAssets(existing, assets)) {
      throw new Error(`ConflictingTiqianSvelteKitAssets:${assets.id}`);
    }
    target.delete(assets.id);
    target.set(assets.id, assets);
    while (target.size > maximumRetainedBundles) {
      target.delete(target.keys().next().value);
    }
  };

  const prepare = async (html, prepareOptions = {}) => {
    const preparer = await preparerPromise;
    const result = await preparer.prepare(html, prepareOptions);
    retain(result.serverAssets);
    // The native call freezes per-item table bytes; the transport serves
    // them and the root attribute points the runtime at the URL.
    const rootAttributes = result.tables != null && tableTransport != null
      ? { ...result.rootAttributes, "tq-tables": tableTransport.write(result.tables) }
      : result.rootAttributes;
    return Object.freeze({
      html: result.html,
      rootAttributes,
      snapshot: result.clientBundle,
      issues: result.issues,
    });
  };

  const handle = async ({ event, resolve }) => {
    const scopedAssets = new Map();
    return requestAssets.run(scopedAssets, async () => {
      let bufferedHtml = "";
      return resolve(event, {
        transformPageChunk: ({ html, done }) => {
          bufferedHtml += html;
          if (!done) return "";
          return injectTiqianSsrAssets(
            bufferedHtml,
            (id) => scopedAssets.get(id) ?? retainedAssets.get(id),
          );
        },
      });
    });
  };

  return Object.freeze({
    prepare,
    handle,
    ...(tableTransport == null ? {} : { tables: tableTransport }),
    getServerAssets(id) {
      const key = String(id);
      return requestAssets.getStore()?.get(key) ?? retainedAssets.get(key);
    },
    async close() {
      const preparer = await preparerPromise;
      preparer.close();
      retainedAssets.clear();
    },
  });
}
