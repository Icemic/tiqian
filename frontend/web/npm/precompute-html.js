import { createHash } from "node:crypto";

import { parseHTML } from "linkedom";

import {
  createPrecomputer,
  renderFontContractBundle,
  renderSnapshotBundle,
} from "./precompute.js";
import { snapshotSourceArtifactFromDom } from "./snapshot-source.js";

const DEFAULT_PARAGRAPH_SELECTOR = "p, li";
const DEFAULT_SKIPPED_ANCESTOR_SELECTOR =
  "[data-tiqian-skip], pre, table, .not-prose, .katex, .katex-display, .expressive-code";
const SOURCE_MAP_IGNORED_ANCESTOR_SELECTOR =
  "iframe, noembed, noframes, noscript, plaintext, xmp";
const LIST_ITEM_CONTAINER_TAGS = new Set(["p", "ul", "ol", "blockquote", "pre", "table"]);
const RAW_TEXT_ELEMENTS = new Set([
  "iframe", "noembed", "noframes", "noscript", "script", "style", "textarea", "title", "xmp",
]);

function escapeAttribute(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;");
}

function selectedTagNames(selector) {
  const names = String(selector).split(",").map((item) => item.trim().toLowerCase());
  if (names.length === 0 || names.some((name) => !/^[a-z][a-z0-9-]*$/u.test(name))) {
    throw new Error("UnsupportedHtmlParagraphSelector");
  }
  return names;
}

/**
 * SourceFaithfulSnapshotKeyInsertion: locate source opening tags without
 * serializing the host's HTML. Browser raw-text containers and inert templates
 * are skipped so a literal `<p>` example can never receive a live snapshot key.
 */
export function findHtmlOpeningTags(htmlValue, tagNames = ["p", "li"]) {
  const html = String(htmlValue);
  const selected = new Set(tagNames.map((tagName) => String(tagName).toLowerCase()));
  const lowerHtml = html.toLowerCase();
  const tags = [];
  let templateDepth = 0;
  for (let start = html.indexOf("<"); start >= 0; start = html.indexOf("<", start + 1)) {
    if (html.startsWith("<!--", start)) {
      const commentEnd = html.indexOf("-->", start + 4);
      if (commentEnd < 0) break;
      start = commentEnd + 2;
      continue;
    }
    let end = start + 1;
    let quote = "";
    for (; end < html.length; end += 1) {
      const character = html[end];
      if (quote) {
        if (character === quote) quote = "";
      } else if (character === '"' || character === "'") {
        quote = character;
      } else if (character === ">") {
        break;
      }
    }
    if (end >= html.length) break;
    const source = html.slice(start, end + 1);
    const match = /^<(\/)?([a-z][a-z0-9-]*)(?:\s|\/?>)/iu.exec(source);
    const closing = match?.[1] === "/";
    const tagName = match?.[2]?.toLowerCase();
    const selfClosing = /\/>$/u.test(source);
    if (!closing && tagName === "plaintext") break;
    if (!closing && tagName && RAW_TEXT_ELEMENTS.has(tagName)) {
      const closingStart = lowerHtml.indexOf(`</${tagName}`, end + 1);
      if (closingStart < 0) break;
      const closingEnd = html.indexOf(">", closingStart + tagName.length + 2);
      if (closingEnd < 0) break;
      start = closingEnd;
      continue;
    }
    if (tagName === "template") {
      if (closing) templateDepth = Math.max(0, templateDepth - 1);
      else if (!selfClosing) templateDepth += 1;
    } else if (!closing && tagName && selected.has(tagName) && templateDepth === 0) {
      tags.push(Object.freeze({ end, source, tagName }));
    }
    start = end;
  }
  return Object.freeze(tags);
}

export function injectHtmlAttributes(htmlValue, insertionsValue) {
  let html = String(htmlValue);
  const insertions = Array.from(insertionsValue ?? []).toSorted(
    (left, right) => Number(right.offset) - Number(left.offset),
  );
  for (const insertion of insertions) {
    const offset = Number(insertion.offset);
    if (!Number.isSafeInteger(offset) || offset < 0 || offset > html.length) {
      throw new Error("InvalidHtmlAttributeInsertionOffset");
    }
    html = `${html.slice(0, offset)}${String(insertion.attribute)}${html.slice(offset)}`;
  }
  return html;
}

