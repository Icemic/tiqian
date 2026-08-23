package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.font.FontRole

/**
 * Justifier — distributes a line's deficit (maxWidth - adjustedWidth) across
 * glue resources in CLREQ's expansion order（拉伸处理的优先顺序）:
 *
 *   0. ProgressiveTechnicalWhitespace — a bounded addition on source-authored
 *                          spaces inside links/inline code. Break-only technical
 *                          boundaries never participate in justification.
 *   1. WordSpace         — space inside Latin runs（西文词距，CLREQ 第一档）.
 *                          Word spaces are standalone clusters after
 *                          `LatinWordSegmentation`; all instances in a line
 *                          stretch simultaneously by equal amounts.
 *   2. CjkLatinSpace     — the sino-western gap（中西间距）: stretches from
 *                          the autospace base (0.25em) by up to another
 *                          0.25em — total 0.5em, CLREQ's upper bound.
 *   2a. Inline-object provider resources, each capped by its measured blank:
 *       punctuation trailing space, relation space, then binary-operator space.
 *   2b. TerminalTechnicalEmergencyTracking — only the terminal technical span whose selected
 *                          break is Emergency authorizes grapheme tracking. Its gaps absorb the
 *                          residual before body tracking.
 *   3. CjkInterChar      — last resort for lines containing CJK body text:
 *                          EVEN inter-character expansion（平均拉大字距）,
 *                          UNCAPPED. A Western-dominant visual line does not
 *                          enter this tier merely because it contains CJK
 *                          punctuation; see `WesternDominantLineNaturalSpacing`.
 *   4. EmergencyGraphemeTracking — exact residual fill over source-grapheme
 *                          boundaries in explicitly authorized technical or
 *                          strongly non-lexical ranges. Ordinary Western prose
 *                          never enters this tier.
 *
 * CLREQ's expansion list has no punctuation-space tier: punctuation
 * adjustment space participates in COMPRESSION only. The earlier tier-1
 * (`PunctuationGlueFirstJustification`) is removed accordingly — see
 * ADR 0004 amendments.
 *
 * Each [JustificationAllocation] targets a specific cluster: the delta is
 * understood as trailing space added to that cluster's advance.
 *
 * Tier-3 eligibility — UNIFORM TRACKING over every logical spacing position at the same share.
 * This includes CJK↔CJK, punctuation↔Western, and the word-space / sino-western gaps that
 * already received their capped tier-1 / tier-2 allocation. The earlier tiers are preferential
 * adjustments, not exclusions from the final uniform pass. A typed space owns one logical gap,
 * so its two physical cluster edges are never double-counted. Intra-word Western letter spacing
 * remains excluded. CLREQ's inseparable symbol interiors and connector / solidus boundaries stay
 * closed. Collapsed line-edge spaces stay collapsed, and atomic long marks (dash / ellipsis) still
 * keep both neighbours closed.
 */
