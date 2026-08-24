// Source-faithful clipboard projection (SourceFaithfulSemanticClipboard).
//
// Plain script: running it installs globalThis.__TiqianCreateClipboardPayload
// and globalThis.__TiqianInstallCopyHandler. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which the generateCopyBridge gradle
// task embeds this source verbatim.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

const BLOCK_ELEMENTS = new Set([
  "ADDRESS",
  "ARTICLE",
  "ASIDE",
  "BLOCKQUOTE",
  "DD",
  "DIV",
  "DL",
  "DT",
  "FIELDSET",
  "FIGCAPTION",
  "FIGURE",
  "FOOTER",
  "FORM",
  "H1",
  "H2",
  "H3",
  "H4",
  "H5",
  "H6",
  "HEADER",
  "HR",
  "LI",
  "MAIN",
  "NAV",
  "OL",
  "P",
  "PRE",
  "SECTION",
  "TABLE",
  "TR",
  "UL",
]);

const ENGINE_FLOW_STYLE_PROPERTIES = [
  "white-space-collapse",
  "overflow-wrap",
  "text-autospace",
  "text-spacing-trim",
  "text-wrap-mode",
  "-webkit-hyphens",
  "hyphens",
  "word-break",
];

interface ClipboardText {
  block: boolean;
  text: string;
}

interface ClipboardPayload {
  text: string;
  html: string;
}

function clipboardTextForChildren(parent: Node): string {
  const children = Array.from(parent.childNodes || []);
  const containsBlock = children.some(
    (child) => child.nodeType === 1 && BLOCK_ELEMENTS.has((child as Element).tagName),
  );
  let result = "";
  let previous: ClipboardText | null = null;
  for (let childIndex = 0; childIndex < children.length; childIndex++) {
    const child = children[childIndex];
    if (containsBlock && child.nodeType === 3 && !((child as Text).data && (child as Text).data.trim())) continue;
    const item = clipboardTextForNode(child);
    if (
      previous && (previous.block || item.block) && result && item.text &&
      !result.endsWith("\n") && !item.text.startsWith("\n")
    ) result += "\n";
    result += item.text;
    previous = item;
  }
  return result;
}

function clipboardTextForNode(node: Node): ClipboardText {
  if (node.nodeType === 3) return { block: false, text: (node as Text).data || "" };
  if (node.nodeType !== 1) return { block: false, text: "" };
  if ((node as Element).tagName === "BR") return { block: false, text: "\n" };
  return {
    block: BLOCK_ELEMENTS.has((node as Element).tagName),
    text: clipboardTextForChildren(node),
  };
}

function stripEngineStyles(element: HTMLElement, rendered: boolean, sourceSemantic: boolean): void {
  if (!element.style || (!rendered && !sourceSemantic)) return;
  for (let propertyIndex = 0; propertyIndex < ENGINE_FLOW_STYLE_PROPERTIES.length; propertyIndex++) {
    element.style.removeProperty(ENGINE_FLOW_STYLE_PROPERTIES[propertyIndex]);
  }
  if (rendered) element.style.removeProperty("position");
  const styleAttribute = element.getAttribute("style");
  if (!(styleAttribute && styleAttribute.trim())) element.removeAttribute("style");
}

/**
 * `SourceFaithfulSemanticClipboard`: remove Tiqian's paint-only DOM, restore
 * source substitutions and hard breaks, then serialize block-aware plain text
 * plus host-owned semantic HTML. Visual soft wraps never enter either payload.
 */
