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

async function createTestSession(probe: any = null) {
  return createServerReplayFontSession([{}], {
    replay: fixtureReplay,
    faceMetadata: [{ weight: [400, 400], localNames: [] }],
    probe,
  });
}

test("shape miss with valid probe measures canvas, backfills session, and returns scaled shape handle", async () => {
  let measureCount = 0;
  const session = await createTestSession({
    measure(font: string, text: string) {
      measureCount++;
      assert.equal(font, "normal 400 32px Noto Sans SC, serif");
      assert.equal(text, "测");
      return { width: 16 };
    },
  });
  const shapeRequest = JSON.stringify({
    text: "测",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC", "serif"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "测",
    openTypeFeatures: [],
  });
  const shapeResponse = JSON.parse(session.shapeJson(shapeRequest));
  assert.equal(measureCount, 1);
  assert.equal(shapeResponse.glyphRuns[0].glyphs.length, 1);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].id, 0);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].advance, 16);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].x, 0);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].y, 0);
  assert.ok(Number.isNaN(shapeResponse.glyphRuns[0].glyphs[0].bounds?.left));
  assert.equal(shapeResponse.glyphRuns[0].advance, 16);
  assert.ok(shapeResponse.decisions[0].resolvedFace?.startsWith(REPLAY_PROBE_FACE_PREFIX));
  assert.ok(shapeResponse.decisions[0].resolvedFace?.includes("Noto Sans SC, serif"));
  assert.equal(shapeResponse.glyphRuns[0].openTypeFeatures.length, 0);
  assert.equal(shapeResponse.decisions[0].glyphsWithoutInkBounds, 1);
  session.close();
});

test("subsequent shape requests for probed key hit the session table without calling measure again", async () => {
  let measureCount = 0;
  const session = await createTestSession({
    measure(font: string, text: string) {
      measureCount++;
      return { width: 16 };
    },
  });
  const families = ["Noto Sans SC", "serif"].join("\u001f");
  const shapeRequest = JSON.stringify({
    text: "复",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC", "serif"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "复",
    openTypeFeatures: [],
  });
  const shapeResponse1 = JSON.parse(session.shapeJson(shapeRequest));
  assert.equal(measureCount, 1);
  const shapeResponse2 = JSON.parse(session.shapeJson(shapeRequest));
  assert.equal(measureCount, 1);
  assert.equal(shapeResponse1.glyphRuns[0].advance, shapeResponse2.glyphRuns[0].advance);
  session.close();
});

