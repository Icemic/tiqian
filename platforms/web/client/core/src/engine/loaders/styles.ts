// Stylesheet loader handles (wc-s5 R9): one stylesheet link element and load
// promise per document, owned by this module. The record lives in this
// module's closure behind a Symbol.for registry key rather than in the
// service container because it is behavior-less state: module copies in one
// document (client routers, HMR, duplicated chunks) still share one record.
interface StylesheetLoaderState {
  stylesheetPromises: WeakMap<Document, Promise<HTMLLinkElement>>;
  stylesheetElements: WeakMap<Document, HTMLLinkElement>;
}

const STYLESHEET_LOADER_KEY: unique symbol = Symbol.for("@tiqian/core.stylesheet-loader.v1");

type StylesheetLoaderRegistry = Record<symbol, StylesheetLoaderState | undefined>;

const getStylesheetState = (): StylesheetLoaderState => {
  const registry = globalThis as StylesheetLoaderRegistry;
  return registry[STYLESHEET_LOADER_KEY] ??= {
    stylesheetPromises: new WeakMap(),
    stylesheetElements: new WeakMap(),
  };
};

export function ensureTiqianStyles(
  targetDocument: Document | null | undefined,
  root: Element | null = null,
): Promise<HTMLLinkElement | null> {
  // TargetDocumentExplicit: the stylesheet lives in one specific document
  // resolved by the caller at the call boundary; callers without a document
  // (rootless API or SSR-style paths) pass null and receive null.
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
    return previousPromise;
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
