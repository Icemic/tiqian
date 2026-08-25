// Shared fixture font backend for the npm-core test world (S1b-c).
//
// Ports installFixtureBackend() from
// frontend/web-precompute/scripts/plan-parity-oracle.mjs (lines 37-82), which
// mirrors the fixture backend of PrecomputeExportsTest.kt: one glyph per code
// point, advance and x scaled by the font size, glyph id 0 marks a missing
// glyph, metrics [1.04, 0.28, 0, 0.88, 0.12] x fontSize. The handle protocol
// is the synchronous Node font session contract the real @tiqian/ffi runtime
// reads from globalThis.__TiqianFontBackend.
//
// Install it around a test (with try/finally) so the real
// precomputeParagraphWithDiagnostics / precomputeParagraphWithBrowserMetrics
// exports can shape and measure in node. uninstall() restores the prior
// globals.

const MISSING_GLYPH_MARKER = "\u22ef"; // "⋯"

function installFixtureFontBackend() {
  const previous = {
    __TiqianFontBackend: globalThis.__TiqianFontBackend,
    __TiqianFontBackendReplayRegistry: globalThis.__TiqianFontBackendReplayRegistry,
    __TiqianFontBackendRevision: globalThis.__TiqianFontBackendRevision,
  };
  let nextHandle = 1;
  const shapes = new Map();
  const metrics = new Map();
  globalThis.__TiqianFontBackend = {
    shape(_session, displayText, _families, fontSize) {
      const handle = nextHandle++;
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
      shapes.set(handle, { glyphs, advance: glyphs.length * fontSize });
      return handle;
    },
    shapeGlyphCount: (handle) => shapes.get(handle).glyphs.length,
    shapeGlyphId: (handle, index) => shapes.get(handle).glyphs[index].id,
    shapeGlyphAdvance: (handle, index) => shapes.get(handle).glyphs[index].advance,
    shapeGlyphX: (handle, index) => shapes.get(handle).glyphs[index].x,
    shapeGlyphY: (handle, index) => shapes.get(handle).glyphs[index].y,
    shapeGlyphBound: (handle, index, edge) => shapes.get(handle).glyphs[index].bounds[edge],
    shapeAdvance: (handle) => shapes.get(handle).advance,
    shapeFaceId: () => "Fixture CJK",
    shapeFontInstanceId: () => "fixture-sha:0:wght=400",
    shapeScript: () => "Hani",
    shapeFeatureCount: () => 0,
    shapeFeature: () => "",
    shapeUnsafeBreakCount: () => 0,
    releaseShape: (handle) => shapes.delete(handle),
    metrics(_session, _families, fontSize) {
      const handle = nextHandle++;
      metrics.set(handle, [fontSize * 1.04, fontSize * 0.28, 0, fontSize * 0.88, fontSize * 0.12]);
      return handle;
    },
    metricValue: (handle, index) => metrics.get(handle)[index],
    releaseMetrics: (handle) => metrics.delete(handle),
  };
  return {
    uninstall() {
      for (const name of Object.keys(previous)) {
        if (previous[name] !== undefined) globalThis[name] = previous[name];
        else delete globalThis[name];
      }
    },
  };
}

// A throwing backend variant: every shape request throws the given error.
// This is how tests force the exact-session capability-failure retry and the
// rethrow path through the real precompute exports.
function installThrowingFontBackend(error) {
  const previous = {
    __TiqianFontBackend: globalThis.__TiqianFontBackend,
    __TiqianFontBackendReplayRegistry: globalThis.__TiqianFontBackendReplayRegistry,
    __TiqianFontBackendRevision: globalThis.__TiqianFontBackendRevision,
  };
  globalThis.__TiqianFontBackend = {
    shape() {
      throw error;
    },
    metrics() {
      throw error;
    },
  };
  return {
    uninstall() {
      for (const name of Object.keys(previous)) {
        if (previous[name] !== undefined) globalThis[name] = previous[name];
        else delete globalThis[name];
      }
    },
  };
}

export { installFixtureFontBackend, installThrowingFontBackend };
