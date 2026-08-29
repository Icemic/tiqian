// DomWriteLayer — the root subtree's mechanical attribute and record
// writes for one enhanced element (core-neutral parts ruling). Owns the
// publishState attribute projection (data-tiqian-enhanced /
// data-tiqian-enhanced-count / data-tiqian-issue-count), the raw-DOM
// fragment probe seam and the rendered raw-DOM record enumeration. Only
// mechanical writes and restores live here; layout decisions stay with
// the parts that call in.

import type { RawDomParagraphRecord } from "../context/enhance-context.js";

export interface DomWriteHooks {
  /** Toggles the runtime-established flag the registry dissolution left. */
  setRuntimeEstablished(value: boolean): void;
  /** Rendered raw-DOM records in DOM order (composition root builds it). */
  renderedRawDomParagraphs(): Iterable<[Element, RawDomParagraphRecord]>;
}

export interface DomWriteLayer {
  publishState(paragraphCount: number, issueCount: number, keepEmpty?: boolean): void;
  rawDomFragmentOf(paragraph: Element): DocumentFragment | null;
  renderedRawDomParagraphs(): Iterable<[Element, RawDomParagraphRecord]>;
}

function observableSnapshotCount(root: Element): number {
  const value = Number(root.getAttribute("data-tiqian-snapshot-count"));
  return Number.isSafeInteger(value) && value > 0 ? value : 0;
}

function createDomWriteLayer(
  root: HTMLElement,
  rawDomParagraphs: Map<Element, RawDomParagraphRecord>,
  hooks: DomWriteHooks,
): DomWriteLayer {
  function publishState(paragraphCount: number, issueCount: number, keepEmpty?: boolean): void {
    const hasWork = paragraphCount > 0 || issueCount > 0;
    if (!hasWork && !keepEmpty) {
      // Baseline deleteState path: the registry is dissolved; clearing the
      // runtime-established flag is its only remaining effect.
      hooks.setRuntimeEstablished(false);
      root.removeAttribute("data-tiqian-enhanced");
      root.removeAttribute("data-tiqian-enhanced-count");
      root.removeAttribute("data-tiqian-issue-count");
      return;
    }
    hooks.setRuntimeEstablished(true);
    root.setAttribute("data-tiqian-enhanced", "true");
    root.setAttribute(
      "data-tiqian-enhanced-count",
      String(paragraphCount + observableSnapshotCount(root)),
    );
    if (issueCount === 0) {
      root.removeAttribute("data-tiqian-issue-count");
    } else {
      root.setAttribute("data-tiqian-issue-count", String(issueCount));
    }
  }

  return {
    publishState,
    // Test/probe seam (ADR 0053): the product carries no DOM property for
    // the raw-DOM fragment, so probes ask the context, which keys the
    // custody records by paragraph element.
    rawDomFragmentOf(paragraph: Element): DocumentFragment | null {
      const record = rawDomParagraphs.get(paragraph);
      return record?.fragment ?? null;
    },
    renderedRawDomParagraphs(): Iterable<[Element, RawDomParagraphRecord]> {
      return hooks.renderedRawDomParagraphs();
    },
  };
}

export { createDomWriteLayer };
