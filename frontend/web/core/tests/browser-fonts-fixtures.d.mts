// Type declarations for browser-fonts-fixtures.mjs

export function digest(bytes: Uint8Array | ArrayBuffer | string): string;

export interface LoadedTable {
  url: string;
  bytes: Uint8Array;
  sha256: string;
}

export function getCurrentTable(): LoadedTable | null;

export interface FaceEvidenceOverrides {
  weight?: [number, number];
  fontWeight?: number;
  family?: string;
  style?: string;
  unicodeRange?: string;
  publicUrl?: string;
  sourceSha256?: string;
  sfntSha256?: string;
  faceIndex?: number;
  sourceOrder?: number;
  axes?: Record<string, number>;
  localNames?: string[];
  coverageText?: string;
  probe?: ProbeOverride;
}

export interface ProbeOverride {
  text?: string;
  advancePx?: number;
  fontSizePx?: number;
  fontWeight?: number;
  italic?: boolean;
  script?: string;
  language?: string;
}

export interface FaceEvidence {
  family: string;
  style: string;
  weight: [number, number];
  unicodeRange: string;
  publicUrl: string;
  sourceSha256: string;
  sfntSha256: string;
  faceIndex: number;
  sourceOrder: number;
  axes: Record<string, number>;
  localNames: string[];
  coverageText: string;
  probe: {
    text: string;
    advancePx: number;
    fontSizePx: number;
    fontWeight: number;
    italic: boolean;
    script: string;
    language: string;
  };
}

export function faceEvidence(sourceSha256: string, overrides?: FaceEvidenceOverrides): FaceEvidence;

export interface ManifestExtras {
  replayShapes?: Array<{ key: string; result: { glyphs: Array<{ id: number; advanceEm: number; xEm: number; yEm: number; boundsEm?: [number | null, number | null, number | null, number | null] }>; faceId: string; fontInstanceId: string; script: string; features: string[]; unsafeBreakCount: number; advanceEm: number } }>;
  replayMetrics?: Array<{ key: string; valuesEm: number[] }>;
  backendRevision?: string;
}

export function manifestWithFaces(
  facesByEntry: FaceEvidence[][],
  versions?: string[],
  typography?: Record<string, unknown>,
  extras?: ManifestExtras,
): any;

export interface SnapshotRootDocumentOverrides {
  fonts?: {
    load: (descriptor: string, text: string) => Promise<unknown[]>;
  };
}

export function snapshotRoot(manifest: any, documentOverrides?: SnapshotRootDocumentOverrides): any;

export interface HarnessOptions {
  bytes?: Uint8Array;
  fetchErrors?: Error[];
  fetchError?: Error;
  responseOk?: boolean;
  responseStatus?: number;
  createError?: Error;
  backendRevision?: string;
  harfbuzzVersion?: string;
  mutateSession?: (session: any) => void;
  documentOverrides?: SnapshotRootDocumentOverrides;
  renderFaceCreateError?: Error;
  renderFaceLoadError?: Error;
  contractResults?: Array<any>;
  preparedContractResults?: Array<any>;
  useDefaultSession?: boolean;
  createRenderFontFace?: (family: string, source: unknown, descriptors: unknown) => any;
  createRenderFontSource?: (source: unknown) => { source: string; release: () => boolean };
}

export interface HarnessResult {
  loader: any;
  root: any;
  requests: Array<{ url: string; init?: RequestInit }>;
  createCalls: Array<{ specs: unknown; options: any }>;
  sessions: Array<any>;
  contractCalls: HTMLElement[];
  preparedContractCalls: HTMLElement[];
  renderFaceCreates: any[];
  renderFaceAdds: unknown[];
  renderFaceDeletes: unknown[];
  renderFontSourceCreates: unknown[];
  renderFontSourceReleases: string[];
  fontLoads: Array<{ descriptor: string; text: string }>;
  closeCount: () => number;
}

export function harness(manifest: any, options?: HarnessOptions): HarnessResult;
