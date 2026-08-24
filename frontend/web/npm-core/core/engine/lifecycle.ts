// Lifecycle helpers for the enhance pipeline: options parsing, capability
// issue markers, and host sizing capture/stabilization (TsHost runtime port,
// Slice 2a).
//
// Plain script, no exports: running it installs globalThis.__TiqianLifecycle.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which a future gradle bridge task will embed this source verbatim. Double
// installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

import type { CjkDashCapability } from "./canvas-shaping.js";
import type {} from "./responsive-measure.js";

// Canonical EnhanceOptions decoded from the host options bag (the plain-object
// shape Kotlin WebEnhancerParagraphLifecycle.kt optionsFromJs produces).
export type EnhanceFontFamilies = {
  cjk: string | null;
  latin: string | null;
  monospace: string | null;
  cjkSerif: string | null;
  latinSerif: string | null;
};

// Post-withRootDefaults view: the five families are resolved to concrete
// stacks (option, inherited font-family, or built-in default), so the family
// fields are non-null strings here.
export type ResolvedEnhanceFontFamilies = {
  cjk: string;
  latin: string;
  monospace: string;
  cjkSerif: string;
  latinSerif: string;
};

export type EnhanceExactFontSessionOption = {
  status: string;
  sessionId: string | null;
  detail: string | null;
};

export type EnhanceOptions = {
  fontFamilies: EnhanceFontFamilies;
  fontSize: number | null;
  lineHeight: number | null;
  firstLineIndentIc: number;
  emphasisDotGapEm: number;
  strongAsEmphasisMarks: boolean;
  paragraphSelector: string;
  cjkDashCapability: CjkDashCapability | null;
  exactFontSession: EnhanceExactFontSessionOption | null;
  requireExactLayoutWorker: boolean;
};

export type ResolvedEnhanceOptions = {
  fontFamilies: ResolvedEnhanceFontFamilies;
  fontSize: number | null;
  lineHeight: number | null;
  firstLineIndentIc: number;
  emphasisDotGapEm: number;
  strongAsEmphasisMarks: boolean;
  paragraphSelector: string;
  cjkDashCapability: CjkDashCapability | null;
  exactFontSession: EnhanceExactFontSessionOption | null;
  requireExactLayoutWorker: boolean;
};

// Capability issue record reported through reportIssue/clearIssue and stored
// in engine state. name/detail/element are required because reportIssue marks
// them onto the DOM; the original-attribute snapshots are filled lazily on
// first report.
export type CapabilityIssueRecord = {
  element?: Element;
  name?: string | null;
  detail?: string | null;
  reportToConsole?: boolean;
  markerCaptured?: boolean;
  originalNameAttribute?: string | null;
  originalDetailAttribute?: string | null;
};

// Pre-fixup decode drafts: optionString may read null before the status
// fallback assigns "unavailable". These stay internal to optionsFromJs.
interface EnhanceCjkDashCapabilityDraft {
  status: string | null;
  detail: string | null;
}

interface EnhanceExactFontSessionDraft {
  status: string | null;
  sessionId: string | null;
  detail: string | null;
}

// Inline size capture produced by captureSourceInlineSize and consumed by
// stabilizeContentSizedItemInlineSize (SourceMeasureBeforeCustodyTransfer).
export type SourceInlineSizeCapture = {
  borderBoxWidth: number;
  contentBoxWidth: number;
  borderBoxSizing: boolean;
};

type LifecycleOptionsFromJsFn = (options: Record<string, unknown>) => EnhanceOptions;
type LifecycleOptionFloatFn = (options: Record<string, unknown>, name: string) => number | null;
type LifecycleConformingExactFontSessionIdFn = (options: EnhanceOptions) => string | null;
type LifecycleAllowsSnapshotExactLayoutFn = (options: EnhanceOptions) => boolean;
type LifecycleWithoutExactFontSessionFn = (options: EnhanceOptions) => EnhanceOptions;
type LifecycleWithRootDefaultsFn = (options: EnhanceOptions, root: Element) => ResolvedEnhanceOptions;
type LifecycleReportIssueFn = (issue: CapabilityIssueRecord) => void;
type LifecycleClearIssueFn = (issue: CapabilityIssueRecord) => void;
type LifecycleRestoreAttributeFn = (element: Element, name: string, value: string | null) => void;
type LifecycleCaptureSourceInlineSizeFn = (paragraph: Element) => SourceInlineSizeCapture;
type LifecycleApplyConfiguredHostFontSizeFn = (paragraph: HTMLElement, fontSize?: number | null) => string | null;
type LifecycleResponsiveSourceMeasureFn = (paragraph: HTMLElement, configuredFontSize: number | null) => number;
type LifecycleStabilizeContentSizedItemInlineSizeFn = (
  paragraph: HTMLElement,
  source: SourceInlineSizeCapture,
) => string | null;