test("shape miss with probe failure throws MissingServerShapingReplay verbatim", async () => {
  const session = await createTestSession({
    measure() {
      return null;
    },
  });
  const key = shapeReplayKey("缺", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "缺");
  const shapeRequest = JSON.stringify({
    text: "缺",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "缺",
    openTypeFeatures: [],
  });
  assert.throws(
    () => JSON.parse(session.shapeJson(shapeRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:shape:${key}`,
  );

  const session2 = await createTestSession({
    measure() {
      return { width: Number.NaN };
    },
  });
  assert.throws(
    () => JSON.parse(session2.shapeJson(shapeRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:shape:${key}`,
  );
  session.close();
  session2.close();
});

test("session without probe throws MissingServerShapingReplay on miss", async () => {
  const session = await createTestSession(null);
  const key = shapeReplayKey("无", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "无");
  const shapeRequest = JSON.stringify({
    text: "无",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "无",
    openTypeFeatures: [],
  });
  assert.throws(
    () => JSON.parse(session.shapeJson(shapeRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:shape:${key}`,
  );
  const metricKey = metricReplayKey("Noto Sans SC", 400, false, "BodyText", "");
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 32,
    role: "BodyText",
    locale: "zh-Hans",
    fontFamilies: ["Noto Sans SC"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  assert.throws(
    () => JSON.parse(session.metricsJson(metricsRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:metrics:${metricKey}`,
  );
  session.close();
});

test("metric miss with latin role probe populates ascent/descent and keeps typo pair NaN", async () => {
  let probedText = "";
  const session = await createTestSession({
    measure(font: string, text: string) {
      probedText = text;
      return {
        width: 15,
        fontBoundingBoxAscent: 24,
        fontBoundingBoxDescent: 8,
      };
    },
  });
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 16,
    role: "BodyText",
    locale: "zh-Hans",
    fontFamilies: ["Roboto"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  const metricsResponse = JSON.parse(session.metricsJson(metricsRequest));
  assert.equal(probedText, LATIN_METRIC_PROBE_TEXT);
  assert.equal(metricsResponse.ascent, 24);
  assert.equal(metricsResponse.descent, 8);
  assert.equal(metricsResponse.leading, 0);
  assert.ok(metricsResponse.typoAscent === null);
  assert.ok(metricsResponse.typoDescent === null);
  session.close();
});

test("metric miss with CJK role probe calculates typo metrics from ideographic baseline and normalizes em", async () => {
  let probedText = "";
  const session = await createTestSession({
    measure(font: string, text: string) {
      probedText = text;
      return {
        width: 32,
        actualBoundingBoxAscent: 30,
        actualBoundingBoxDescent: 10,
        ideographicBaseline: -12,
      };
    },
  });
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 32,
    role: "CjkText",
    locale: "zh-Hans",
    fontFamilies: ["Noto Sans SC"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  const metricsResponse = JSON.parse(session.metricsJson(metricsRequest));
  assert.equal(probedText, CJK_METRIC_PROBE_TEXT);
  assert.equal(metricsResponse.ascent, 30);
  assert.equal(metricsResponse.descent, 10);
  assert.equal(metricsResponse.leading, 0);
  assert.equal(metricsResponse.typoAscent, 20);
  assert.equal(metricsResponse.typoDescent, 12);

  const valuesEm = probeMetricReplayValues(
    { serializedFamilies: "Noto Sans SC\u001fserif", fontSize: 32, fontWeight: 400, italic: false, role: "CjkText" },
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
  const key = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 32,
    role: "CjkText",
    locale: "zh-Hans",
    fontFamilies: ["Noto Sans SC"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  assert.throws(
    () => JSON.parse(session.metricsJson(metricsRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:metrics:${key}`,
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
  const key = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 32,
    role: "CjkText",
    locale: "zh-Hans",
    fontFamilies: ["Noto Sans SC"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  assert.throws(
    () => JSON.parse(session.metricsJson(metricsRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:metrics:${key}`,
  );
  session.close();
});

test("measure function throwing an error fails closed without leaking exception", async () => {
  const session = await createTestSession({
    measure() {
      throw new Error("CanvasContextCrash");
    },
  });
  const shapeKey = shapeReplayKey("崩", "Noto Sans SC", 400, false, "zh-Hans", "CjkText", "崩");
  const shapeRequest = JSON.stringify({
    text: "崩",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "崩",
    openTypeFeatures: [],
  });
  assert.throws(
    () => JSON.parse(session.shapeJson(shapeRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:shape:${shapeKey}`,
  );
  const metricKey = metricReplayKey("Noto Sans SC", 400, false, "CjkText", "");
  const metricsRequest = JSON.stringify({
    fontKey: "cjk-primary",
    fontSize: 32,
    role: "CjkText",
    locale: "zh-Hans",
    fontFamilies: ["Noto Sans SC"],
    fontWeight: 400,
    italic: false,
    faceSelectionText: "",
  });
  assert.throws(
    () => JSON.parse(session.metricsJson(metricsRequest)),
    (err: Error) => err.message === `MissingServerShapingReplay:metrics:${metricKey}`,
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
  const shapeRequest = JSON.stringify({
    text: "\u200B",
    range: { start: 0, end: 1 },
    style: { fontFamilies: ["Noto Sans SC"], fontSize: 32, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "\u200B",
    openTypeFeatures: [],
  });
  const shapeResponse1 = JSON.parse(session.shapeJson(shapeRequest));
  assert.equal(measureCount, 1);
  assert.equal(shapeResponse1.glyphRuns[0].advance, 0);
  assert.equal(shapeResponse1.glyphRuns[0].glyphs[0].advance, 0);

  const shapeResponse2 = JSON.parse(session.shapeJson(shapeRequest));
  assert.equal(measureCount, 1);
  assert.equal(shapeResponse2.glyphRuns[0].advance, 0);

  session.close();
});

test("probe options validation rejects non-function measure with ServerShapingReplayProbeInvalid", async () => {
  await assert.rejects(
    async () => createServerReplayFontSession([{}], {
      replay: fixtureReplay,
      faceMetadata: [{ weight: [400, 400], localNames: [] }],
      probe: {},
    }),
    (err: Error) => err.message === "ServerShapingReplayProbeInvalid",
  );

  await assert.rejects(
    async () => createServerReplayFontSession([{}], {
      replay: fixtureReplay,
      faceMetadata: [{ weight: [400, 400], localNames: [] }],
      probe: { measure: "not a function" },
    }),
    (err: Error) => err.message === "ServerShapingReplayProbeInvalid",
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