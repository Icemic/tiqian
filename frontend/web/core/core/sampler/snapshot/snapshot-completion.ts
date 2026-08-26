// Snapshot/runtime completion selection (lowered from the prose element).
// After a keyed snapshot adoption the remaining unkeyed paragraphs may still
// need runtime completion; these predicates decide which candidates justify
// loading layout code, mirroring the Kotlin runtime candidate set.

import { isPureBlockImageParagraph, skippedAncestorSelector } from "../../engine/eligibility.js";
import { belongsToRootScope } from "../observers.js";

const RUNTIME_COMPLETION_SELECTOR = ":is(p, li):not([data-tq-snapshot-key])";
const NESTED_BLOCK_CHILD_SELECTOR =
  ":scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > table";

export function isRuntimeCompletionCandidate(element: Element, root: Element): boolean {
  if (!belongsToRootScope(element, root)) return false;
  if (element.closest(skippedAncestorSelector)) return false;
  // PureBlockImageParagraphExclusion must match the Kotlin runtime candidate
  // set so an image-only root does not load layout code merely to do no work.
  if (isPureBlockImageParagraph(element)) return false;
  if (element.tagName === "LI" && element.querySelector(NESTED_BLOCK_CHILD_SELECTOR)) return false;
  return true;
}

export function snapshotCompletionSelector(root: Element): string {
  const candidates = root.querySelectorAll(RUNTIME_COMPLETION_SELECTOR);
  for (let i = 0; i < candidates.length; i += 1) {
    if (isRuntimeCompletionCandidate(candidates[i], root)) return RUNTIME_COMPLETION_SELECTOR;
  }
  return "";
}
