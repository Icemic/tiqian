// HostContentReconcile: classification and DOM preparation for live-DOM
// content changes on an enhanced root.
//
// Stateless module: probeContentDrift(custody, ...), classifyReconcile(
// custody, ...), prepareTrackedParagraphForRelowering(custody, ...) and
// stripEngineMarkupFromStrandedParagraph(custody, ...) are named functions
// that receive the custody collaborator as an explicit first parameter. The
// engine bootstrap passes the shared custody instance; tests pass a fake.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw
// string, so the source must contain no dollar sign and no triple
// double-quote sequence, and the Kotlin parser accepts only a classic JS
// subset: no optional chaining, no nullish coalescing, no spread arguments,
// no for-of loops, no bare catch. Use explicit conditionals, indexed loops
// and apply instead.

// Ambient global declarations pulled in via import type from owner modules.
import type { PreparedDomRendererApi } from "../sampler/snapshot/prepared-dom.js";
import type { CustodyApi } from "./custody.js";

export interface ReconcileSpec {
  trackedSources: Element[];
  tainted?: Element[];
  strandedCandidates?: Element[];
  rootSelector: string;
}

export interface ReconcileResult {
  outcome: "idle" | "work";
  drifted: Element[];
  custody: Element[];
  tainted: Element[];
  stranded: Element[];
  dead: number;
  json: string;
}

function releasePreparedStyles(element: Element): boolean {
  const renderer = globalThis.__TiqianPreparedDomRenderer;
  if (renderer && renderer.release && renderer.release(element) === true) return true;
  return false;
}

// Read-only drift probe for captured in-flight jobs: answers the same
// per-paragraph classification question as classifyReconcile without
// touching the DOM, so element.js cancels only on real drift.
export function probeContentDrift(custody: CustodyApi, trackedSources: Element[]): string {
  let drifted = 0;
  let dead = 0;
  let custodyCount = 0;
  for (let index = 0; index < trackedSources.length; index++) {
    const source = trackedSources[index];
    if (!source.isConnected) {
      dead += 1;
    } else if (!custody.renderedMatches(source)) {
      drifted += 1;
    } else if (!custody.custodyMatches(source)) {
      custodyCount += 1;
    }
  }
  return '{"unknown":0,"drifted":' + drifted + ',"dead":' + dead +
    ',"custody":' + custodyCount + '}';
}

// Per-paragraph classification, never per MutationRecord. DeadTrackedParagraphDrop
// counts tracked sources the host detached; the RenderedContentInvariant
// identity check flags drifted paragraphs; the custody identity check
// flags custody drift. A tainted host survives only when connected,
// inside a root, tracked, and not already classified as drifted. A
// stranded candidate is skipped when it already failed lowering with a
// capability marker and was never rendered (StrandedCapabilityNoRetry).
export function classifyReconcile(custody: CustodyApi, spec: ReconcileSpec): ReconcileResult {
  const trackedSources = spec.trackedSources;
  const drifted: Element[] = [];
  const custodyDrifted: Element[] = [];
  let dead = 0;
  const trackedSet = new Set<Element>();
  for (let trackIndex = 0; trackIndex < trackedSources.length; trackIndex++) {
    const trackedSource = trackedSources[trackIndex];
    trackedSet.add(trackedSource);
    if (!trackedSource.isConnected) {
      dead += 1;
    } else if (!custody.renderedMatches(trackedSource)) {
      drifted.push(trackedSource);
    } else if (!custody.custodyMatches(trackedSource)) {
      custodyDrifted.push(trackedSource);
    }
  }
  const driftedSources = new Set<Element>();
  let driftedIndex;
  for (driftedIndex = 0; driftedIndex < drifted.length; driftedIndex++) {
    driftedSources.add(drifted[driftedIndex]);
  }
  for (driftedIndex = 0; driftedIndex < custodyDrifted.length; driftedIndex++) {
    driftedSources.add(custodyDrifted[driftedIndex]);
  }
  const tainted = spec.tainted || [];
  const taintedTracked: Element[] = [];
  for (let taintedIndex = 0; taintedIndex < tainted.length; taintedIndex++) {
    const host = tainted[taintedIndex];
    if (!host.isConnected) continue;
    if (!(host.closest && host.closest(spec.rootSelector))) continue;
    if (!trackedSet.has(host)) continue;
    if (driftedSources.has(host)) continue;
    taintedTracked.push(host);
  }
  const stranded: Element[] = [];
  const candidates = spec.strandedCandidates || [];
  for (let candidateIndex = 0; candidateIndex < candidates.length; candidateIndex++) {
    const candidate = candidates[candidateIndex];
    if (!candidate.hasAttribute("data-tiqian-capability-issue") ||
        candidate.hasAttribute("data-tq-rendered")) {
      stranded.push(candidate);
    }
  }
  const empty = drifted.length === 0 && custodyDrifted.length === 0 &&
    taintedTracked.length === 0 && stranded.length === 0;
  return {
    outcome: empty ? "idle" : "work",
    drifted: drifted,
    custody: custodyDrifted,
    tainted: taintedTracked,
    stranded: stranded,
    dead: dead,
    json: '{"outcome":"' + (empty ? "idle" : "work") + '","drifted":' +
      drifted.length + ',"custody":' + custodyDrifted.length +
      ',"tainted":' + taintedTracked.length + ',"stranded":' +
      stranded.length + ',"dead":' + dead + '}',
  };
}

