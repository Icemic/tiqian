package org.tiqian.web

import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.PREPARED_PARAGRAPH_LAYOUT_REVISION
import org.tiqian.layout.toPreparedParagraphJson
import org.tiqian.web.TiqianWeb.CapabilityIssue
import org.tiqian.web.TiqianWeb.EnhanceOptions
import org.tiqian.web.TiqianWeb.EnhancedParagraph
import org.tiqian.web.TiqianWeb.ParagraphCommitResult
import org.tiqian.web.TiqianWeb.ParagraphLayoutPreparation
import org.tiqian.web.TiqianWeb.RootState
import org.w3c.dom.HTMLElement

/**
 * WorkerLayoutInputContract keeps DOM ownership on the main thread while
 * serializing only the immutable layout model. The Worker runs the existing
 * Lookahead engine against the already-proven exact replay session; any
 * snapshot-ineligible textual semantics replay shallow clones of their live
 * source elements; unsupported structure, decoration or inline objects stay
 * native. Exact layout must never fall back to synchronous Kotlin/JS merely
 * because the snapshot serializer has a narrower semantic vocabulary.
 */
internal fun TiqianWeb.workerLayoutRequest(
    root: HTMLElement,
    paragraph: HTMLElement,
    options: EnhanceOptions,
): String? {
    if (!belongsToRootScope(paragraph, root, ROOT_SELECTOR) || !eligibilityBridge().shouldTryParagraph(paragraph)) {
        return null
    }
    if (!options.allowsSnapshotExactLayout()) return null
    val resolved = options.withRootDefaults(root)
    val lowered = try {
        MarkdownParagraphLowerer.lower(paragraph, resolved)
    } catch (_: Throwable) {
        null
    } ?: return null
    return workerLayoutRequest(paragraph, lowered, resolved)
}

