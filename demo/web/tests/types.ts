// Shared test types for the demo package.
// Consolidates test-side shapes used across multiple test files.

import type { ChildProcess } from "node:child_process";
import type { Server } from "node:http";

export type Box = [x: number, y: number, width: number, height: number];

export interface KidGeometry {
  k: "t" | "e" | string;
  b?: Box;
}

export interface ParaGeometry {
  key?: string;
  rect?: Box;
  lineMarks?: Box[];
  kids?: KidGeometry[];
}

export interface RootGeometry {
  root?: Box;
  paras?: ParaGeometry[];
}

export interface DeepGeometryReport {
  pageHeight?: number;
  roots?: RootGeometry[];
}

export interface DeepGeometryStats {
  equal: boolean;
  boxesCompared: number;
  divergentBoxes: number;
  examples: string[];
}

export interface DeepGeometryCounts {
  lineMarks: number;
  runEls: number;
  textNodes: number;
}

export interface CdpTarget {
  type: string;
  url: string;
  webSocketDebuggerUrl: string;
  id?: string;
  title?: string;
}

export interface CdpPendingCallback {
  resolve: (val: unknown) => void;
  reject: (err: unknown) => void;
}

export interface CdpEvaluateExceptionDetails {
  exception?: { description?: string };
  text?: string;
}

export interface CdpEvaluateResponse<T = unknown> {
  result?: { value?: T };
  exceptionDetails?: CdpEvaluateExceptionDetails;
  data?: string;
}

export interface CdpClip {
  x: number;
  y: number;
  width: number;
  height: number;
  scale?: number;
}

export interface CdpScreenshotParams {
  clip?: CdpClip;
  captureBeyondViewport?: boolean;
  format?: "png" | "jpeg" | "webp";
}

export interface CdpDeviceMetricsOverride {
  width: number;
  height: number;
  deviceScaleFactor: number;
  mobile: boolean;
}

export interface SettleResult {
  settled: boolean;
  pending?: string[];
  fpCount?: number;
  liveCount?: number;
  pageHeight?: number;
}

export interface DiffIssue {
  k: string;
  kind: string;
  coordinated?: boolean;
  oneshot?: boolean;
  linesCoordinated?: number;
  linesOneshot?: number;
  hostPath?: string | null;
  domPath?: string | null;
}

export interface EvaluateCompareResult {
  issues: DiffIssue[];
  quiet: boolean;
  errors: string[];
  geoAfter: DeepGeometryReport;
}

export interface CompareRoundResult {
  count: number;
  ms: number;
  boxes: number;
  width?: number;
}

export interface CoverageResult {
  optionsCaptured: number;
  roots: number;
  dashIssue: string | null;
  dashNative: boolean;
  compressRendered: boolean;
  compressLines: number;
  mixedRendered: boolean;
  mixedLines: number;
  pageCarriers: number;
  negativeSpacing: number;
  positiveSpacing: number;
}

export interface SweepPlan {
  offsets: number[];
  viewportHeight: number;
  pageHeight: number;
}

export interface SweepCapture {
  offset: number;
  pageHeight: number;
  selfEqual: boolean;
  selfDivergentBoxes: number;
  geometry: DeepGeometryReport;
}

export interface SweepResult {
  plan: SweepPlan;
  captures: SweepCapture[];
}

export interface KitSession {
  server: Server;
  browserProc: ChildProcess | null;
  client: {
    wsUrl: string;
    ws: WebSocket | null;
    id: number;
    send: (method: string, params?: Record<string, unknown>) => Promise<unknown>;
    evaluate: <T = unknown>(expression: string) => Promise<T>;
    screenshot: (params?: Record<string, unknown>) => Promise<Buffer>;
    close: () => void;
  };
  pageLog: string[];
}

export interface PngDecoded {
  width: number;
  height: number;
  idat: Buffer;
}

export interface PixelsDecoded {
  width: number;
  height: number;
  bpp: number;
  pixels: Buffer;
}

export interface ScreenshotComparison {
  equal: boolean;
  differentPixels: number;
  detail: string | null;
}

export interface VisualCapturePlan {
  rect: { x: number; y: number; width: number; height: number };
  viewportHeight: number;
  pageHeight: number;
  scrolls: number[];
}

