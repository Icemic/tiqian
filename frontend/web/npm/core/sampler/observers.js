// DOM invalidation sources and root sampling observers (ADR 0053 batch 2;
// decomposition report section 2). Each source owns the platform wiring
// (observer construction, observe targets, event listeners) and reports
// facts through callbacks; comparison and commit decisions stay with the
// element. Kinds: geometry, typography, content.

import { DEFAULT_PARAGRAPH_SELECTOR, fragmentedBorderBoxInlineSize } from "./signatures.js";

export function createTypographyInvalidationSource(root, handlers) {
  let observer = null;
  let fontListener = null;

  return {
    kind: "typography",
    start() {
      observer?.disconnect();
      observer = new MutationObserver(() => handlers.onMutation());
      // Descendant class/style changes can alter inline semantics. Any ancestor
      // attribute can participate in selectors that change inherited typography.
      observer.observe(root, {
        attributes: true,
        subtree: true,
        attributeFilter: ["class", "style", "data-theme", "data-color-mode"],
      });
      for (let ancestor = root.parentElement; ancestor; ancestor = ancestor.parentElement) {
        observer.observe(ancestor, {
          attributes: true,
          attributeFilter: ["class", "data-theme", "data-color-mode", "lang", "dir"],
        });
      }
      if (document.fonts) {
        fontListener = handlers.onFontEvent;
        document.fonts.addEventListener("loadingdone", fontListener);
        document.fonts.addEventListener("loadingerror", fontListener);
      }
    },
    stop() {
      observer?.disconnect();
      observer = null;
      if (fontListener) {
        document.fonts?.removeEventListener("loadingdone", fontListener);
        document.fonts?.removeEventListener("loadingerror", fontListener);
        fontListener = null;
      }
    },
  };
}

export function createLayoutWorkTypographyInvalidationSource(root, handlers) {
  let observer = null;
  let fontListener = null;

  return {
    kind: "typography",
    start() {
      this.stop();
      observer = new MutationObserver((records) => handlers.onMutation(records));
      observer.observe(root, {
        attributes: true,
        subtree: true,
        attributeFilter: ["class", "style", "data-theme", "data-color-mode"],
        attributeOldValue: true,
      });
      for (let ancestor = root.parentElement; ancestor; ancestor = ancestor.parentElement) {
        observer.observe(ancestor, {
          attributes: true,
          attributeFilter: ["class", "data-theme", "data-color-mode", "lang", "dir"],
        });
      }
      if (document.fonts) {
        fontListener = (event) => handlers.onFontEvent(event);
        document.fonts.addEventListener("loadingdone", fontListener);
        document.fonts.addEventListener("loadingerror", fontListener);
      }
    },
    stop() {
      observer?.disconnect();
      observer = null;
      if (fontListener) {
        document.fonts?.removeEventListener("loadingdone", fontListener);
        document.fonts?.removeEventListener("loadingerror", fontListener);
        fontListener = null;
      }
    },
  };
}

export function createViewportResizeInvalidationSource(handlers) {
  let listener = null;

  return {
    kind: "geometry",
    start() {
      if (listener) return;
      listener = () => handlers.onResize();
      window.addEventListener("resize", listener);
      globalThis.visualViewport?.addEventListener?.("resize", listener);
    },
    stop() {
      if (!listener) return;
      window.removeEventListener("resize", listener);
      globalThis.visualViewport?.removeEventListener?.("resize", listener);
      listener = null;
    },
  };
}

