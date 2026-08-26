import { globalServices } from "../../services/global-services.js";

const getStylesheetState = () => globalServices().stylesheetLoader!;

export function ensureTiqianStyles(root: Element | null = null): Promise<HTMLLinkElement | null> {
  // TargetDocumentExplicit: the stylesheet lives in one specific document.
  // Resolve it from the root first; the rootless API path keeps a guarded
  // ambient fallback so SSR-style callers without any document get null.
  const targetDocument: Document | undefined = root?.ownerDocument ?? globalThis.document;
  if (!targetDocument) return Promise.resolve(null);
  const state = getStylesheetState();
  // StaticStylesheetFastPath: bundlers can include the public CSS entry in the
  // server-rendered head. In that case its readiness marker is already in the
  // cascade and injecting a duplicate runtime <link> would only delay takeover.
  if (
    root && typeof getComputedStyle === "function" &&
    getComputedStyle(root).getPropertyValue("--tq-styles-ready").trim() === "1"
  ) return Promise.resolve(null);
  const existing = targetDocument.querySelector<HTMLLinkElement>("link[data-tiqian-stylesheet]");
  if (existing?.sheet) {
    state.stylesheetElements.set(targetDocument, existing);
    return Promise.resolve(existing);
  }
  const previousPromise = state.stylesheetPromises.get(targetDocument);
  if (existing && existing === state.stylesheetElements.get(targetDocument) && previousPromise) {
    return previousPromise as Promise<HTMLLinkElement | null>;
  }

  const link = existing ?? targetDocument.createElement("link");
  if (!existing) {
    link.rel = "stylesheet";
    // styles.css stays at the package root (report section 11 publishing
    // constraint), so resolve it relative to the root from this subdirectory.
    link.href = new URL("../../../styles.css", import.meta.url).href;
    link.dataset.tiqianStylesheet = "true";
  }
  state.stylesheetElements.set(targetDocument, link);
  const stylesheetPromise = new Promise<HTMLLinkElement>((resolve, reject) => {
    link.addEventListener("load", () => resolve(link), { once: true });
    link.addEventListener(
      "error",
      () => reject(new Error("Tiqian stylesheet failed to load")),
      { once: true },
    );
    if (!existing) targetDocument.head.append(link);
  }).catch((error: unknown) => {
    const loaderState = getStylesheetState();
    if (loaderState.stylesheetElements.get(targetDocument) === link) {
      loaderState.stylesheetElements.delete(targetDocument);
      loaderState.stylesheetPromises.delete(targetDocument);
    }
    throw error;
  });
  state.stylesheetPromises.set(targetDocument, stylesheetPromise);
  return stylesheetPromise;
}
