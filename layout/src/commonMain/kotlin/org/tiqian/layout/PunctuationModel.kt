package org.tiqian.layout

import org.tiqian.clreq.ClreqPunctuationPolicies
import org.tiqian.clreq.GlueSide
import org.tiqian.clreq.PunctuationClass
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.glueSideFor
import org.tiqian.core.Rect
import org.tiqian.core.TextRange

data class PunctuationAtom(
    val range: TextRange,
    val char: Char,
    val punctuationClass: PunctuationClass,
    val advance: Float,
    val inkBounds: Rect?,
    val bodyWidth: Float,
    /** Font-measured `halt` advance used to request the compressed box; null = ink/policy path. */
    val haltAdvance: Float? = null,
    /**
     * Non-null when the default glyph's ink bounds prevent faithfully replaying
     * the font's requested `halt` trim without drawing into a neighbour.
     */
    val haltValidation: String? = null,
    val leadingGlue: Glue,
    val trailingGlue: Glue,
    val anchor: PunctuationAnchor,
    val geometrySource: String,
    val policyBodyFloor: Float,
    val inkWidth: Float?,
    val inkCenter: Float?,
    /** Minimum body left after all removable sidebearings have been consumed. */
    val inkContainmentBodyFloor: Float?,
    /** Named `InkContainmentBodyFloor` decision; false when policy/halt already suffices. */
    val inkContainmentApplied: Boolean,
    /** `MissingInkBoundsFallback` reason; see [PunctuationInkInput.boundsFallbackReason]. */
    val inkBoundsFallback: String?,
    /**
     * `UnderwidthPunctuationAdvanceExpansion`: layout advance added when the
     * shaped glyph is narrower than the CLREQ-required punctuation box.
     */
    val advanceExpansion: Float,
    /** Placement of an underwidth font-owned glyph box inside a synthesized full-width cell. */
    val glyphInlineShift: Float,
    /** Named placement heuristic, null when [glyphInlineShift] is zero. */
    val glyphPlacementReason: String?,
    /** Glue consumed before line breaking by a fixed-half punctuation style. */
    val leadingGlueInitiallyConsumed: Float = 0f,
    /** Glue consumed before line breaking by a fixed-half punctuation style. */
    val trailingGlueInitiallyConsumed: Float = 0f,
)

data class PunctuationInkInput(
    val advance: Float,
    val inkBounds: Rect? = null,
    /**
     * Font-measured OpenType `halt` advance — the font designer's requested
     * compressed advance for this mark.
     */
    val haltAdvance: Float? = null,
    /**
     * The x placement shift the font applies under `halt` (-0.5em for
     * opening brackets, -0.25em for centred marks, 0 for closing/stop).
     * Together with [haltAdvance], this directly defines the leading and
     * trailing compression budgets.
     */
    val haltPlacementX: Float? = null,
    /**
     * Named heuristic: `MissingInkBoundsFallback`.
     *
     * Non-null exactly when shaping ran but [inkBounds] is absent; explains
     * why so the dump can distinguish "shaper gave no ink" from "no shaping
     * at all". Known reason codes:
     *
     * - `shaper-no-ink-bounds` — the shaper resolved a glyph but reported
     *   empty visual bounds (blank glyph, or font without outlines).
     * - `glyph-cluster-mapping-ambiguous` — glyph count does not match the
     *   cluster's display characters, so per-character ink cannot be
     *   attributed; geometry falls back to pure policy ([advance] is unset).
     *
     * Missing ink disables font-geometry-derived compression; a named profile
     * fallback remains available for shapers that cannot expose glyph bounds.
     */
    val boundsFallbackReason: String? = null,
)

enum class PunctuationAnchor {
    Leading,
    Center,
    Trailing,
}

data class Glue(
    val kind: GlueKind,
    val min: Float,
    val natural: Float,
    val max: Float,
    val priority: Int,
    val penalty: Int,
) {
    init {
        require(min <= natural) { "Glue min must not exceed natural." }
        require(natural <= max) { "Glue natural must not exceed max." }
    }
}

enum class GlueKind {
    PunctuationLeading,
    PunctuationTrailing,
    CjkLatinSpace,
    WordSpace,
    CjkInterChar,
    InlineObjectPunctuationTrailing,
    InlineObjectRelation,
    InlineObjectBinaryOperator,
    InlineObjectBoundary,
}

data class AdjustmentOpportunity(
    val range: TextRange,
    val glue: Glue,
)

