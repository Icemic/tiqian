import assert from "node:assert/strict";
import test from "node:test";

import {
  createServerReplayFontSession,
} from "./browser-font-replay.js";
import {
  normalizeReplayNumber,
  scaleMetricReplayItem,
  scaleShapeReplayItem,
} from "./replay-entry-codec.js";
import {
  CJK_METRIC_PROBE_TEXT,
  LATIN_METRIC_PROBE_TEXT,
  probeMetricReplayValues,
  probeShapeReplayResult,
  replayProbeCssFont,
  REPLAY_PROBE_FACE_PREFIX,
  ZERO_ADVANCE_EPSILON,
} from "./replay-probe.js";
import {
  FONT_REPLAY_REVISION,
  metricReplayKey,
  shapeReplayKey,
} from "./snapshot-schema.js";

const fixtureReplay = {
  revision: FONT_REPLAY_REVISION,
  shapes: [
    {
      key: shapeReplayKey("init", "InitFamily", 400, false, "zh-Hans", "CjkText", "init"),
      result: {
        faceId: "fixture-face",
        fontInstanceId: "",
        script: "",
        features: [],
        unsafeBreakCount: 0,
        advanceEm: 1,
        glyphs: [{ id: 1, advanceEm: 1, xEm: 0, yEm: 0, boundsEm: null }],
      },
    },
  ],
  metrics: [
    {
      key: metricReplayKey("InitFamily", 400, false, "CjkText", ""),
      valuesEm: [0.8, 0.2, 0, 0.8, 0.2],
    },
  ],
};

async function createTestSession(probe = null) {
  return createServerReplayFontSession([{}], {
    replay: fixtureReplay,
    faceMetadata: [{ weight: [400, 400], localNames: [] }],
    probe,
  });
}