class Justifier(
    /**
     * CLREQ 拉伸第①档：「每个西文词距最大可以拉伸到半个汉字字宽」——the
     * word space's FINAL width is capped at this (absolute). Headroom is
     * `cap − naturalSpaceWidth`, so a 二分空 (0.5em) is already at the cap
     * and does not stretch (a finer proportional space would).
     */
    private val wordSpaceMaxEm: Float = 0.5f,
    /**
     * `ProgressiveTechnicalWhitespaceStretch`: source-authored whitespace inside a link or
     * inline-code span may absorb a small amount beyond the ordinary Western word-space cap.
     * This is an ADDITIONAL cap, not a final-width cap. Structural, camel-case, syllable, and
     * emergency break boundaries are deliberately excluded: turning those into glue would
     * manufacture visible letter spacing inside technical text.
     */
    private val progressiveTechnicalWhitespaceStretchMaxEm: Float = 0.25f,
) {
    internal fun progressiveTechnicalWhitespaceStretchCapacity(fontSize: Float): Float =
        progressiveTechnicalWhitespaceStretchMaxEm * fontSize

    fun justify(
        adjustedClusters: List<Cluster>,
        clusterRoles: List<FontRole>,
        eastAsianSpacingEdges: List<EastAsianSpacingEdges>,
        lineClusterRange: IntRange,
        maxWidth: Float,
        fontSize: Float,
        skip: Boolean,
        /** Named line policy that disabled positive expansion, when [skip] is true. */
        skipReason: String? = null,
        /**
         * 「在一些排版风格中，中西间距固定默认宽度……不允许被拉伸」—
         * false keeps the gap fixed: it disables both the preferred
         * CjkLatinSpace stretch and its participation in final uniform spacing.
         */
        allowSinoWesternGapStretch: Boolean = true,
        /**
         * 中西间距的基准（autospace）宽度，拉伸从此起步。与 [cjkLatinSpaceMaxEm]
         * 是一对（`AutoSpacePolicy.gapEm..stretchMaxEm`，ADR 0009 修订），由调用方
         * 从 profile 传入——REQUIRED，避免同一数字在两处各存一份漂移。
         */
        cjkLatinSpaceBaseEm: Float,
        /**
         * CLREQ 拉伸第②档：中西间距的拉伸上限（final width）。CLREQ 字面 0.5em，
         * 注② 实践 1/3em——`AutoSpacePolicy.stretchMaxEm` 提供。
         */
        cjkLatinSpaceMaxEm: Float,
        /**
         * `NoStretchBoundaryClusters` — cluster indices whose adjacent
         * boundaries stay closed throughout stretching. Covers CLREQ's explicit
         * connector / solidus limit and the engine's atomic long-mark model
         * for dash / ellipsis.
         */
        noStretchBoundaryClusters: Set<Int> = emptySet(),
        /**
         * Cluster indices whose trailing boundary must not stretch. This is
     * the precise form used for CLREQ's symbol-separation rule: `50|%`,
     * `¥|100`, and similar inseparable pairs stay closed without also
     * closing the pair's outer boundaries.
     */
        noStretchBoundaryAfterClusters: Set<Int> = emptySet(),
        /**
         * `WesternBracketCjkInterChar`: boundaries where a proportional
         * Western bracket directly touches CJK body text. They join tier 3 at
         * the same share as every other eligible character gap.
         */
        westernBracketCjkInterCharBoundaryAfterClusters: Set<Int> = emptySet(),
        /** Physical edges touching an attached run; neither represents adjacent prose. */
        attachedInlinePhysicalBoundaryAfterClusters: Set<Int> = emptySet(),
        /** Target edge after an attached run -> virtual prose cluster on its left. */
        attachedInlineVirtualBoundaryAfterClusters: Map<Int, Int> = emptyMap(),
        /** Virtual prose boundaries that are W/N and therefore also join tier ②. */
        attachedInlineVirtualSinoWesternBoundaryAfterClusters: Set<Int> = emptySet(),
        /**
         * Explicit object edges that join the final equal-spacing pass. The
         * index is the cluster on the left of the boundary. Opaque inline
         * objects remain fixed unless their provider opts an edge in.
         */
        uniformInlineObjectBoundaryAfterClusters: Set<Int> = emptySet(),
        /**
         * Provider-measured capped stretch at specific object boundaries. These run after the
         * CLREQ word/sino-western tiers and before final uniform tracking. Their semantic order is
         * fixed here; the provider supplies classification, natural width, and an absolute target,
         * not allocation policy.
         */
        preferredInlineObjectBoundaryAfterClusters: Map<Int, InlineObjectPreferredStretch> = emptyMap(),
        /** Source-whitespace boundaries in link/inline-code text, keyed by the space cluster. */
        technicalBoundaryAfterClusters: Map<Int, ProgressiveBreakTier> = emptyMap(),
        /** Explicitly authorized grapheme boundaries, keyed by the cluster on their left. */
        emergencyTrackingBoundaryAfterClusters: Map<Int, String> = emptyMap(),
        /**
         * Authorized boundaries in the terminal technical span whose selected break is Emergency.
         * These absorb the residual before CJK body tracking; unrelated prose and mid-line
         * technical spans keep the ordinary justification order.
         */
        preferredEmergencyTrackingBoundaryAfterClusters: Map<Int, String> = emptyMap(),
    ): JustificationPlan {
        require(clusterRoles.size == adjustedClusters.size) {
            "clusterRoles must align with adjustedClusters."
        }
        require(eastAsianSpacingEdges.size == adjustedClusters.size) {
            "East_Asian_Spacing values must align with adjustedClusters."
        }

        val adjustedWidth = lineClusterRange.sumOf { adjustedClusters[it].advance.toDouble() }.toFloat()
        val deficitBefore = (maxWidth - adjustedWidth).coerceAtLeast(0f)

        if (skip || deficitBefore <= 0f) {
            return JustificationPlan(
                lineClusterRange = lineClusterRange,
                allocations = emptyList(),
                deficitBefore = deficitBefore,
                unfilledDeficit = deficitBefore,
                fallbackReason = if (skip) skipReason else null,
            )
        }

        var remaining = deficitBefore
        val allocations = mutableListOf<JustificationAllocation>()
        fun boundaryIsClosed(leftIdx: Int, rightIdx: Int): Boolean =
            leftIdx in noStretchBoundaryAfterClusters ||
                leftIdx in noStretchBoundaryClusters ||
                rightIdx in noStretchBoundaryClusters

        // A source-space cluster represents one logical gap even though it has
        // a physical boundary on each side. If either side is protected, the
        // logical gap is protected too.
        fun spaceGapIsClosed(spaceIdx: Int): Boolean =
            spaceIdx - 1 in noStretchBoundaryAfterClusters ||
                spaceIdx in noStretchBoundaryAfterClusters ||
                spaceIdx - 1 in noStretchBoundaryClusters ||
                spaceIdx + 1 in noStretchBoundaryClusters

        // `ProgressiveTechnicalWhitespaceStretch`: only a real source-space is additional glue.
        // It runs before the ordinary prose tiers so the inline span absorbs a small residual
        // first. The prose opportunities remain available when this bounded capacity is not
        // enough; no range is frozen or removed from the ordinary Justifier.
        val technicalWhitespaceOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.ProgressiveTechnical,
            priority = ProgressiveBreakTier.Whitespace.priority,
            capacity = progressiveTechnicalWhitespaceStretchCapacity(fontSize),
            reason = "ProgressiveTechnicalWhitespaceStretch",
        ) { leftIdx, rightIdx ->
            technicalBoundaryAfterClusters[leftIdx] == ProgressiveBreakTier.Whitespace &&
                adjustedClusters[leftIdx].text.all(Char::isWhitespace)
        }
        remaining = allocate(
            deficit = remaining,
            opportunities = technicalWhitespaceOpps,
            reason = "ProgressiveTechnicalWhitespaceStretch",
            into = allocations,
        )
        if (remaining <= 0f) return finalize(lineClusterRange, deficitBefore, remaining, allocations)

        // 1. WordSpace — stretch Latin word spaces（CLREQ 拉伸第一档：西文
        // 词距，一行内多处应同时、同等量处理）. A word space is a space-run
        // cluster between two Latin word clusters; sino-western gap spaces
        // (CJK-adjacent) are normalised by autospace and are NOT word
        // spaces. Line-edge-collapsed spaces (advance 0) are skipped.
        // Equal caps + proportional allocation = equal amounts.
        val wordSpaceOpps = buildList {
            for (idx in lineClusterRange) {
                if (!adjustedClusters.isWordSpaceBetweenNarrow(idx, eastAsianSpacingEdges)) continue
                if (spaceGapIsClosed(idx)) continue
                val naturalWidth = adjustedClusters[idx].advance
                if (naturalWidth <= 0f) continue
                // Headroom to the absolute cap (CLREQ ≤ 0.5em final width);
                // a 二分空 already at the cap gets none.
                val headroom = (wordSpaceMaxEm * fontSize - naturalWidth).coerceAtLeast(0f)
                if (headroom <= 0f) continue
                add(
                    JustificationOpportunity(
                        targetClusterIndex = idx,
                        kind = GlueKind.WordSpace,
                        priority = 0,
                        capacity = headroom,
                    ),
                )
            }
        }
        remaining = allocate(
            deficit = remaining,
            opportunities = wordSpaceOpps,
            reason = "WordSpace",
            into = allocations,
        )
        if (remaining <= 0f) return finalize(lineClusterRange, deficitBefore, remaining, allocations)

        // 2. CjkLatinSpace — the sino-western gap（中西间距）, stretched from its
        // 0.25em base up to 0.5em, every instance in the line by equal amounts
        // (CLREQ 拉伸第②档：同时、同等量). The historical enum name
        // is retained for compatibility; eligibility is Unicode East_Asian_Spacing
        // W↔N, not FontRole.CjkText↔FontRole.LatinText. The same gap takes two shapes:
        //
        //   (a) VIRTUAL — a W↔N source boundary with no typed space.
        //       A punctuation-led shaping run such as `/TERFism` begins with O,
        //       so it keeps its Western shaping without fabricating 中西间距.
        //   (b) TYPED — an author U+0020 between W and N source units,
        //       which autospace normalised to the 0.25em base. It IS the 中西
        //       间距 and must stretch too (`TypedSinoWesternSpaceStretches`).
        //       Earlier `TypedSpaceBoundaryDefersToWordSpace` deferred it to the
        //       WordSpace tier, but a CJK-adjacent space is not a word space, so
        //       it fell through ALL tiers — a「了 espresso」line then stretched
        //       only on its CJK half. The virtual boundary still excludes typed
        //       spaces so the same gap is never counted twice.
        val cjkLatinOpps = if (!allowSinoWesternGapStretch) {
            emptyList()
        } else {
            buildBoundaryOpportunities(
                adjustedClusters = adjustedClusters,
                lineClusterRange = lineClusterRange,
                kind = GlueKind.CjkLatinSpace,
                priority = 1,
                // Headroom from the base 中西间距 up to the (style-set) cap.
                capacity = ((cjkLatinSpaceMaxEm - cjkLatinSpaceBaseEm) * fontSize).coerceAtLeast(0f),
            ) { leftIdx, rightIdx ->
                isWideNarrowBoundary(leftIdx, rightIdx, eastAsianSpacingEdges) &&
                    leftIdx !in attachedInlinePhysicalBoundaryAfterClusters &&
                    !boundaryIsClosed(leftIdx, rightIdx) &&
                    !adjustedClusters[leftIdx].text.endsWith(' ') &&
                    !adjustedClusters[rightIdx].text.startsWith(' ')
            } + buildList {
                attachedInlineVirtualSinoWesternBoundaryAfterClusters.forEach { targetIndex ->
                    val previousIndex = attachedInlineVirtualBoundaryAfterClusters[targetIndex]
                        ?: return@forEach
                    val nextIndex = targetIndex + 1
                    if (targetIndex !in lineClusterRange || nextIndex !in lineClusterRange) return@forEach
                    if (previousIndex in noStretchBoundaryClusters || nextIndex in noStretchBoundaryClusters) {
                        return@forEach
                    }
                    add(
                        JustificationOpportunity(
                            targetClusterIndex = targetIndex,
                            kind = GlueKind.CjkLatinSpace,
                            priority = 1,
                            capacity = ((cjkLatinSpaceMaxEm - cjkLatinSpaceBaseEm) * fontSize)
                                .coerceAtLeast(0f),
                            reason = "AttachedInlineVirtualAutoSpace",
                        ),
                    )
                }
                for (idx in lineClusterRange) {
                    if (!adjustedClusters.isWideNarrowTypedSpace(idx, eastAsianSpacingEdges)) continue
                    if (spaceGapIsClosed(idx)) continue
                    // A space collapsed to 0 at a line edge (LineEdgeWordSpaceCollapse)
                    // must NOT be revived as a stretchable gap, or the trimmed edge
                    // blank reappears at 0.5em.
                    val width = adjustedClusters[idx].advance
                    if (width <= 0f) continue
                    val headroom = (cjkLatinSpaceMaxEm * fontSize - width).coerceAtLeast(0f)
                    if (headroom <= 0f) continue
                    add(
                        JustificationOpportunity(
                            targetClusterIndex = idx,
                            kind = GlueKind.CjkLatinSpace,
                            priority = 1,
                            capacity = headroom,
                        ),
                    )
                }
            }
        }
        remaining = allocate(
            deficit = remaining,
            opportunities = cjkLatinOpps,
            reason = "CjkLatinSpace",
            into = allocations,
        )
        if (remaining <= 0f) return finalize(lineClusterRange, deficitBefore, remaining, allocations)

        for (preferredKind in InlineObjectPreferredStretchKind.entries) {
            val preferredOpps = buildList {
                preferredInlineObjectBoundaryAfterClusters.forEach { (leftIdx, preferred) ->
                    if (preferred.kind != preferredKind) return@forEach
                    val rightIdx = leftIdx + 1
                    if (leftIdx !in lineClusterRange || rightIdx !in lineClusterRange) return@forEach
                    if (boundaryIsClosed(leftIdx, rightIdx)) return@forEach
                    add(
                        JustificationOpportunity(
                            targetClusterIndex = leftIdx,
                            kind = preferredKind.glueKind(),
                            priority = 2,
                            capacity = preferred.capacity,
                        ),
                    )
                }
            }
            remaining = allocate(
                deficit = remaining,
                opportunities = preferredOpps,
                reason = preferredKind.reason,
                into = allocations,
            )
            if (remaining <= 0f) {
                return finalize(lineClusterRange, deficitBefore, remaining, allocations)
            }
        }

        val preferredEmergencyTrackingOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.EmergencyGraphemeTracking,
            priority = 3,
            capacity = remaining,
        ) { leftIdx, _ ->
            leftIdx in preferredEmergencyTrackingBoundaryAfterClusters
        }.map { opportunity ->
            opportunity.copy(
                reason = "TerminalTechnicalEmergencyTracking:" +
                    preferredEmergencyTrackingBoundaryAfterClusters.getValue(opportunity.targetClusterIndex),
            )
        }
        remaining = allocate(
            deficit = remaining,
            opportunities = preferredEmergencyTrackingOpps,
            reason = "TerminalTechnicalEmergencyTracking",
            into = allocations,
        )
        if (remaining <= 0f) return finalize(lineClusterRange, deficitBefore, remaining, allocations)

        // WesternDominantLineNaturalSpacing: a visual line containing no CJK
        // body text remains Western composition even when full-width Chinese
        // punctuation occurs inside technical names such as `Rust（Winio）`.
        // Word-space and sino-western tiers above still run when applicable;
        // the remaining deficit stays ragged instead of turning punctuation ↔
        // Latin boundaries into half-em pseudo-spaces.
        val hasCjkBodyText = lineClusterRange.any { eastAsianSpacingEdges[it].containsWide }
        val hasAdjustableInlineObjectBoundary =
            (lineClusterRange.first until lineClusterRange.last).any { leftIdx ->
                leftIdx in uniformInlineObjectBoundaryAfterClusters &&
                    !boundaryIsClosed(leftIdx, leftIdx + 1)
            }
        val lineHasEmergencyTrackingBoundary =
            (lineClusterRange.first until lineClusterRange.last).any {
                it in emergencyTrackingBoundaryAfterClusters
            }
        if (!hasCjkBodyText && !hasAdjustableInlineObjectBoundary && !lineHasEmergencyTrackingBoundary) {
            return finalize(
                lineClusterRange = lineClusterRange,
                deficitBefore = deficitBefore,
                unfilled = remaining,
                allocations = allocations,
                fallbackReason = "WesternDominantLineNaturalSpacing",
            )
        }

        // 3. CjkInterChar — last resort: EVEN expansion across logical gaps
        // （CLREQ「剩余所有字符间距，同时、同等量拉伸」）, uncapped (equal
        // per-boundary capacity = the whole remaining deficit, so proportional
        // allocation degenerates to an exact even split that always fills the
        // line). Uniform tracking over EVERY remaining 字符间距 — punctuation
        // solid sides and collapsed pairs included, all at the same share (no
        // preferential refill of trimmed blanks; see class doc). Eligible:
        //   - CJK↔CJK（汉字、标点任一侧、标点↔标点）;
        //   - `PunctuationLatinInterChar`: 标点↔西文 — a 标点 face abutting a
        //     Western word IS 剩余字符间距 too (CLREQ tier ③ excludes only
        //     不可断标点 + 连接号/分隔号, NOT 标点↔西文);
        //   - `WesternBracketCjkInterChar`: proportional OP/CL/CP brackets
        //     directly touching CJK body text, without changing their face;
        //   - virtual W↔N gaps after their tier-② cap;
        //   - one logical gap for every non-collapsed word space / typed W↔N space after its
        //     tier-①/② allocation. The delta lands on the space cluster itself.
        // Excluded: intra-word Western letter spacing and the no-stretch boundaries.
        val uniformTextBoundaryOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.CjkInterChar,
            priority = 3,
            capacity = remaining,
        ) { leftIdx, rightIdx ->
            val l = clusterRoles[leftIdx]
            val r = clusterRoles[rightIdx]
            val bothCjk = l.isCjkLike() && r.isCjkLike()
            val punctWestern =
                (l == FontRole.CjkPunctuation &&
                    eastAsianSpacingEdges[rightIdx].leading == EastAsianSpacingValue.Narrow) ||
                    (eastAsianSpacingEdges[leftIdx].trailing == EastAsianSpacingValue.Narrow &&
                        r == FontRole.CjkPunctuation)
            val virtualSinoWestern = allowSinoWesternGapStretch &&
                isWideNarrowBoundary(leftIdx, rightIdx, eastAsianSpacingEdges)
            (bothCjk || punctWestern || virtualSinoWestern) &&
                leftIdx !in westernBracketCjkInterCharBoundaryAfterClusters &&
                leftIdx !in attachedInlinePhysicalBoundaryAfterClusters &&
                leftIdx !in attachedInlineVirtualBoundaryAfterClusters &&
                leftIdx !in uniformInlineObjectBoundaryAfterClusters &&
                // CLREQ: inseparable symbol pairs stay closed; boundaries
                // touching connectors, solidus, dash, or ellipsis also stay closed.
                !boundaryIsClosed(leftIdx, rightIdx)
        }
        val westernBracketCjkOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.CjkInterChar,
            priority = 3,
            capacity = remaining,
            reason = "WesternBracketCjkInterChar",
        ) { leftIdx, rightIdx ->
            leftIdx in westernBracketCjkInterCharBoundaryAfterClusters &&
                leftIdx !in attachedInlinePhysicalBoundaryAfterClusters &&
                leftIdx !in uniformInlineObjectBoundaryAfterClusters &&
                !boundaryIsClosed(leftIdx, rightIdx)
        }
        val attachedInlineVirtualOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.CjkInterChar,
            priority = 3,
            capacity = remaining,
            reason = "AttachedInlineVirtualInterChar",
        ) { leftIdx, rightIdx ->
            val previousIndex = attachedInlineVirtualBoundaryAfterClusters[leftIdx]
            previousIndex != null &&
                (allowSinoWesternGapStretch ||
                    leftIdx !in attachedInlineVirtualSinoWesternBoundaryAfterClusters) &&
                leftIdx !in uniformInlineObjectBoundaryAfterClusters &&
                previousIndex !in noStretchBoundaryClusters &&
                rightIdx !in noStretchBoundaryClusters &&
                previousIndex !in noStretchBoundaryAfterClusters
        }
        val uniformInlineObjectBoundaryOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.InlineObjectBoundary,
            priority = 3,
            capacity = remaining,
        ) { leftIdx, rightIdx ->
            leftIdx in uniformInlineObjectBoundaryAfterClusters &&
                !boundaryIsClosed(leftIdx, rightIdx)
        }
        val uniformSpaceOpps = buildList {
            for (idx in lineClusterRange) {
                val isWordSpace = adjustedClusters.isWordSpaceBetweenNarrow(idx, eastAsianSpacingEdges)
                val isTypedSinoWestern = allowSinoWesternGapStretch &&
                    adjustedClusters.isWideNarrowTypedSpace(idx, eastAsianSpacingEdges)
                if ((!isWordSpace && !isTypedSinoWestern) || adjustedClusters[idx].advance <= 0f) continue
                if (spaceGapIsClosed(idx)) continue
                add(
                    JustificationOpportunity(
                        targetClusterIndex = idx,
                        kind = GlueKind.CjkInterChar,
                        priority = 3,
                        capacity = remaining,
                    ),
                )
            }
        }
        val cjkInterOpps =
            uniformTextBoundaryOpps + westernBracketCjkOpps + attachedInlineVirtualOpps +
                uniformInlineObjectBoundaryOpps + uniformSpaceOpps
        remaining = allocate(
            deficit = remaining,
            opportunities = cjkInterOpps,
            reason = "CjkInterChar",
            into = allocations,
        )
        if (remaining <= 0f) return finalize(lineClusterRange, deficitBefore, remaining, allocations)

        // `ExplicitEmergencyGraphemeTracking`: ordinary paragraph opportunities
        // above always run first. Only an upstream, structured eligibility
        // decision can open these source-grapheme boundaries; this is the exact
        // fill fallback for standalone links, hashes, and identifiers.
        val emergencyTrackingOpps = buildBoundaryOpportunities(
            adjustedClusters = adjustedClusters,
            lineClusterRange = lineClusterRange,
            kind = GlueKind.EmergencyGraphemeTracking,
            priority = 4,
            capacity = remaining,
        ) { leftIdx, _ ->
            // This authorization is deliberately independent from ordinary
            // prose no-stretch glue. In an opaque identifier, digit and symbol
            // cohesion may close break boundaries but cannot leave tracking
            // concentrated on the few remaining letter gaps. Empty/object and
            // source-space edges were already excluded when this map was built.
            leftIdx in emergencyTrackingBoundaryAfterClusters &&
                leftIdx !in preferredEmergencyTrackingBoundaryAfterClusters
        }.map { opportunity ->
            opportunity.copy(
                reason = "EmergencyGraphemeTracking:" +
                    emergencyTrackingBoundaryAfterClusters.getValue(opportunity.targetClusterIndex),
            )
        }
        remaining = allocate(
            deficit = remaining,
            opportunities = emergencyTrackingOpps,
            reason = "EmergencyGraphemeTracking",
            into = allocations,
        )

        return finalize(
            lineClusterRange = lineClusterRange,
            deficitBefore = deficitBefore,
            unfilled = remaining,
            allocations = allocations,
            fallbackReason = if (remaining > 0f && lineHasEmergencyTrackingBoundary) {
                "EmergencyTrackingNoOpenBoundary"
            } else {
                null
            },
        )
    }

    /**
     * 压缩到行长 — the COMPRESSION counterpart of [justify] (CLREQ §6.2.2.3
     * 挤压处理的优先顺序). Distributes a line's [surplus] (adjustedWidth − 行长)
     * over the engine's tiered [shrinkOpportunities] in ASCENDING tier order
     * （①行末半宽 ②西文词距 ③间隔号 ④夹注 ⑤逗顿分 ⑥中西间距 ⑦句问叹）: a tier is
     * exhausted before the next is touched; within a tier every instance shrinks
     * simultaneously by an equal fraction of its capacity (matching [allocate]'s
     * stretch sharing). Output [PushInAllocation]s reuse the existing
     * channel-based application path (ADR 0020), so this is purely the
     * direction-symmetric distributor — `LineAdjustmentStrategy` decides WHEN to
     * call it vs [justify]. `lineEndOnly` opportunities (行末削半 promotion) are
     * the caller's to filter; this only distributes what it is given.
     */
    fun compress(
        surplus: Float,
        shrinkOpportunities: List<ShrinkOpportunity>,
    ): CompressionPlan {
        if (surplus <= 0f) return CompressionPlan(emptyList(), 0f, 0f)
        var remaining = surplus
        val allocations = mutableListOf<PushInAllocation>()
        val byTier = shrinkOpportunities.filter { it.capacity > 0f }.groupBy { it.tier }
        for (tier in byTier.keys.sorted()) {
            if (remaining <= 0f) break
            val tierOpps = byTier.getValue(tier)
            val totalCapacity = tierOpps.sumOf { it.capacity.toDouble() }.toFloat()
            if (totalCapacity <= 0f) continue
            val factor = (remaining / totalCapacity).coerceAtMost(1f)
            for (opp in tierOpps) {
                val shrink = opp.capacity * factor
                if (shrink > 0f) {
                    allocations += PushInAllocation(opp.clusterIndex, shrink, opp.capacity, opp.channel)
                }
            }
            remaining -= totalCapacity * factor
        }
        return CompressionPlan(
            allocations = allocations,
            surplusBefore = surplus,
            unfilledSurplus = remaining.coerceAtLeast(0f),
        )
    }

    private inline fun buildBoundaryOpportunities(
        adjustedClusters: List<Cluster>,
        lineClusterRange: IntRange,
        kind: GlueKind,
        priority: Int,
        capacity: Float,
        reason: String? = null,
        predicate: (leftIdx: Int, rightIdx: Int) -> Boolean,
    ): List<JustificationOpportunity> {
        if (capacity <= 0f) return emptyList()
        val opps = mutableListOf<JustificationOpportunity>()
        for (idx in lineClusterRange.first until lineClusterRange.last) {
            if (predicate(idx, idx + 1)) {
                opps += JustificationOpportunity(
                    targetClusterIndex = idx,
                    kind = kind,
                    priority = priority,
                    capacity = capacity,
                    reason = reason,
                )
            }
        }
        return opps
    }

    private fun allocate(
        deficit: Float,
        opportunities: List<JustificationOpportunity>,
        reason: String,
        into: MutableList<JustificationAllocation>,
    ): Float {
        if (deficit <= 0f || opportunities.isEmpty()) return deficit
        val totalCapacity = opportunities.sumOf { it.capacity.toDouble() }.toFloat()
        if (totalCapacity <= 0f) return deficit

        return if (totalCapacity >= deficit) {
            val factor = deficit / totalCapacity
            opportunities.forEach { opp ->
                val alloc = opp.capacity * factor
                if (alloc > 0f) {
                    into += JustificationAllocation(
                        targetClusterIndex = opp.targetClusterIndex,
                        kind = opp.kind,
                        priority = opp.priority,
                        delta = alloc,
                        reason = opp.reason ?: reason,
                    )
                }
            }
            0f
        } else {
            opportunities.forEach { opp ->
                if (opp.capacity > 0f) {
                    into += JustificationAllocation(
                        targetClusterIndex = opp.targetClusterIndex,
                        kind = opp.kind,
                        priority = opp.priority,
                        delta = opp.capacity,
                        reason = opp.reason ?: reason,
                    )
                }
            }
            deficit - totalCapacity
        }
    }

    private fun finalize(
        lineClusterRange: IntRange,
        deficitBefore: Float,
        unfilled: Float,
        allocations: List<JustificationAllocation>,
        fallbackReason: String? = null,
    ): JustificationPlan = JustificationPlan(
        lineClusterRange = lineClusterRange,
        allocations = allocations,
        deficitBefore = deficitBefore,
        unfilledDeficit = unfilled.coerceAtLeast(0f),
        fallbackReason = fallbackReason,
    )

    private fun isWideNarrowBoundary(
        leftIdx: Int,
        rightIdx: Int,
        spacingEdges: List<EastAsianSpacingEdges>,
    ): Boolean =
        spacingEdges[leftIdx].trailing.isWideNarrowPairWith(spacingEdges[rightIdx].leading)

    private fun FontRole.isCjkLike(): Boolean =
        this == FontRole.CjkText || this == FontRole.CjkPunctuation

    private fun InlineObjectPreferredStretchKind.glueKind(): GlueKind = when (this) {
        InlineObjectPreferredStretchKind.PunctuationTrailing -> GlueKind.InlineObjectPunctuationTrailing
        InlineObjectPreferredStretchKind.Relation -> GlueKind.InlineObjectRelation
        InlineObjectPreferredStretchKind.BinaryOperator -> GlueKind.InlineObjectBinaryOperator
    }

    private val InlineObjectPreferredStretchKind.reason: String
        get() = when (this) {
            InlineObjectPreferredStretchKind.PunctuationTrailing -> "InlineObjectPunctuationTrailing"
            InlineObjectPreferredStretchKind.Relation -> "InlineObjectRelation"
            InlineObjectPreferredStretchKind.BinaryOperator -> "InlineObjectBinaryOperator"
        }

}