data class PunctuationSpacingAdjustment(
    val range: TextRange,
    val reductionTargetRange: TextRange,
    val leftChar: Char,
    val rightChar: Char,
    val naturalInnerGlue: Float,
    val adjustedInnerGlue: Float,
    val reduction: Float,
    val reason: String,
)

data class PunctuationSpacingCompressionResult(
    val adjustments: List<PunctuationSpacingAdjustment>,
) {
    val totalReduction: Float =
        adjustments.sumOf { it.reduction.toDouble() }.toFloat()
}

class PunctuationSpacingCompressor {
    /**
     * Named heuristic: `CollapseAdjacentPunctuationInnerGlue`.
     *
     * CLREQ rule for two adjacent half-width punctuation marks: the visible
     * inner gap is collapsed by **one half-em** (capped at zero, never
     * negative). The previous halving implementation (`natural / 2`) was
     * wrong for several common pairs — see ADR 0014 amendment notes.
     *
     * Walking through the class-derived glue cases at fontSize=16:
     * - `」。` closing+closing → natural inner = trailing(8) + leading(0) = 8.
     *   Halving says adjusted=4; CLREQ says **0** (bodies touch). The
     *   half-em subtraction gives `max(0, 8 - 8) = 0` ✓.
     * - `「（` opening+opening → natural inner = trailing(0) + leading(8) = 8.
     *   Same as above → 0 ✓.
     * - `。「` closing+opening → natural inner = 8 + 8 = 16. CLREQ says **8**
     *   (half-em gap remains). `max(0, 16 - 8) = 8` ✓.
     * - `。「」` chain handled per pair via `zipWithNext`.
     *
     * The collapse amount (`emHalf`) is supplied by the caller because the
     * atom alone doesn't know its design em — `atom.advance` reflects the
     * shaped advance, not the design em box.
     *
     * Consecutive PauseOrStop marks (`！！` `？！`…) compress like any other
     * adjacent pair — this is expected MainlandSimplified horizontal
     * behaviour. The audit doc's deferred item is only the dedicated
     * two-em-width strategy for `！！！`/`？？？` runs, not the per-pair
     * collapse itself.
     */
    fun compress(atoms: List<PunctuationAtom>, em: Float): PunctuationSpacingCompressionResult {
        if (atoms.size < 2) return PunctuationSpacingCompressionResult(emptyList())
        val emHalf = em / 2f

        val adjustments = atoms.zipWithNext().mapNotNull { (left, right) ->
            if (left.range.end != right.range.start) return@mapNotNull null

            val leftTrailing = (left.trailingGlue.natural - left.trailingGlueInitiallyConsumed)
                .coerceAtLeast(0f)
            val rightLeading = (right.leadingGlue.natural - right.leadingGlueInitiallyConsumed)
                .coerceAtLeast(0f)
            val naturalInnerGlue = leftTrailing + rightLeading
            if (naturalInnerGlue <= 0f) return@mapNotNull null

            val adjustedInnerGlue = (naturalInnerGlue - emHalf).coerceAtLeast(0f)
            val reduction = naturalInnerGlue - adjustedInnerGlue
            if (reduction <= 0f) return@mapNotNull null

            PunctuationSpacingAdjustment(
                range = TextRange(left.range.start, right.range.end),
                reductionTargetRange = if (leftTrailing >= rightLeading) {
                    left.range
                } else {
                    right.range
                },
                leftChar = left.char,
                rightChar = right.char,
                naturalInnerGlue = naturalInnerGlue,
                adjustedInnerGlue = adjustedInnerGlue,
                reduction = reduction,
                reason = "collapse-adjacent-punctuation-inner-glue",
            )
        }

        return PunctuationSpacingCompressionResult(adjustments)
    }

