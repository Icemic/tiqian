export const SNAPSHOT_SCHEMA = 1;
export const SNAPSHOT_TABLES_SCHEMA = 2;
export const LAYOUT_REVISION = "tiqian-layout-v2";
export const RENDER_REVISION = "prebroken-dom-v15";
export const FONT_SOURCE_POLICY = "host-compatible-stylesheet-v1";
export const FONT_BACKEND_REVISION = "tiqian-shared-harfbuzz-v5";
export const FONT_REPLAY_REVISION = "tiqian-server-shaping-replay-v1";
export const FONT_REPLAY_TRANSPORT = "shared-strings-v1";

/** The manifest schema this runtime build reads: snapshot tables. */
export function readableSnapshotSchema(schema: unknown): boolean {
  return schema === SNAPSHOT_TABLES_SCHEMA;
}

export function shapeReplayKey(
  displayText: unknown,
  serializedFamilies: unknown,
  fontWeight: unknown,
  italic: unknown,
  locale: unknown,
  role: unknown,
  sourceText: unknown,
): string {
  return JSON.stringify([
    displayText,
    serializedFamilies,
    Number(fontWeight),
    Boolean(italic),
    String(locale),
    String(role),
    sourceText,
  ]);
}

export function metricReplayKey(
  serializedFamilies: unknown,
  fontWeight: unknown,
  italic: unknown,
  role: unknown,
  faceSelectionText: unknown,
): string {
  return JSON.stringify([
    serializedFamilies,
    Number(fontWeight),
    Boolean(italic),
    String(role),
    String(faceSelectionText),
  ]);
}

export function stableStringify(value: unknown): string {
  if (value == null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  const entries = Object.keys(value).sort().map((key) =>
    `${JSON.stringify(key)}:${stableStringify((value as Record<string, unknown>)[key])}`);
  return `{${entries.join(",")}}`;
}