/** A source space between two Narrow runs: one logical Western word gap. */
private fun List<Cluster>.isWordSpaceBetweenNarrow(
    idx: Int,
    spacingEdges: List<EastAsianSpacingEdges>,
): Boolean {
    val cluster = getOrNull(idx) ?: return false
    if (cluster.text.isEmpty() || !cluster.text.all { it == ' ' }) return false
    val previousNarrowWord = idx > 0 &&
        spacingEdges[idx - 1].trailing == EastAsianSpacingValue.Narrow &&
        !this[idx - 1].text.all { it == ' ' }
    val nextNarrowWord = idx < lastIndex &&
        spacingEdges[idx + 1].leading == EastAsianSpacingValue.Narrow &&
        !this[idx + 1].text.all { it == ' ' }
    return previousNarrowWord && nextNarrowWord
}

/** A source space whose two non-space neighbours resolve to Wide and Narrow. */
private fun List<Cluster>.isWideNarrowTypedSpace(
    idx: Int,
    spacingEdges: List<EastAsianSpacingEdges>,
): Boolean {
    val cluster = getOrNull(idx) ?: return false
    if (cluster.text.isEmpty() || !cluster.text.all { it == ' ' }) return false
    val left = spacingEdges.getOrNull(idx - 1)?.trailing
    val right = spacingEdges.getOrNull(idx + 1)?.leading
    return left?.isWideNarrowPairWith(right) == true
}

