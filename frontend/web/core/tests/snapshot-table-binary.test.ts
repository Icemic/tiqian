import assert from "node:assert/strict";
import test from "node:test";

import { writeBinaryTable } from "../core/sampler/snapshot/table-binary-writer.js";
import type { BinaryTableInput } from "../core/sampler/snapshot/table-binary-writer.js";
import { decodeSnapshotTableBinary } from "../core/sampler/snapshot/snapshot-table-binary.js";
import { snapshotTablesForRoot, snapshotTablesFromBytes } from "../core/sampler/snapshot/snapshot-tables.js";
import { expandSnapshotManifest } from "../core/sampler/snapshot/snapshot-manifest.js";
import type { SnapshotManifestWire, SnapshotTablesPin } from "../core/sampler/snapshot/snapshot-manifest.js";

interface MockElement {
  getAttribute: GetAttributeFn;
}

type GetAttributeFn = (name: string) => string | null;

interface FetchResponse {
  ok: boolean;
  arrayBuffer: ArrayBufferFn;
}

type ArrayBufferFn = () => Promise<ArrayBuffer>;

/** Helper to create a valuesEm array with nulls */
function valuesEm(...values: Array<number | null>): (number | null)[] {
  return values;
}

/**
 * One table held in both file forms. The strings list follows the binary
 * encoder's intern order (replay strings, metric strings, then probe scan
 * order) so integer references mean the same row in both readers.
 */
const TABLE: BinaryTableInput = {
  replayStrings: ["源", "Noto Serif CJK", "zh-Hans", "body",
    "noto-serif-1", "noto-serif-i1", "hani"],
  metrics: [
    {
      serializedFamilies: "Noto Serif CJK",
      fontWeight: 400,
      italic: false,
      role: "body",
      faceSelectionText: '{"weight":400}',
      valuesEm: valuesEm(0.5, null, null, null, null),
    },
    {
      serializedFamilies: "Noto Serif CJK",
      fontWeight: 700,
      italic: true,
      role: "body",
      faceSelectionText: '{"weight":400}',
      valuesEm: valuesEm(0.5, 0.6, 0.7, 0.8, 0.9),
    },
  ],
  probes: [
    {
      text: "永",
      advancePx: 16,
      fontSizePx: 16,
      fontWeight: 400,
      italic: false,
      script: "hani",
      language: "ZH",
      features: ["kern"],
    },
    {
      text: "永",
      advancePx: 16.5,
      fontSizePx: 16,
      fontWeight: 700,
      italic: true,
      script: "hani",
      language: "ZH",
      features: [],
    },
  ],
  faces: [{ family: "Noto Serif CJK", sourceOrder: 0 }],
  typographies: [{ sha256: "aa", value: { lines: [] } }],
  valueStyles: [".tq-root[data-v=da0e] { line-height: 1.7; }"],
  fontPreloads: [],
  revisions: { backendRevision: "r123", harfbuzzVersion: "11.0.1" },
};

/** A manifest whose references address the fixture table. */
function manifestPinning(): SnapshotManifestWire {
  const tablesPin: SnapshotTablesPin = { snapshot: "0".repeat(64) };
  // Use 0 for absent metric values (test fixture simplification)
  const shapeRow: [number, number, number, number, number, number, number, number, number, number, number[], number, number, number[]] = [
    0, 1, 400, 0, 2, 3, 0, 4, 5, 6,
    [10], 0, 1,
    [1001, 1, 0, 0, 0, 0, 0, 0],
  ];
  return {
    schema: 2,
    tables: tablesPin,
    fontReplay: {
      revision: "tiqian-server-shaping-replay-v1",
      encoding: "shared-strings-v1",
      shapes: [shapeRow],
    },
    entries: [{
      key: "p1",
      sourceSha256: "bb",
      typographyRef: 0,
      maxWidthPx: 360,
      fontFaceEvidence: [{ faceRef: 0, coverageText: "永远", probeRef: 1 }],
      renderArtifactSha256: "cc",
    }],
  };
}

