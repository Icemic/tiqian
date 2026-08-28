import type { Server } from "node:http";
import type { DeepGeometryCounts, DeepGeometryReport, DeepGeometryStats } from "../web/tests/types.js";

export const DEMO_PORT: number;
export const CDP_PORT: number;
export const VIEWPORT_WIDTH: number;
export const VIEWPORT_HEIGHT: number;
export const SETTLE_HELPERS: string;
export const DEEP_GEOMETRY_HELPERS: string;

export class CdpClient {
  constructor(wsUrl: string);
  wsUrl: string;
  ws: WebSocket | null;
  id: number;
  pending: Map<number, { resolve: (val: unknown) => void; reject: (err: unknown) => void }>;
  connect(): Promise<void>;
  send(method: string, params?: Record<string, unknown>): Promise<unknown>;
  evaluate<T = unknown>(expression: string): Promise<T>;
  screenshot(params?: Record<string, unknown>): Promise<Buffer>;
  close(): void;
}

export function diffDeepGeometry(
  a: DeepGeometryReport | null | undefined,
  b: DeepGeometryReport | null | undefined,
): DeepGeometryStats;

export function deepGeometryCounts(
  report: DeepGeometryReport | null | undefined,
): DeepGeometryCounts;

export interface EraConfig {
  label: string;
  adapter: string;
  stylesheet: string;
  importMap: Record<string, unknown>;
  static?: Record<string, string>;
}

export function startKitServer(era: EraConfig): Promise<Server>;

export function waitForCdpEndpoint(port: number, timeoutMs?: number): Promise<void>;

export interface ChainCaptureResult {
  commit: string;
  era: string;
  valid: boolean;
  reason?: string;
  geometry?: DeepGeometryReport;
  counts?: DeepGeometryCounts;
  pageHeight?: number;
  selfEqual?: boolean;
  selfDivergentBoxes?: number;
  fontsStatus?: string;
  viewport?: { width: number; height: number };
  pageLog?: string[];
}

export function chainCapture(
  client: CdpClient,
  era: EraConfig,
  commit: string,
  pageLog: string[],
): Promise<ChainCaptureResult>;