export function createContentInvalidationSource(root, handlers) {
  let observer = null;
  const custodyTargets = new Map();

  // Attribution for a record under a custody fragment: walk up to the
  // enclosing detached fragment and map it back to its live paragraph. Live
  // nodes never reach a DocumentFragment ancestor, so the walk is safe there.
  function paragraphFor(node) {
    let current = node;
    while (current) {
      if (current.nodeType === 11) {
        return custodyTargets.get(current) || null;
      }
      current = current.parentNode;
    }
    return null;
  }

  // CustodyFragmentObservation: takeover moves the host's semantic children
  // into a detached fragment the engine holds. Frameworks keep references to
  // those original nodes (React's text update writes .data on them), so host
  // edits land inside custody where the live-DOM subtree never sees them.
  // Kotlin publishes the current fragment on each rendered paragraph; observe
  // every tracked fragment alongside the root. Re-lowering creates a fresh
  // fragment, so diff the desired set at every job boundary and re-target the
  // observer only when it changed.
  function syncCustody() {
    const desired = new Map();
    const paragraphs = root.querySelectorAll(
      `:is(${DEFAULT_PARAGRAPH_SELECTOR})[data-tq-rendered="true"]`,
    );
    for (let i = 0; i < paragraphs.length; i++) {
      const paragraph = paragraphs[i];
      if (!handlers.belongsToRootScope(paragraph, root)) continue;
      const fragment = paragraph.__tqCustodyFragment;
      if (fragment) desired.set(fragment, paragraph);
    }
    let unchanged = desired.size === custodyTargets.size;
    if (unchanged) {
      for (const [fragment, paragraph] of desired) {
        if (custodyTargets.get(fragment) !== paragraph) {
          unchanged = false;
          break;
        }
      }
    }
    if (unchanged) return;
    // Pending records from the outgoing target set still count. Flush them
    // through the handler first, or a host edit landing in the same frame
    // would be dropped together with the old registration.
    const pending = observer.takeRecords();
    if (pending.length) handlers.onRecords(pending);
    observer.disconnect();
    observer.observe(root, { childList: true, characterData: true, subtree: true });
    for (const fragment of desired.keys()) {
      observer.observe(fragment, { childList: true, characterData: true, subtree: true });
    }
    custodyTargets.clear();
    for (const [fragment, paragraph] of desired) custodyTargets.set(fragment, paragraph);
  }

  return {
    kind: "content",
    start() {
      if (!observer) {
        observer = new MutationObserver((records) => handlers.onRecords(records));
        observer.observe(root, { childList: true, characterData: true, subtree: true });
      }
      syncCustody();
    },
    stop() {
      observer?.disconnect();
      observer = null;
      custodyTargets.clear();
    },
    syncCustody,
    paragraphFor,
    takePendingRecords() {
      return observer?.takeRecords() ?? [];
    },
  };
}

export function createRootSizeObservation(options) {
  let observer = null;
  const { widths, onRootEntry, onWidthsChanged, root } = options;

  return {
    kind: "geometry",
    get widths() { return widths; },
    start(targets) {
      observer = new ResizeObserver((entries) => {
        let changed = false;
        for (let i = 0; i < entries.length; i++) {
          const entry = entries[i];
          let width = 0;
          if (entry.borderBoxSize) {
            const box = Array.isArray(entry.borderBoxSize) ? entry.borderBoxSize[0] : entry.borderBoxSize;
            width = box?.inlineSize ?? 0;
          }
          if (!width && entry.contentRect) {
            width = entry.contentRect.width;
          }
          if (!width) {
            width = fragmentedBorderBoxInlineSize(entry.target);
          }
          const previous = widths.get(entry.target);
          widths.set(entry.target, width);
          if (entry.target === root) {
            const height = entry.contentRect ? entry.contentRect.height : 0;
            onRootEntry({ width, height });
          }
          if (previous == null || Math.abs(width - previous) >= 0.5) changed = true;
        }
        if (!changed) return;
        onWidthsChanged();
      });
      for (let i = 0; i < targets.length; i++) {
        observer.observe(targets[i], { box: "border-box" });
      }
    },
    observe(target) {
      observer?.observe(target, { box: "border-box" });
    },
    unobserve(target) {
      observer?.unobserve(target);
    },
    stop() {
      observer?.disconnect();
      observer = null;
    },
  };
}

export function createRootVisibilityObservation(root, handlers) {
  let observer = null;

  return {
    kind: "geometry",
    start() {
      if (typeof IntersectionObserver === "undefined") return;
      observer = new IntersectionObserver((entries) => {
        for (let i = 0; i < entries.length; i++) {
          const entry = entries[i];
          if (entry.target === root) {
            const rect = entry.boundingClientRect;
            const interRect = entry.intersectionRect;
            const visibleArea = interRect ? interRect.width * interRect.height : 0;
            const fact = {
              isIntersecting: entry.isIntersecting,
              intersectionRatio: entry.intersectionRatio || (entry.isIntersecting ? 1.0 : 0.0),
              visibleArea,
              inlineSize: rect ? rect.width : 0,
              area: rect ? rect.width * rect.height : 0,
            };
            handlers.onRootEntry(fact);
          }
        }
      }, { rootMargin: "200px 0px" });
      observer.observe(root);
    },
    stop() {
      observer?.disconnect();
      observer = null;
    },
  };
}