export interface VisualCaptureSet {
  shots: Record<string, Buffer>;
  pageHeight: number;
}

export interface CompareStateOptions {
  assertPixels: boolean;
}

export interface CompareStateResult {
  shots: number;
  pageHeight: number;
}

export interface FlickerMutationRecord {
  type: string;
  element: string;
  currentVal?: string | null;
  text: string;
}

export interface FlickerReport {
  bareDomFlashes: number;
  mutationRecords: FlickerMutationRecord[];
}

export interface BrowserSession {
  cdp: {
    send: (method: string, params?: Record<string, unknown>) => Promise<unknown>;
    evaluate: <T = unknown>(expression: string) => Promise<T>;
    close: () => void;
  };
  chromeProc: ChildProcess | null;
  serverProc: ChildProcess | null;
}

export interface ScrollMetrics {
  scrollEvents: number;
  totalDuration: number;
  totalFrames: number;
  meanFrameDuration: number;
  maxFrameDuration: number;
  longTaskCount: number;
  maxLongTaskDuration: number;
  totalBlockingTime: number;
  maxEventLoopDelay: number;
  totalParagraphs: number;
  enhancedParagraphs: number;
}

export interface ProseParagraphDetail {
  isRendered: boolean;
  lineCount: number;
  firstLineWidth: number;
}

export interface ProseElementInfo {
  index: number;
  title: string;
  enhanced: string | null;
  paragraphTotal: number;
  renderedParagraphs: number;
  paragraphsDetail: ProseParagraphDetail[];
}

export interface DragStepParagraphLine {
  width: number;
  endReason: string;
}

export interface DragStepParagraphDetail {
  fontSize: number;
  isRendered: boolean;
  lineCount: number;
  lines: DragStepParagraphLine[];
}

export interface DragStepProseInfo {
  index: number;
  title: string;
  enhanced: string | null;
  paragraphCount: number;
  renderedCount: number;
  containerWidth: number;
  paragraphsDetail: DragStepParagraphDetail[];
}

export interface Width670ProseInfo {
  index: number;
  title: string;
  containerWidth: number;
  enhanced: string | null;
  isRendered: boolean;
  fontSize: number;
  lineWidth: number;
  endReason: string;
}

export interface AnchorProbeResult {
  index: number;
  top: number;
  scrollY: number;
  pageHeight: number;
}

export interface ParagraphTopResult {
  top: number;
  scrollY: number;
  pageHeight: number;
}

export interface AnimationSamplesResult {
  held: number;
  observed: number[];
}

export interface LaunchScenarioOptions {
  disableRoots: boolean;
}

export interface EnhancementStallDetail {
  i: number;
  rendered: number;
  paragraphs: number;
  capability: string | null;
  loadMs: string | null;
}

export interface EnhancementWaitResult {
  total: number;
  done: number;
  stalled?: boolean;
  detail?: EnhancementStallDetail[];
}

export interface UnfreezeRootReport {
  index: number;
  sidebar: boolean;
  width: number;
  readyDelta: number;
  lastReadyWidth: number;
  capabilityIssue: string | null;
}

export interface UnfreezeSettleReport {
  label: string;
  report: UnfreezeRootReport[];
  eventCount: number;
  maxEventGapMs: number;
}

export interface FinalCheckRoot {
  index: number;
  sidebar: boolean;
  currentWidth: number;
  lastReadyWidth: number;
}

export interface PrepaintDelivery {
  t: number;
  hostWidth: number;
  overflow: number;
  lines: number;
}

export interface PrepaintEvents {
  destroy: number;
  relayoutReady: number;
}

export interface PrepaintReport {
  deliveries: PrepaintDelivery[];
  events: PrepaintEvents;
}

export interface SameTaskReconnectResult {
  reconnected: number;
  adoptCount: number;
  enhanced: boolean;
  rendered: boolean;
}

export interface CrossTaskReconnectResult {
  reconnected: number;
  enhanced: boolean;
  rendered: boolean;
}

export interface RandomAlternationCounts {
  sameTask: number;
  crossTask0: number;
  crossTask5: number;
  editDetach: number;
}

