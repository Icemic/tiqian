// Loads the platform-specific build of the addon on the current system.
// The four platforms are the first-version targets of ADR 0050. The debug
// path serves local builds placed by `neon dist` (`bun run debug:native`);
// when it is absent the proxy falls back to the installed platform package.

import { createRequire } from "node:module";
import { currentPlatform, proxy } from "@neon-rs/load";

// `proxy` needs synchronous CommonJS requires for the platform packages and
// the local `.node` file, so this ESM module carries its own require.
const require = createRequire(import.meta.url);

/** One face entry of the `createFontSession` wire protocol. */
export interface NativeFaceSpec {
  family: string;
  publicUrl: string;
  /** Index into the `sources` array passed alongside the face list. */
  font: number;
  faceIndex: number | null;
  weight?: number | [number, number];
  style?: string;
  unicodeRange: string | null;
  sourceOrder: number | null;
}

/** Session options of the `createFontSession` wire protocol. */
export interface NativeSessionOptions {
  sessionPrefix: string | null;
  baseFeatures: string[] | null;
}

/** Boundary style of the `sourceBoundaries` wire protocol. */
export interface NativeBoundaryStyle {
  fontFamilies: string[];
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  baselineShiftPx?: number | null;
}

/** Boundary span of the `sourceBoundaries` wire protocol. */
export interface NativeBoundarySpan {
  start: number;
  end: number;
  style: NativeBoundaryStyle;
}

/** One styled span of the `precomputeParagraph` protocol. */
export interface NativeTextSpan {
  start: number;
  end: number;
  families: string[];
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  baselineShiftPx: number;
}

/** One inline box of the `precomputeParagraph` protocol. */
export interface NativeInlineBox {
  start: number;
  end: number;
  inlineStartPx: number;
  inlineEndPx: number;
  /** Omitted boxes use the `Narrow` default of the protocol. */
  outerSpacing?: "Narrow" | "Source";
}

/** One line-break policy span of the `precomputeParagraph` protocol. */
export interface NativeLineBreakSpan {
  start: number;
  end: number;
  policy: "ProgressiveTechnical";
}

/**
 * The native surface exported by `tiqian-precompute-neon`. Structured results
 * arrive as JSON strings produced by the shared Rust emitters; flat arguments
 * mirror the backend protocol used by the Kotlin/JS oracle.
 */
export interface NativeAddon {
  backendRevision(): string;
  harfbuzzVersion(): string;
  createFontSession(
    faces: NativeFaceSpec[],
    sources: Buffer[],
    options: NativeSessionOptions,
  ): string;
  sessionFaces(sessionId: string): string;
  shape(
    sessionId: string,
    displayText: string,
    families: string,
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    locale: string,
    role: string | null,
    sourceText: string | null,
  ): string;
  metrics(
    sessionId: string,
    families: string,
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    role: string | null,
    faceSelectionText: string | null,
  ): string;
  renderFamilies(sessionId: string, requestedFamilies: string[]): string;
  sourceBoundaries(
    sessionId: string,
    text: string,
    baseStyle: NativeBoundaryStyle,
    textSpans: NativeBoundarySpan[],
  ): string;
  /**
   * The structured form of the js facade call: arrays and span objects arrive
   * as themselves. Returns the plan JSON. An addon built without the engine
   * archive throws `EngineNotLinked`.
   */
  precomputeParagraph(
    sessionId: string,
    text: string,
    maxWidthPx: number,
    families: string[],
    fontSizePx: number,
    lineHeightPx: number,
    locale: string,
    fontWeight: number,
    italic: boolean,
    firstLineIndentIc: number,
    lineLengthGridEnabled: boolean,
    sourceBoundaries: number[],
    textSpans: NativeTextSpan[],
    inlineBoxes: NativeInlineBox[],
    lineBreakSpans: NativeLineBreakSpan[],
  ): string;
  beginCapture(sessionId: string): void;
  captureEvidence(sessionId: string): string;
  closeSession(sessionId: string): void;
}

export const addon: NativeAddon = proxy({
  platforms: {
    "win32-x64-msvc": () => require("@tiqian/precompute-win32-x64-msvc"),
    "darwin-arm64": () => require("@tiqian/precompute-darwin-arm64"),
    "linux-x64-gnu": () => require("@tiqian/precompute-linux-x64-gnu"),
    "linux-arm64-gnu": () => require("@tiqian/precompute-linux-arm64-gnu"),
  },
  debug: () => require(`../platforms/${currentPlatform()}/index.node`),
});