// HostEditRelowering: the host replaced or edited the live children of a
// rendered paragraph. Release prepared styles, restore the engine-owned
// shell, stamp the rendered marker, and let the caller re-lower the
// surviving live content as the new custody source.
export function prepareTrackedParagraphForRelowering(custody: CustodyApi, element: HTMLElement): void {
  releasePreparedStyles(element);
  custody.restoreShell(element);
  custody.stampRendered(element);
}

// CloneDescaffoldEngineMarkup: innerHTML re-projection hands the runtime a
// clone that still carries engine scaffolding: line markers, copy-ignore
// spans, engine break elements, prepared value styles, and the paragraph
// takeover attributes. Remove exactly those engine-authored artifacts so
// the clone lowers as ordinary host content. Host elements and host
// inline styles survive untouched.
export function stripEngineMarkupFromStrandedParagraph(custody: CustodyApi, paragraph: HTMLElement): void {
  releasePreparedStyles(paragraph);
  // The hidden data-tq-hard-break span is the only place a cloned hard
  // break keeps its source form. Restore a bare br before removing
  // engine elements: a newline text node would be folded into a space by
  // collapse-mode re-lowering and lose the break.
  const hardBreaks = paragraph.querySelectorAll("[data-tq-hard-break]");
  for (let breakIndex = 0; breakIndex < hardBreaks.length; breakIndex++) {
    const hardBreak = hardBreaks[breakIndex];
    if (hardBreak.parentNode) {
      hardBreak.parentNode.replaceChild(document.createElement("br"), hardBreak);
    }
  }
  const artifacts = paragraph.querySelectorAll(
    "[data-tq-copy-ignore], [data-tq-engine-break], [data-tq-src], [data-tq-prepared-value-styles]",
  );
  for (let artifactIndex = 0; artifactIndex < artifacts.length; artifactIndex++) {
    const artifact = artifacts[artifactIndex];
    if (artifact.parentNode) artifact.parentNode.removeChild(artifact);
  }
  // Engine run spans position glyphs through --tq-* custom properties.
  // Those values are meaningless on host content and would survive
  // lowering, so strip them from every remaining descendant.
  const descendants = paragraph.querySelectorAll<HTMLElement>("*");
  for (let descIndex = 0; descIndex < descendants.length; descIndex++) {
    const element = descendants[descIndex];
    const engineProperties: string[] = [];
    for (let styleIndex = 0; styleIndex < element.style.length; styleIndex++) {
      const name = element.style.item(styleIndex);
      if (name.indexOf("--tq-") === 0) engineProperties.push(name);
    }
    for (let removeIndex = 0; removeIndex < engineProperties.length; removeIndex++) {
      element.style.removeProperty(engineProperties[removeIndex]);
    }
  }
  paragraph.removeAttribute("data-tq-rendered");
  paragraph.removeAttribute("data-tq-canonical-plain");
  paragraph.removeAttribute("data-tq-canonical-source");
  paragraph.removeAttribute("data-tq-exact-prepared-dom");
  paragraph.removeAttribute("data-tq-runtime-render-font");
  paragraph.removeAttribute("data-tq-host-inline-size");
  paragraph.removeAttribute("data-tiqian-capability-issue");
  paragraph.removeAttribute("data-tiqian-capability-detail");
  // EngineInlineStyleStrippingOnClone: takeover writes position,
  // inline-size and font-size with important priority. Originals are
  // unknown on a clone, so remove exactly those engine-signed writes.
  if (paragraph.style.getPropertyPriority("position") === "important" &&
      paragraph.style.getPropertyValue("position") === "relative") {
    paragraph.style.removeProperty("position");
  }
  if (paragraph.style.getPropertyPriority("inline-size") === "important") {
    paragraph.style.removeProperty("inline-size");
  }
  if (paragraph.style.getPropertyPriority("font-size") === "important") {
    paragraph.style.removeProperty("font-size");
  }
  const styleAttribute = paragraph.getAttribute("style");
  if (styleAttribute === null || styleAttribute.trim() === "") {
    paragraph.removeAttribute("style");
  }
}