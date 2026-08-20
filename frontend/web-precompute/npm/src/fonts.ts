// The unified font session over the native addon (ADR 0050). The session
// surface matches `createFontSession` from `@tiqian/prose/precompute-fonts`;
// shape and metrics are session methods here because the native world has no
// global WASM backend handle protocol.

import { currentPlatform } from "@neon-rs/load";
import { addon } from "./load.js";

const SUPPORTED_PLATFORMS = new Set([
  "win32-x64-msvc",
  "darwin-arm64",
  "linux-x64-gnu",
  "linux-arm64-gnu",
]);

const platform = currentPlatform();
if (!SUPPORTED_PLATFORMS.has(platform)) {
  throw new Error(`UnsupportedPrecomputePlatform:${platform}`);
}

/**
 * Parses a JSON string produced by the shared Rust emitters. The addon and
 * the Kotlin/JS oracle emit the same bytes; parsing happens only here, at
 * the boundary.
 */
function parse<T>(json: string): T {
  return JSON.parse(json) as T;
}

function toBuffer(source: Uint8Array | Buffer): Buffer {
  return Buffer.isBuffer(source) ? source : Buffer.from(source);
}

/**
 * One `@font-face` input of `createFontSession`.
 */
export interface FontFaceSpecInput {
  family: string;
  publicUrl: string;
  source: Uint8Array | Buffer;
  weight?: number | [number, number];
  style?: string;
  unicodeRange?: string;
  sourceOrder?: number;
  faceIndex?: number;
}

export interface FontSessionOptions {
  sessionPrefix?: string;
  /** Only `lnum` is accepted; any other tag throws `UnsupportedFontSessionBaseFeatures`. */
  baseFeatures?: string[];
}

export interface ShapeGlyph {
  id: number;
  cluster: number;
  advance: number;
  x: number;
  y: number;
  bounds?: number[];
}

export interface ShapeResult {
  faceId: string;
  fontInstanceId: string;
  script: string;
  features: string[];
  probeFeatures: string[];
  unsafeBreakCount: number;
  advance: number;
  glyphs: ShapeGlyph[];
}

export interface FaceInfo {
  family: string;
  style: string;
  weight: [number, number];
  unicodeRange: string;
  publicUrl: string;
  sourceSha256: string;
  sfntSha256: string;
  faceIndex: number;
  sourceOrder: number;
  axisTags: string[];
  localNames: string[];
}

export interface BoundaryStyleInput {
  fontFamilies: string[];
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  baselineShiftPx?: number;
}

export interface BoundaryTextSpanInput {
  start: number;
  end: number;
  style: BoundaryStyleInput;
}

export interface FontEvidence {
  backendRevision: string;
  harfbuzzVersion: string;
  faces: Array<Record<string, unknown>>;
  replay: {
    revision: string;
    shapes: Array<Record<string, unknown>>;
    metrics: Array<Record<string, unknown>>;
  };
}

export interface FontSession {
  id: string;
  backendRevision: string;
  harfbuzzVersion: string;
  readonly faces: FaceInfo[];
  shape(
    displayText: string,
    families: string[],
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    locale: string,
    role?: string | null,
    sourceText?: string | null,
  ): ShapeResult;
  metrics(
    families: string[],
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    role?: string | null,
    faceSelectionText?: string | null,
  ): number[];
  renderFamilies(requestedFamilies: string[]): string[];
  sourceBoundaries(
    text: string,
    baseStyle: BoundaryStyleInput,
    textSpans: BoundaryTextSpanInput[],
  ): number[];
  beginCapture(): void;
  captureEvidence(): FontEvidence;
  close(): void;
}

/**
 * Creates a font session from explicit `@font-face` inputs. `faceSpecs`
 * entries carry their font binary as `source` (Uint8Array or Buffer).
 */
export async function createFontSession(
  faceSpecs: FontFaceSpecInput[],
  options: FontSessionOptions = {},
): Promise<FontSession> {
  if (!Array.isArray(faceSpecs) || faceSpecs.length === 0) {
    throw new Error("MissingExplicitFontFaces");
  }
  const sources: Buffer[] = [];
  const faces = faceSpecs.map((face) => {
    sources.push(toBuffer(face.source));
    return {
      family: face.family,
      publicUrl: face.publicUrl,
      font: sources.length - 1,
      faceIndex: face.faceIndex ?? null,
      weight: face.weight,
      style: face.style,
      unicodeRange: face.unicodeRange ?? null,
      sourceOrder: face.sourceOrder ?? null,
    };
  });
  const id = addon.createFontSession(faces, sources, {
    sessionPrefix: options.sessionPrefix ?? null,
    baseFeatures: options.baseFeatures ?? null,
  });
  return {
    id,
    backendRevision: addon.backendRevision(),
    harfbuzzVersion: addon.harfbuzzVersion(),
    get faces(): FaceInfo[] {
      return parse<FaceInfo[]>(addon.sessionFaces(id));
    },
    shape(
      displayText: string,
      families: string[],
      fontSize: number,
      fontWeight: number,
      italic: boolean,
      locale: string,
      role: string | null = null,
      sourceText: string | null = null,
    ): ShapeResult {
      return parse<ShapeResult>(
        addon.shape(
          id,
          displayText,
          families.join("\u001f"),
          fontSize,
          fontWeight,
          italic,
          locale,
          role,
          sourceText,
        ),
      );
    },
    metrics(
      families: string[],
      fontSize: number,
      fontWeight: number,
      italic: boolean,
      role: string | null = null,
      faceSelectionText: string | null = null,
    ): number[] {
      return parse<number[]>(
        addon.metrics(
          id,
          families.join("\u001f"),
          fontSize,
          fontWeight,
          italic,
          role,
          faceSelectionText,
        ),
      );
    },
    renderFamilies(requestedFamilies: string[]): string[] {
      return parse<string[]>(addon.renderFamilies(id, requestedFamilies));
    },
    sourceBoundaries(
      text: string,
      baseStyle: BoundaryStyleInput,
      textSpans: BoundaryTextSpanInput[],
    ): number[] {
      return parse<number[]>(addon.sourceBoundaries(id, text, baseStyle, textSpans));
    },
    beginCapture(): void {
      addon.beginCapture(id);
    },
    captureEvidence(): FontEvidence {
      return parse<FontEvidence>(addon.captureEvidence(id));
    },
    close(): void {
      addon.closeSession(id);
    },
  };
}

export const backendRevision: string = addon.backendRevision();
export const harfbuzzVersion: string = addon.harfbuzzVersion();
