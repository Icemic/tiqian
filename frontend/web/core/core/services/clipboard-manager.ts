// ClipboardManager: the page-wide copy interceptor service (wc-s6 scope 4).
// One instance per document, held by the globalServices container. The install
// method is idempotent per document through a WeakSet, so enhance-time calls
// from any root share the same listener. The listener reads the selection from
// the installed document's defaultView, not globalThis.window, so cross-realm
// installations (iframe documents) project the correct selection.
//
// Container admission: the service is page-level behavior reached from async
// host callbacks (the copy event listener); it owns the per-document WeakSet
// that keeps the one-listener-per-document invariant.
import { createTiqianClipboardPayload } from "../utils/copy.js";

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

export class ClipboardManager {
  #installedDocuments = new WeakSet<Document>();

  install(documentObject: Document): void {
    if (!documentObject || this.#installedDocuments.has(documentObject) ||
        typeof documentObject.addEventListener !== "function")
      return;
    this.#installedDocuments.add(documentObject);
    const hostWindow = documentObject.defaultView;
    documentObject.addEventListener("copy", (event) => {
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
}
