package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import org.tiqian.core.positionedClusters

/**
 * Packed plan writer (corrective-2, ADR 0050/0053).
 *
 * The engine is the single writer for the packed plan (tiqian_plan_abi.h).
 * Little endian, per-column partitions, string pool with u32 deltas, f64 for
 * geometry. Mirrors `toPreparedParagraphJson` evidence gating so the production
 * buffer (renderEvidence = false) decodes to the same [Plan] defaults as the
 * JSON dump; the dump entry keeps JSON bytes for oracle.
 */
private const val PLAN_MAGIC: UInt = 0x54515050u
private const val PLAN_PROTOCOL_REVISION: UInt = 1u
private const val PLAN_STRING_ABSENT: UInt = 0xFFFFFFFFu

fun org.tiqian.core.LayoutResult.toPackedPlanBytes(): ByteArray =
    toPackedPlanBytesInternal(renderEvidence = false)

internal fun org.tiqian.core.LayoutResult.toPackedPlanBytesInternal(renderEvidence: Boolean): ByteArray {
    val naturalWidth = mutableMapOf<TextRange, Float>()
    val openTypeFeatures = mutableMapOf<TextRange, LinkedHashSet<String>>()
    val renderFontFamily = mutableMapOf<TextRange, String>()
    val glyphIdsByRange = mutableMapOf<TextRange, MutableList<UInt>>()
    for (run in glyphRuns) {
        for (glyph in run.glyphs) {
            naturalWidth[glyph.clusterRange] = (naturalWidth[glyph.clusterRange] ?: 0f) + glyph.advance
            if (run.openTypeFeatures.isNotEmpty()) {
                openTypeFeatures.getOrPut(glyph.clusterRange) { linkedSetOf() }.addAll(run.openTypeFeatures)
            }
            glyph.renderFontKey?.let { renderFontFamily[glyph.clusterRange] = it }
            glyphIdsByRange.getOrPut(glyph.clusterRange) { mutableListOf() }.add(glyph.id)
        }
    }
    val zeroWidthBreaks = debug.zeroWidthBreakDecisions.mapTo(mutableSetOf()) { it.range }
    val shapingDecisionByRange = debug.shapingDecisions.associateBy { it.range }
    val punctuationDecisionByRange = debug.punctuationDecisions.associateBy { it.range }
    val inlineStartByOffset = HashMap<Int, Float>()
    val inlineEndByOffset = HashMap<Int, Float>()
    for (box in input.inlineBoxes) {
        if (box.inlineStart != 0f) {
            inlineStartByOffset[box.range.start] = (inlineStartByOffset[box.range.start] ?: 0f) + box.inlineStart
        }
        if (box.inlineEnd != 0f) {
            inlineEndByOffset[box.range.end] = (inlineEndByOffset[box.range.end] ?: 0f) + box.inlineEnd
        }
    }
    val inlineObjectAdvanceByRange = input.inlineObjects.associate { it.range to it.advance }
    fun styleAt(offset: Int): org.tiqian.core.TextStyle =
        input.content.spans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style
            ?: input.textStyle

    data class PositionedCell(val cluster: Cluster, val drawX: Float, val naturalWidth: Float)

    val linesWithCells = lines.map { line ->
        val cells = positionedClusters(line).filter { positioned ->
            val cluster = clusters[positioned.clusterIndex]
            cluster.displayText.isNotEmpty() || cluster.range in zeroWidthBreaks ||
                (renderEvidence && cluster.range in inlineObjectAdvanceByRange)
        }.map { positioned ->
            val cluster = clusters[positioned.clusterIndex]
            PositionedCell(cluster, positioned.drawX, naturalWidth[cluster.range] ?: cluster.advance)
        }
        line to cells
    }
    val lineCount = linesWithCells.size
    val cellCount = linesWithCells.sumOf { it.second.size }

    val emphasisRanges = if (renderEvidence) input.decorations.filter { it.kind == org.tiqian.core.DecorationKind.Emphasis } else emptyList()
    val rubyDecisions = if (renderEvidence) debug.rubyDecisions else emptyList()
    val bopomofoDecisions = if (renderEvidence) debug.bopomofoDecisions else emptyList()
    val decorationSegments = if (renderEvidence) debug.decorationSegments.filter {
        it.kind == org.tiqian.core.DecorationKind.ProperNoun.name || it.kind == org.tiqian.core.DecorationKind.BookTitle.name
    } else emptyList()
    val emphasisDots = if (renderEvidence) debug.decorationDecisions.filter {
        it.applied && it.kind == org.tiqian.core.DecorationKind.Emphasis.name && it.dotDiameter > 0f
    } else emptyList()

    val inlineEdgeList: List<Pair<Int, Pair<Float?, Float?>>> = if (renderEvidence && (inlineStartByOffset.isNotEmpty() || inlineEndByOffset.isNotEmpty())) {
        val offsets = (inlineStartByOffset.keys + inlineEndByOffset.keys).toSet().sorted()
        offsets.map { offset -> offset to (inlineStartByOffset[offset] to inlineEndByOffset[offset]) }
    } else emptyList()

    val pool = StringPool()
    val cellFeatureLists = mutableListOf<List<String>>()
    var flatFeatureTotal = 0
    var rubyFamilyTotal = 0
    var bopomofoFamilyTotal = 0
    val bopomofoPlacementsTotal = bopomofoDecisions.sumOf { it.placements.size }

    data class CellWrite(
        val rangeStart: Int,
        val rangeEnd: Int,
        val sourceRef: UInt,
        val displayRef: UInt,
        val drawX: Double,
        val naturalWidth: Double,
        val leadingAdvance: Double,
        val shapingBoundary: Int,
        val latin: Int,
        val renderFamilyRef: UInt,
        val dashRef: UInt,
        val languageRef: UInt,
        val resolvedFaceRef: UInt,
        val glyphIdsRef: UInt,
        val evidenceRef: UInt,
        val inkFloor: Double,
        val bodyWidth: Double,
        val advance: Double,
        val inlineObject: Double,
        val styleFontSize: Double,
        val styleFontWeight: Double,
        val styleItalic: Int,
        val featureOffset: Int,
        val featureCount: Int,
    )
    val cellWrites = ArrayList<CellWrite>(cellCount)

    var flatFeatureCursor = 0
    for ((_, cells) in linesWithCells) {
        for (pc in cells) {
            val cluster = pc.cluster
            val sourceRef = pool.intern(cluster.text)
            val displayRef = pool.intern(cluster.displayText)
            val shaping = shapingDecisionByRange[cluster.range]
            val punct = punctuationDecisionByRange[cluster.range]
            var renderFamilyRef = PLAN_STRING_ABSENT
            var dashRef = PLAN_STRING_ABSENT
            var languageRef = PLAN_STRING_ABSENT
            var resolvedFaceRef = PLAN_STRING_ABSENT
            var glyphIdsRef = PLAN_STRING_ABSENT
            var evidenceRef = PLAN_STRING_ABSENT
            var inkFloor = Double.NaN
            var bodyWidth = Double.NaN
            var advance = Double.NaN
            var inlineObject = Double.NaN
            var styleFontSize = Double.NaN
            var styleFontWeight = Double.NaN
            var styleItalic = 2
            var latinInt = 0
            if (renderEvidence) {
                val latinFlag = org.tiqian.font.FontRole.LatinText.name ==
                    debug.fontDecisions.firstOrNull {
                        cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
                    }?.role
                latinInt = if (latinFlag) 1 else 0
                renderFontFamily[cluster.range]?.let { renderFamilyRef = pool.intern(it) }
                shaping?.let { sd ->
                    sd.strategy?.let { dashRef = pool.intern(it) }
                    sd.language?.let { languageRef = pool.intern(it) }
                    sd.resolvedFace?.let { resolvedFaceRef = pool.intern(it) }
                    glyphIdsByRange[cluster.range]?.takeIf { it.isNotEmpty() }?.let { ids ->
                        glyphIdsRef = pool.intern(ids.joinToString(","))
                    }
                    evidenceRef = pool.intern(sd.reason)
                }
                if (punct?.inkContainmentApplied == true) {
                    punct.inkContainmentBodyFloor?.let { inkFloor = it.toDouble() }
                    bodyWidth = punct.bodyWidth.toDouble()
                }
                inlineObjectAdvanceByRange[cluster.range]?.let { inlineObject = it.toDouble() }
                val gw = if (inlineObject.isFinite()) inlineObject else pc.naturalWidth.toDouble()
                if (cluster.advance.toDouble() != gw) advance = cluster.advance.toDouble()
                val clusterStyle = styleAt(cluster.range.start)
                if (clusterStyle != input.textStyle) {
                    if (clusterStyle.fontSize != input.textStyle.fontSize) styleFontSize = clusterStyle.fontSize.toDouble()
                    if (clusterStyle.fontWeight != input.textStyle.fontWeight) styleFontWeight = clusterStyle.fontWeight.toDouble()
                    if (clusterStyle.italic != input.textStyle.italic) styleItalic = if (clusterStyle.italic) 1 else 0
                }
            }
            val features = openTypeFeatures[cluster.range]?.toList() ?: emptyList()
            for (f in features) pool.intern(f)
            val featureOffset = flatFeatureCursor
            val featureCount = features.size
            cellFeatureLists.add(features)
            flatFeatureCursor += featureCount
            cellWrites.add(
                CellWrite(
                    rangeStart = cluster.range.start,
                    rangeEnd = cluster.range.end,
                    sourceRef = sourceRef,
                    displayRef = displayRef,
                    drawX = pc.drawX.toDouble(),
                    naturalWidth = pc.naturalWidth.toDouble(),
                    leadingAdvance = cluster.leadingLayoutAdvance.toDouble(),
                    shapingBoundary = if (cluster.range.end - cluster.range.start > 1) 1 else 0,
                    latin = latinInt,
                    renderFamilyRef = renderFamilyRef,
                    dashRef = dashRef,
                    languageRef = languageRef,
                    resolvedFaceRef = resolvedFaceRef,
                    glyphIdsRef = glyphIdsRef,
                    evidenceRef = evidenceRef,
                    inkFloor = inkFloor,
                    bodyWidth = bodyWidth,
                    advance = advance,
                    inlineObject = inlineObject,
                    styleFontSize = styleFontSize,
                    styleFontWeight = styleFontWeight,
                    styleItalic = styleItalic,
                    featureOffset = featureOffset,
                    featureCount = featureCount,
                )
            )
        }
    }
    flatFeatureTotal = flatFeatureCursor

    val rubyFamilyOffsets = IntArray(rubyDecisions.size)
    val rubyFamilyCounts = IntArray(rubyDecisions.size)
    var rubyFamilyCursor = 0
    for ((idx, ruby) in rubyDecisions.withIndex()) {
        for (fam in ruby.fontFamilies) pool.intern(fam)
        pool.intern(ruby.text)
        rubyFamilyOffsets[idx] = rubyFamilyCursor
        rubyFamilyCounts[idx] = ruby.fontFamilies.size
        rubyFamilyCursor += ruby.fontFamilies.size
    }
    rubyFamilyTotal = rubyFamilyCursor

    val bopomofoFamilyOffsets = IntArray(bopomofoDecisions.size)
    val bopomofoFamilyCounts = IntArray(bopomofoDecisions.size)
    val bopomofoPlaceOffsets = IntArray(bopomofoDecisions.size)
    val bopomofoPlaceCounts = IntArray(bopomofoDecisions.size)
    var bopomofoFamCursor = 0
    var bopoPlaceCursor = 0
    for ((idx, bopo) in bopomofoDecisions.withIndex()) {
        for (fam in bopo.fontFamilies) pool.intern(fam)
        pool.intern(bopo.text)
        bopomofoFamilyOffsets[idx] = bopomofoFamCursor
        bopomofoFamilyCounts[idx] = bopo.fontFamilies.size
        bopomofoFamCursor += bopo.fontFamilies.size
        bopomofoPlaceOffsets[idx] = bopoPlaceCursor
        bopomofoPlaceCounts[idx] = bopo.placements.size
        bopoPlaceCursor += bopo.placements.size
        for (pl in bopo.placements) {
            pool.intern(pl.text)
            pool.intern(pl.role.name)
        }
    }
    bopomofoFamilyTotal = bopomofoFamCursor

    for (seg in decorationSegments) pool.intern(seg.kind)

    val writer = PackedWriter()
    writer.u32(PLAN_MAGIC)
    writer.u32(PLAN_PROTOCOL_REVISION)
    writer.f64(input.constraints.maxWidth.toDouble())
    writer.f64(size.height.toDouble())
    writer.f64(if (renderEvidence) input.textStyle.fontSize.toDouble() else Double.NaN)
    writer.f64(if (renderEvidence) size.width.toDouble() else Double.NaN)
    writer.u32(lineCount.toUInt())
    writer.u32(cellCount.toUInt())
    writer.u32(emphasisRanges.size.toUInt())
    writer.u32(inlineEdgeList.size.toUInt())
    writer.u32(rubyDecisions.size.toUInt())
    writer.u32(bopomofoDecisions.size.toUInt())
    writer.u32(bopomofoPlacementsTotal.toUInt())
    writer.u32(decorationSegments.size.toUInt())
    writer.u32(emphasisDots.size.toUInt())
    writer.u32(pool.size.toUInt())
    writer.u32(flatFeatureTotal.toUInt())
    writer.u32(rubyFamilyTotal.toUInt())
    writer.u32(bopomofoFamilyTotal.toUInt())

    for (s in pool.ordered) {
        writer.u32(s.encodeToByteArray().size.toUInt())
    }
    for (s in pool.ordered) {
        writer.bytes(s.encodeToByteArray())
    }

    for ((line, _) in linesWithCells) writer.i32(line.range.start)
    for ((line, _) in linesWithCells) writer.i32(line.range.end)
    for ((line, _) in linesWithCells) writer.f64(line.top.toDouble())
    for ((line, _) in linesWithCells) writer.f64(line.bottom.toDouble())
    for ((line, _) in linesWithCells) writer.f64(line.baseline.toDouble())
    for ((line, _) in linesWithCells) writer.f64(line.indent.toDouble())
    for ((line, _) in linesWithCells) writer.f64(line.visualWidth.toDouble())
    for ((line, _) in linesWithCells) writer.f64(line.hyphenAdvance.toDouble())
    for ((line, _) in linesWithCells) {
        writer.u8(
            when (line.endReason) {
                org.tiqian.core.LineEndReason.AutoWrap -> 0
                org.tiqian.core.LineEndReason.MandatoryBreak -> 1
                org.tiqian.core.LineEndReason.ParagraphEnd -> 2
            }
        )
    }
    for ((_, cells) in linesWithCells) writer.u32(cells.size.toUInt())

    for (c in cellWrites) writer.i32(c.rangeStart)
    for (c in cellWrites) writer.i32(c.rangeEnd)
    for (c in cellWrites) writer.u32(c.sourceRef)
    for (c in cellWrites) writer.u32(c.displayRef)
    for (c in cellWrites) writer.f64(c.drawX)
    for (c in cellWrites) writer.f64(c.naturalWidth)
    for (c in cellWrites) writer.f64(c.leadingAdvance)
    for (c in cellWrites) writer.u8(c.shapingBoundary)
    for (c in cellWrites) writer.u8(c.latin)
    for (c in cellWrites) writer.u32(c.renderFamilyRef)
    for (c in cellWrites) writer.u32(c.dashRef)
    for (c in cellWrites) writer.u32(c.languageRef)
    for (c in cellWrites) writer.u32(c.resolvedFaceRef)
    for (c in cellWrites) writer.u32(c.glyphIdsRef)
    for (c in cellWrites) writer.u32(c.evidenceRef)
    for (c in cellWrites) writer.f64(c.inkFloor)
    for (c in cellWrites) writer.f64(c.bodyWidth)
    for (c in cellWrites) writer.f64(c.advance)
    for (c in cellWrites) writer.f64(c.inlineObject)
    for (c in cellWrites) writer.f64(c.styleFontSize)
    for (c in cellWrites) writer.f64(c.styleFontWeight)
    for (c in cellWrites) writer.u8(c.styleItalic)
    for (c in cellWrites) writer.u32(c.featureOffset.toUInt())
    for (c in cellWrites) writer.u32(c.featureCount.toUInt())

    for (list in cellFeatureLists) {
        for (feat in list) writer.u32(pool.indexOf(feat))
    }

    for (r in emphasisRanges) writer.f64(r.range.start.toDouble())
    for (r in emphasisRanges) writer.f64(r.range.end.toDouble())

    for ((off, pair) in inlineEdgeList) writer.f64(off.toDouble())
    for ((_, pair) in inlineEdgeList) writer.f64(pair.first?.toDouble() ?: Double.NaN)
    for ((_, pair) in inlineEdgeList) writer.f64(pair.second?.toDouble() ?: Double.NaN)

    for (r in rubyDecisions) writer.i32(r.baseRange.start)
    for (r in rubyDecisions) writer.i32(r.baseRange.end)
    for (r in rubyDecisions) writer.u32(pool.indexOf(r.text))
    for (r in rubyDecisions) writer.f64(r.centerX.toDouble())
    for (r in rubyDecisions) writer.f64(r.baselineY.toDouble())
    for (r in rubyDecisions) writer.f64(r.fontSize.toDouble())
    for (r in rubyDecisions) writer.f64(r.fontWeight.toDouble())
    for (idx in rubyDecisions.indices) writer.u32(rubyFamilyOffsets[idx].toUInt())
    for (idx in rubyDecisions.indices) writer.u32(rubyFamilyCounts[idx].toUInt())
    for (r in rubyDecisions) writer.f64(r.ascent.toDouble())

    for (r in rubyDecisions) {
        for (fam in r.fontFamilies) writer.u32(pool.indexOf(fam))
    }

    for (b in bopomofoDecisions) writer.i32(b.baseRange.start)
    for (b in bopomofoDecisions) writer.i32(b.baseRange.end)
    for (b in bopomofoDecisions) writer.u32(pool.indexOf(b.text))
    for (b in bopomofoDecisions) writer.f64(b.fontWeight.toDouble())
    for (idx in bopomofoDecisions.indices) writer.u32(bopomofoFamilyOffsets[idx].toUInt())
    for (idx in bopomofoDecisions.indices) writer.u32(bopomofoFamilyCounts[idx].toUInt())
    for (idx in bopomofoDecisions.indices) writer.u32(bopomofoPlaceOffsets[idx].toUInt())
    for (idx in bopomofoDecisions.indices) writer.u32(bopomofoPlaceCounts[idx].toUInt())

    for (b in bopomofoDecisions) {
        for (fam in b.fontFamilies) writer.u32(pool.indexOf(fam))
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.u32(pool.indexOf(pl.text))
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.u32(pool.indexOf(pl.role.name))
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.f64(pl.left.toDouble())
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.f64(pl.top.toDouble())
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.f64(pl.width.toDouble())
    }
    for (b in bopomofoDecisions) {
        for (pl in b.placements) writer.f64(pl.height.toDouble())
    }

    for (s in decorationSegments) writer.u32(pool.indexOf(s.kind))
    for (s in decorationSegments) writer.f64(s.left.toDouble())
    for (s in decorationSegments) writer.f64(s.top.toDouble())
    for (s in decorationSegments) writer.f64(s.right.toDouble())

    for (d in emphasisDots) writer.f64(d.clusterRange.start.toDouble())
    for (d in emphasisDots) writer.f64(d.anchorX.toDouble())
    for (d in emphasisDots) writer.f64(d.anchorY.toDouble())
    for (d in emphasisDots) writer.f64(d.dotDiameter.toDouble())

    return writer.toByteArray()
}