test("binary table rows read back through the accessors", () => {
  const bytes = writeBinaryTable(TABLE);
  const view = decodeSnapshotTableBinary(bytes);
  assert.equal(view.binary, true);
  assert.equal(view.stringAt(0), "源");
  assert.equal(view.stringAt(10), "kern");
  assert.throws(() => view.stringAt(11), /SnapshotFontReplayStringReferenceInvalid/u);

  const rows = view.metricRows();
  if (rows.length < 2 || TABLE.metrics == null || TABLE.metrics.length < 2) {
    throw new Error("Expected at least 2 metric rows");
  }
  assert.deepEqual(rows[0], TABLE.metrics[0]);
  assert.deepEqual(rows[1], TABLE.metrics[1]);

  if (TABLE.probes == null || TABLE.probes.length < 2) {
    throw new Error("Expected at least 2 probes");
  }
  assert.deepEqual(view.probeAt(0), TABLE.probes[0]);
  assert.deepEqual(view.probeAt(1), TABLE.probes[1]);
  assert.throws(() => view.probeAt(2), /SnapshotProbeReferenceInvalid/u);

  if (TABLE.typographies == null || TABLE.typographies.length === 0) {
    throw new Error("Expected at least 1 typography");
  }
  assert.deepEqual(view.typographyAt(0), TABLE.typographies[0]);

  if (TABLE.faces == null || TABLE.faces.length === 0) {
    throw new Error("Expected at least 1 face");
  }
  assert.deepEqual(view.faceAt(0), TABLE.faces[0]);
  assert.deepEqual(view.valueStyles(), TABLE.valueStyles);
  assert.deepEqual(view.revisions(), TABLE.revisions);
});

test("damaged binary bytes fail closed", () => {
  const bytes = writeBinaryTable(TABLE);
  assert.throws(
    () => decodeSnapshotTableBinary(bytes.subarray(0, bytes.length - 1)),
    /SnapshotTablesInvalid/u,
  );
  assert.throws(
    () => decodeSnapshotTableBinary(bytes.subarray(0, 60)),
    /SnapshotTablesInvalid/u,
  );
  const wrongMagic = new Uint8Array(bytes);
  wrongMagic[4] = "9".charCodeAt(0);
  assert.throws(() => decodeSnapshotTableBinary(wrongMagic), /SnapshotTablesInvalid/u);
  // The revision tail parses during decode; trailing bytes break the parse
  // and the file fails closed.
  const overstuffed = new Uint8Array([...bytes, 0]);
  assert.throws(() => decodeSnapshotTableBinary(overstuffed), /SnapshotTablesInvalid/u);
});

test("the binary reader loads through the transport", async () => {
  const bytes = writeBinaryTable(TABLE);
  const key = "https://tables.test/snapshot-deadbeef.tiqtbl";
  const previousFetch = globalThis.fetch;
  const arrayBufferFn: ArrayBufferFn = async () => {
    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  };
  const fetchResponse: FetchResponse = { ok: true, arrayBuffer: arrayBufferFn };
  // Split the double assertion into two separate statements per ADR 0053 StrictTsDiscipline
  const intermediate = fetchResponse as unknown;
  globalThis.fetch = async () => intermediate as Response;
  try {
    const root: MockElement = { getAttribute: (name) => (name === "tq-tables" ? key : null) };
    const table = await snapshotTablesForRoot(root as Element, null);
    if (table == null) throw new Error("Table load failed");
    assert.equal(table.view.binary, true);
    assert.deepEqual([...table.bytes], [...bytes]);
    assert.equal(new TextDecoder().decode(table.bytes.subarray(0, 8)), "TIQTBL03");
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("the binary table expands the manifest it pins", () => {
  const binary = expandSnapshotManifest(
    manifestPinning(),
    decodeSnapshotTableBinary(writeBinaryTable(TABLE)),
  );
  if (binary.entries[0].fontEvidence.faces.length === 0) {
    throw new Error("Expected at least one face");
  }
  const firstFace = binary.entries[0].fontEvidence.faces[0];
  if (firstFace.probe == null) {
    throw new Error("Expected probe on first face");
  }
  assert.equal(firstFace.probe.advancePx, 16.5);
  assert.equal(firstFace.probe.features.length, 0);
  if (binary.fontReplay == null) {
    throw new Error("Expected font replay");
  }
  if (binary.fontReplay.shapes[0].result.features == null) {
    throw new Error("Expected features");
  }
  assert.equal(binary.fontReplay.shapes[0].result.features[0], "kern");
  assert.equal(binary.fontReplay.metrics.length, 2);
  assert.throws(
    () => expandSnapshotManifest(
      manifestPinning(),
      snapshotTablesFromBytes(new TextEncoder().encode("{\"schema\":2}")),
    ),
    /SnapshotTablesInvalid/u,
  );
});
