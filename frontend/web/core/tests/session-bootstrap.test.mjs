import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  createManifestFontSession,
  createProbeBootstrapFontSession,
} from "../core/engine/web-worker/session-bootstrap.js";
import { createServerReplayFontSession } from "../core/measurement/browser-font-replay.js";
import { FONT_REPLAY_REVISION } from "../core/sampler/snapshot/snapshot-schema.js";
import { writeBinaryTable } from "../core/sampler/snapshot/table-binary-writer.mjs";

function recordingMeasureAdapter(calls) {
  return (cssFont, text) => {
    calls.push({ cssFont, text });
    return { width: 18, fontBoundingBoxAscent: 30, fontBoundingBoxDescent: 10 };
  };
}

test("empty tables with a probe create an unbaked session", async () => {
  const calls = [];
  const session = await createServerReplayFontSession([], {
    sessionPrefix: "tq-test-nobake",
    replay: { revision: FONT_REPLAY_REVISION, shapes: [], metrics: [] },
    faceMetadata: [],
    harfbuzzVersion: "",
    probe: { measure: recordingMeasureAdapter(calls) },
  });
  try {
    assert.equal(session.faces.length, 0);
    assert.match(session.id, /^tq-test-nobake-/);
    // Verify callbacks exist
    assert.ok(typeof session.shapeJson === "function");
    assert.ok(typeof session.metricsJson === "function");
  } finally {
    session.close();
  }
});

test("empty tables without a probe still fail closed", async () => {
  await assert.rejects(
    () => createServerReplayFontSession([], {
      replay: { revision: FONT_REPLAY_REVISION, shapes: [], metrics: [] },
      faceMetadata: [],
    }),
    /ServerShapingReplayEmpty/u,
  );
});

test("probe bootstrap backfills a miss and serves the same key from the table", async () => {
  const calls = [];
  const session = await createProbeBootstrapFontSession("bootstrap-test", {
    measureAdapter: recordingMeasureAdapter(calls),
  });
  assert.match(session.id, /^tq-worker-nobake-bootstrap-test-/);
  try {
    const shapeRequest = JSON.stringify({
      text: "中",
      range: { start: 0, end: 1 },
      style: { fontFamilies: ["Fixture CJK"], fontSize: 18, fontWeight: 400, italic: false, locale: "zh-Hans" },
      fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
      displayText: "中",
      openTypeFeatures: [],
    });
    const shapeResponse = JSON.parse(session.shapeJson(shapeRequest));
    assert.equal(shapeResponse.glyphRuns[0].advance, 18);
    assert.equal(calls.length, 1);

    // Second call should hit cache
    const shapeResponse2 = JSON.parse(session.shapeJson(shapeRequest));
    assert.equal(shapeResponse2.glyphRuns[0].advance, 18);
    assert.equal(calls.length, 1);
  } finally {
    session.close();
  }
});

test("a missing measure adapter names LayoutWorkerProbeUnavailable", () => {
  assert.throws(
    () => createProbeBootstrapFontSession("no-adapter", { measureAdapter: null }),
    /LayoutWorkerProbeUnavailable/u,
  );
});

function snapshotTablesFixture() {
  return {
    replayStrings: ["a", "Fixture CJK", "zh-Hans", "CjkText", "fixture-face", "fixture-instance", "Hani"],
    typographies: [{
      sha256: "t".repeat(64),
      value: {
        fontFamilies: ["Fixture CJK"],
        fontSizePx: 18,
        lineHeightPx: 27,
        locale: "zh-Hans",
      },
    }],
    faces: [{
      family: "Fixture CJK",
      style: "normal",
      weight: [400, 400],
      unicodeRange: "U+4E00-9FFF",
      publicUrl: "/fixture-deadbeef.woff2",
      sourceSha256: "a".repeat(64),
      sfntSha256: "b".repeat(64),
      faceIndex: 0,
      sourceOrder: 0,
      axes: {},
      localNames: ["Fixture CJK"],
    }],
    metrics: [{
      serializedFamilies: "Fixture CJK",
      fontWeight: 400,
      italic: false,
      role: "CjkText",
      faceSelectionText: "a",
      valuesEm: [1.16, 0.28, 0, null, null],
    }],
    probes: [{ text: "中", advancePx: 18, fontSizePx: 18, fontWeight: 400, italic: false, script: "Hani", language: "zh-Hans", features: [] }],
    valueStyles: ["font-variant-numeric: lining-nums"],
    fontPreloads: ["/fixture-deadbeef.woff2"],
    revisions: {
      backendRevision: "fixture-backend",
      harfbuzzVersion: "fixture-hb",
    },
  };
}

function tablesManifestFixture() {
  return {
    schema: 2,
    tables: { snapshot: "0".repeat(64) },
    layoutRevision: "tiqian-layout-v2",
    renderRevision: "prebroken-dom-v16",
    fontSourcePolicy: "host-compatible-stylesheet-v1",
    paragraphSelector: "p[data-tq-snapshot-key]",
    renderFontFamilies: ["Fixture CJK"],
    fontReplay: {
      revision: "tiqian-server-shaping-replay-v1",
      encoding: "shared-strings-v1",
      shapes: [[0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, [1, 1, 0, 0, 0, -0.8, 1, 0.2]]],
    },
    entries: [{
      key: "a",
      sourceSha256: "a".repeat(64),
      typographyRef: 0,
      maxWidthPx: 360,
      fontFaceEvidence: [{ faceRef: 0, probeRef: 0 }],
      renderArtifactSha256: "r".repeat(64),
    }],
  };
}

test("manifest sessions keep the baked contract path", async () => {
  const manifestText = JSON.stringify(tablesManifestFixture());
  const tablesBytes = writeBinaryTable(snapshotTablesFixture());
  const session = await createManifestFontSession(manifestText, tablesBytes, "manifest-test");
  try {
    assert.equal(session.faces.length, 1);
    assert.equal(session.faces[0].family, "Fixture CJK");
    assert.match(session.id, /^tq-worker-manifest-test-/);
    assert.ok(typeof session.shapeJson === "function");
    assert.ok(typeof session.metricsJson === "function");
  } finally {
    session.close();
  }

  const broken = tablesManifestFixture();
  delete broken.fontReplay;
  assert.throws(
    () => createManifestFontSession(JSON.stringify(broken), tablesBytes, "manifest-broken"),
    /LayoutWorkerFontContractInvalid/u,
  );
});

test("layout-worker keeps its ffi import and snapshot-subset wiring", async () => {
  const source = await readFile(new URL("../core/engine/layout-worker.js", import.meta.url), "utf8");
  assert.match(source, /createProbeBootstrapFontSession/u);
  assert.match(source, /createManifestFontSession/u);
  assert.match(source, /from "@tiqian\/ffi"/u);
  assert.match(source, /workerSnapshotSubsetSourceBoundaries\(session\.faces, request\)/u);
});