internal fun TiqianWeb.workerLayoutRequest(
    paragraph: HTMLElement,
    lowered: LoweredParagraph,
    options: EnhanceOptions,
): String? {
    if (options.conformingExactFontSessionId() == null) return null
    // WorkerRequestMatchesRuntimeEligibility: inline objects no longer exclude a
    // paragraph from Worker preparation. Their measured geometry travels on the
    // request wire (ADR 0053 B8.2) and the live elements enter at commit time,
    // the same split the runtime exact path uses. Decorated paragraphs stay
    // excluded here because the request wire carries no decoration input; they
    // lower on the main thread, whose LayoutInput carries the decorations, and
    // commit through the same prepared bridge. Every other exclusion mirrors
    // isRuntimeExactPreparedDomEligible so both exact paths adopt one shape.
    if (
        lowered.decorations.isNotEmpty() || lowered.sourceSpans.any { span ->
            span.inlineBoxStyle.boxDecorationBreak == "clone" &&
                (kotlin.math.abs(span.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
                    kotlin.math.abs(span.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON)
        } || lowered.spans.any { it.style.locale != lowered.textStyle.locale }
    ) return null
    val rawWidth = sourceParagraphWidth(paragraph)
    if (!rawWidth.isFinite() || rawWidth <= 0f) return null
    // WorkerLineMeasureMatchesResponsiveGrid: the responsive coordinator
    // intentionally treats widths within the same floor(width / fontSize)
    // cell count as one layout input. Serialize that effective measure,
    // not the transient CSS width observed while a window is being dragged,
    // so preparation and commit use the same Worker plan inside the grid.
    val measure = effectiveLineMeasure(rawWidth, lowered.textStyle.fontSize)
    return workerLayoutRequestJson(
        paragraph = paragraph,
        lowered = lowered,
        width = measure,
        firstLineIndentIc = if (paragraph.tagName.uppercase() == "LI") {
            0f
        } else {
            options.firstLineIndentIc
        },
    )
}

internal fun TiqianWeb.processParagraph(paragraph: HTMLElement, state: RootState) {
    if (!eligibilityBridge().shouldTryParagraph(paragraph)) return
    // Capture host-owned inline typography before any computed-style probe.
    // CSSStyleDeclaration can leave an empty style attribute after a
    // temporary property is removed even when the source had no attribute.
    val originalStyleAttribute = paragraph.getAttribute("style")
    val lowered = try {
        MarkdownParagraphLowerer.lower(paragraph, state.options)
    } catch (error: Throwable) {
        val issue = CapabilityIssue(
            "DomLoweringFailure",
            error.message ?: "unexpected DOM lowering failure",
            paragraph,
        )
        state.issues += issue
        reportIssue(issue)
        return
    }
    if (lowered == null) {
        val issue = MarkdownParagraphLowerer.lastIssue ?: CapabilityIssue(
            "UnsupportedParagraph",
            "paragraph could not be lowered",
            paragraph,
        )
        state.issues += issue
        reportIssue(issue)
        return
    }

    custodyBridge().begin(
        source = paragraph,
        renderedAttribute = paragraph.getAttribute("data-tq-rendered"),
        preparedFlowAttribute = paragraph.getAttribute("data-tq-canonical-plain"),
        canonicalSourceAttribute = paragraph.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
        exactPreparedDomAttribute = paragraph.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
        langAttribute = paragraph.getAttribute("lang"),
        styleAttribute = originalStyleAttribute,
        position = paragraph.style.getPropertyValue("position"),
        positionPriority = paragraph.style.getPropertyPriority("position"),
        inlineSize = paragraph.style.getPropertyValue("inline-size"),
        inlineSizePriority = paragraph.style.getPropertyPriority("inline-size"),
        fontSize = paragraph.style.getPropertyValue("font-size"),
        fontSizePriority = paragraph.style.getPropertyPriority("font-size"),
        hostInlineSizeAttribute = paragraph.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
    )
    val hostFontSizeApplied = applyConfiguredHostFontSize(paragraph, state.options.fontSize)
    val sourceInlineSize = captureSourceInlineSize(paragraph)
    val activeOptions = state.activeOptions()
    val workerRequest = workerLayoutRequest(paragraph, lowered, activeOptions)
    val workerPlan = workerRequest?.let { request ->
        takePreparedWorkerLayoutPlan(
            paragraph,
            activeOptions.conformingExactFontSessionId()!!,
            request,
        )
    }
    val workerIssue = if (workerRequest != null && workerPlan == null) {
        preparedWorkerLayoutIssue(
            paragraph,
            activeOptions.conformingExactFontSessionId()!!,
            workerRequest,
        )
    } else {
        null
    }
    // WorkerIneligibleRichRunBrowserFallback: SSR and the exact Worker
    // still fail closed when a semantic run has no replayable font
    // evidence. In the live browser, a rich paragraph can shape just that
    // unsupported run through its resolved host font while covered runs
    // remain on the exact session. The progressive scheduler bounds this
    // main-thread fallback to the individual paragraph slice.
    val canUseRichBrowserFallback =
        !lowered.isCanonicalPlainParagraph() &&
            workerIssue?.let(::isExactFontSessionCapabilityFailureDetail) == true
    if (
        activeOptions.requireExactLayoutWorker &&
        workerRequest != null &&
        workerPlan == null &&
        !canUseRichBrowserFallback
    ) {
        if (originalStyleAttribute == null) {
            paragraph.removeAttribute("style")
        } else {
            paragraph.setAttribute("style", originalStyleAttribute)
        }
        val detail = workerIssue ?: "the exact layout Worker produced no reusable plan"
        val issue = CapabilityIssue(
            name = "ExactLayoutWorkerPlanUnavailable",
            detail = detail,
            element = paragraph,
        )
        state.issues += issue
        reportIssue(issue)
        return
    }
    custodyBridge().take(paragraph, hostFontSizeApplied)
    val hostInlineSizeApplied = stabilizeContentSizedItemInlineSize(
        paragraph,
        sourceInlineSize,
    )
    paragraph.setAttribute("data-tq-rendered", "true")
    paragraph.setAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE, "true")
    val item = EnhancedParagraph(
        source = paragraph,
        lowered = lowered,
    )
    custodyBridge().commit(paragraph, hostInlineSizeApplied)
    val layoutIssue = try {
        if (workerPlan == null) {
            layoutParagraph(
                paragraph = item,
                options = activeOptions,
                engine = state.activeEngine(),
                semanticExactEngine = state.activeSemanticExactEngine(),
                browserFallbackEngine = state.activeExactFallbackEngine(),
                onExactPreparedDomFallback = state::disableExactPreparedDom,
                preparedDomEnabled = state.preparedDomEnabled,
            )
        } else {
            commitWorkerPreparedParagraph(
                paragraph = item,
                workerPlan = workerPlan,
                onExactPreparedDomFallback = state::disableExactPreparedDom,
            )
        }
    } catch (error: Throwable) {
        CapabilityIssue(
            "WebEnhancementFailure",
            error.message ?: "unexpected layout or DOM rendering failure",
            paragraph,
        )
    }
    if (layoutIssue == null) {
        state.paragraphs += item
    } else {
        custodyBridge().restoreParagraph(paragraph)
        state.issues += layoutIssue
        reportIssue(layoutIssue)
    }
}

internal fun TiqianWeb.layoutParagraph(
    paragraph: EnhancedParagraph,
    options: EnhanceOptions,
    engine: ExplainableStubParagraphLayoutEngine,
    semanticExactEngine: ExplainableStubParagraphLayoutEngine? = null,
    browserFallbackEngine: ExplainableStubParagraphLayoutEngine? = null,
    onExactPreparedDomFallback: (String) -> Unit = {},
    preparedDomEnabled: Boolean = true,
): CapabilityIssue? {
    return when (
        val preparation = prepareParagraphLayout(
            paragraph = paragraph,
            options = options,
            engine = engine,
            semanticExactEngine = semanticExactEngine,
            browserFallbackEngine = browserFallbackEngine,
            preparedDomEnabled = preparedDomEnabled,
        )
    ) {
        ParagraphLayoutPreparation.Unchanged -> null
        is ParagraphLayoutPreparation.Unsupported -> preparation.issue
        is ParagraphLayoutPreparation.Ready -> when (
            val commit = commitPreparedParagraph(
                paragraph = paragraph,
                preparation = preparation,
                options = options,
                browserFallbackEngine = browserFallbackEngine,
                onExactPreparedDomFallback = onExactPreparedDomFallback,
            )
        ) {
            is ParagraphCommitResult.Success -> {
                paragraph.lastMeasure = commit.measure
                null
            }
            is ParagraphCommitResult.Unsupported -> commit.issue
        }
    }
}

internal fun TiqianWeb.commitWorkerPreparedParagraph(
    paragraph: EnhancedParagraph,
    workerPlan: String,
    onExactPreparedDomFallback: (String) -> Unit,
): CapabilityIssue? {
    val width = paragraphWidth(paragraph)
    paragraph.source.setAttribute(EXACT_PREPARED_DOM_ATTRIBUTE, "true")
    paragraph.source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, "true")
    // CanonicalPlainMatchesRuntimeScope: the re-lowering treats a paragraph as
    // a prepared plain host only when the paragraph shape really is plain.
    // Inline-object paragraphs carry replacement characters the re-lowering
    // must re-measure, so sourceSpans alone must not mark them plain.
    if (paragraph.lowered.isCanonicalPlainParagraph()) {
        paragraph.source.setAttribute("data-tq-canonical-plain", "true")
    } else {
        paragraph.source.removeAttribute("data-tq-canonical-plain")
    }
    paragraph.source.setAttribute("lang", paragraph.lowered.textStyle.locale)
    renderPreparedWorkerParagraphDom(
        paragraph.source,
        workerPlan,
        paragraph.lowered.textStyle.locale,
        paragraph.lowered.text,
        paragraph.lowered.sourceSpans.map { it.element }.toTypedArray(),
        paragraph.lowered.domInlineObjects.map { it.element }.toTypedArray(),
        paragraph.lowered.preparedInlineObjectMetaJson(),
        paragraph.lowered.preparedCjkStrongSemanticsJson(),
    )
    val preparedDomIssue = validatePreparedParagraphDom(paragraph.source, width.toDouble())
    if (preparedDomIssue != null) {
        onExactPreparedDomFallback(preparedDomIssue)
        releasePreparedParagraphDomStyles(paragraph.source)
        paragraph.source.removeAttribute(EXACT_PREPARED_DOM_ATTRIBUTE)
        paragraph.source.removeAttribute("data-tq-canonical-plain")
        paragraph.source.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE)
        paragraph.source.removeAttribute("lang")
        return CapabilityIssue(
            name = "WorkerPreparedDomContractMismatch",
            detail = preparedDomIssue,
            element = paragraph.source,
        )
    }
    paragraph.lastMeasure = effectiveLineMeasure(width, paragraph.lowered.textStyle.fontSize)
    custodyBridge().stampRendered(paragraph.source)
    return null
}