export interface RandomAlternationResult {
  counts: RandomAlternationCounts;
  enhanced: boolean;
}

export interface InflightDisconnectResult {
  settled: boolean;
}

export interface AbandonedMutationDetail {
  type: string;
  target: string;
  attr: string | null;
  added: number;
  removed: number;
}

export interface AbandonedSubtreeResult {
  mutationCount: number;
  mutationDetails: AbandonedMutationDetail[];
  relayoutReadyAfterDisconnect: boolean;
  snapshotBefore: string;
  snapshotAfter: string;
  identical: boolean;
}

export interface RelayoutReadyEventsResult {
  readyDuringCycle: number;
  readyAfterDisconnect: number;
}

export interface TransientEventRecord {
  type: string;
  t: number;
  rootId?: string | null;
  rootTag?: string | null;
  mine?: boolean | null;
}

export interface TransientFrameSample {
  t: number;
  enhanced: boolean;
  rendered: number;
  paragraphs: number;
  lines: number;
  overflow: number;
}

export interface TransientReproReport {
  events: TransientEventRecord[];
  frames: TransientFrameSample[];
}

export interface TransientFailuresSummary {
  destroys: TransientEventRecord[];
  detaches: TransientEventRecord[];
  bareFrames: TransientFrameSample[];
  overflowFrames: TransientFrameSample[];
  detail: string;
}

export interface DebounceViolation {
  idx: number;
  settledMs: number;
}

export interface DragMetricsResult {
  dragEventCount: number;
  totalDragDuration: number;
  totalFrames: number;
  meanFrameDuration: number;
  maxFrameDuration: number;
  longTaskCount: number;
  maxLongTaskDuration: number;
  totalBlockingTime: number;
  maxEventLoopDelay: number;
  meanEventLoopDelay: number;
  bareDomFlashes: number;
  mutationNodeOps: number;
  paragraphGbcReads: number;
  paragraphGcsReads: number;
  relayoutReadyTotal: number;
  offscreenCount: number;
  offscreenReadyDuringDrag: number;
  offscreenDebounceViolations: DebounceViolation[];
  wakeLatencyMs: number | null;
  totalParagraphs: number;
  enhancedParagraphs: number;
  finalSliderWidth: string;
}

export interface BurstMetricsResult {
  burstGbcReads: number;
  burstGcsReads: number;
  readyDuringBurst: number;
}

export interface GeometryReportResult {
  firstDiff: string | null;
  childDiffs: number;
  paraRectDiffs: number;
  maxDelta: number;
  examples: string[];
}

export interface DemoServerHandle {
  server: Server;
  notFound: string[];
}

export interface GeometryNodeReport {
  root: Box;
  paras: {
    rect: Box;
    kids: Box[];
  }[];
}

export interface SvelteComponents {
  main: string;
  multi: string;
}

export interface FrameworkSnapshotItem {
  rendered: string | null;
  lines: number;
  rawDom: string | null;
  live: string;
}