    /**
     * Named heuristic: `CollapseCjkClosingBeforeAsciiPointMark`.
     *
     * ASCII point marks are deliberately shaped by the Latin face and therefore
     * do not become [PunctuationAtom]s. They still form a punctuation boundary
     * with an immediately preceding CJK closing mark: in `」,` the closing
     * mark's trailing half-em must be consumed instead of appearing as a blank
     * between the two glyph bodies. Only the CJK mark's own glue is reduced;
     * the ASCII point mark keeps its source character, role, glyph and advance.
     */
    fun compressCjkClosingBeforeAsciiPointMark(
        atoms: List<PunctuationAtom>,
        text: String,
        em: Float,
    ): PunctuationSpacingCompressionResult {
        val emHalf = em / 2f
        val adjustments = atoms.mapNotNull { left ->
            if (left.punctuationClass != PunctuationClass.Closing) return@mapNotNull null
            val rightChar = text.getOrNull(left.range.end) ?: return@mapNotNull null
            if (!ClreqPunctuationPolicies.isAsciiPointMark(rightChar)) return@mapNotNull null

            val naturalInnerGlue = (left.trailingGlue.natural - left.trailingGlueInitiallyConsumed)
                .coerceAtLeast(0f)
            if (naturalInnerGlue <= 0f) return@mapNotNull null
            val adjustedInnerGlue = (naturalInnerGlue - emHalf).coerceAtLeast(0f)
            val reduction = naturalInnerGlue - adjustedInnerGlue
            if (reduction <= 0f) return@mapNotNull null

            PunctuationSpacingAdjustment(
                range = TextRange(left.range.start, left.range.end + 1),
                reductionTargetRange = left.range,
                leftChar = left.char,
                rightChar = rightChar,
                naturalInnerGlue = naturalInnerGlue,
                adjustedInnerGlue = adjustedInnerGlue,
                reduction = reduction,
                reason = "collapse-cjk-closing-before-ascii-point-mark",
            )
        }
        return PunctuationSpacingCompressionResult(adjustments)
    }
}