internal fun TiqianWeb.paragraphWidth(paragraph: EnhancedParagraph): Float {
    return sourceParagraphWidth(paragraph.source)
}

internal fun TiqianWeb.sourceParagraphWidth(paragraph: HTMLElement): Float {
    // ContentBoxLineMeasure: LayoutConstraints describe the inline content
    // box where glyphs are placed. A host may add padding directly to a
    // paragraph-shaped list item; using its border-box width lays the line
    // out once through that padding and then starts it after the padding,
    // causing a real right-edge overflow. Font backend selection does not
    // change which CSS box owns the available line measure.
    return elementContentWidth(paragraph).toFloat()
        .takeIf { it > 0f }
        ?: elementContentWidth(paragraph.parentElement as? HTMLElement ?: paragraph).toFloat()
            .takeIf { it > 0f }
        ?: 320f
}

internal fun TiqianWeb.prepareParagraphLayout(
    paragraph: EnhancedParagraph,
    options: EnhanceOptions,
    engine: ExplainableStubParagraphLayoutEngine,
    semanticExactEngine: ExplainableStubParagraphLayoutEngine? = null,
    browserFallbackEngine: ExplainableStubParagraphLayoutEngine? = null,
    widthOverride: Float? = null,
    ignoreUnchangedMeasure: Boolean = false,
    preparedDomEnabled: Boolean = true,
): ParagraphLayoutPreparation {
    val width = widthOverride ?: paragraphWidth(paragraph)
    // LineLengthGridResponsiveInvalidation: the Web adapter currently
    // exposes the default Start-aligned body, so widths within the same
    // floor(width / fontSize) cell count produce identical layout and a
    // zero body offset. Compare the actual engine measure instead of a raw
    // pixel tolerance, which could hide a grid crossing at fractional font
    // sizes.
    val fontSize = paragraph.lowered.textStyle.fontSize
    val measure = effectiveLineMeasure(width, fontSize)
    if (!ignoreUnchangedMeasure && paragraph.lastMeasure == measure) {
        return ParagraphLayoutPreparation.Unchanged
    }
    val input = LayoutInput(
        content = TiqianTextContent(
            text = paragraph.lowered.text,
            spans = paragraph.lowered.spans,
            sourceBoundaries = paragraph.lowered.sourceBoundaries,
            lineBreakSpans = paragraph.lowered.lineBreakSpans,
        ),
        textStyle = paragraph.lowered.textStyle,
        // EngineLineMeasureMatchesResponsiveGrid: retain the raw width in
        // ParagraphLayoutPreparation for host-box validation, but feed the
        // same quantized measure to every synchronous/Worker layout path.
        constraints = LayoutConstraints(maxWidth = measure),
        paragraphStyle = ParagraphStyle(
            lineHeight = paragraph.lowered.lineHeight,
            firstLineIndent = if (
                paragraph.source.tagName.uppercase() == "LI"
            ) Ic.Zero else Ic(options.firstLineIndentIc),
            emphasisDotGapEm = options.emphasisDotGapEm,
        ),
        decorations = paragraph.lowered.decorations,
        rubySpans = emptyList(),
        inlineBoxes = paragraph.lowered.inlineBoxes,
        inlineObjects = paragraph.lowered.inlineObjects,
    )
    // ExactSessionSemanticLayout: semantic DOM changes how LayoutResult is
    // replayed, not which font backend owns shaping and measurement. Keep
    // links, code, and other supported inline semantics on the same exact
    // session as canonical plain paragraphs; use the browser adapter only
    // when that session reports a named font capability failure.
    val exactFontLayout = browserFallbackEngine != null
    // PreparedDomUnifiedEligibility: every eligible paragraph lowers through
    // the prepared DOM, with or without a snapshot key or an exact font
    // session; the prepared renderer replays whatever LayoutResult this host
    // produced. The lane needs a host-installed bridge whose plan wire
    // matches this runtime; without one the paragraph keeps the native
    // renderer. The root disables the lane only after a replay failed
    // geometry validation.
    val preparedDom = preparedDomEnabled &&
        isPreparedDomBridgeAvailable(PREPARED_PARAGRAPH_LAYOUT_REVISION) &&
        paragraph.lowered.isRuntimeExactPreparedDomEligible()
    // KeyedCanonicalStrictSessionOnly: a snapshot key proves that the server
    // captured a complete exact replay corpus for this canonical source.
    // An unkeyed runtime-completion paragraph may carry only the required
    // exact runs (notably a CJK dash) and must therefore retain per-run
    // browser fallback instead of retrying its whole paragraph through the
    // browser shaper after one unrelated replay miss.
    val strictExactSession = exactFontLayout && preparedDom &&
        paragraph.source.hasAttribute("data-tq-snapshot-key") &&
        paragraph.lowered.isCanonicalPlainParagraph()
    val layoutEngine = if (exactFontLayout && !strictExactSession) {
        // RuntimeExactRichPreparedDom: rich paragraphs keep the per-run
        // fallback shaper whether they land on the prepared DOM or the native
        // renderer; the strict session stays reserved for canonical plain
        // paragraphs whose single run must fail as a whole.
        semanticExactEngine ?: engine
    } else {
        engine
    }
    var exactFontSessionUsed = exactFontLayout
    val result = if (exactFontLayout) {
        try {
            layoutEngine.layout(input)
        } catch (error: Throwable) {
            if (!isExactFontSessionCapabilityFailure(error)) throw error
            // PreparedDomAfterSessionFailure: the fallback engine's result is
            // ordinary browser-metric output, so a later replay validation
            // failure must not distrust metrics that never touched the
            // failed session.
            exactFontSessionUsed = false
            browserFallbackEngine.layout(input)
        }
    } else {
        engine.layout(input)
    }
    val shapingCapabilityIssue = result.debug.shapingDecisions.firstOrNull {
        it.capabilityIssue != null
    }
    if (shapingCapabilityIssue != null) {
        return ParagraphLayoutPreparation.Unsupported(
            CapabilityIssue(
                name = shapingCapabilityIssue.capabilityIssue!!,
                detail = shapingCapabilityIssue.reason,
                element = paragraph.source,
            ),
        )
    }
    val invalidShaping = result.debug.shapingDecisions.firstOrNull { decision ->
        decision.displayText.isNotEmpty() &&
            decision.displayText.none { it == '\n' || it == '\r' } &&
            (!decision.advance.isFinite() || decision.advance <= ZERO_ADVANCE_EPSILON)
    }
    if (invalidShaping != null) {
        return ParagraphLayoutPreparation.Unsupported(
            CapabilityIssue(
                name = "InvalidWebShapingAdvance",
                detail = buildString {
                    append("text=")
                    append(invalidShaping.displayText)
                    append("; advance=")
                    append(invalidShaping.advance)
                    append("; ")
                    append(invalidShaping.reason)
                },
                element = paragraph.source,
            ),
        )
    }
    val clonedDecoration = paragraph.lowered.sourceSpans.firstOrNull { span ->
        span.inlineBoxStyle.boxDecorationBreak == "clone" &&
            (kotlin.math.abs(span.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
                kotlin.math.abs(span.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON) &&
            result.lines.count { line ->
                line.range.start < span.range.end && line.range.end > span.range.start
            } > 1
    }
    if (clonedDecoration != null) {
        return ParagraphLayoutPreparation.Unsupported(
            CapabilityIssue(
                name = "InlineCloneDecorationBreakUnsupported",
                detail = clonedDecoration.element.tagName.lowercase(),
                element = paragraph.source,
            ),
        )
    }
    return ParagraphLayoutPreparation.Ready(
        result = result,
        width = width,
        measure = measure,
        preparedDom = preparedDom,
        exactFontSessionUsed = exactFontSessionUsed,
    )
}

internal fun TiqianWeb.effectiveLineMeasure(width: Float, fontSize: Float): Float {
    // InvalidTypographyPreservesCapabilityDiagnosis: a zero/non-finite
    // host font size has no meaningful character grid. Keep the positive
    // host width so shaping can report its precise zero-advance capability
    // issue instead of failing earlier with an unrelated maxWidth error.
    if (!fontSize.isFinite() || fontSize <= 0f) return width
    val gridCells = kotlin.math.floor(width / fontSize).toInt().coerceAtLeast(1)
    return (gridCells * fontSize).coerceAtMost(width)
}

internal fun TiqianWeb.isCurrentResponsiveMeasure(
    preparedWidth: Float,
    currentWidth: Float,
    fontSize: Float,
): Boolean = effectiveLineMeasure(preparedWidth, fontSize) ==
    effectiveLineMeasure(currentWidth, fontSize)

internal fun TiqianWeb.commitPreparedParagraph(
    paragraph: EnhancedParagraph,
    preparation: ParagraphLayoutPreparation.Ready,
    options: EnhanceOptions,
    browserFallbackEngine: ExplainableStubParagraphLayoutEngine?,
    onExactPreparedDomFallback: (String) -> Unit = {},
): ParagraphCommitResult {
    val result = preparation.result
    if (preparation.preparedDom) {
        // PreparedPlainHostPromise: canonical-plain promises the re-lowerer a
        // prepared plain host, so a rich prepared paragraph only carries
        // canonical-source and re-lowers through its live clones.
        if (paragraph.lowered.isCanonicalPlainParagraph()) {
            paragraph.source.setAttribute("data-tq-canonical-plain", "true")
        }
        paragraph.source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, "true")
        paragraph.source.setAttribute("lang", paragraph.lowered.textStyle.locale)
        renderPreparedParagraphDom(
            paragraph.source,
            result.toPreparedParagraphJson(
                renderEvidence = !paragraph.lowered.isCanonicalPlainParagraph(),
            ),
            paragraph.lowered.textStyle.locale,
            paragraph.lowered.text,
            paragraph.lowered.sourceSpans.map { it.element }.toTypedArray(),
            paragraph.lowered.preparedSemanticReplayJson(),
            paragraph.lowered.domInlineObjects.map { it.element }.toTypedArray(),
            paragraph.lowered.preparedInlineObjectMetaJson(),
            paragraph.lowered.preparedCjkStrongSemanticsJson(),
        )
        val preparedDomIssue = validatePreparedParagraphDom(
            paragraph.source,
            preparation.width.toDouble(),
        )
        if (preparedDomIssue == null) {
            custodyBridge().stampRendered(paragraph.source)
            return ParagraphCommitResult.Success(preparation.measure)
        }
        onExactPreparedDomFallback(preparedDomIssue)
        paragraph.source.removeAttribute("data-tq-canonical-plain")
        paragraph.source.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE)
        paragraph.source.removeAttribute("lang")
        if (preparation.exactFontSessionUsed) {
            // ExactSessionMetricDistrust: the replay failed geometry
            // validation against a result shaped by the exact session, so
            // re-lay the paragraph out with browser metrics. The recursion
            // renders native because the root has just disabled the
            // prepared lane.
            val fallbackOptions = options.withoutExactFontSession()
            val fallbackPreparation = prepareParagraphLayout(
                paragraph = paragraph,
                options = fallbackOptions,
                engine = browserFallbackEngine!!,
                browserFallbackEngine = null,
                widthOverride = preparation.width,
                ignoreUnchangedMeasure = true,
                preparedDomEnabled = false,
            )
            return when (fallbackPreparation) {
                ParagraphLayoutPreparation.Unchanged -> error(
                    "Exact prepared DOM fallback unexpectedly skipped relayout",
                )
                is ParagraphLayoutPreparation.Unsupported ->
                    ParagraphCommitResult.Unsupported(fallbackPreparation.issue)
                is ParagraphLayoutPreparation.Ready -> commitPreparedParagraph(
                    paragraph = paragraph,
                    preparation = fallbackPreparation,
                    options = fallbackOptions,
                    browserFallbackEngine = null,
                    onExactPreparedDomFallback = onExactPreparedDomFallback,
                )
            }
        }
        // PreparedReplayMismatchWithoutSession: no exact session shaped this
        // result, so a re-layout would reproduce it. Fall through to the
        // native renderer with the result the browser already measured.
    }
    releasePreparedParagraphDomStyles(paragraph.source)
    paragraph.source.removeAttribute("data-tq-canonical-plain")
    paragraph.source.removeAttribute("lang")
    custodyBridge().ensureContainingBlock(paragraph.source)
    DomParagraphRenderer.render(
        paragraph.source,
        result,
        options.fonts,
        sourceSpans = paragraph.lowered.sourceSpans,
        inlineObjects = paragraph.lowered.domInlineObjects,
    )
    DomParagraphRenderer.verifyCjkDashRuns(paragraph.source)?.let { detail ->
        return ParagraphCommitResult.Unsupported(
            CapabilityIssue(
                name = "DomDashFaceGeometryMismatch",
                detail = detail,
                element = paragraph.source,
            ),
        )
    }
    if (paragraph.lowered.isCanonicalPlainParagraph()) {
        paragraph.source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, "true")
    } else {
        paragraph.source.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE)
    }
    custodyBridge().stampRendered(paragraph.source)
    return ParagraphCommitResult.Success(preparation.measure)
}