function projectedTextOnly(paragraph) {
  let raw = "";
  const hardBreakOffsets = new Set();
  const append = (node) => {
    if (node.nodeType === 3) {
      raw += String(node.textContent ?? "");
      return;
    }
    if (node.nodeType !== 1) return;
    const tagName = String(node.tagName ?? "").toLowerCase();
    if (tagName === "br") {
      hardBreakOffsets.add(raw.length);
      raw += "\n";
      return;
    }
    if (["script", "style", "template"].includes(tagName)) return;
    for (const child of Array.from(node.childNodes ?? [])) append(child);
  };
  for (const child of Array.from(paragraph.childNodes ?? [])) append(child);

  const output = [];
  let pendingWhitespace = false;
  for (let index = 0; index < raw.length; index += 1) {
    const character = raw[index];
    if (character === "\n" && hardBreakOffsets.has(index)) {
      pendingWhitespace = false;
      output.push("\n");
    } else if (/[ \t\n\r\f]/u.test(character)) {
      pendingWhitespace = output.length > 0 && output.at(-1) !== "\n";
    } else {
      if (pendingWhitespace) output.push(" ");
      pendingWhitespace = false;
      output.push(character);
    }
  }
  return output.join("");
}

function nestedListItem(element) {
  return String(element.tagName).toLowerCase() === "li" && Array.from(element.children ?? [])
    .some((child) => LIST_ITEM_CONTAINER_TAGS.has(String(child.tagName).toLowerCase()));
}

function snapshotProjection(element, typography, projector) {
  let source;
  try {
    source = snapshotSourceArtifactFromDom(element);
  } catch {
    return null;
  }
  if (typeof projector === "function") {
    const projected = projector(Object.freeze({ element, source, typography }));
    if (projected == null || projected === false) return null;
    return Object.freeze({
      text: source.text,
      semantics: projected.semantics ?? source.semantics,
      textSpans: projected.textSpans ?? [],
      inlineBoxes: projected.inlineBoxes ?? [],
      sourceBoundaries: projected.sourceBoundaries ?? [],
    });
  }
  // DefaultSnapshotPlainSemanticBoundary: arbitrary host inline CSS cannot be
  // reconstructed in Node. Plain text and explicit <br> are safe by default;
  // hosts opt richer semantics in through one named projection callback.
  if (source.semantics.length > 0) return null;
  return Object.freeze({
    text: source.text,
    semantics: source.semantics,
    textSpans: Object.freeze([]),
    inlineBoxes: Object.freeze([]),
    sourceBoundaries: Object.freeze([]),
  });
}

function clientBundle(bundle) {
  return Object.freeze({
    id: bundle.id,
    clientTemplate: bundle.clientTemplate,
    initialStyle: bundle.initialStyle,
    fontPreloads: bundle.fontPreloads,
  });
}

export function snapshotServerAssets(bundle) {
  if (!bundle) return null;
  return Object.freeze({
    id: bundle.id,
    initialStyle: bundle.initialStyle,
    inertTemplate: bundle.inertTemplate,
    fontPreloads: Object.freeze([...bundle.fontPreloads]),
  });
}

export function renderSnapshotServerAssets(assets) {
  if (!assets) return "";
  const preloads = assets.fontPreloads.map((href) =>
    `<link rel="preload" as="font" type="font/woff2" crossorigin href="${escapeAttribute(href)}">`
  ).join("");
  return preloads +
    `<style data-tq-initial-snapshot="${escapeAttribute(assets.id)}">${assets.initialStyle}</style>` +
    assets.inertTemplate;
}

/**
 * Creates the framework-neutral server boundary consumed by SvelteKit and
 * Astro integrations. Host HTML remains byte-for-byte intact except for
 * snapshot keys inserted into paragraphs that produced reusable geometry.
 */