class PunctuationAtomBuilder(
    private val gluePlacement: PunctuationGluePlacement = PunctuationGluePlacement.MainlandSimplified,
    private val widthPolicy: org.tiqian.clreq.PunctuationWidthPolicy =
        org.tiqian.clreq.PunctuationWidthPolicy(),
) {
    fun build(text: String, index: Int, em: Float): PunctuationAtom? {
        val char = text.getOrNull(index) ?: return null
        return build(
            char = char,
            range = TextRange(index, index + 1),
            em = em,
        )
    }

    /**
     * Builds the punctuation box from font evidence before consulting a regional fallback.
     *
     * `FontHaltFittedBodyCompression` is authoritative when both `halt` advance and
     * placement are available. Otherwise `InkBoundsFittedBodyCompression` tests the
     * leading, centred, and trailing policy-width boxes, selects the smallest one that
     * contains the original ink, and preserves every safety margin inside that box.
     *
     * `ProfileGlueFallbackWithoutFontGeometry` is used only when neither source can state
     * the sides. It keeps stub and capability-limited shapers explicit and deterministic.
     */
    fun build(
        char: Char,
        range: TextRange,
        em: Float,
        inkInput: PunctuationInkInput? = null,
        gluePlacement: PunctuationGluePlacement = this.gluePlacement,
        widthPolicy: org.tiqian.clreq.PunctuationWidthPolicy = this.widthPolicy,
    ): PunctuationAtom? {
        val policy = ClreqPunctuationPolicies.policyFor(char)
        if (policy.punctuationClass == PunctuationClass.Other) return null

        val policyAdvance = policy.defaultAdvanceEm * em
        val shapedAdvance = inkInput?.advance?.takeIf { it > 0f }
        val rawGlyphAdvance = shapedAdvance ?: policyAdvance
        val rawInkBounds = inkInput?.inkBounds
        val policyExpansion = (policyAdvance - rawGlyphAdvance).coerceAtLeast(0f)
        // `UnderwidthPunctuationFullWidthBoxPlacement`: after `fwid` still
        // shapes underwidth, place the intact proportional glyph box inside a
        // synthesized full-width cell. Opening marks receive the missing width
        // before their box, closing marks after it, and centred conventions
        // split it. Compression later removes glue from this completed cell; it
        // does not reposition ink inside the font-owned proportional box.
        val synthesizedFullWidthPlacement = if (shapedAdvance != null && policyExpansion > 0f) {
            when (gluePlacement.glueSideFor(policy.punctuationClass)) {
                GlueSide.LeadingOnly -> policyExpansion
                GlueSide.BothSides -> policyExpansion / 2f
                GlueSide.TrailingOnly -> 0f
            }
        } else {
            0f
        }
        val inkBounds = rawInkBounds?.shiftInline(synthesizedFullWidthPlacement)
        val inkWidth = inkBounds?.width?.coerceAtLeast(0f)
        val inkCenter = inkBounds?.let { (it.left + it.right) / 2f }
        val advance = maxOf(rawGlyphAdvance, policyAdvance, inkBounds?.right ?: 0f)
        val advanceExpansion = (advance - rawGlyphAdvance).coerceAtLeast(0f)
        val policyBodyFloor = policy.defaultBodyEm * em
        // A `halt` measured from an already-proportional glyph is not a
        // half-width form of the synthesized full-width cell. Only accept it
        // when shaping actually produced the policy-width natural box.
        val haltBody = inkInput?.haltAdvance?.takeIf {
            policyExpansion <= PLACEMENT_EPSILON && it > 0f && it < advance
        }
        val forcedHalf = ClreqPunctuationPolicies.forcedHalfWidth(char, widthPolicy)
        val compression = compressionGeometry(
            advance = advance,
            rawGlyphAdvance = rawGlyphAdvance,
            targetBody = haltBody ?: if (forcedHalf) minOf(policyBodyFloor, 0.5f * em) else policyBodyFloor,
            inkBounds = inkBounds,
            haltBody = haltBody,
            haltPlacementX = inkInput?.haltPlacementX,
            punctuationClass = policy.punctuationClass,
            gluePlacement = gluePlacement,
        )
        val leadingInitiallyConsumed = if (forcedHalf) compression.leadingTrim else 0f
        val trailingInitiallyConsumed = if (forcedHalf) compression.trailingTrim else 0f

        return PunctuationAtom(
            range = range,
            char = char,
            punctuationClass = policy.punctuationClass,
            advance = advance,
            inkBounds = inkBounds,
            bodyWidth = compression.bodyWidth,
            haltAdvance = haltBody,
            haltValidation = compression.haltValidation,
            leadingGlue = Glue(
                kind = GlueKind.PunctuationLeading,
                min = 0f,
                natural = compression.leadingTrim,
                max = compression.leadingTrim,
                priority = 0,
                penalty = 0,
            ),
            trailingGlue = Glue(
                kind = GlueKind.PunctuationTrailing,
                min = 0f,
                natural = compression.trailingTrim,
                max = compression.trailingTrim,
                priority = 0,
                penalty = 0,
            ),
            leadingGlueInitiallyConsumed = leadingInitiallyConsumed,
            trailingGlueInitiallyConsumed = trailingInitiallyConsumed,
            anchor = compression.anchor,
            geometrySource = if (forcedHalf) {
                "${compression.source}FixedHalfWidth"
            } else {
                compression.source
            },
            policyBodyFloor = policyBodyFloor,
            inkWidth = inkWidth,
            inkCenter = inkCenter,
            inkContainmentBodyFloor = compression.inkBodyFloor,
            inkContainmentApplied = compression.inkContainmentApplied,
            // MissingInkBoundsFallback: only meaningful when shaping ran but
            // produced no usable ink bounds for this character.
            inkBoundsFallback = if (inkBounds == null) inkInput?.boundsFallbackReason else null,
            advanceExpansion = advanceExpansion,
            glyphInlineShift = synthesizedFullWidthPlacement,
            glyphPlacementReason = synthesizedFullWidthPlacement.takeIf { it != 0f }
                ?.let { "UnderwidthPunctuationFullWidthBoxPlacement" },
        )
    }

    private fun Rect.shiftInline(amount: Float): Rect =
        if (amount == 0f) this else Rect(left + amount, top, right + amount, bottom)

    private fun compressionGeometry(
        advance: Float,
        rawGlyphAdvance: Float,
        targetBody: Float,
        inkBounds: Rect?,
        haltBody: Float?,
        haltPlacementX: Float?,
        punctuationClass: PunctuationClass,
        gluePlacement: PunctuationGluePlacement,
    ): CompressionGeometry {
        val requestedReduction = (advance - targetBody).coerceAtLeast(0f)
        if (haltBody != null && haltPlacementX != null && haltPlacementX.isFinite()) {
            // halt x is measured in the raw glyph box. Any policy expansion is
            // trailing-only because the default glyph origin was never moved.
            val rawReduction = (rawGlyphAdvance - haltBody).coerceAtLeast(0f)
            val requestedLeading = (-haltPlacementX).coerceIn(0f, rawReduction)
                .takeIf { it > PLACEMENT_EPSILON } ?: 0f
            val requestedTrailing = (requestedReduction - requestedLeading).coerceAtLeast(0f)
            val leading = inkBounds?.let { minOf(requestedLeading, it.left.coerceAtLeast(0f)) }
                ?: requestedLeading
            val trailing = inkBounds?.let { minOf(requestedTrailing, (advance - it.right).coerceAtLeast(0f)) }
                ?: requestedTrailing
            val limited = leading + PLACEMENT_EPSILON < requestedLeading ||
                trailing + PLACEMENT_EPSILON < requestedTrailing
            return CompressionGeometry(
                leadingTrim = leading,
                trailingTrim = trailing,
                bodyWidth = advance - leading - trailing,
                anchor = anchorFor(leading, trailing),
                source = "FontHaltFittedBodyCompression",
                inkBodyFloor = inkBounds?.let { advance - leading - trailing },
                inkContainmentApplied = limited,
                haltValidation = if (limited) "halt-trim-limited-by-default-ink-bounds" else null,
            )
        }

        if (inkBounds != null) {
            val frame = fittedBodyFrame(
                advance = advance,
                targetBody = targetBody.coerceIn(0f, advance),
                inkBounds = inkBounds,
            )
            return CompressionGeometry(
                leadingTrim = frame.start,
                trailingTrim = (advance - frame.start - frame.width).coerceAtLeast(0f),
                bodyWidth = frame.width,
                anchor = frame.anchor,
                source = if (haltBody != null) {
                    "FontHaltAdvanceWithInkBoundsFittedPlacement"
                } else {
                    "InkBoundsFittedBodyCompression"
                },
                inkBodyFloor = frame.width,
                inkContainmentApplied = frame.width > targetBody + PLACEMENT_EPSILON,
                haltValidation = null,
            )
        }

        val (leading, trailing) = classBasedGlue(
            punctuationClass = punctuationClass,
            totalGlue = requestedReduction,
            gluePlacement = gluePlacement,
        )
        return CompressionGeometry(
            leadingTrim = leading,
            trailingTrim = trailing,
            bodyWidth = advance - leading - trailing,
            anchor = anchorFor(leading, trailing),
            source = if (haltBody != null) {
                "FontHaltAdvanceWithProfileFallback"
            } else {
                "ProfileGlueFallbackWithoutFontGeometry"
            },
            inkBodyFloor = null,
            inkContainmentApplied = false,
            haltValidation = null,
        )
    }

    /**
     * `InkBoundsFittedBodyCompression` keeps the glyph at its font-defined
     * position. For each canonical placement it expands the target body only as
     * much as required to contain the original ink, then picks the narrowest
     * result. Equal-width candidates are resolved by the closest body centre.
     */
    private fun fittedBodyFrame(
        advance: Float,
        targetBody: Float,
        inkBounds: Rect,
    ): BodyFrame {
        val leadingWidth = maxOf(targetBody, inkBounds.right).coerceIn(targetBody, advance)
        val trailingWidth = maxOf(targetBody, advance - inkBounds.left).coerceIn(targetBody, advance)
        val centeredWidth = maxOf(
            targetBody,
            advance - 2f * inkBounds.left,
            2f * inkBounds.right - advance,
        ).coerceIn(targetBody, advance)
        val candidates = listOf(
            BodyFrame(PunctuationAnchor.Leading, 0f, leadingWidth),
            BodyFrame(PunctuationAnchor.Center, (advance - centeredWidth) / 2f, centeredWidth),
            BodyFrame(PunctuationAnchor.Trailing, advance - trailingWidth, trailingWidth),
        )
        val inkCenter = (inkBounds.left + inkBounds.right) / 2f
        return candidates.minWith(
            compareBy<BodyFrame> { it.width }
                .thenBy { kotlin.math.abs((it.start + it.width / 2f) - inkCenter) }
                .thenBy { it.anchor.ordinal },
        )
    }

    private fun anchorFor(leadingTrim: Float, trailingTrim: Float): PunctuationAnchor =
        when {
            leadingTrim > PLACEMENT_EPSILON && trailingTrim > PLACEMENT_EPSILON -> PunctuationAnchor.Center
            leadingTrim > PLACEMENT_EPSILON -> PunctuationAnchor.Trailing
            trailingTrim > PLACEMENT_EPSILON -> PunctuationAnchor.Leading
            else -> PunctuationAnchor.Center
        }

    private fun classBasedGlue(
        punctuationClass: PunctuationClass,
        totalGlue: Float,
        gluePlacement: PunctuationGluePlacement,
    ): Pair<Float, Float> =
        when (gluePlacement.glueSideFor(punctuationClass)) {
            GlueSide.LeadingOnly -> totalGlue to 0f
            GlueSide.TrailingOnly -> 0f to totalGlue
            GlueSide.BothSides -> {
                val sideGlue = totalGlue / 2f
                sideGlue to sideGlue
            }
        }

    private data class BodyFrame(
        val anchor: PunctuationAnchor,
        val start: Float,
        val width: Float,
    )

    private data class CompressionGeometry(
        val leadingTrim: Float,
        val trailingTrim: Float,
        val bodyWidth: Float,
        val anchor: PunctuationAnchor,
        val source: String,
        val inkBodyFloor: Float?,
        val inkContainmentApplied: Boolean,
        val haltValidation: String?,
    )

    private companion object {
        private const val PLACEMENT_EPSILON = 0.001f
    }
}