export interface ReactTextResult {
  first: boolean;
  second: boolean;
  third: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactAnchorResult {
  inserted: boolean;
  removed: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactSwapResult {
  swapped: boolean;
  back: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactEachResult {
  reversed: boolean;
  reshaped: boolean;
  snapshot: FrameworkSnapshotItem[];
  errors: string[];
}

export interface ReactBatchResult {
  batched: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactMidflightResult {
  settled: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactStressResult {
  settled: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface ReactUnmountResult {
  remaining: number;
}

export interface ReactZonesResult {
  initial: boolean;
  inView: boolean;
  near: boolean;
  far: boolean;
  above: boolean;
  finalA: boolean;
  finalB: boolean;
  zonesA: number[];
  zoneB: number;
  zoneA5Scrolled: number;
  farRenderedBeforeScroll: boolean;
}

export interface ReactRootflowResult {
  initialA: boolean;
  bGone: boolean;
  aStable: boolean;
  shrunk: boolean;
  dRendered: boolean;
  dContent: boolean;
  bBack: boolean;
  finalA: boolean;
  snapshotA: FrameworkSnapshotItem[];
}

export interface SvelteBasicResult {
  first: boolean;
  second: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface SvelteIfResult {
  initial: boolean;
  off: boolean;
  on: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface SvelteEachResult {
  initial: boolean;
  reordered: boolean;
  grown: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface SvelteStressResult {
  settled: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface SvelteUnmountResult {
  remaining: number;
}

export interface SvelteMultirootResult {
  initialA: boolean;
  initialB: boolean;
  initialC: boolean;
  zones: { a: number; b: number; c: number };
  far: boolean;
  near: boolean;
  bGone: boolean;
  aStable: boolean;
  cStable: boolean;
  grown: boolean;
  bBack: boolean;
  finalC: boolean;
  farRenderedBeforeScroll: boolean;
}

export interface DirectOpsResult {
  settled: boolean;
  snapshot: FrameworkSnapshotItem[];
}

export interface PostSuiteResult {
  newErrors: string[];
  stageChildren: number;
  roots: number;
}

export interface ParagraphMutationState {
  rendered: string | null;
  lines: number;
  childCount: number;
  head: string;
}

export interface MutationLogEvent {
  type: string;
  ri: string;
  t: number;
}

export interface ZoneInfo {
  ri: number;
  zone: string;
  below: boolean;
  top: number;
  bottom: number;
}

export interface ZonesResult {
  zones: ZoneInfo[];
  pick: { inVp: ZoneInfo; edge: ZoneInfo; off: ZoneInfo };
}

export interface PhaseQuietResult {
  before: number;
  afterIdle: number;
}

export interface PhaseSingleResult {
  done: boolean;
  before: ParagraphMutationState;
  after: ParagraphMutationState;
  newEvents: number;
  events: MutationLogEvent[];
}

export interface PhaseMutationsResult {
  picks: { inVp: number; edge: number; off: number };
  settle: { ok: boolean; scrolled: boolean };
  before: Record<string, ParagraphMutationState>;
  idle: Record<string, ParagraphMutationState>;
  idleEvents: MutationLogEvent[];
}

export interface PhasePreservedResult {
  ok: boolean;
  after: Record<string, ParagraphMutationState>;
  flagHistory: (string | null)[];
  hosts: { total: number; missing: string[] };
}

export interface PhaseAppendsResult {
  picks: { inVp: number; edge: number; off: number };
  settle: { ok: boolean; scrolled: boolean };
  states: Record<string, ParagraphMutationState>;
  events: MutationLogEvent[];
}

export interface PhaseSettledResult {
  ok: boolean;
  hosts: { total: number; missing: string[] };
  renderedDrops: number;
}

export interface PhaseReplacedResult {
  before: ParagraphMutationState;
  beforeHtml: string;
  idleHtml0: string;
  oldHostDetached: boolean;
  cloneIsNewNode: boolean;
  settle: { ok: boolean; scrolled: boolean };
  idle: ParagraphMutationState & { probe: string | null; html: string };
  idleEvents: MutationLogEvent[];
}

export interface PhaseCloneAfterWidthResult {
  ok: boolean;
  state: ParagraphMutationState;
  probe: string | null;
}

export interface PhaseSameFrameResult {
  done: boolean;
  before: ParagraphMutationState;
  after: ParagraphMutationState;
  newEvents: number;
}

export interface PhaseInPlaceResult {
  reacted: boolean;
  before: ParagraphMutationState;
  after: ParagraphMutationState;
  newEvents: number;
  beforeData: string;
}

export interface PhaseClearInsertResult {
  done: boolean;
  before: ParagraphMutationState;
  after: ParagraphMutationState;
}

export interface PhaseMidFlightResult {
  done: boolean;
  allRendered: boolean;
  after: ParagraphMutationState;
  flagHistory: (string | null)[];
  hosts: { total: number; missing: string[] };
}

export interface PhaseRawDomTextResult {
  ok: boolean;
  why?: string;
  first?: boolean;
  referenceSurvived?: boolean;
  second?: boolean;
  after: ParagraphMutationState;
  newEvents: number;
  idleEvents: number;
}

export interface PhaseRawDomRemoveResult {
  ok: boolean;
  why?: string;
  prepared: boolean;
  removedIsHeld?: boolean;
  done?: boolean;
  after: ParagraphMutationState;
  newEvents?: number;
}
