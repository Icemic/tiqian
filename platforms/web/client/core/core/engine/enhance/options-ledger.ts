// OptionsLedger — enhancement options and the applied-options ledger for
// one enhanced element (core-neutral parts ruling). The options object input
// is the single truth (options single-truth ruling): updateOptions diffs
// the incoming object against the ledger, never against the root element's
// attributes, and the context never reads option values back off the root.
// Attribute reflection (the four observed attributes) belongs to the web
// component shell; programmatic hosts call updateOptions directly.
//
// The ledger also owns the two engine-option resolution verbs dissolved
// from root-state.ts: resolveEngineOptions (bag first-parse: optionsFromJs,
// the snapshot gate, withRootDefaults, traceConfig publication) and
// resolveEngineOptionsFromCanonical (canonical re-entry: withRootDefaults
// only). progressive-drivers.test.mjs locks the two verbs apart.

import { globalServices } from "../../services/global-services.js";
import type { EnhanceOptions, ResolvedEnhanceOptions } from "../lifecycle.js";
import {
  allowsSnapshotLayout,
  optionsFromJs,
  withoutSnapshotFontSession,
  withRootDefaults,
} from "../lifecycle.js";

// Host enhancement options. The web component shell mirrors its four
// observed attributes into this shape; programmatic hosts set the same
// values directly. Renamed from ProseHostOptions in the core-neutral wave.
export interface EnhancementOptions {
  readonly disabled?: boolean;
  readonly emphasisDotGapEm?: number | null;
  readonly strongAsEmphasisMarks?: boolean;
  readonly snapshotRef?: string | null;
}

/** Resolved options held by the applied-options ledger. */
export interface AppliedEnhancementOptions {
  disabled: boolean;
  emphasisDotGapEm: number | null;
  strongAsEmphasisMarks: boolean;
  snapshotRef: string | null;
}

// Enhance options bag built from the ledger; the progressive drivers
// consume it as a plain record.
export interface HostEnhanceOptionsBag {
  emphasisDotGapEm?: number;
  strongAsEmphasisMarks?: boolean;
  paragraphSelector?: string;
}

// The four observed host option attributes. The web component shell returns
// this list from its observedAttributes static; the mount lifecycle uses it
// for the deferred-teardown detach attribute snapshot. Moved out of the
// deleted prose-host-session.ts in the core-neutral wave; the dependency
// direction (shell imports core) keeps the constant on the core side.
export const OBSERVED_ATTRIBUTES: string[] = [
  "disabled",
  "emphasis-dot-gap-em",
  "strong-as-emphasis-marks",
  "snapshot-ref",
];

export type OptionsChangedReaction = (
  name: string,
  oldValue: string | null,
  newValue: string | null,
) => void;

export interface OptionsLedger {
  readonly disabled: boolean;
  readonly emphasisDotGapEm: number | null;
  readonly strongAsEmphasisMarks: boolean;
  readonly snapshotRef: string | null;
  /** Diffs the incoming object against the ledger and runs the reaction. */
  updateOptions(options: EnhancementOptions): void;
  /** AppliedLedgerMountSync: seed the ledger from the live attributes. */
  syncFromAttributes(): void;
  /** The bag the dispatch paths build from the applied ledger values. */
  baseEnhanceOptions(): HostEnhanceOptionsBag | undefined;
  /** Captures the applied ledger for the deferred-teardown comparison. */
  captureAppliedSnapshot(): AppliedEnhancementOptions;
  /** True when the captured snapshot still matches the applied ledger. */
  appliedMatchesSnapshot(snapshot: AppliedEnhancementOptions): boolean;
  /** Bag first-parse: optionsFromJs, snapshot gate, defaults, trace. */
  resolveEngineOptions(root: Element, optionsBag: Record<string, unknown>): ResolvedEnhanceOptions;
  /** Canonical re-entry: defaults only, the gate was passed on first parse. */
  resolveEngineOptionsFromCanonical(root: Element, canonicalOptions: EnhanceOptions): ResolvedEnhanceOptions;
}

