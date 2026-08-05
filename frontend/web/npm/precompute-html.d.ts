import type {
  BuildFontFace,
  BuildFontStylesheet,
  Precomputer,
  SnapshotBundle,
  SnapshotInlineBox,
  SnapshotSemanticSpan,
  SnapshotTextSpan,
  SnapshotTypography,
} from "./precompute.js";
import type { ClientSnapshotBundle } from "./snapshot-client.js";

export interface SnapshotProjection {
  readonly semantics?: readonly SnapshotSemanticSpan[];
  readonly textSpans?: readonly SnapshotTextSpan[];
  readonly inlineBoxes?: readonly SnapshotInlineBox[];
  readonly sourceBoundaries?: readonly number[];
}

export interface HtmlSnapshotProjectionContext {
  readonly element: Element;
  readonly source: {
    readonly text: string;
    readonly semantics: readonly SnapshotSemanticSpan[];
  };
  readonly typography: SnapshotTypography;
}

export interface HtmlProjectionOptions {
  /** Only simple tag-name lists such as `p, li` are supported. */
  readonly paragraphSelector?: string;
  readonly skippedAncestorSelector?: string;
  /** Rich semantic snapshots are opt-in because host inline CSS is not observable in Node. */
  readonly projectSnapshotParagraph?: (
    context: HtmlSnapshotProjectionContext,
  ) => SnapshotProjection | false | null | undefined;
}

export type HtmlPreparerOptions = HtmlProjectionOptions & (
  | {
    /** Reuse an existing session instead of opening another exact-font session. */
    readonly precomputer: Precomputer;
    readonly fontStylesheets?: never;
    readonly faces?: never;
    readonly typography?: never;
  }
  | {
    readonly precomputer?: undefined;
    readonly fontStylesheets?: readonly BuildFontStylesheet[];
    readonly faces?: readonly BuildFontFace[];
    readonly typography: SnapshotTypography;
  }
);

export interface HtmlPrepareOptions {
  /** Distinct retained asset payloads must not share an explicit id within one adapter scope. */
  readonly id?: string;
  /** Optional fixed-measure prepared geometry; omit for width-independent font evidence. */
  readonly snapshot?: { readonly maxWidthPx: number };
}

export interface SnapshotServerAssets {
  readonly id: string;
  readonly initialStyle: string;
  readonly inertTemplate: string;
  readonly fontPreloads: readonly string[];
}

export interface PreparedHtmlIssue {
  readonly index: number;
  readonly key: string;
  readonly stage: "snapshot" | "font-contract";
  readonly issue: string;
}

export interface PreparedHtml {
  readonly html: string;
  readonly rootAttributes: Readonly<Record<string, string>>;
  readonly bundle: SnapshotBundle | null;
  readonly clientBundle: ClientSnapshotBundle | null;
  readonly serverAssets: SnapshotServerAssets | null;
  readonly issues: readonly PreparedHtmlIssue[];
}

export interface HtmlPreparer {
  readonly typography: Precomputer["typography"];
  prepare(html: string, options?: HtmlPrepareOptions): Promise<PreparedHtml>;
  close(): void;
}

export declare function findHtmlOpeningTags(
  html: string,
  tagNames?: readonly string[],
): readonly { readonly end: number; readonly source: string; readonly tagName: string }[];
export declare function injectHtmlAttributes(
  html: string,
  insertions: readonly { readonly offset: number; readonly attribute: string }[],
): string;
export declare function snapshotServerAssets(bundle: SnapshotBundle | null): SnapshotServerAssets | null;
export declare function renderSnapshotServerAssets(assets: SnapshotServerAssets | null): string;
export declare function createHtmlPreparer(options: HtmlPreparerOptions): Promise<HtmlPreparer>;
