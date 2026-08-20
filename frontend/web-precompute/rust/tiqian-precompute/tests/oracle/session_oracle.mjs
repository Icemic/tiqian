// Session parity oracle (ADR 0050). Runs the case matrix written by the
// Rust integration test through the production JS font session
// (`frontend/web/npm/precompute-fonts.js`) and emits one JSON document to
// stdout. The Rust test runs the same matrix through tiqian-precompute and
// byte-compares the two documents.
//
//   node session_oracle.mjs <cases.json> <repo-root>
//
// Fonts are referenced by absolute path inside the cases file. Every object
// literal below is written in the key order the Rust emitter mirrors; the
// per-glyph `bounds` key is omitted when the JS result leaves it undefined.

import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const casesPath = process.argv[2];
const repoRoot = process.argv[3];

const cases = JSON.parse(await readFile(casesPath, "utf8"));
const { createFontSession } = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/precompute-fonts.js")).href
);
const {
  mergeSerializedSourceBoundaries,
  workerExactSubsetSourceBoundaries,
} = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/font-face-boundaries.js")).href
);

const fontBuffers = new Map();
for (const [key, path] of Object.entries(cases.fonts)) {
  fontBuffers.set(key, new Uint8Array(await readFile(path)));
}

const registry = globalThis[Symbol.for("org.tiqian.web.fonts.tiqian-shared-harfbuzz-v5")];
const entries = [];
const sessions = new Map();

for (const spec of cases.sessions) {
  try {
    const faceSpecs = spec.faces.map((face) => {
      const out = {
        family: face.family,
        publicUrl: face.publicUrl,
        source: fontBuffers.get(face.font),
        weight: face.weight,
        style: face.style,
      };
      if (face.unicodeRange != null) out.unicodeRange = face.unicodeRange;
      if (face.sourceOrder != null) out.sourceOrder = face.sourceOrder;
      return out;
    });
    const session = await createFontSession(faceSpecs, {
      sessionPrefix: spec.prefix,
      baseFeatures: spec.baseFeatures,
    });
    sessions.set(spec.id, session);
    entries.push({
      kind: "session",
      id: spec.id,
      ok: true,
      sessionId: session.id,
      backendRevision: session.backendRevision,
      harfbuzzVersion: session.harfbuzzVersion,
      faces: session.faces,
    });
  } catch (error) {
    entries.push({ kind: "session", id: spec.id, ok: false, error: String(error.message) });
  }
}

for (const call of cases.calls) {
  // installGlobalBackend ran inside the first successful createFontSession;
  // the handle only exists from that point on.
  const backend = globalThis.__TiqianFontBackend;
  const live = sessions.get(call.session);
  if (call.kind === "shape") {
    try {
      const handle = backend.shape(
        live.id,
        call.displayText,
        call.families.join("\u001f"),
        call.fontSize,
        call.fontWeight,
        call.italic,
        call.locale,
        call.role,
        call.sourceText ?? undefined,
      );
      const result = registry.shapeResults.get(handle);
      entries.push({
        kind: "shape",
        session: call.session,
        tag: call.tag,
        ok: true,
        faceId: result.record.faceId,
        fontInstanceId: backend.shapeFontInstanceId(handle),
        script: result.script,
        features: result.features,
        probeFeatures: result.probeFeatures,
        unsafeBreakCount: result.unsafeBreakCount,
        advance: result.advance,
        glyphs: result.glyphs.map((glyph) => {
          const out = {
            id: glyph.id,
            cluster: glyph.cluster,
            advance: glyph.advance,
            x: glyph.x,
            y: glyph.y,
          };
          if (glyph.bounds !== undefined) out.bounds = glyph.bounds;
          return out;
        }),
      });
    } catch (error) {
      entries.push({
        kind: "shape",
        session: call.session,
        tag: call.tag,
        ok: false,
        error: String(error.message),
      });
    }
  } else if (call.kind === "metrics") {
    try {
      const handle = backend.metrics(
        live.id,
        call.families.join("\u001f"),
        call.fontSize,
        call.fontWeight,
        call.italic,
        call.role,
        call.faceSelectionText,
      );
      entries.push({
        kind: "metrics",
        session: call.session,
        tag: call.tag,
        ok: true,
        values: [0, 1, 2, 3, 4].map((index) => backend.metricValue(handle, index)),
      });
    } catch (error) {
      entries.push({
        kind: "metrics",
        session: call.session,
        tag: call.tag,
        ok: false,
        error: String(error.message),
      });
    }
  } else if (call.kind === "renderFamilies") {
    try {
      entries.push({
        kind: "renderFamilies",
        session: call.session,
        tag: call.tag,
        ok: true,
        families: live.renderFamilies(call.requested),
      });
    } catch (error) {
      entries.push({
        kind: "renderFamilies",
        session: call.session,
        tag: call.tag,
        ok: false,
        error: String(error.message),
      });
    }
  } else if (call.kind === "sourceBoundaries") {
    try {
      const baseStyle = {
        fontFamilies: call.families,
        fontSizePx: call.fontSizePx,
        fontWeight: call.fontWeight,
        italic: call.italic,
      };
      if (call.baselineShiftPx != null) baseStyle.baselineShiftPx = call.baselineShiftPx;
      const spans = call.spans.map((span) => {
        const flat = {
          start: span.start,
          end: span.end,
          fontFamilies: span.fontFamilies,
          fontSizePx: span.fontSizePx,
          fontWeight: span.fontWeight,
          italic: span.italic,
        };
        if (span.baselineShiftPx != null) flat.baselineShiftPx = span.baselineShiftPx;
        return flat;
      });
      entries.push({
        kind: "sourceBoundaries",
        session: call.session,
        tag: call.tag,
        ok: true,
        boundaries: live.sourceBoundaries(call.text, baseStyle, spans),
      });
    } catch (error) {
      entries.push({
        kind: "sourceBoundaries",
        session: call.session,
        tag: call.tag,
        ok: false,
        error: String(error.message),
      });
    }
  } else if (call.kind === "workerBoundaries") {
    try {
      entries.push({
        kind: "workerBoundaries",
        tag: call.tag,
        ok: true,
        boundaries: workerExactSubsetSourceBoundaries(call.faces, {
          text: call.text,
          fontFamilies: call.fontFamilies,
          fontSizePx: call.fontSizePx,
          fontWeight: call.fontWeight,
          italic: call.italic,
          textSpans: call.textSpans,
        }),
      });
    } catch (error) {
      entries.push({ kind: "workerBoundaries", tag: call.tag, ok: false, error: String(error.message) });
    }
  } else if (call.kind === "mergeBoundaries") {
    try {
      entries.push({
        kind: "mergeBoundaries",
        tag: call.tag,
        ok: true,
        merged: mergeSerializedSourceBoundaries(call.serialized, call.additional),
      });
    } catch (error) {
      entries.push({ kind: "mergeBoundaries", tag: call.tag, ok: false, error: String(error.message) });
    }
  } else if (call.kind === "beginCapture") {
    live.beginCapture();
    entries.push({ kind: "beginCapture", session: call.session });
  } else if (call.kind === "evidence") {
    const evidence = live.captureEvidence();
    entries.push({
      kind: "evidence",
      session: call.session,
      ok: true,
      backendRevision: evidence.backendRevision,
      harfbuzzVersion: evidence.harfbuzzVersion,
      faces: evidence.faces,
      replay: evidence.replay,
    });
  }
}

process.stdout.write(`${JSON.stringify(entries)}
`);