test("shape miss with valid probe measures canvas, backfills session, and returns scaled shape handle", async () => {
  let measureCount = 0;
  const session = await createTestSession({
    measure(font, text) {
      measureCount++;
      assert.equal(font, "normal 400 32px Noto Sans SC, serif");
      assert.equal(text, "测");
      return { width: 16 };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const handle = backend.shape(
    session.id,
    "测",
    ["Noto Sans SC", "serif"].join("\u001f"),
    32,
    400,
    false,
    "zh-Hans",
    "CjkText",
    "测",
  );
  assert.equal(measureCount, 1);
  assert.equal(backend.shapeGlyphCount(handle), 1);
  assert.equal(backend.shapeGlyphId(handle, 0), 0);
  assert.equal(backend.shapeGlyphAdvance(handle, 0), 16);
  assert.equal(backend.shapeGlyphX(handle, 0), 0);
  assert.equal(backend.shapeGlyphY(handle, 0), 0);
  assert.ok(Number.isNaN(backend.shapeGlyphBound(handle, 0, 0)));
  assert.equal(backend.shapeAdvance(handle), 16);
  assert.ok(backend.shapeFaceId(handle).startsWith(REPLAY_PROBE_FACE_PREFIX));
  assert.ok(backend.shapeFaceId(handle).includes("Noto Sans SC, serif"));
  assert.equal(backend.shapeFeatureCount(handle), 0);
  assert.equal(backend.shapeUnsafeBreakCount(handle), 0);
  backend.releaseShape(handle);
  session.close();
});

test("subsequent shape requests for probed key hit the session table without calling measure again", async () => {
  let measureCount = 0;
  const session = await createTestSession({
    measure(font, text) {
      measureCount++;
      return { width: 16 };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const families = ["Noto Sans SC", "serif"].join("\u001f");
  const handle1 = backend.shape(session.id, "复", families, 32, 400, false, "zh-Hans", "CjkText", "复");
  assert.equal(measureCount, 1);
  const handle2 = backend.shape(session.id, "复", families, 32, 400, false, "zh-Hans", "CjkText", "复");
  assert.equal(measureCount, 1);
  assert.equal(backend.shapeAdvance(handle1), backend.shapeAdvance(handle2));
  backend.releaseShape(handle1);
  backend.releaseShape(handle2);
  session.close();
});

test("shape miss with probe failure throws MissingServerShapingReplay verbatim", async () => {
  const session = await createTestSession({
    measure() {
      return null;
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const key = shapeReplayKey("缺", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "缺");
  assert.throws(
    () => backend.shape(session.id, "缺", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "缺"),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:shape:${key}`,
  );

  const session2 = await createTestSession({
    measure() {
      return { width: Number.NaN };
    },
  });
  assert.throws(
    () => backend.shape(session2.id, "缺", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "缺"),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:shape:${key}`,
  );
  session.close();
  session2.close();
});

test("session without probe throws MissingServerShapingReplay on miss", async () => {
  const session = await createTestSession(null);
  const backend = globalThis.__TiqianFontBackend;
  const key = shapeReplayKey("无", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "无");
  assert.throws(
    () => backend.shape(session.id, "无", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "无"),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:shape:${key}`,
  );
  const metricKey = metricReplayKey("Noto Sans SC", 400, false, "BodyText", "");
  assert.throws(
    () => backend.metrics(session.id, "Noto Sans SC", 32, 400, false, "BodyText", ""),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:metrics:${metricKey}`,
  );
  session.close();
});

test("metric miss with latin role probe populates ascent/descent and keeps typo pair NaN", async () => {
  let probedText = "";
  const session = await createTestSession({
    measure(font, text) {
      probedText = text;
      return {
        width: 15,
        fontBoundingBoxAscent: 24,
        fontBoundingBoxDescent: 8,
      };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const handle = backend.metrics(session.id, "Roboto", 16, 400, false, "BodyText", "");
  assert.equal(probedText, LATIN_METRIC_PROBE_TEXT);
  assert.equal(backend.metricValue(handle, 0), 24);
  assert.equal(backend.metricValue(handle, 1), 8);
  assert.equal(backend.metricValue(handle, 2), 0);
  assert.ok(Number.isNaN(backend.metricValue(handle, 3)));
  assert.ok(Number.isNaN(backend.metricValue(handle, 4)));
  backend.releaseMetrics(handle);
  session.close();
});

test("metric miss with CJK role probe calculates typo metrics from ideographic baseline and normalizes em", async () => {
  let probedText = "";
  const session = await createTestSession({
    measure(font, text) {
      probedText = text;
      return {
        width: 32,
        actualBoundingBoxAscent: 30,
        actualBoundingBoxDescent: 10,
        ideographicBaseline: -12,
      };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const handle = backend.metrics(session.id, "Noto Sans SC", 32, 400, false, "CjkText", "");
  assert.equal(probedText, CJK_METRIC_PROBE_TEXT);
  assert.equal(backend.metricValue(handle, 0), 30);
  assert.equal(backend.metricValue(handle, 1), 10);
  assert.equal(backend.metricValue(handle, 2), 0);
  assert.equal(backend.metricValue(handle, 3), 20);
  assert.equal(backend.metricValue(handle, 4), 12);

  const valuesEm = probeMetricReplayValues(
    { serializedFamilies: "Noto Sans SC", fontSize: 32, fontWeight: 400, italic: false, role: "CjkText" },
    () => ({
      width: 32,
      actualBoundingBoxAscent: 30,
      actualBoundingBoxDescent: 10,
      ideographicBaseline: -12,
    }),
  );
  assert.deepEqual(valuesEm, [
    normalizeReplayNumber(30, 32),
    normalizeReplayNumber(10, 32),
    0,
    normalizeReplayNumber(20, 32),
    normalizeReplayNumber(12, 32),
  ]);
  assert.deepEqual(valuesEm, [0.9375, 0.3125, 0, 0.625, 0.375]);

  backend.releaseMetrics(handle);
  session.close();
});

test("metric miss with measure width <= epsilon throws MissingServerShapingReplay", async () => {
  const session = await createTestSession({
    measure() {
      return {
        width: 0.005,
        fontBoundingBoxAscent: 20,
        fontBoundingBoxDescent: 5,
      };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const key = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  assert.throws(
    () => backend.metrics(session.id, "Noto Sans SC", 32, 400, false, "CjkText", ""),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:metrics:${key}`,
  );
  session.close();
});

test("metric miss with missing ascent in measure throws MissingServerShapingReplay", async () => {
  const session = await createTestSession({
    measure() {
      return {
        width: 10,
        fontBoundingBoxDescent: 5,
      };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const key = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  assert.throws(
    () => backend.metrics(session.id, "Noto Sans SC", 32, 400, false, "CjkText", ""),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:metrics:${key}`,
  );
  session.close();
});

test("measure function throwing an error fails closed without leaking exception", async () => {
  const session = await createTestSession({
    measure() {
      throw new Error("CanvasContextCrash");
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const shapeKey = shapeReplayKey("崩", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "崩");
  assert.throws(
    () => backend.shape(session.id, "崩", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "崩"),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:shape:${shapeKey}`,
  );
  const metricKey = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  assert.throws(
    () => backend.metrics(session.id, "Noto Sans SC", 32, 400, false, "CjkText", ""),
    (err) => err instanceof Error && err.message === `MissingServerShapingReplay:metrics:${metricKey}`,
  );
  session.close();
});

test("zero-width display text probing succeeds, records 0 advance, and hits cache on revisit", async () => {
  let measureCount = 0;
  const session = await createTestSession({
    measure() {
      measureCount++;
      return { width: 0 };
    },
  });
  const backend = globalThis.__TiqianFontBackend;
  const handle1 = backend.shape(session.id, "\u200B", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "\u200B");
  assert.equal(measureCount, 1);
  assert.equal(backend.shapeAdvance(handle1), 0);
  assert.equal(backend.shapeGlyphAdvance(handle1, 0), 0);

  const handle2 = backend.shape(session.id, "\u200B", "Noto Sans SC", 32, 400, false, "zh-Hans", "CjkText", "\u200B");
  assert.equal(measureCount, 1);
  assert.equal(backend.shapeAdvance(handle2), 0);

  backend.releaseShape(handle1);
  backend.releaseShape(handle2);
  session.close();
});

test("probe options validation rejects non-function measure with ServerShapingReplayProbeInvalid", async () => {
  await assert.rejects(
    async () => createServerReplayFontSession([{}], {
      replay: fixtureReplay,
      faceMetadata: [{ weight: [400, 400], localNames: [] }],
      probe: {},
    }),
    (err) => err instanceof Error && err.message === "ServerShapingReplayProbeInvalid",
  );

  await assert.rejects(
    async () => createServerReplayFontSession([{}], {
      replay: fixtureReplay,
      faceMetadata: [{ weight: [400, 400], localNames: [] }],
      probe: { measure: "not a function" },
    }),
    (err) => err instanceof Error && err.message === "ServerShapingReplayProbeInvalid",
  );
});

test("probed entries pass scaleShapeReplayItem and scaleMetricReplayItem without throwing", () => {
  const probedShape = probeShapeReplayResult(
    {
      displayText: "测试",
      serializedFamilies: "Noto Sans SC\u001fserif",
      fontSize: 16,
      fontWeight: 400,
      italic: false,
    },
    () => ({ width: 32 }),
  );
  assert.ok(probedShape);
  const scaledShape = scaleShapeReplayItem({ result: probedShape }, 16);
  assert.equal(scaledShape.advance, 32);
  assert.equal(scaledShape.glyphs.length, 1);
  assert.equal(scaledShape.glyphs[0].advance, 32);

  const probedMetrics = probeMetricReplayValues(
    {
      serializedFamilies: "Noto Sans SC\u001fserif",
      fontSize: 16,
      fontWeight: 400,
      italic: false,
      role: "CjkText",
    },
    () => ({
      width: 16,
      fontBoundingBoxAscent: 14,
      fontBoundingBoxDescent: 4,
      ideographicBaseline: -4,
    }),
  );
  assert.ok(probedMetrics);
  const scaledMetrics = scaleMetricReplayItem({ valuesEm: probedMetrics }, 16);
  assert.equal(scaledMetrics[0], 14);
  assert.equal(scaledMetrics[1], 4);
  assert.equal(scaledMetrics[2], 0);
  assert.equal(scaledMetrics[3], 12);
  assert.equal(scaledMetrics[4], 4);
});

test("replayProbeCssFont unit tests format css font strings correctly", () => {
  const fontNormal = replayProbeCssFont(
    ["Noto Sans SC", "serif"].join("\u001f"),
    400,
    false,
    32,
  );
  assert.equal(fontNormal, "normal 400 32px Noto Sans SC, serif");

  const fontItalic = replayProbeCssFont(
    ["Noto Sans SC", "serif"].join("\u001f"),
    700,
    true,
    16,
  );
  assert.equal(fontItalic, "italic 700 16px Noto Sans SC, serif");
});