export type LifecycleApi = {
  optionsFromJs: LifecycleOptionsFromJsFn;
  optionFloat: LifecycleOptionFloatFn;
  conformingExactFontSessionId: LifecycleConformingExactFontSessionIdFn;
  allowsSnapshotExactLayout: LifecycleAllowsSnapshotExactLayoutFn;
  withoutExactFontSession: LifecycleWithoutExactFontSessionFn;
  withRootDefaults: LifecycleWithRootDefaultsFn;
  reportIssue: LifecycleReportIssueFn;
  clearIssue: LifecycleClearIssueFn;
  restoreAttribute: LifecycleRestoreAttributeFn;
  captureSourceInlineSize: LifecycleCaptureSourceInlineSizeFn;
  applyConfiguredHostFontSize: LifecycleApplyConfiguredHostFontSizeFn;
  responsiveSourceMeasure: LifecycleResponsiveSourceMeasureFn;
  stabilizeContentSizedItemInlineSize: LifecycleStabilizeContentSizedItemInlineSizeFn;
};

declare global {
  var __TiqianLifecycle: LifecycleApi | undefined;
}

(function () {
  if (globalThis.__TiqianLifecycle) return;

  // Constants copied from the Kotlin sources: DEFAULT_EMPHASIS_DOT_GAP_EM in
  // core TextModel.kt, DEFAULT_FONT_SIZE and the default families in
  // WebEnhancerSupport.kt, DEFAULT_PARAGRAPH_SELECTOR in WebEnhancer.kt.
  const DEFAULT_EMPHASIS_DOT_GAP_EM: number = 0.1;
  const DEFAULT_FONT_SIZE: number = 19;
  const DEFAULT_PARAGRAPH_SELECTOR: string = "p, li";
  const CAPABILITY_DETAIL_LIMIT: number = 512;
  const HOST_INLINE_SIZE_ATTRIBUTE: string = "data-tq-host-inline-size";
  const DEFAULT_CJK_FONT_FAMILY: string = '"MiSans VF", "PingFang SC", "Noto Sans CJK SC", sans-serif';
  const DEFAULT_LATIN_FONT_FAMILY: string = '"InterVariable", "Inter", "MiSans VF", sans-serif';
  const DEFAULT_MONOSPACE_FONT_FAMILY: string =
    '"JetBrains Mono Variable", "SFMono-Regular", Menlo, Consolas, "MiSans VF", monospace';
  const DEFAULT_CJK_SERIF_FONT_FAMILY: string = '"MetroSungPlus-SC", "Songti SC", serif';
  const DEFAULT_LATIN_SERIF_FONT_FAMILY: string = 'Georgia, "Times New Roman", serif';

  // Plain-object reads mirroring the @JsFun option helpers in
  // WebEnhancerSupport.kt. Null and undefined both read as null.
  function optionString(options: Record<string, unknown>, name: string): string | null {
    return options && options[name] != null ? String(options[name]) : null;
  }

  function optionNumber(options: Record<string, unknown>, name: string): number {
    if (!options || options[name] == null) return Number.NaN;
    const number = Number(options[name]);
    return Number.isFinite(number) ? number : Number.NaN;
  }

  function optionFloat(options: Record<string, unknown>, name: string): number | null {
    const number = optionNumber(options, name);
    return Number.isFinite(number) ? number : null;
  }

  function optionBoolean(options: Record<string, unknown>, name: string): boolean | null {
    return options && typeof options[name] === "boolean" ? options[name] : null;
  }

  function optionObject(options: Record<string, unknown>, name: string): Record<string, unknown> | null {
    return options && options[name] && typeof options[name] === "object" ? (options[name] as Record<string, unknown>) : null;
  }

  // ComputedStylePort: the Kotlin external reads getPropertyValue off the
  // computed style object.
  function computedStyle(element: Element, property: string): string {
    return globalThis.getComputedStyle(element).getPropertyValue(property);
  }

  // CssFragmentedBlockInlineMeasure: getBoundingClientRect().width is the
  // union of every CSS column fragment, so callers use it only for coarse
  // drift detection against the 0.5px tolerance.
  function elementFragmentBorderBoxInlineSize(element: Element | null): number {
    if (!element) return 0;
    return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
  }

  // FragmentFractionalContentMeasureFallback: without the responsive measure
  // bridge, subtract the computed inline padding and borders from the border
  // box to approximate the content box.
  function computedContentBoxWidth(paragraph: Element | null): number {
    if (!paragraph) return 0;
    const style = globalThis.getComputedStyle(paragraph);
    const number = function (value: string): number {
      return Number.parseFloat(value) || 0;
    };
    return elementFragmentBorderBoxInlineSize(paragraph) -
      number(style.getPropertyValue("padding-left")) -
      number(style.getPropertyValue("padding-right")) -
      number(style.getPropertyValue("border-left-width")) -
      number(style.getPropertyValue("border-right-width"));
  }

  // The responsive measure module is installed alongside; only the content
  // width read is defensive here, mirroring the Kotlin bridge contract.
  function elementContentWidth(paragraph: Element | null): number {
    const measure = globalThis.__TiqianResponsiveMeasure;
    if (measure && typeof measure.elementContentWidth === "function") {
      return measure.elementContentWidth(paragraph);
    }
    return computedContentBoxWidth(paragraph);
  }

  function effectiveLineMeasure(width: number, fontSize: number): number {
    return globalThis.__TiqianResponsiveMeasure!.effectiveLineMeasure(width, fontSize);
  }

  function sourceParagraphWidth(paragraph: Element): number {
    return globalThis.__TiqianResponsiveMeasure!.sourceParagraphWidth(paragraph);
  }

  // Parse a "Npx" length; anything else reads as null.
  function parseCssPx(value: string): number | null {
    const trimmed = value.trim();
    if (!trimmed.endsWith("px")) return null;
    const stripped = trimmed.slice(0, -2).trim();
    if (stripped.length === 0) return null;
    const number = Number(stripped);
    return Number.isNaN(number) ? null : number;
  }

  // EnhanceOptionsJsPort: decode the host options bag into the plain-object
  // EnhanceOptions shape (WebEnhancerParagraphLifecycle.kt optionsFromJs).
  function optionsFromJs(options: Record<string, unknown>): EnhanceOptions {
    const cjk = optionString(options, "cjkFontFamily");
    const latin = optionString(options, "latinFontFamily");
    const monospace = optionString(options, "monospaceFontFamily");
    const cjkSerif = optionString(options, "cjkSerifFontFamily");
    const latinSerif = optionString(options, "latinSerifFontFamily");
    const fontSize = optionFloat(options, "fontSize");
    const lineHeight = optionFloat(options, "lineHeight");
    let firstLineIndentIc = optionFloat(options, "firstLineIndentIc");
    if (firstLineIndentIc === null) firstLineIndentIc = 0;
    let emphasisDotGapEm = optionFloat(options, "emphasisDotGapEm");
    if (emphasisDotGapEm === null) emphasisDotGapEm = DEFAULT_EMPHASIS_DOT_GAP_EM;
    let strongAsEmphasisMarks = optionBoolean(options, "strongAsEmphasisMarks");
    if (strongAsEmphasisMarks === null) strongAsEmphasisMarks = false;
    let paragraphSelector = optionString(options, "paragraphSelector");
    if (paragraphSelector === null) paragraphSelector = DEFAULT_PARAGRAPH_SELECTOR;
    let requireExactLayoutWorker = optionBoolean(options, "requireExactLayoutWorker");
    if (requireExactLayoutWorker === null) requireExactLayoutWorker = false;
    const dashCapabilityObject = optionObject(options, "cjkDashCapability");
    let cjkDashCapability: EnhanceCjkDashCapabilityDraft | null = null;
    if (dashCapabilityObject != null) {
      cjkDashCapability = {
        status: optionString(dashCapabilityObject, "status"),
        detail: optionString(dashCapabilityObject, "detail"),
      };
      if (cjkDashCapability.status === null) cjkDashCapability.status = "unavailable";
    }
    const exactFontSessionObject = optionObject(options, "exactFontSession");
    let exactFontSession: EnhanceExactFontSessionDraft | null = null;
    if (exactFontSessionObject != null) {
      exactFontSession = {
        status: optionString(exactFontSessionObject, "status"),
        sessionId: optionString(exactFontSessionObject, "sessionId"),
        detail: optionString(exactFontSessionObject, "detail"),
      };
      if (exactFontSession.status === null) exactFontSession.status = "unavailable";
    }
    return {
      fontFamilies: {
        cjk: cjk,
        latin: latin,
        monospace: monospace,
        cjkSerif: cjkSerif,
        latinSerif: latinSerif,
      },
      fontSize: fontSize,
      lineHeight: lineHeight,
      firstLineIndentIc: firstLineIndentIc,
      emphasisDotGapEm: emphasisDotGapEm,
      strongAsEmphasisMarks: strongAsEmphasisMarks,
      paragraphSelector: paragraphSelector,
      cjkDashCapability: cjkDashCapability as CjkDashCapability | null,
      exactFontSession: exactFontSession as EnhanceExactFontSessionOption | null,
      requireExactLayoutWorker: requireExactLayoutWorker,
    };
  }

  function conformingExactFontSessionId(options: EnhanceOptions): string | null {
    const session = options && options.exactFontSession;
    if (!session || session.status !== "conforming" ||
        typeof session.sessionId !== "string" || session.sessionId.trim().length === 0) {
      return null;
    }
    return session.sessionId;
  }

  function allowsSnapshotExactLayout(options: EnhanceOptions): boolean {
    return options.fontSize == null &&
      options.lineHeight == null &&
      options.firstLineIndentIc === 0 &&
      options.fontFamilies.cjk == null &&
      options.fontFamilies.latin == null &&
      options.fontFamilies.monospace == null &&
      options.fontFamilies.cjkSerif == null &&
      options.fontFamilies.latinSerif == null;
  }

  function withoutExactFontSession(options: EnhanceOptions): EnhanceOptions {
    const copy = Object.assign({}, options);
    copy.exactFontSession = null;
    return copy;
  }

  // WithRootDefaultsPort: resolve the five families from the option, the
  // inherited font-family, or the defaults, without mutating the input.
  function withRootDefaults(options: EnhanceOptions, root: Element): ResolvedEnhanceOptions {
    if (options.fontSize != null && (!Number.isFinite(options.fontSize) || options.fontSize <= 0)) {
      throw new Error("InvalidFontSize");
    }
    let inherited: string | null = computedStyle(root, "font-family").trim();
    if (inherited.length === 0) inherited = null;
    const families: Partial<EnhanceFontFamilies> = options.fontFamilies || {};
    const resolvedCjk = families.cjk != null ? families.cjk : (inherited != null ? inherited : DEFAULT_CJK_FONT_FAMILY);
    const resolvedLatin = families.latin != null ? families.latin : (inherited != null ? inherited : DEFAULT_LATIN_FONT_FAMILY);
    const resolvedMonospace = families.monospace != null ? families.monospace : DEFAULT_MONOSPACE_FONT_FAMILY;
    const resolvedCjkSerif = families.cjkSerif != null ? families.cjkSerif : DEFAULT_CJK_SERIF_FONT_FAMILY;
    const resolvedLatinSerif = families.latinSerif != null ? families.latinSerif : DEFAULT_LATIN_SERIF_FONT_FAMILY;
    return Object.assign({}, options, {
      fontFamilies: {
        cjk: resolvedCjk,
        latin: resolvedLatin,
        monospace: resolvedMonospace,
        cjkSerif: resolvedCjkSerif,
        latinSerif: resolvedLatinSerif,
      },
    });
  }

  // PendingCapabilityIsObservableNotTerminal: the semantic paragraph is kept
  // native while the asynchronous dash-face probe is in flight; reserve the
  // console warning for the retry's final unavailable/mismatch result.
  function reportIssue(issue: CapabilityIssueRecord): void {
    if (!issue.markerCaptured) {
      issue.originalNameAttribute = issue.element!.getAttribute("data-tiqian-capability-issue");
      issue.originalDetailAttribute = issue.element!.getAttribute("data-tiqian-capability-detail");
      issue.markerCaptured = true;
    }
    issue.element!.setAttribute("data-tiqian-capability-issue", issue.name!);
    issue.element!.setAttribute("data-tiqian-capability-detail", issue.detail!.slice(0, CAPABILITY_DETAIL_LIMIT));
    if (issue.reportToConsole) {
      console.warn("TiqianWeb skipped paragraph: " + issue.name + " (" + issue.detail + ")");
    }
  }

  function clearIssue(issue: CapabilityIssueRecord): void {
    if (!issue.markerCaptured) return;
    restoreAttribute(issue.element!, "data-tiqian-capability-issue", issue.originalNameAttribute);
    restoreAttribute(issue.element!, "data-tiqian-capability-detail", issue.originalDetailAttribute);
    issue.markerCaptured = false;
  }

  function restoreAttribute(element: Element, name: string, value?: string | null): void {
    if (value == null) {
      element.removeAttribute(name);
    } else {
      element.setAttribute(name, value);
    }
  }

  function captureSourceInlineSize(paragraph: Element): SourceInlineSizeCapture {
    return {
      borderBoxWidth: elementFragmentBorderBoxInlineSize(paragraph),
      contentBoxWidth: elementContentWidth(paragraph),
      borderBoxSizing: computedStyle(paragraph, "box-sizing").trim().toLowerCase() === "border-box",
    };
  }

  function applyConfiguredHostFontSize(paragraph: HTMLElement, fontSize?: number | null): string | null {
    if (fontSize == null) return null;
    paragraph.style.setProperty("font-size", fontSize + "px", "important");
    return paragraph.style.getPropertyValue("font-size");
  }

  function responsiveSourceMeasure(paragraph: HTMLElement, configuredFontSize: number | null): number {
    if (configuredFontSize == null) {
      let computedFontSize = parseCssPx(computedStyle(paragraph, "font-size"));
      if (computedFontSize === null) computedFontSize = DEFAULT_FONT_SIZE;
      return effectiveLineMeasure(sourceParagraphWidth(paragraph), computedFontSize);
    }
    const originalStyle = paragraph.getAttribute("style");
    paragraph.style.setProperty("font-size", configuredFontSize + "px", "important");
    try {
      return effectiveLineMeasure(sourceParagraphWidth(paragraph), configuredFontSize);
    } finally {
      if (originalStyle == null) {
        paragraph.removeAttribute("style");
      } else {
        paragraph.setAttribute("style", originalStyle);
      }
    }
  }

  // SourceMeasureBeforeCustodyTransfer: flex/grid items and descendants of
  // shrink-to-fit ancestors can derive their used inline size from the
  // semantic children that Tiqian moves into source custody, so the
  // before/after used size detects the real dependency instead of guessing
  // parent display modes. Ordinary blocks keep their host auto sizing; only a
  // custody-induced width change is stabilized.
  function stabilizeContentSizedItemInlineSize(paragraph: HTMLElement, source: SourceInlineSizeCapture): string | null {
    const empty = captureSourceInlineSize(paragraph);
    const sourceUsedInlineSize = source.borderBoxSizing ? source.borderBoxWidth : source.contentBoxWidth;
    const emptyUsedInlineSize = source.borderBoxSizing ? empty.borderBoxWidth : empty.contentBoxWidth;
    if (!Number.isFinite(sourceUsedInlineSize) || sourceUsedInlineSize <= 0 ||
        !Number.isFinite(emptyUsedInlineSize) ||
        Math.abs(sourceUsedInlineSize - emptyUsedInlineSize) < 0.5) {
      return null;
    }
    const usedInlineSize = sourceUsedInlineSize;
    if (!Number.isFinite(usedInlineSize) || usedInlineSize <= 0) return null;
    const serialized = usedInlineSize + "px";
    paragraph.style.setProperty("inline-size", serialized, "important");
    paragraph.setAttribute(HOST_INLINE_SIZE_ATTRIBUTE, "true");
    return serialized;
  }

  globalThis.__TiqianLifecycle = {
    optionsFromJs: optionsFromJs,
    optionFloat: optionFloat,
    conformingExactFontSessionId: conformingExactFontSessionId,
    allowsSnapshotExactLayout: allowsSnapshotExactLayout,
    withoutExactFontSession: withoutExactFontSession,
    withRootDefaults: withRootDefaults,
    reportIssue: reportIssue,
    clearIssue: clearIssue,
    restoreAttribute: restoreAttribute,
    captureSourceInlineSize: captureSourceInlineSize,
    applyConfiguredHostFontSize: applyConfiguredHostFontSize,
    responsiveSourceMeasure: responsiveSourceMeasure,
    stabilizeContentSizedItemInlineSize: stabilizeContentSizedItemInlineSize,
  };
})();