private class StringPool {
    val ordered = mutableListOf<String>()
    private val indexMap = HashMap<String, UInt>()
    fun intern(value: String): UInt {
        indexMap[value]?.let { return it }
        val idx = ordered.size.toUInt()
        ordered.add(value)
        indexMap[value] = idx
        return idx
    }
    fun indexOf(value: String): UInt = indexMap[value] ?: error("string not interned: $value")
    val size: Int get() = ordered.size
}

private class PackedWriter {
    private val bytes = mutableListOf<Byte>()
    fun u32(v: UInt) { bytes.addAll(v.toLeBytes().toList()) }
    fun i32(v: Int) = u32(v.toUInt())
    fun f64(v: Double) { bytes.addAll(v.toRawBitsToBytes().toList()) }
    fun u8(v: Int) { bytes.add(v.toByte()) }
    fun bytes(arr: ByteArray) { bytes.addAll(arr.toList()) }
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun UInt.toLeBytes(): ByteArray = byteArrayOf(
    (this and 0xFFu).toByte(),
    ((this shr 8) and 0xFFu).toByte(),
    ((this shr 16) and 0xFFu).toByte(),
    ((this shr 24) and 0xFFu).toByte(),
)

private fun ULong.toLeBytes(): ByteArray = byteArrayOf(
    (this and 0xFFu).toByte(),
    ((this shr 8) and 0xFFu).toByte(),
    ((this shr 16) and 0xFFu).toByte(),
    ((this shr 24) and 0xFFu).toByte(),
    ((this shr 32) and 0xFFu).toByte(),
    ((this shr 40) and 0xFFu).toByte(),
    ((this shr 48) and 0xFFu).toByte(),
    ((this shr 56) and 0xFFu).toByte(),
)

private fun Double.toRawBitsToBytes(): ByteArray = this.toBits().toULong().toLeBytes()