export async function createHtmlPreparer(options = {}) {
  const ownsPrecomputer = options.precomputer == null;
  const precomputer = options.precomputer ?? await createPrecomputer(options);
  const paragraphSelector = String(options.paragraphSelector ?? DEFAULT_PARAGRAPH_SELECTOR);
  const tagNames = selectedTagNames(paragraphSelector);
  const skippedAncestorSelector = String(
    options.skippedAncestorSelector ?? DEFAULT_SKIPPED_ANCESTOR_SELECTOR,
  );
  const projector = options.projectSnapshotParagraph;
  let closed = false;

  const prepare = async (htmlValue, prepareOptions = {}) => {
    if (closed) throw new Error("HtmlPreparerClosed");
    const html = String(htmlValue);
    const snapshotWidth = prepareOptions.snapshot?.maxWidthPx;
    if (snapshotWidth != null && (!Number.isFinite(Number(snapshotWidth)) || Number(snapshotWidth) <= 0)) {
      throw new Error("InvalidMaximumMeasure");
    }
    const id = String(prepareOptions.id ?? `tq-prose-${createHash("sha256")
      .update(html)
      .update("\0")
      .update(snapshotWidth == null ? "runtime" : String(snapshotWidth))
      .digest("hex").slice(0, 16)}`);
    const { document } = parseHTML(`<main data-tq-html-prepare-root>${html}</main>`);
    const root = document.querySelector("main[data-tq-html-prepare-root]");
    if (!root) throw new Error("HtmlPrepareRootUnavailable");
    const sourceElements = Array.from(root.querySelectorAll(paragraphSelector)).filter(
      (element) => !element.closest(SOURCE_MAP_IGNORED_ANCESTOR_SELECTOR),
    );
    const openingTags = findHtmlOpeningTags(html, tagNames);
    if (sourceElements.length !== openingTags.length) {
      throw new Error(`HtmlParagraphSourceMapMismatch:${sourceElements.length}:${openingTags.length}`);
    }

    const preparedParagraphs = [];
    const fontContracts = [];
    const insertions = [];
    const issues = [];
    for (const [index, element] of sourceElements.entries()) {
      const openingTag = openingTags[index];
      if (openingTag.tagName !== String(element.tagName).toLowerCase()) {
        throw new Error(`HtmlParagraphSourceOrderMismatch:${index}`);
      }
      if (element.closest(skippedAncestorSelector) || nestedListItem(element)) continue;
      const projected = snapshotProjection(element, precomputer.typography, projector);
      const text = projected?.text ?? projectedTextOnly(element);
      if (!text.trim()) continue;

      let prepared = null;
      const snapshotKey = `p-${index}`;
      if (snapshotWidth != null && projected) {
        prepared = await precomputer.prepareParagraph({
          key: snapshotKey,
          text: projected.text,
          maxWidthPx: Number(snapshotWidth),
          semantics: projected.semantics,
          textSpans: projected.textSpans,
          inlineBoxes: projected.inlineBoxes,
          sourceBoundaries: projected.sourceBoundaries,
        });
        if (prepared.status === "prepared") {
          preparedParagraphs.push(prepared);
          insertions.push({
            offset: openingTag.end,
            attribute: ` data-tq-snapshot-key="${snapshotKey}"`,
          });
          continue;
        }
        issues.push(Object.freeze({ index, key: snapshotKey, stage: "snapshot", issue: prepared.issue }));
      }

      const contractKey = `f-${index}`;
      const contract = await precomputer.prepareFontContract({
        key: contractKey,
        text,
        ...(projected ? {
          semantics: projected.semantics,
          textSpans: projected.textSpans,
          inlineBoxes: projected.inlineBoxes,
          sourceBoundaries: projected.sourceBoundaries,
        } : {}),
      });
      if (contract.status === "prepared") fontContracts.push(contract);
      else issues.push(Object.freeze({ index, key: contractKey, stage: "font-contract", issue: contract.issue }));
    }

    const bundle = preparedParagraphs.length > 0
      ? renderSnapshotBundle(preparedParagraphs, { id, fontContractParagraphs: fontContracts })
      : fontContracts.length > 0
        ? renderFontContractBundle(fontContracts, { id })
        : null;
    const preparedHtml = injectHtmlAttributes(html, insertions);
    if (!bundle) {
      return Object.freeze({
        html: preparedHtml,
        rootAttributes: Object.freeze({}),
        bundle: null,
        clientBundle: null,
        serverAssets: null,
        issues: Object.freeze(issues),
      });
    }
    return Object.freeze({
      html: preparedHtml,
      rootAttributes: Object.freeze({ "snapshot-ref": bundle.id, ...bundle.rootAttributes }),
      bundle,
      clientBundle: clientBundle(bundle),
      serverAssets: snapshotServerAssets(bundle),
      issues: Object.freeze(issues),
    });
  };

  return Object.freeze({
    typography: precomputer.typography,
    prepare,
    close() {
      if (closed) return;
      closed = true;
      if (ownsPrecomputer) precomputer.close();
    },
  });
}
