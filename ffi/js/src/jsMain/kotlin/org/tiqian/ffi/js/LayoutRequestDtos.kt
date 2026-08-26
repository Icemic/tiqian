@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport

/**
 * Internal DTO data classes for the layout request pipeline.
 * These are used internally by Kotlin code and tests.
 */
data class WorkerLayoutRequestDto(
    val text: String,
    val maxWidthPx: Double,
    val fontFamilies: Array<String>,
    val fontSizePx: Double,
    val lineHeightPx: Double,
    val locale: String,
    val fontWeight: Int,
    val italic: Boolean,
    val firstLineIndentIc: Double,
    val lineLengthGridEnabled: Boolean,
    val sourceBoundaries: Array<Int>,
    val textSpans: Array<TextSpanWireDto>,
    val inlineBoxes: Array<InlineBoxWireDto>,
    val lineBreakSpans: Array<LineBreakSpanWireDto>,
    val inlineObjects: Array<InlineObjectWireDto>,
    val renderEvidence: Boolean,
    val semantics: Array<SemanticSpanWireDto>,
    val renderInlineBoxes: Array<RenderInlineBoxWireDto>,
    val sourceTag: String,
)

data class PrepareParagraphRequestDto(
    val text: String,
    val maxWidthPx: Double,
    val fontFamilies: Array<String>,
    val fontSizePx: Double,
    val lineHeightPx: Double,
    val locale: String,
    val fontWeight: Int,
    val italic: Boolean,
    val firstLineIndentIc: Double,
    val lineLengthGridEnabled: Boolean,
    val sourceBoundaries: Array<Int>,
    val textSpans: Array<TextSpanWireDto>,
    val inlineBoxes: Array<InlineBoxWireDto>,
    val lineBreakSpans: Array<LineBreakSpanWireDto>,
    val inlineObjects: Array<InlineObjectWireDto>,
    val decorations: Array<DecorationWireDto>,
    val emphasisDotGapEm: Double?,
    val renderEvidenceOverride: Boolean?,
)

data class TextSpanWireDto(
    val start: Int,
    val end: Int,
    val fontFamilies: Array<String>,
    val fontSize: Double,
    val fontWeight: Int,
    val italic: Boolean,
    val baselineShift: Double,
)

data class InlineBoxWireDto(
    val start: Int,
    val end: Int,
    val inlineStart: Double,
    val inlineEnd: Double,
    val outerSpacing: String,
)

data class LineBreakSpanWireDto(
    val start: Int,
    val end: Int,
    val policy: String,
)

data class InlineObjectWireDto(
    val start: Int,
    val end: Int,
    val advance: Double,
    val ascent: Double,
    val descent: Double,
)

data class DecorationWireDto(
    val start: Int,
    val end: Int,
    val kind: String,
)

data class SemanticSpanWireDto(
    val start: Int,
    val end: Int,
    val tagName: String,
    val attributes: Array<Array<String>>,
    val sourceIndex: Int,
    val order: Int,
)

data class RenderInlineBoxWireDto(
    val start: Int,
    val end: Int,
    val inlineStartPx: Double,
    val inlineEndPx: Double,
    val outerSpacing: String,
)

/**
 * DTOs for the JS boundary layout requests (ADR 0053 corrective wave 5).
 * These are @JsExport interfaces (not data classes) to survive worker postMessage
 * structured clone. Field types are restricted to String, Double, Int, Boolean,
 * Array<T> where T is primitive/String/nested @JsExport interface.
 * Nullable fields only where the current wire format encodes absence.
 */

@JsExport
interface WorkerLayoutRequest {
    val text: String
    val maxWidthPx: Double
    val fontFamilies: Array<String>
    val fontSizePx: Double
    val lineHeightPx: Double
    val locale: String
    val fontWeight: Int
    val italic: Boolean
    val firstLineIndentIc: Double
    val lineLengthGridEnabled: Boolean
    val sourceBoundaries: Array<Int>
    val textSpans: Array<TextSpanWire>
    val inlineBoxes: Array<InlineBoxWire>
    val lineBreakSpans: Array<LineBreakSpanWire>
    val inlineObjects: Array<InlineObjectWire>
    val renderEvidence: Boolean
    val semantics: Array<SemanticSpanWire>
    val renderInlineBoxes: Array<RenderInlineBoxWire>
    val sourceTag: String
}

@JsExport
interface PrepareParagraphRequest {
    val text: String
    val maxWidthPx: Double
    val fontFamilies: Array<String>
    val fontSizePx: Double
    val lineHeightPx: Double
    val locale: String
    val fontWeight: Int
    val italic: Boolean
    val firstLineIndentIc: Double
    val lineLengthGridEnabled: Boolean
    val sourceBoundaries: Array<Int>
    val textSpans: Array<TextSpanWire>
    val inlineBoxes: Array<InlineBoxWire>
    val lineBreakSpans: Array<LineBreakSpanWire>
    val inlineObjects: Array<InlineObjectWire>
    val decorations: Array<DecorationWire>
    val emphasisDotGapEm: Double?
    val renderEvidenceOverride: Boolean?
}

@JsExport
interface TextSpanWire {
    val start: Int
    val end: Int
    val fontFamilies: Array<String>
    val fontSize: Double
    val fontWeight: Int
    val italic: Boolean
    val baselineShift: Double
}

@JsExport
interface InlineBoxWire {
    val start: Int
    val end: Int
    val inlineStart: Double
    val inlineEnd: Double
    val outerSpacing: String
}

@JsExport
interface LineBreakSpanWire {
    val start: Int
    val end: Int
    val policy: String
}

@JsExport
interface InlineObjectWire {
    val start: Int
    val end: Int
    val advance: Double
    val ascent: Double
    val descent: Double
}

@JsExport
interface DecorationWire {
    val start: Int
    val end: Int
    val kind: String
}

@JsExport
interface SemanticSpanWire {
    val start: Int
    val end: Int
    val tagName: String
    val attributes: Array<Array<String>>
    val sourceIndex: Int
    val order: Int
}

@JsExport
interface RenderInlineBoxWire {
    val start: Int
    val end: Int
    val inlineStartPx: Double
    val inlineEndPx: Double
    val outerSpacing: String
}