function createTiqianClipboardPayload(fragment: DocumentFragment | null, documentObject: Document = globalThis.document): ClipboardPayload {
  if (!fragment || !fragment.querySelectorAll || !documentObject || !documentObject.createElement) {
    return { text: "", html: "" };
  }

  fragment.querySelectorAll("[data-tq-copy-ignore]").forEach((element) => element.remove());
  fragment.querySelectorAll("[data-tq-src]").forEach((element) => {
    if (element.hasAttribute("data-tq-hard-break")) {
      // PartialRangeMandatoryBreak: Range.cloneContents() may contain only the
      // hidden source marker, only the semantic BR, or both. Prefer the BR when
      // both survived; otherwise materialize the missing half as one BR.
      const semanticBreak = element.nextElementSibling;
      if (semanticBreak && semanticBreak.matches &&
          semanticBreak.matches("br[data-tq-engine-break='MandatoryBreak']")) {
        element.remove();
      } else {
        element.replaceWith(documentObject.createElement("br"));
      }
    } else {
      element.replaceWith(documentObject.createTextNode(element.getAttribute("data-tq-src") || ""));
    }
  });
  fragment.querySelectorAll(
    "[data-tq-engine-break]:not([data-tq-engine-break='MandatoryBreak'])",
  ).forEach((element) => element.remove());

  Array.from(fragment.querySelectorAll("[data-tq-geometry]"))
    .reverse()
    .forEach((element) => element.replaceWith.apply(element, Array.from(element.childNodes)));

  fragment.querySelectorAll<HTMLElement>("*").forEach((element) => {
    const rendered = element.hasAttribute("data-tq-rendered");
    const sourceSemantic = element.hasAttribute("data-tq-source-semantic");
    const cjkStrong = element.hasAttribute("data-tq-cjk-emphasis");
    stripEngineStyles(element, rendered, sourceSemantic);
    if (cjkStrong) {
      if (element.style) element.style.removeProperty("font-weight");
      const emphasisStyleAttribute = element.getAttribute("style");
      if (!(emphasisStyleAttribute && emphasisStyleAttribute.trim())) element.removeAttribute("style");
    }
    Array.from(element.attributes).forEach((attribute) => {
      if (attribute.name.startsWith("data-tq-")) element.removeAttribute(attribute.name);
    });
  });

  const wrapper = documentObject.createElement("div");
  wrapper.appendChild(fragment);
  return {
    text: clipboardTextForChildren(wrapper),
    html: wrapper.innerHTML,
  };
}

function installTiqianCopyHandler(documentObject: Document = globalThis.document): void {
  if (!documentObject || globalThis.__tiqianCopyHandlerInstalled) return;
  globalThis.__tiqianCopyHandlerInstalled = true;
  documentObject.addEventListener("copy", (event) => {
    const hostWindow = globalThis.window;
    const selection = hostWindow && hostWindow.getSelection ? hostWindow.getSelection() : null;
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    const renderedAncestor = (node: Node | null): Element | null => {
      const element = node && node.nodeType === 1 ? node as Element : (node ? node.parentElement : null);
      return element && element.closest ? element.closest("[data-tq-rendered]") : null;
    };
    let touchesRendered = Boolean(
      renderedAncestor(range.startContainer) || renderedAncestor(range.endContainer),
    );
    if (!touchesRendered) {
      const common = range.commonAncestorContainer;
      const commonElement = common && common.nodeType === 1 ? common as Element : (common ? common.parentElement : null);
      const candidates = commonElement && commonElement.querySelectorAll
        ? Array.from(commonElement.querySelectorAll("[data-tq-rendered]"))
        : [];
      if (commonElement && commonElement.matches && commonElement.matches("[data-tq-rendered]")) {
        candidates.unshift(commonElement);
      }
      touchesRendered = candidates.some((candidate) => {
        try {
          return range.intersectsNode(candidate);
        } catch (error) {
          return false;
        }
      });
    }
    if (!touchesRendered) return;
    const payload = createTiqianClipboardPayload(range.cloneContents(), documentObject);
    if ((payload.text || payload.html) && event.clipboardData) {
      event.clipboardData.setData("text/plain", payload.text);
      if (payload.html) event.clipboardData.setData("text/html", payload.html);
      event.preventDefault();
    }
  });
}

declare global {
  var __TiqianCreateClipboardPayload: typeof createTiqianClipboardPayload | undefined;
  var __TiqianInstallCopyHandler: typeof installTiqianCopyHandler | undefined;
  var __tiqianCopyHandlerInstalled: boolean | undefined;
}

globalThis.__TiqianCreateClipboardPayload = createTiqianClipboardPayload;
globalThis.__TiqianInstallCopyHandler = installTiqianCopyHandler;

export {};