function createOptionsLedger(root: HTMLElement, onChanged: OptionsChangedReaction): OptionsLedger {
  const applied: AppliedEnhancementOptions = {
    disabled: false,
    emphasisDotGapEm: null,
    strongAsEmphasisMarks: false,
    snapshotRef: null,
  };

  function updateOptions(options: EnhancementOptions): void {
    // The ledger diff (not the live attribute) keeps every host faithful:
    // the custom-element path arrives after the platform wrote the
    // attribute, programmatic paths never write one. Every value that
    // differs from the applied ledger is synced onto the root attributes
    // and runs the same reaction as a custom-element attribute change.
    const apply = (name: string, next: string | null): void => {
      if (root.getAttribute(name) !== next) {
        if (next == null) root.removeAttribute(name);
        else root.setAttribute(name, next);
      }
    };
    if (options.disabled !== undefined && options.disabled !== applied.disabled) {
      const oldValue = applied.disabled ? "" : null;
      const newValue = options.disabled ? "" : null;
      apply("disabled", newValue);
      applied.disabled = options.disabled;
      onChanged("disabled", oldValue, newValue);
    }
    if (options.emphasisDotGapEm !== undefined && options.emphasisDotGapEm !== applied.emphasisDotGapEm) {
      const oldValue = applied.emphasisDotGapEm == null ? null : String(applied.emphasisDotGapEm);
      const newValue = options.emphasisDotGapEm == null ? null : String(options.emphasisDotGapEm);
      // AttributeAlreadyEquivalent: the custom-element path arrives after the
      // platform wrote the attribute, and the author string may carry a
      // non-canonical form ("0.50"). Compare parsed values so the reflection
      // never rewrites the author's attribute text, only absent or
      // numerically different ones.
      const currentParsed = Number.parseFloat(root.getAttribute("emphasis-dot-gap-em") ?? "");
      const attributeAlreadyEquivalent = options.emphasisDotGapEm == null
        ? !Number.isFinite(currentParsed)
        : currentParsed === options.emphasisDotGapEm;
      if (!attributeAlreadyEquivalent) {
        if (newValue == null) root.removeAttribute("emphasis-dot-gap-em");
        else root.setAttribute("emphasis-dot-gap-em", newValue);
      }
      applied.emphasisDotGapEm = options.emphasisDotGapEm;
      onChanged("emphasis-dot-gap-em", oldValue, newValue);
    }
    if (options.strongAsEmphasisMarks !== undefined && options.strongAsEmphasisMarks !== applied.strongAsEmphasisMarks) {
      const oldValue = applied.strongAsEmphasisMarks ? "" : null;
      const newValue = options.strongAsEmphasisMarks ? "" : null;
      apply("strong-as-emphasis-marks", newValue);
      applied.strongAsEmphasisMarks = options.strongAsEmphasisMarks;
      onChanged("strong-as-emphasis-marks", oldValue, newValue);
    }
    if (options.snapshotRef !== undefined && options.snapshotRef !== applied.snapshotRef) {
      const oldValue = applied.snapshotRef ?? null;
      const newValue = options.snapshotRef ?? null;
      apply("snapshot-ref", newValue);
      applied.snapshotRef = newValue;
      onChanged("snapshot-ref", oldValue, newValue);
    }
  }

  function syncFromAttributes(): void {
    const gap = Number.parseFloat(root.getAttribute("emphasis-dot-gap-em") ?? "");
    applied.disabled = root.hasAttribute("disabled");
    applied.emphasisDotGapEm = Number.isFinite(gap) ? gap : null;
    applied.strongAsEmphasisMarks = root.hasAttribute("strong-as-emphasis-marks");
    applied.snapshotRef = root.getAttribute("snapshot-ref");
  }

  function baseEnhanceOptions(): HostEnhanceOptionsBag | undefined {
    const emphasisDotGapEm = applied.emphasisDotGapEm;
    const strongAsEmphasisMarks = applied.strongAsEmphasisMarks;
    if (
      emphasisDotGapEm == null &&
      !strongAsEmphasisMarks
    ) {
      return undefined;
    }
    return {
      ...(emphasisDotGapEm == null ? {} : { emphasisDotGapEm }),
      ...(strongAsEmphasisMarks ? { strongAsEmphasisMarks: true } : {}),
    };
  }

  function captureAppliedSnapshot(): AppliedEnhancementOptions {
    return {
      disabled: applied.disabled,
      emphasisDotGapEm: applied.emphasisDotGapEm,
      strongAsEmphasisMarks: applied.strongAsEmphasisMarks,
      snapshotRef: applied.snapshotRef,
    };
  }

  function appliedMatchesSnapshot(snapshot: AppliedEnhancementOptions): boolean {
    return snapshot.disabled === applied.disabled &&
      snapshot.emphasisDotGapEm === applied.emphasisDotGapEm &&
      snapshot.strongAsEmphasisMarks === applied.strongAsEmphasisMarks &&
      snapshot.snapshotRef === applied.snapshotRef;
  }

  function resolveEngineOptions(root: Element, optionsBag: Record<string, unknown>): ResolvedEnhanceOptions {
    const canonical = optionsFromJs(optionsBag);
    // allowsSnapshotLayout ? options : options.copy(snapshotFontSession =
    // null): an exact snapshot only reproduces the host with root defaults,
    // so configured typography lowers the snapshot font session.
    const snapshotEligible = allowsSnapshotLayout(canonical)
      ? canonical
      : withoutSnapshotFontSession(canonical);
    const resolved = withRootDefaults(snapshotEligible, root);
    if (resolved.trace) globalServices().coordination.traceConfig = resolved.trace;
    return resolved;
  }

  function resolveEngineOptionsFromCanonical(root: Element, canonicalOptions: EnhanceOptions): ResolvedEnhanceOptions {
    // Re-entry path for relayout/refresh: the canonical options already came
    // from optionsFromJs output shape, so the snapshot gate is skipped.
    const resolved = withRootDefaults(canonicalOptions, root);
    if (resolved.trace) globalServices().coordination.traceConfig = resolved.trace;
    return resolved;
  }

  return {
    get disabled() {
      return applied.disabled;
    },
    get emphasisDotGapEm() {
      return applied.emphasisDotGapEm;
    },
    get strongAsEmphasisMarks() {
      return applied.strongAsEmphasisMarks;
    },
    get snapshotRef() {
      return applied.snapshotRef;
    },
    updateOptions,
    syncFromAttributes,
    baseEnhanceOptions,
    captureAppliedSnapshot,
    appliedMatchesSnapshot,
    resolveEngineOptions,
    resolveEngineOptionsFromCanonical,
  };
}

export { createOptionsLedger };