private fun EastAsianSpacingValue.isWideNarrowPairWith(other: EastAsianSpacingValue?): Boolean =
    (this == EastAsianSpacingValue.Wide && other == EastAsianSpacingValue.Narrow) ||
        (this == EastAsianSpacingValue.Narrow && other == EastAsianSpacingValue.Wide)

data class JustificationOpportunity(
    val targetClusterIndex: Int,
    val kind: GlueKind,
    val priority: Int,
    val capacity: Float,
    val reason: String? = null,
)

data class JustificationAllocation(
    val targetClusterIndex: Int,
    val kind: GlueKind,
    val priority: Int,
    val delta: Float,
    val reason: String,
)

data class JustificationPlan(
    val lineClusterRange: IntRange,
    val allocations: List<JustificationAllocation>,
    val deficitBefore: Float,
    val unfilledDeficit: Float,
    val fallbackReason: String? = null,
)

/**
 * The compression counterpart of [JustificationPlan]: negative-direction
 * [PushInAllocation]s that shrink a line by [surplusBefore] − [unfilledSurplus]
 * (CLREQ §6.2.2.3). [unfilledSurplus] > 0 means even full compression could not
 * absorb the overflow — the caller (`LineAdjustmentStrategy`) must then 推出.
 */
data class CompressionPlan(
    val allocations: List<PushInAllocation>,
    val surplusBefore: Float,
    val unfilledSurplus: Float,
)
