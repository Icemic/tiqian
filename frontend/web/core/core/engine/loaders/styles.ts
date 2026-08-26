import { globalServices } from "../../services/global-services.js";

const getStylesheetState = () => globalServices().stylesheetLoader!;

export function ensureTiqianStyles(root: Element | null = null): Promise<HTMLLinkElement | null> {
  const state = getStylesheetState();
  // StaticStylesheetFastPath: bundlers can include the public CSS entry in the
  // server-rendered head. In that case its readiness marker is already in the
  // cascade and injecting a duplicate runtime <link> would only delay takeover.
  if (
    root && typeof getComputedStyle === "function" &&
    getComputedStyle(root).getPropertyValue("--tq-styles-ready").trim() === "1"
  ) return Promise.resolve(null);
  const existing = document.querySelector<HTMLLinkElement>("link[data-tiqian-stylesheet]");
  if (existing?.sheet) {
    state.stylesheetElement = existing;
    return Promise.resolve(existing);
  }
  if (existing && existing === state.stylesheetElement && state.stylesheetPromise) {
    return state.stylesheetPromise as Promise<HTMLLinkElement | null>;
  }

  const link = existing ?? document.createElement("link");
  if (!existing) {
    link.rel = "stylesheet";
    // styles.css stays at the package root (report section 11 publishing
    // constraint), so resolve it relative to the root from this subdirectory.
    link.href = new URL("../../../styles.css", import.meta.url).href;
    link.dataset.tiqianStylesheet = "true";
  }
  state.stylesheetElement = link;
  state.stylesheetPromise = new Promise<HTMLLinkElement>((resolve, reject) => {
    link.addEventListener("load", () => resolve(link), { once: true });
    link.addEventListener(
      "error",
      () => reject(new Error("Tiqian stylesheet failed to load")),
      { once: true },
    );
    if (!existing) document.head.append(link);
  }).catch((error: unknown) => {
    if (getStylesheetState().stylesheetElement === link) {
      getStylesheetState().stylesheetElement = undefined;
      getStylesheetState().stylesheetPromise = undefined;
    }
    throw error;
  });
  return state.stylesheetPromise as Promise<HTMLLinkElement | null>;
}
