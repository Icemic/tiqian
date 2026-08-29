// Shared fixture font backend for the core test world (S1b-c).
//
// Ports installFixtureBackend() from
// frontend/web-precompute/scripts/plan-parity-oracle.ts (lines 37-82), which
// mirrors the fixture backend of PrecomputeExportsTest.kt: one glyph per code
// point, advance and x scaled by the font size, glyph id 0 marks a missing
// glyph, metrics [1.04, 0.28, 0, 0.88, 0.12] x fontSize. The callback protocol
// is the synchronous JSON request/response contract the real @tiqian/ffi runtime
// uses.
//
// Tests pass the returned callbacks as the snapshot-session descriptor or the
// scripted canvas model; the ffi entries take them as call parameters, so
// there is no global to install or restore.

const MISSING_GLYPH_MARKER = "\u22ef"; // "⋯"

function makeFixtureCallbacks() {
  return {
    shapeJson: (requestJson) => {
      const request = JSON.parse(requestJson);
      const displayText = request.displayText;
      const fontSize = request.style.fontSize;
      const missing = String(displayText).includes(MISSING_GLYPH_MARKER);
      const glyphs = [];
      let index = 0;
      for (const _point of displayText) {
        glyphs.push({
          id: missing ? 0 : 100 + index,
          advance: fontSize,
          x: index * fontSize,
          y: 0,
          bounds: [0, -fontSize * 0.88, fontSize, fontSize * 0.12],
        });
        index += 1;
      }
      return JSON.stringify({
        clusters: [{
          range: request.range,
          text: request.text.substring(request.range.start, request.range.end),
          displayText,
          fontKey: request.fontDecision.candidateKey,
          advance: glyphs.length * fontSize,
        }],
        glyphRuns: [{
          range: request.range,
          fontKey: request.fontDecision.candidateKey,
          glyphs: glyphs.map((g) => ({
            id: g.id,
            clusterRange: request.range,
            advance: g.advance,
            x: g.x,
            y: g.y,
            bounds: { left: g.bounds[0], top: g.bounds[1], right: g.bounds[2], bottom: g.bounds[3] },
          })),
          advance: glyphs.length * fontSize,
          openTypeFeatures: [],
        }],
        decisions: [{
          range: request.range,
          sourceText: request.text.substring(request.range.start, request.range.end),
          displayText,
          fontKey: request.fontDecision.candidateKey,
          glyphCount: glyphs.length,
          advance: glyphs.length * fontSize,
          source: "HarfBuzz",
          reason: "test",
          glyphsWithoutInkBounds: 0,
          missingGlyphs: missing ? glyphs.length : 0,
          resolvedFace: "Fixture CJK",
          script: "Hani",
          language: request.style.locale,
          featureEvidence: null,
        }],
      });
    },
    metricsJson: (requestJson) => {
      const request = JSON.parse(requestJson);
      const fontSize = request.fontSize;
      return JSON.stringify({
        ascent: fontSize * 1.04,
        descent: fontSize * 0.28,
        leading: 0,
        source: "RawTables",
        typoAscent: fontSize * 0.88,
        typoDescent: fontSize * 0.12,
      });
    },
  };
}

function installFixtureFontBackend() {
  const callbacks = makeFixtureCallbacks();
  return {
    uninstall() {
      // Retained for the try/finally shape of the callers; nothing to undo.
    },
    shapeJson: callbacks.shapeJson,
    metricsJson: callbacks.metricsJson,
  };
}

// A throwing backend variant: every shape request throws the given error.
// This is how tests force the snapshot-session capability-failure retry and the
// rethrow path through the real precompute exports.
function installThrowingFontBackend(error) {
  return {
    uninstall() {
      // No globals to restore
    },
    shapeJson: () => { throw error; },
    metricsJson: () => { throw error; },
  };
}

export { installFixtureFontBackend, installThrowingFontBackend };