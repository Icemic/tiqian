@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.js.JsAny
import kotlin.js.js
import kotlinx.browser.document
import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.toPreparedParagraphJson
import org.tiqian.shaping.HarfBuzzSessionFontMetricsResolver
import org.tiqian.shaping.HarfBuzzSessionTextShaper
import org.tiqian.shaping.web.WebCanvasFontMetricsResolver
import org.tiqian.shaping.web.WebCanvasTextShaper
import org.tiqian.shaping.web.WebCjkDashCapability
import org.tiqian.shaping.web.WebFontFamilies
import org.w3c.dom.DocumentFragment
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Browser embed API for ADR 0039 dogfood.
 *
 * Host pages keep their SSR markdown for no-JS / SEO / Pagefind. Tiqian enhances
 * eligible paragraphs in place: the source `<p>` remains the semantic and CSS
 * owner while only its inline children are replaced with pre-broken line DOM.
 */
object TiqianWeb {
    private const val ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]"
    private const val DEFAULT_PARAGRAPH_SELECTOR = "p, li"
    private const val SKIPPED_ANCESTOR_SELECTOR =
        ".not-prose, pre, table, .katex, .katex-display, .expressive-code, .tq-paragraph, [data-tiqian-skip]"

    private var installed = false
    // DetachedRootWeakOwnership: navigation can discard a rendered article
    // without reconstructing its semantic DOM. Weak ownership retains the
    // source fragments only if a host later reconnects that exact element.
    private val states: dynamic = js("new WeakMap()")
    private val progressiveJobs = LinkedHashMap<HTMLElement, ProgressiveJob>()

    fun install() {
        if (installed) return
        installed = true
        installTiqianCopyHandler()
        installTiqianGlobalApiBridge()
        document.addEventListener("tiqian:enhance", listener@{ event: Event ->
            val root = eventRoot(event) ?: document.body ?: return@listener
            enhance(root, optionsFromJs(eventOptions(event)))
        })
        document.addEventListener("tiqian:enhance-progressively", listener@{ event: Event ->
            val root = eventRoot(event) ?: document.body ?: return@listener
            enhanceProgressively(root, optionsFromJs(eventOptions(event)))
        })
        document.addEventListener("tiqian:destroy", listener@{ event: Event ->
            val root = eventRoot(event) ?: document.body ?: return@listener
            destroy(root)
        })
        document.addEventListener("tiqian:detach", listener@{ event: Event ->
            val root = eventRoot(event) ?: return@listener
            detach(root)
        })
        document.addEventListener("tiqian:enhance-all", { event: Event ->
            enhanceAll(optionsFromJs(eventOptions(event)))
        })
        document.addEventListener("tiqian:relayout", listener@{ event: Event ->
            val root = eventRoot(event) ?: return@listener
            relayout(root)
        })
        document.addEventListener("tiqian:cancel-layout-work", listener@{ event: Event ->
            val root = eventRoot(event) ?: return@listener
            cancelProgressiveJob(root)
        })
        document.addEventListener("tiqian:worker-layout-request", listener@{ event: Event ->
            val root = eventRoot(event) ?: return@listener
            val paragraph = eventParagraph(event) ?: return@listener
            setEventResult(
                event,
                workerLayoutRequest(root, paragraph, optionsFromJs(eventOptions(event))),
            )
        })
        document.addEventListener("tiqian:refresh", listener@{ event: Event ->
            val root = eventRoot(event) ?: return@listener
            refresh(root)
        })
    }

    fun enhanceAll(options: EnhanceOptions = EnhanceOptions()): Int {
        val roots = document.querySelectorAll(ROOT_SELECTOR)
        var count = 0
        for (i in 0 until roots.length) {
            val root = roots.item(i) as? HTMLElement ?: continue
            count += enhance(root, options)
        }
        return count
    }

    fun enhance(root: HTMLElement, options: EnhanceOptions = EnhanceOptions()): Int {
        installTiqianCopyHandler()
        destroy(root)
        val state = createRootState(root, options)
        val candidates = paragraphCandidates(root, state.options.paragraphSelector)
        if (rejectMissingSharedRuntimeStyles(state, candidates)) return 0
        for (paragraph in candidates) {
            processParagraph(paragraph, state)
        }
        publishState(state)
        return state.paragraphs.size
    }

    /**
     * Enhance viewport-near paragraphs first in bounded animation-frame slices.
     * Each paragraph is replaced atomically in the slice that prepared it; the
     * remaining paragraphs keep responsive semantic source DOM.
     */
    fun enhanceProgressively(root: HTMLElement, options: EnhanceOptions = EnhanceOptions()) =
        enhanceProgressively(root, options, ProgressiveJobKind.Enhance)

    private fun enhanceProgressively(
        root: HTMLElement,
        options: EnhanceOptions,
        kind: ProgressiveJobKind,
    ) {
        installTiqianCopyHandler()
        destroy(root)
        val state = createRootState(root, options)
        val sourceCandidates = paragraphCandidates(root, state.options.paragraphSelector)
        if (rejectMissingSharedRuntimeStyles(state, sourceCandidates)) return
        val candidates = sourceCandidates
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<HTMLElement>> {
                    paragraphViewportDistance(it.value)
                }.thenBy { it.index },
            )
            .map { it.value }
        val capturedMeasures = candidates.map { paragraph ->
            responsiveSourceMeasure(paragraph, state.options.fontSize)
        }
        var stale = false
        fun liveMeasure(index: Int): Float =
            responsiveSourceMeasure(candidates[index], state.options.fontSize)
        val job = ProgressiveJob(
            state = state,
            kind = kind,
            itemCount = candidates.size,
            processItem = { index ->
                if (liveMeasure(index) != capturedMeasures[index]) {
                    stale = true
                } else {
                    processParagraph(candidates[index], state)
                }
            },
            onItemsFinished = {
                stale = stale || candidates.indices.any { index ->
                    liveMeasure(index) != capturedMeasures[index]
                }
                if (stale) {
                    for (paragraph in state.paragraphs.asReversed()) restoreParagraph(paragraph)
                    for (issue in state.issues.asReversed()) clearIssue(issue)
                    state.paragraphs.clear()
                    state.issues.clear()
                }
            },
            stale = { stale },
            shouldScheduleIdle = { index ->
                candidates.getOrNull(index)?.let(::paragraphIsWithinProgressiveForegroundRange) == false
            },
            startedAt = performanceNow(),
        )
        states.set(root, state)
        publishState(state, keepEmpty = true)
        startProgressiveJob(job)
    }

    /**
     * SharedRuntimeStylesCapabilityGate: renderer-owned geometry depends on the
     * package stylesheet for its line strut, reset, and nowrap invariants. The
     * public ESM entry waits for that stylesheet; direct Kotlin callers must do
     * the same instead of silently painting a second browser-owned layout.
     */
    private fun rejectMissingSharedRuntimeStyles(
        state: RootState,
        candidates: List<HTMLElement>,
    ): Boolean {
        if (computedStyle(state.root, "--tq-styles-ready").trim() == "1") return false
        for (paragraph in candidates) {
            val issue = CapabilityIssue(
                name = "MissingSharedRuntimeStyles",
                detail = "Load @tiqian/prose/styles.css before TiqianWeb.enhance",
                element = paragraph,
            )
            state.issues += issue
            reportIssue(issue)
        }
        publishState(state)
        return true
    }

    fun destroy(root: HTMLElement) {
        cancelProgressiveJob(root)
        val state = states.get(root) as? RootState
        states.delete(root)
        if (state != null) {
            for (paragraph in state.paragraphs) {
                restoreParagraph(paragraph)
            }
            for (issue in state.issues) {
                clearIssue(issue)
            }
            // A precomputed snapshot may be live without a Kotlin runtime
            // state while list-only enhancement starts. Its compact value CSS
            // belongs to the snapshot owner and must survive that no-op destroy.
            releasePreparedRootDomStyles(root)
        }
        val snapshotCount = observableSnapshotCount(root)
        if (snapshotCount > 0) {
            root.setAttribute("data-tiqian-enhanced", "true")
            root.setAttribute("data-tiqian-enhanced-count", "$snapshotCount")
        } else {
            root.removeAttribute("data-tiqian-enhanced")
            root.removeAttribute("data-tiqian-enhanced-count")
        }
        root.removeAttribute("data-tiqian-issue-count")
        root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
        root.removeAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE)
    }

    /**
     * Cancels a detached root and releases its document-scoped value styles
     * without rebuilding paragraph DOM that the router is about to discard.
     * The weak state remains available to [destroy] if the same node reconnects.
     */
    fun detach(root: HTMLElement) {
        cancelProgressiveJob(root)
        releasePreparedRootDomStyles(root)
    }

    private fun createRootState(root: HTMLElement, options: EnhanceOptions): RootState {
        root.removeAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE)
        val exactEligibleOptions = if (options.allowsSnapshotExactLayout()) {
            options
        } else {
            options.copy(exactFontSession = null)
        }
        val resolved = exactEligibleOptions.withRootDefaults(root)
        val exactSessionId = resolved.conformingExactFontSessionId()
        val browserMetrics = WebCanvasFontMetricsResolver(resolved.fonts)
        val browserShaper = WebCanvasTextShaper(resolved.fonts, resolved.cjkDashCapability)
        fun lineBreaker(): org.tiqian.layout.LineBreaker = LookaheadLineBreaker()
        val browserEngine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = lineBreaker(),
            fontMetricsResolver = browserMetrics,
            textShaper = browserShaper,
        )
        val exactMetrics = exactSessionId?.let(::HarfBuzzSessionFontMetricsResolver)
        val exactShaper = exactSessionId?.let(::HarfBuzzSessionTextShaper)
        val engine = if (exactMetrics != null && exactShaper != null) {
            ExplainableStubParagraphLayoutEngine(
                lineBreaker = lineBreaker(),
                fontMetricsResolver = exactMetrics,
                textShaper = exactShaper,
            )
        } else {
            browserEngine
        }
        val semanticExactEngine = if (exactMetrics != null && exactShaper != null) {
            ExplainableStubParagraphLayoutEngine(
                lineBreaker = lineBreaker(),
                fontMetricsResolver = ExactSessionBrowserFallbackFontMetricsResolver(
                    exact = exactMetrics,
                    browser = browserMetrics,
                ),
                textShaper = ExactSessionBrowserFallbackTextShaper(
                    exact = exactShaper,
                    browser = browserShaper,
                ),
            )
        } else {
            null
        }
        return RootState(
            root = root,
            options = resolved,
            engine = engine,
            semanticExactEngine = semanticExactEngine,
            browserFallbackEngine = browserEngine.takeIf { exactSessionId != null },
            paragraphs = mutableListOf(),
            issues = mutableListOf(),
        )
    }

    private fun paragraphCandidates(root: HTMLElement, selector: String): List<HTMLElement> {
        val nodes = root.querySelectorAll(selector)
        return buildList {
            for (i in 0 until nodes.length) {
                val paragraph = nodes.item(i) as? HTMLElement ?: continue
                // RuntimeEligibleMeasureSet: progressive staleness compares the
                // same leaf paragraphs that can actually enter the pipeline.
                // Measuring a host-owned outer <li> and later rendering its
                // child <p> changes the container's live width/measure, which
                // used to roll back every valid child as a false stale job.
                if (
                    belongsToRootScope(paragraph, root, ROOT_SELECTOR) &&
                    shouldTryParagraph(paragraph)
                ) add(paragraph)
            }
        }
    }

    /**
     * WorkerLayoutInputContract keeps DOM ownership on the main thread while
     * serializing only the immutable layout model. The Worker runs the existing
     * Lookahead engine against the already-proven exact replay session; any
     * snapshot-ineligible textual semantics replay shallow clones of their live
     * source elements; unsupported structure, decoration or inline objects stay
     * native. Exact layout must never fall back to synchronous Kotlin/JS merely
     * because the snapshot serializer has a narrower semantic vocabulary.
     */
    private fun workerLayoutRequest(
        root: HTMLElement,
        paragraph: HTMLElement,
        options: EnhanceOptions,
    ): String? {
        if (!belongsToRootScope(paragraph, root, ROOT_SELECTOR) || !shouldTryParagraph(paragraph)) {
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

    private fun workerLayoutRequest(
        paragraph: HTMLElement,
        lowered: LoweredParagraph,
        options: EnhanceOptions,
    ): String? {
        if (options.conformingExactFontSessionId() == null) return null
        if (
            lowered.decorations.isNotEmpty() || lowered.inlineObjects.isNotEmpty() ||
            lowered.domInlineObjects.isNotEmpty() || lowered.sourceSpans.any { span ->
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

    private fun processParagraph(paragraph: HTMLElement, state: RootState) {
        if (!shouldTryParagraph(paragraph)) return
        // Capture host-owned inline typography before any computed-style probe.
        // CSSStyleDeclaration can leave an empty style attribute after a
        // temporary property is removed even when the source had no attribute.
        val originalStyleAttribute = paragraph.getAttribute("style")
        val originalFontSize = paragraph.style.getPropertyValue("font-size")
        val originalFontSizePriority = paragraph.style.getPropertyPriority("font-size")
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

        val originalRenderedAttribute = paragraph.getAttribute("data-tq-rendered")
        val originalPreparedFlowAttribute = paragraph.getAttribute("data-tq-canonical-plain")
        val originalCanonicalSourceAttribute = paragraph.getAttribute(CANONICAL_SOURCE_ATTRIBUTE)
        val originalExactPreparedDomAttribute = paragraph.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE)
        val originalLangAttribute = paragraph.getAttribute("lang")
        val originalPosition = paragraph.style.getPropertyValue("position")
        val originalPositionPriority = paragraph.style.getPropertyPriority("position")
        val originalInlineSize = paragraph.style.getPropertyValue("inline-size")
        val originalInlineSizePriority = paragraph.style.getPropertyPriority("inline-size")
        val originalHostInlineSizeAttribute = paragraph.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE)
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
        val originalContent = document.createDocumentFragment()
        while (paragraph.firstChild != null) {
            originalContent.appendChild(paragraph.firstChild!!)
        }
        val hostInlineSizeApplied = stabilizeContentSizedItemInlineSize(
            paragraph,
            sourceInlineSize,
        )
        paragraph.setAttribute("data-tq-rendered", "true")
        paragraph.setAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE, "true")
        val item = EnhancedParagraph(
            source = paragraph,
            originalContent = originalContent,
            lowered = lowered,
            originalRenderedAttribute = originalRenderedAttribute,
            originalPreparedFlowAttribute = originalPreparedFlowAttribute,
            originalCanonicalSourceAttribute = originalCanonicalSourceAttribute,
            originalExactPreparedDomAttribute = originalExactPreparedDomAttribute,
            originalLangAttribute = originalLangAttribute,
            originalStyleAttribute = originalStyleAttribute,
            originalPosition = originalPosition,
            originalPositionPriority = originalPositionPriority,
            originalInlineSize = originalInlineSize,
            originalInlineSizePriority = originalInlineSizePriority,
            originalFontSize = originalFontSize,
            originalFontSizePriority = originalFontSizePriority,
            originalHostInlineSizeAttribute = originalHostInlineSizeAttribute,
            hostInlineSizeApplied = hostInlineSizeApplied,
            hostFontSizeApplied = hostFontSizeApplied,
        )
        val layoutIssue = try {
            if (workerPlan == null) {
                layoutParagraph(
                    paragraph = item,
                    options = activeOptions,
                    engine = state.activeEngine(),
                    semanticExactEngine = state.activeSemanticExactEngine(),
                    browserFallbackEngine = state.activeExactFallbackEngine(),
                    onExactPreparedDomFallback = state::disableExactPreparedDom,
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
            restoreParagraph(item)
            state.issues += layoutIssue
            reportIssue(layoutIssue)
        }
    }

    private fun publishState(state: RootState, keepEmpty: Boolean = false) {
        val hasWork = state.paragraphs.isNotEmpty() || state.issues.isNotEmpty()
        if (!hasWork && !keepEmpty) {
            states.delete(state.root)
            state.root.removeAttribute("data-tiqian-enhanced")
            state.root.removeAttribute("data-tiqian-enhanced-count")
            state.root.removeAttribute("data-tiqian-issue-count")
            return
        }
        states.set(state.root, state)
        state.root.setAttribute("data-tiqian-enhanced", "true")
        state.root.setAttribute(
            "data-tiqian-enhanced-count",
            "${state.paragraphs.size + observableSnapshotCount(state.root)}",
        )
        if (state.issues.isEmpty()) {
            state.root.removeAttribute("data-tiqian-issue-count")
        } else {
            state.root.setAttribute("data-tiqian-issue-count", "${state.issues.size}")
        }
    }

    private fun scheduleProgressiveSlice(job: ProgressiveJob) {
        val idle = job.shouldScheduleIdle(job.nextIndex)
        job.scheduledSliceToken = scheduleProgressiveCallback(
            callback = { runProgressiveSlice(job, idle) },
            idle = idle,
        )
    }

    private fun startProgressiveJob(job: ProgressiveJob) {
        cancelProgressiveJob(job.state.root)
        job.state.root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
        progressiveJobs[job.state.root] = job
        if (job.itemCount == 0) {
            try {
                job.onItemsFinished?.invoke()
                finishProgressiveJob(job)
            } catch (error: Throwable) {
                job.onFailure?.invoke()
                failProgressiveJob(job, error)
            }
        } else {
            scheduleProgressiveSlice(job)
        }
    }

    private fun cancelProgressiveJob(root: HTMLElement) {
        progressiveJobs.remove(root)?.scheduledSliceToken?.let(::cancelProgressiveCallback)
    }

    private fun runProgressiveSlice(job: ProgressiveJob, idleSlice: Boolean) {
        if (progressiveJobs[job.state.root] !== job) return
        job.scheduledSliceToken = null
        val sliceStartedAt = performanceNow()
        var processedInSlice = 0
        try {
            do {
                job.processItem(job.nextIndex)
                job.nextIndex += 1
                processedInSlice += 1
            } while (
                job.nextIndex < job.itemCount &&
                processedInSlice < MAX_PROGRESSIVE_ITEMS_PER_SLICE &&
                performanceNow() - sliceStartedAt < MAX_PROGRESSIVE_SLICE_MS &&
                (!idleSlice || processedInSlice < MAX_PROGRESSIVE_IDLE_ITEMS_PER_SLICE) &&
                !job.shouldScheduleIdle(job.nextIndex) &&
                !progressiveInputIsPending()
            )
        } catch (error: Throwable) {
            job.onFailure?.invoke()
            failProgressiveJob(job, error)
            return
        }
        val sliceDuration = performanceNow() - sliceStartedAt
        job.maxSliceDuration = maxOf(job.maxSliceDuration, sliceDuration)
        publishState(job.state, keepEmpty = true)
        if (job.nextIndex >= job.itemCount) {
            try {
                job.onItemsFinished?.invoke()
                job.maxSliceDuration = maxOf(
                    job.maxSliceDuration,
                    performanceNow() - sliceStartedAt,
                )
                finishProgressiveJob(job)
            } catch (error: Throwable) {
                job.onFailure?.invoke()
                failProgressiveJob(job, error)
            }
        } else {
            scheduleProgressiveSlice(job)
        }
    }

    private fun finishProgressiveJob(job: ProgressiveJob) {
        if (progressiveJobs.remove(job.state.root) !== job) return
        job.state.root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
        publishState(job.state)
        val runtimeEnhancedCount = job.state.paragraphs.size
        val snapshotCount = observableSnapshotCount(job.state.root)
        if (job.kind == ProgressiveJobKind.Relayout) {
            dispatchTiqianRelayoutReady(
                root = job.state.root,
                enhancedCount = runtimeEnhancedCount + snapshotCount,
                runtimeEnhancedCount = runtimeEnhancedCount,
                snapshotCount = snapshotCount,
                issueCount = job.state.issues.size,
                durationMs = performanceNow() - job.startedAt,
                maxSliceMs = job.maxSliceDuration,
                failed = false,
                error = null,
                stale = job.commitSkipped || job.stale?.invoke() == true,
            )
        } else {
            dispatchTiqianReady(
                root = job.state.root,
                enhancedCount = runtimeEnhancedCount + snapshotCount,
                runtimeEnhancedCount = runtimeEnhancedCount,
                snapshotCount = snapshotCount,
                issueCount = job.state.issues.size,
                durationMs = performanceNow() - job.startedAt,
                maxSliceMs = job.maxSliceDuration,
                stale = job.commitSkipped || job.stale?.invoke() == true,
            )
        }
    }

    private fun failProgressiveJob(job: ProgressiveJob, error: Throwable) {
        if (progressiveJobs.remove(job.state.root) !== job) return
        val detail = (error.message ?: error.toString()).take(CAPABILITY_DETAIL_LIMIT)
        job.state.root.setAttribute(RELAYOUT_ERROR_ATTRIBUTE, detail)
        publishState(job.state, keepEmpty = true)
        dispatchTiqianProgressiveError(
            root = job.state.root,
            kind = job.kind.name,
            detail = detail,
            durationMs = performanceNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
        )
        val runtimeEnhancedCount = job.state.paragraphs.size
        val snapshotCount = observableSnapshotCount(job.state.root)
        if (job.kind == ProgressiveJobKind.Relayout) {
            dispatchTiqianRelayoutReady(
                root = job.state.root,
                enhancedCount = runtimeEnhancedCount + snapshotCount,
                runtimeEnhancedCount = runtimeEnhancedCount,
                snapshotCount = snapshotCount,
                issueCount = job.state.issues.size,
                durationMs = performanceNow() - job.startedAt,
                maxSliceMs = job.maxSliceDuration,
                failed = true,
                error = detail,
                stale = false,
            )
        } else {
            dispatchTiqianReady(
                root = job.state.root,
                enhancedCount = runtimeEnhancedCount + snapshotCount,
                runtimeEnhancedCount = runtimeEnhancedCount,
                snapshotCount = snapshotCount,
                issueCount = job.state.issues.size,
                durationMs = performanceNow() - job.startedAt,
                maxSliceMs = job.maxSliceDuration,
                stale = false,
            )
        }
    }

    private fun relayout(root: HTMLElement) {
        val runningJob = progressiveJobs[root]
        if (runningJob?.kind == ProgressiveJobKind.Enhance) {
            // Responsive changes are normally observed only after tiqian:ready,
            // but a manual relayout can still arrive during initial enhancement.
            // Restart from native source at the latest width so candidates that
            // have not been reached by the old job are not stranded.
            enhanceProgressively(root, runningJob.state.options)
            return
        }
        val state = states.get(root) as? RootState ?: return
        val activeOptions = state.activeOptions()
        val activeEngine = state.activeEngine()
        val activeExactFallbackEngine = state.activeExactFallbackEngine()
        cancelProgressiveJob(root)
        if (state.issues.any { it.name in WIDTH_DEPENDENT_CAPABILITY_ISSUES }) {
            // WidthDependentCapabilityTransitionRetry: only named
            // capabilities whose eligibility depends on line count need to be
            // lowered again at the new width. Restore semantic source once,
            // then let viewport-near paragraphs take over atomically in bounded
            // slices just like any other source refresh.
            enhanceProgressively(root, state.options, ProgressiveJobKind.Relayout)
            return
        }
        val paragraphs = state.paragraphs.toList()
        // ViewportPriorityRelayout: capture the priority before any live DOM is
        // changed. A paragraph intersecting the viewport has distance zero;
        // the remaining paragraphs follow by proximity and document order.
        val workOrder = paragraphs.indices
            .map { index -> index to paragraphViewportDistance(paragraphs[index].source) }
            .sortedWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .map { it.first }
        // WidthSnapshotPerRelayoutJob: every paragraph is prepared against the
        // geometry seen when the job starts. If the host changes again while
        // slices are running, element.js schedules one latest-width follow-up
        // instead of allowing a queue of obsolete widths to replay.
        val widths = paragraphs.map(::paragraphWidth)
        val commitSession = ProgressiveRelayoutSession(
            paragraphs = paragraphs,
            state = state,
        )
        startProgressiveJob(
            ProgressiveJob(
                state = state,
                kind = ProgressiveJobKind.Relayout,
                itemCount = paragraphs.size,
                processItem = { index ->
                    val paragraphIndex = workOrder[index]
                    val paragraph = paragraphs[paragraphIndex]
                    val preparation = prepareParagraphLayout(
                        paragraph = paragraph,
                        options = activeOptions,
                        engine = activeEngine,
                        semanticExactEngine = state.activeSemanticExactEngine(),
                        browserFallbackEngine = activeExactFallbackEngine,
                        widthOverride = widths[paragraphIndex],
                    )
                    // ParagraphCurrentMeasureCommit: keep the previous
                    // paragraph DOM until its replacement is ready, then
                    // require the captured measure to still equal the live
                    // measure immediately before the single-paragraph commit.
                    val currentWidth = paragraphWidth(paragraph)
                    if (
                        isCurrentResponsiveMeasure(
                            preparedWidth = widths[paragraphIndex],
                            currentWidth = currentWidth,
                            fontSize = paragraph.lowered.textStyle.fontSize,
                        )
                    ) {
                        commitSession.processItem(paragraphIndex, preparation)
                    } else {
                        commitSession.stale = true
                    }
                },
                onItemsFinished = commitSession::finish,
                onFailure = commitSession::rollback,
                stale = { commitSession.stale },
                shouldScheduleIdle = { index ->
                    workOrder.getOrNull(index)
                        ?.let { paragraphIndex -> paragraphs[paragraphIndex].source }
                        ?.let(::paragraphIsWithinProgressiveForegroundRange) == false
                },
                startedAt = performanceNow(),
            ),
        )
    }

    /**
     * HostTypographyInvalidation: width-only relayout can reuse lowered source,
     * but a host font/size/weight/line-height change must restore the semantic
     * DOM and lower it again. Otherwise canvas measures the old computed style
     * while light DOM paints the new one, producing clipped whole-line overflow.
     */
    internal fun refresh(root: HTMLElement, progressively: Boolean = true) {
        val options = (states.get(root) as? RootState)?.options ?: return
        if (progressively) {
            enhanceProgressively(root, options)
        } else {
            enhance(root, options)
        }
    }

    private fun layoutParagraph(
        paragraph: EnhancedParagraph,
        options: EnhanceOptions,
        engine: ExplainableStubParagraphLayoutEngine,
        semanticExactEngine: ExplainableStubParagraphLayoutEngine? = null,
        browserFallbackEngine: ExplainableStubParagraphLayoutEngine? = null,
        onExactPreparedDomFallback: (String) -> Unit = {},
    ): CapabilityIssue? {
        return when (
            val preparation = prepareParagraphLayout(
                paragraph = paragraph,
                options = options,
                engine = engine,
                semanticExactEngine = semanticExactEngine,
                browserFallbackEngine = browserFallbackEngine,
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

    private fun commitWorkerPreparedParagraph(
        paragraph: EnhancedParagraph,
        workerPlan: String,
        onExactPreparedDomFallback: (String) -> Unit,
    ): CapabilityIssue? {
        val width = paragraphWidth(paragraph)
        paragraph.source.setAttribute(EXACT_PREPARED_DOM_ATTRIBUTE, "true")
        paragraph.source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, "true")
        if (paragraph.lowered.sourceSpans.isEmpty()) {
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
        )
        val preparedDomIssue = validatePreparedParagraphDom(paragraph.source, width.toDouble())
        if (preparedDomIssue != null) {
            onExactPreparedDomFallback(preparedDomIssue)
            releasePreparedParagraphDomStyles(paragraph.source)
            restoreAttribute(
                paragraph.source,
                EXACT_PREPARED_DOM_ATTRIBUTE,
                paragraph.originalExactPreparedDomAttribute,
            )
            restoreAttribute(
                paragraph.source,
                "data-tq-canonical-plain",
                paragraph.originalPreparedFlowAttribute,
            )
            restoreAttribute(
                paragraph.source,
                CANONICAL_SOURCE_ATTRIBUTE,
                paragraph.originalCanonicalSourceAttribute,
            )
            restoreAttribute(paragraph.source, "lang", paragraph.originalLangAttribute)
            return CapabilityIssue(
                name = "WorkerPreparedDomContractMismatch",
                detail = preparedDomIssue,
                element = paragraph.source,
            )
        }
        paragraph.lastMeasure = effectiveLineMeasure(width, paragraph.lowered.textStyle.fontSize)
        return null
    }

    private fun paragraphWidth(paragraph: EnhancedParagraph): Float {
        return sourceParagraphWidth(paragraph.source)
    }

    private fun sourceParagraphWidth(paragraph: HTMLElement): Float {
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

    private fun prepareParagraphLayout(
        paragraph: EnhancedParagraph,
        options: EnhanceOptions,
        engine: ExplainableStubParagraphLayoutEngine,
        semanticExactEngine: ExplainableStubParagraphLayoutEngine? = null,
        browserFallbackEngine: ExplainableStubParagraphLayoutEngine? = null,
        widthOverride: Float? = null,
        ignoreUnchangedMeasure: Boolean = false,
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
        // KeyedCanonicalPreparedDomOnly: a snapshot key proves that the server
        // captured a complete exact replay corpus for this canonical source.
        // An unkeyed runtime-completion paragraph may carry only the required
        // exact runs (notably a CJK dash) and must therefore retain per-run
        // browser fallback instead of retrying its whole paragraph through the
        // browser shaper after one unrelated replay miss.
        var exactPreparedDom = exactFontLayout &&
            paragraph.source.hasAttribute("data-tq-snapshot-key") &&
            paragraph.lowered.isCanonicalPlainParagraph()
        val layoutEngine = if (exactFontLayout && !exactPreparedDom) {
            semanticExactEngine ?: engine
        } else {
            engine
        }
        val result = if (exactFontLayout) {
            try {
                layoutEngine.layout(input)
            } catch (error: Throwable) {
                if (!isExactFontSessionCapabilityFailure(error)) throw error
                exactPreparedDom = false
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
            exactPreparedDom = exactPreparedDom,
        )
    }

    private fun effectiveLineMeasure(width: Float, fontSize: Float): Float {
        // InvalidTypographyPreservesCapabilityDiagnosis: a zero/non-finite
        // host font size has no meaningful character grid. Keep the positive
        // host width so shaping can report its precise zero-advance capability
        // issue instead of failing earlier with an unrelated maxWidth error.
        if (!fontSize.isFinite() || fontSize <= 0f) return width
        val gridCells = kotlin.math.floor(width / fontSize).toInt().coerceAtLeast(1)
        return (gridCells * fontSize).coerceAtMost(width)
    }

    private fun isCurrentResponsiveMeasure(
        preparedWidth: Float,
        currentWidth: Float,
        fontSize: Float,
    ): Boolean = effectiveLineMeasure(preparedWidth, fontSize) ==
        effectiveLineMeasure(currentWidth, fontSize)

    private fun commitPreparedParagraph(
        paragraph: EnhancedParagraph,
        preparation: ParagraphLayoutPreparation.Ready,
        options: EnhanceOptions,
        browserFallbackEngine: ExplainableStubParagraphLayoutEngine?,
        onExactPreparedDomFallback: (String) -> Unit = {},
    ): ParagraphCommitResult {
        val result = preparation.result
        if (preparation.exactPreparedDom) {
            paragraph.source.setAttribute("data-tq-canonical-plain", "true")
            paragraph.source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, "true")
            paragraph.source.setAttribute("lang", paragraph.lowered.textStyle.locale)
            renderPreparedParagraphDom(
                paragraph.source,
                result.toPreparedParagraphJson(),
                paragraph.lowered.textStyle.locale,
            )
            val preparedDomIssue = validatePreparedParagraphDom(
                paragraph.source,
                preparation.width.toDouble(),
            )
            if (preparedDomIssue != null) {
                onExactPreparedDomFallback(preparedDomIssue)
                restoreAttribute(
                    paragraph.source,
                    "data-tq-canonical-plain",
                    paragraph.originalPreparedFlowAttribute,
                )
                restoreAttribute(
                    paragraph.source,
                    CANONICAL_SOURCE_ATTRIBUTE,
                    paragraph.originalCanonicalSourceAttribute,
                )
                restoreAttribute(paragraph.source, "lang", paragraph.originalLangAttribute)
                val fallbackOptions = options.withoutExactFontSession()
                val fallbackPreparation = prepareParagraphLayout(
                    paragraph = paragraph,
                    options = fallbackOptions,
                    engine = browserFallbackEngine!!,
                    browserFallbackEngine = null,
                    widthOverride = preparation.width,
                    ignoreUnchangedMeasure = true,
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
        } else {
            releasePreparedParagraphDomStyles(paragraph.source)
            restoreAttribute(
                paragraph.source,
                "data-tq-canonical-plain",
                paragraph.originalPreparedFlowAttribute,
            )
            restoreAttribute(paragraph.source, "lang", paragraph.originalLangAttribute)
            ensureContainingBlock(paragraph)
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
                restoreAttribute(
                    paragraph.source,
                    CANONICAL_SOURCE_ATTRIBUTE,
                    paragraph.originalCanonicalSourceAttribute,
                )
            }
        }
        return ParagraphCommitResult.Success(preparation.measure)
    }

    private class ProgressiveRelayoutSession(
        paragraphs: List<EnhancedParagraph>,
        private val state: RootState,
    ) {
        private val paragraphs = paragraphs.toList()
        private val snapshots = LinkedHashMap<EnhancedParagraph, LiveParagraphSnapshot>()
        private val successful = mutableListOf<Pair<EnhancedParagraph, Float>>()
        private val unsupported = mutableListOf<Pair<EnhancedParagraph, CapabilityIssue>>()
        private val stateParagraphsBefore = state.paragraphs.toList()
        private val stateIssuesBefore = state.issues.toList()
        var stale: Boolean = false

        fun processItem(index: Int, preparation: ParagraphLayoutPreparation) {
            val paragraph = paragraphs[index]
            when (preparation) {
                ParagraphLayoutPreparation.Unchanged -> Unit
                is ParagraphLayoutPreparation.Unsupported -> {
                    snapshots[paragraph] = TiqianWeb.captureLiveParagraph(paragraph)
                    unsupported += paragraph to preparation.issue
                    TiqianWeb.restoreParagraph(paragraph)
                }
                is ParagraphLayoutPreparation.Ready -> {
                    snapshots[paragraph] = TiqianWeb.captureLiveParagraph(paragraph)
                    when (
                        val result = TiqianWeb.commitPreparedParagraph(
                            paragraph = paragraph,
                            preparation = preparation,
                            options = state.options,
                            browserFallbackEngine = state.browserFallbackEngine,
                            onExactPreparedDomFallback = state::disableExactPreparedDom,
                        )
                    ) {
                        is ParagraphCommitResult.Success -> {
                            paragraph.lastMeasure = result.measure
                            successful += paragraph to result.measure
                        }
                        is ParagraphCommitResult.Unsupported -> {
                            unsupported += paragraph to result.issue
                            TiqianWeb.restoreParagraph(paragraph)
                        }
                    }
                }
            }
        }

        fun finish() {
            for ((paragraph, measure) in successful) {
                if (unsupported.none { (unsupportedParagraph, _) -> unsupportedParagraph === paragraph }) {
                    paragraph.lastMeasure = measure
                }
            }
            for ((paragraph, issue) in unsupported) {
                state.paragraphs.remove(paragraph)
                state.issues += issue
                TiqianWeb.reportIssue(issue)
            }
        }

        fun rollback() {
            state.paragraphs.clear()
            state.paragraphs.addAll(stateParagraphsBefore)
            state.issues.clear()
            state.issues.addAll(stateIssuesBefore)
            TiqianWeb.rollbackRelayoutSnapshots(snapshots.values.toList())
        }
    }

    private fun captureLiveParagraph(paragraph: EnhancedParagraph): LiveParagraphSnapshot {
        val content = document.createDocumentFragment()
        val snapshot = LiveParagraphSnapshot(
            paragraph = paragraph,
            content = content,
            renderedAttribute = paragraph.source.getAttribute("data-tq-rendered"),
            preparedFlowAttribute = paragraph.source.getAttribute("data-tq-canonical-plain"),
            canonicalSourceAttribute = paragraph.source.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
            exactPreparedDomAttribute = paragraph.source.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
            langAttribute = paragraph.source.getAttribute("lang"),
            styleAttribute = paragraph.source.getAttribute("style"),
            capabilityNameAttribute = paragraph.source.getAttribute("data-tiqian-capability-issue"),
            capabilityDetailAttribute = paragraph.source.getAttribute("data-tiqian-capability-detail"),
            lastMeasure = paragraph.lastMeasure,
            containingBlockApplied = paragraph.containingBlockApplied,
            hostInlineSizeApplied = paragraph.hostInlineSizeApplied,
            hostInlineSizeAttribute = paragraph.source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
            originalContentHadChildren = paragraph.originalContent.firstChild != null,
        )
        while (paragraph.source.firstChild != null) {
            content.appendChild(paragraph.source.firstChild!!)
        }
        return snapshot
    }

    private fun rollbackRelayoutSnapshots(snapshots: List<LiveParagraphSnapshot>) {
        for (snapshot in snapshots.asReversed()) {
            val paragraph = snapshot.paragraph
            if (snapshot.originalContentHadChildren && paragraph.originalContent.firstChild == null) {
                // restoreParagraph() handed the semantic source fragment back
                // to the live DOM; move those exact nodes into source custody
                // again before replaying the previous rendered fragment.
                while (paragraph.source.firstChild != null) {
                    paragraph.originalContent.appendChild(paragraph.source.firstChild!!)
                }
            } else {
                while (paragraph.source.firstChild != null) {
                    paragraph.source.removeChild(paragraph.source.firstChild!!)
                }
            }
            paragraph.source.appendChild(snapshot.content)
            restoreAttribute(paragraph.source, "data-tq-rendered", snapshot.renderedAttribute)
            restoreAttribute(
                paragraph.source,
                "data-tq-canonical-plain",
                snapshot.preparedFlowAttribute,
            )
            restoreAttribute(
                paragraph.source,
                CANONICAL_SOURCE_ATTRIBUTE,
                snapshot.canonicalSourceAttribute,
            )
            restoreAttribute(
                paragraph.source,
                EXACT_PREPARED_DOM_ATTRIBUTE,
                snapshot.exactPreparedDomAttribute,
            )
            restoreAttribute(paragraph.source, "lang", snapshot.langAttribute)
            restoreAttribute(paragraph.source, "style", snapshot.styleAttribute)
            restoreAttribute(
                paragraph.source,
                "data-tiqian-capability-issue",
                snapshot.capabilityNameAttribute,
            )
            restoreAttribute(
                paragraph.source,
                "data-tiqian-capability-detail",
                snapshot.capabilityDetailAttribute,
            )
            paragraph.lastMeasure = snapshot.lastMeasure
            paragraph.containingBlockApplied = snapshot.containingBlockApplied
            paragraph.hostInlineSizeApplied = snapshot.hostInlineSizeApplied
            restoreAttribute(
                paragraph.source,
                HOST_INLINE_SIZE_ATTRIBUTE,
                snapshot.hostInlineSizeAttribute,
            )
        }
    }

    private fun shouldTryParagraph(paragraph: HTMLElement): Boolean {
        if (hasClosest(paragraph, SKIPPED_ANCESTOR_SELECTOR)) return false
        if (paragraph.getAttribute("data-tiqian-skip") != null) return false
        // `LeafListItemParagraph`: Markdown commonly emits list text directly
        // inside <li>, so a list item is a paragraph-shaped flow owner and must
        // enter the same pipeline. An outer item that owns a nested block stays
        // native as a container; its leaf descendants are still independent
        // candidates. This avoids replacing a nested <ul>/<ol> while preserving
        // list markers and host list semantics.
        if (
            paragraph.tagName.uppercase() == "LI" &&
            paragraph.querySelector(":scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > table") != null
        ) {
            return false
        }
        // PureBlockImageParagraphExclusion: Markdown commonly wraps a
        // standalone image in <p>. A block image owns no inline text flow for
        // Tiqian to lay out, so leave the host wrapper native without reporting
        // a capability issue. Text mixed with a block image still enters the
        // lowerer and fails atomically as an unsupported formatting context.
        if (isPureBlockImageParagraph(paragraph)) return false
        if (paragraph.textContent?.isBlank() != false && !hasOpaqueInlineCandidate(paragraph)) return false
        return true
    }

    private fun isPureBlockImageParagraph(paragraph: HTMLElement): Boolean {
        if (paragraph.tagName.uppercase() != "P" || paragraph.textContent?.isBlank() != true) return false
        val children = paragraph.querySelectorAll(":scope > *")
        if (children.length == 0) return false
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: return false
            if (
                child.tagName.uppercase() != "IMG" ||
                computedStyle(child, "display").trim().lowercase() != "block"
            ) return false
        }
        return true
    }

    private fun hasOpaqueInlineCandidate(paragraph: HTMLElement): Boolean {
        val descendants = paragraph.querySelectorAll("*")
        for (index in 0 until descendants.length) {
            val element = descendants.item(index) as? Element ?: continue
            val tag = element.tagName.uppercase()
            val display = computedStyle(element, "display").trim().lowercase()
            if (tag in NON_TEXT_INLINE_TAGS || tag.contains('-') || display in OPAQUE_INLINE_DISPLAYS) {
                return true
            }
        }
        return false
    }

    private fun reportIssue(issue: CapabilityIssue) {
        if (!issue.markerCaptured) {
            issue.originalNameAttribute = issue.element.getAttribute("data-tiqian-capability-issue")
            issue.originalDetailAttribute = issue.element.getAttribute("data-tiqian-capability-detail")
            issue.markerCaptured = true
        }
        issue.element.setAttribute("data-tiqian-capability-issue", issue.name)
        issue.element.setAttribute("data-tiqian-capability-detail", issue.detail.take(CAPABILITY_DETAIL_LIMIT))
        // PendingCapabilityIsObservableNotTerminal: the semantic paragraph is
        // intentionally kept native while the asynchronous dash-face probe is
        // in flight. Keep the DOM marker for the targeted retry, but reserve a
        // console warning for the retry's final unavailable/mismatch result.
        if (issue.reportToConsole) {
            consoleWarn("TiqianWeb skipped paragraph: ${issue.name} (${issue.detail})")
        }
    }

    private fun optionsFromJs(options: JsAny?): EnhanceOptions {
        val cjk = optionString(options, "cjkFontFamily")
        val latin = optionString(options, "latinFontFamily")
        val monospace = optionString(options, "monospaceFontFamily")
        val cjkSerif = optionString(options, "cjkSerifFontFamily")
        val latinSerif = optionString(options, "latinSerifFontFamily")
        val fontSize = optionFloat(options, "fontSize")
        val lineHeight = optionFloat(options, "lineHeight")
        val firstLineIndent = optionFloat(options, "firstLineIndentIc") ?: 0f
        val emphasisDotGapEm = optionFloat(options, "emphasisDotGapEm")
            ?: DEFAULT_EMPHASIS_DOT_GAP_EM
        val strongAsEmphasisMarks = optionBoolean(options, "strongAsEmphasisMarks") ?: false
        val paragraphSelector = optionString(options, "paragraphSelector") ?: DEFAULT_PARAGRAPH_SELECTOR
        val requireExactLayoutWorker = optionBoolean(options, "requireExactLayoutWorker") ?: false
        val dashCapabilityObject = optionObject(options, "cjkDashCapability")
        val dashCapability = dashCapabilityObject?.let { capability ->
            WebCjkDashCapability(
                status = optionString(capability, "status") ?: "unavailable",
                detail = optionString(capability, "detail"),
            )
        }
        val exactFontSessionObject = optionObject(options, "exactFontSession")
        val exactFontSession = exactFontSessionObject?.let { capability ->
            ExactFontSessionCapability(
                status = optionString(capability, "status") ?: "unavailable",
                sessionId = optionString(capability, "sessionId"),
                detail = optionString(capability, "detail"),
            )
        }
        return EnhanceOptions(
            fontFamilies = FontFamilyOptions(cjk, latin, monospace, cjkSerif, latinSerif),
            fontSize = fontSize,
            lineHeight = lineHeight,
            firstLineIndentIc = firstLineIndent,
            emphasisDotGapEm = emphasisDotGapEm,
            strongAsEmphasisMarks = strongAsEmphasisMarks,
            paragraphSelector = paragraphSelector,
            cjkDashCapability = dashCapability,
            exactFontSession = exactFontSession,
            requireExactLayoutWorker = requireExactLayoutWorker,
        )
    }

    private fun optionFloat(options: JsAny?, name: String): Float? {
        val value = optionNumber(options, name)
        return if (value.isFinite()) value.toFloat() else null
    }

    data class EnhanceOptions(
        val fontFamilies: FontFamilyOptions = FontFamilyOptions(),
        val fontSize: Float? = null,
        val lineHeight: Float? = null,
        val firstLineIndentIc: Float = 0f,
        val emphasisDotGapEm: Float = DEFAULT_EMPHASIS_DOT_GAP_EM,
        val strongAsEmphasisMarks: Boolean = false,
        val paragraphSelector: String = DEFAULT_PARAGRAPH_SELECTOR,
        val cjkDashCapability: WebCjkDashCapability? = null,
        val exactFontSession: ExactFontSessionCapability? = null,
        /**
         * Internal custom-element contract: every Worker-representable exact
         * layout must commit its prepared plan. Rich paragraphs outside that
         * contract retain the sliced browser-shaping path.
         */
        val requireExactLayoutWorker: Boolean = false,
    ) {
        lateinit var fonts: WebFontFamilies
            private set

        fun withRootDefaults(root: HTMLElement): EnhanceOptions {
            require(fontSize == null || (fontSize.isFinite() && fontSize > 0f)) {
                "InvalidFontSize"
            }
            val inheritedFontFamily = computedStyle(root, "font-family").trim().takeIf { it.isNotBlank() }
            val resolvedCjk = fontFamilies.cjk ?: inheritedFontFamily ?: DEFAULT_CJK_FONT_FAMILY
            val resolvedLatin = fontFamilies.latin ?: inheritedFontFamily ?: DEFAULT_LATIN_FONT_FAMILY
            val resolved = copy(
                fontFamilies = fontFamilies.copy(
                    cjk = resolvedCjk,
                    latin = resolvedLatin,
                    monospace = fontFamilies.monospace ?: DEFAULT_MONOSPACE_FONT_FAMILY,
                    cjkSerif = fontFamilies.cjkSerif ?: DEFAULT_CJK_SERIF_FONT_FAMILY,
                    latinSerif = fontFamilies.latinSerif ?: DEFAULT_LATIN_SERIF_FONT_FAMILY,
                ),
            )
            resolved.fonts = WebFontFamilies(
                cjk = resolved.fontFamilies.cjk!!,
                latin = resolved.fontFamilies.latin!!,
                latinMonospace = resolved.fontFamilies.monospace!!,
                cjkSerif = resolved.fontFamilies.cjkSerif!!,
                latinSerif = resolved.fontFamilies.latinSerif!!,
            )
            return resolved
        }

        internal fun conformingExactFontSessionId(): String? = exactFontSession
            ?.takeIf { it.status == "conforming" }
            ?.sessionId
            ?.takeIf(String::isNotBlank)

        internal fun allowsSnapshotExactLayout(): Boolean =
            fontSize == null &&
                lineHeight == null &&
                firstLineIndentIc == 0f &&
                fontFamilies.cjk == null &&
                fontFamilies.latin == null &&
                fontFamilies.monospace == null &&
                fontFamilies.cjkSerif == null &&
                fontFamilies.latinSerif == null

        internal fun withoutExactFontSession(): EnhanceOptions =
            copy(exactFontSession = null).also { fallback -> fallback.fonts = fonts }
    }

    data class FontFamilyOptions(
        val cjk: String? = null,
        val latin: String? = null,
        val monospace: String? = null,
        val cjkSerif: String? = null,
        val latinSerif: String? = null,
    )

    data class ExactFontSessionCapability(
        val status: String = "unavailable",
        val sessionId: String? = null,
        val detail: String? = null,
    )

    private data class RootState(
        val root: HTMLElement,
        var options: EnhanceOptions,
        var engine: ExplainableStubParagraphLayoutEngine,
        var semanticExactEngine: ExplainableStubParagraphLayoutEngine?,
        var browserFallbackEngine: ExplainableStubParagraphLayoutEngine?,
        val paragraphs: MutableList<EnhancedParagraph>,
        val issues: MutableList<CapabilityIssue>,
        var exactPreparedDomEnabled: Boolean = browserFallbackEngine != null,
        var exactPreparedDomFallback: String? = null,
    ) {
        fun activeOptions(): EnhanceOptions =
            if (exactPreparedDomEnabled) options else options.withoutExactFontSession()

        fun activeEngine(): ExplainableStubParagraphLayoutEngine =
            if (exactPreparedDomEnabled) engine else browserFallbackEngine ?: engine

        fun activeSemanticExactEngine(): ExplainableStubParagraphLayoutEngine? =
            semanticExactEngine.takeIf { exactPreparedDomEnabled }

        fun activeExactFallbackEngine(): ExplainableStubParagraphLayoutEngine? =
            browserFallbackEngine.takeIf { exactPreparedDomEnabled }

        fun disableExactPreparedDom(detail: String) {
            if (!exactPreparedDomEnabled) return
            exactPreparedDomEnabled = false
            exactPreparedDomFallback = detail.take(CAPABILITY_DETAIL_LIMIT)
            root.setAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE, exactPreparedDomFallback!!)
        }
    }

    private enum class ProgressiveJobKind {
        Enhance,
        Relayout,
    }

    private class ProgressiveJob(
        val state: RootState,
        val kind: ProgressiveJobKind,
        val itemCount: Int,
        val processItem: (Int) -> Unit,
        val onItemsFinished: (() -> Unit)? = null,
        val onFailure: (() -> Unit)? = null,
        val stale: (() -> Boolean)? = null,
        val shouldScheduleIdle: (Int) -> Boolean = { false },
        val startedAt: Double,
        var nextIndex: Int = 0,
        var scheduledSliceToken: JsAny? = null,
        var maxSliceDuration: Double = 0.0,
        var commitSkipped: Boolean = false,
    )


    private sealed class ParagraphLayoutPreparation {
        data object Unchanged : ParagraphLayoutPreparation()

        data class Ready(
            val result: LayoutResult,
            val width: Float,
            val measure: Float,
            val exactPreparedDom: Boolean,
        ) : ParagraphLayoutPreparation()

        data class Unsupported(val issue: CapabilityIssue) : ParagraphLayoutPreparation()
    }

    private sealed class ParagraphCommitResult {
        data class Success(val measure: Float) : ParagraphCommitResult()
        data class Unsupported(val issue: CapabilityIssue) : ParagraphCommitResult()
    }

    private data class EnhancedParagraph(
        val source: HTMLElement,
        val originalContent: DocumentFragment,
        val lowered: LoweredParagraph,
        val originalRenderedAttribute: String?,
        val originalPreparedFlowAttribute: String?,
        val originalCanonicalSourceAttribute: String?,
        val originalExactPreparedDomAttribute: String?,
        val originalLangAttribute: String?,
        val originalStyleAttribute: String?,
        val originalPosition: String,
        val originalPositionPriority: String,
        val originalInlineSize: String,
        val originalInlineSizePriority: String,
        val originalFontSize: String,
        val originalFontSizePriority: String,
        val originalHostInlineSizeAttribute: String?,
        var lastMeasure: Float? = null,
        var containingBlockApplied: Boolean = false,
        var hostInlineSizeApplied: String? = null,
        val hostFontSizeApplied: String? = null,
    )

    private data class SourceInlineSize(
        val borderBoxWidth: Double,
        val contentBoxWidth: Double,
        val borderBoxSizing: Boolean,
    )

    private data class LiveParagraphSnapshot(
        val paragraph: EnhancedParagraph,
        val content: DocumentFragment,
        val renderedAttribute: String?,
        val preparedFlowAttribute: String?,
        val canonicalSourceAttribute: String?,
        val exactPreparedDomAttribute: String?,
        val langAttribute: String?,
        val styleAttribute: String?,
        val capabilityNameAttribute: String?,
        val capabilityDetailAttribute: String?,
        val lastMeasure: Float?,
        val containingBlockApplied: Boolean,
        val hostInlineSizeApplied: String?,
        val hostInlineSizeAttribute: String?,
        val originalContentHadChildren: Boolean,
    )

    data class CapabilityIssue(
        val name: String,
        val detail: String,
        val element: HTMLElement,
        val reportToConsole: Boolean = true,
    ) {
        internal var markerCaptured: Boolean = false
        internal var originalNameAttribute: String? = null
        internal var originalDetailAttribute: String? = null
    }

    private fun ensureContainingBlock(paragraph: EnhancedParagraph) {
        if (paragraph.containingBlockApplied) return
        if (computedStyle(paragraph.source, "position").trim().lowercase() != "static") return
        paragraph.source.style.setProperty("position", "relative", "important")
        paragraph.containingBlockApplied = true
    }

    private fun captureSourceInlineSize(paragraph: HTMLElement): SourceInlineSize =
        SourceInlineSize(
            borderBoxWidth = elementFragmentBorderBoxInlineSize(paragraph),
            contentBoxWidth = elementContentWidth(paragraph),
            borderBoxSizing =
                computedStyle(paragraph, "box-sizing").trim().lowercase() == "border-box",
        )

    private fun applyConfiguredHostFontSize(paragraph: HTMLElement, fontSize: Float?): String? {
        if (fontSize == null) return null
        paragraph.style.setProperty("font-size", "${fontSize}px", "important")
        return paragraph.style.getPropertyValue("font-size")
    }

    private fun responsiveSourceMeasure(paragraph: HTMLElement, configuredFontSize: Float?): Float {
        if (configuredFontSize == null) {
            val computedFontSize = parseCssPx(computedStyle(paragraph, "font-size"))
                ?: DEFAULT_FONT_SIZE
            return effectiveLineMeasure(sourceParagraphWidth(paragraph), computedFontSize)
        }
        val originalStyle = paragraph.getAttribute("style")
        paragraph.style.setProperty("font-size", "${configuredFontSize}px", "important")
        return try {
            effectiveLineMeasure(sourceParagraphWidth(paragraph), configuredFontSize)
        } finally {
            if (originalStyle == null) {
                paragraph.removeAttribute("style")
            } else {
                paragraph.setAttribute("style", originalStyle)
            }
        }
    }

    private fun stabilizeContentSizedItemInlineSize(
        paragraph: HTMLElement,
        source: SourceInlineSize,
    ): String? {
        val empty = captureSourceInlineSize(paragraph)
        val sourceUsedInlineSize = if (source.borderBoxSizing) {
            source.borderBoxWidth
        } else {
            source.contentBoxWidth
        }
        val emptyUsedInlineSize = if (source.borderBoxSizing) {
            empty.borderBoxWidth
        } else {
            empty.contentBoxWidth
        }
        // SourceMeasureBeforeCustodyTransfer: flex/grid items and descendants
        // of shrink-to-fit ancestors can derive their used inline size from the
        // semantic children that Tiqian moves into source custody. Detect that
        // real dependency from the before/after used size rather than guessing
        // a finite set of parent display modes. Ordinary blocks keep their host
        // auto sizing; only a custody-induced width change is stabilized.
        if (
            !sourceUsedInlineSize.isFinite() || sourceUsedInlineSize <= 0.0 ||
            !emptyUsedInlineSize.isFinite() ||
            kotlin.math.abs(sourceUsedInlineSize - emptyUsedInlineSize) < 0.5
        ) return null
        val usedInlineSize = sourceUsedInlineSize
        if (!usedInlineSize.isFinite() || usedInlineSize <= 0.0) return null
        val serialized = "${usedInlineSize}px"
        paragraph.style.setProperty("inline-size", serialized, "important")
        paragraph.setAttribute(HOST_INLINE_SIZE_ATTRIBUTE, "true")
        return serialized
    }

    private fun restoreParagraph(paragraph: EnhancedParagraph) {
        releasePreparedParagraphDomStyles(paragraph.source)
        while (paragraph.source.firstChild != null) {
            paragraph.source.removeChild(paragraph.source.firstChild!!)
        }
        paragraph.source.appendChild(paragraph.originalContent)
        restoreAttribute(paragraph.source, "data-tq-rendered", paragraph.originalRenderedAttribute)
        restoreAttribute(
            paragraph.source,
            "data-tq-canonical-plain",
            paragraph.originalPreparedFlowAttribute,
        )
        restoreAttribute(
            paragraph.source,
            CANONICAL_SOURCE_ATTRIBUTE,
            paragraph.originalCanonicalSourceAttribute,
        )
        restoreAttribute(
            paragraph.source,
            EXACT_PREPARED_DOM_ATTRIBUTE,
            paragraph.originalExactPreparedDomAttribute,
        )
        paragraph.source.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE)
        restoreAttribute(paragraph.source, "lang", paragraph.originalLangAttribute)
        if (paragraph.containingBlockApplied &&
            paragraph.source.style.getPropertyValue("position") == "relative" &&
            paragraph.source.style.getPropertyPriority("position") == "important"
        ) {
            if (paragraph.originalPosition.isEmpty()) {
                paragraph.source.style.removeProperty("position")
            } else {
                paragraph.source.style.setProperty(
                    "position",
                    paragraph.originalPosition,
                    paragraph.originalPositionPriority,
                )
            }
        }
        val appliedInlineSize = paragraph.hostInlineSizeApplied
        if (
            appliedInlineSize != null &&
            paragraph.source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) == "true" &&
            paragraph.source.style.getPropertyValue("inline-size") == appliedInlineSize &&
            paragraph.source.style.getPropertyPriority("inline-size") == "important"
        ) {
            if (paragraph.originalInlineSize.isEmpty()) {
                paragraph.source.style.removeProperty("inline-size")
            } else {
                paragraph.source.style.setProperty(
                    "inline-size",
                    paragraph.originalInlineSize,
                    paragraph.originalInlineSizePriority,
                )
            }
        }
        val appliedFontSize = paragraph.hostFontSizeApplied
        if (
            appliedFontSize != null &&
            paragraph.source.style.getPropertyValue("font-size") == appliedFontSize &&
            paragraph.source.style.getPropertyPriority("font-size") == "important"
        ) {
            if (paragraph.originalFontSize.isEmpty()) {
                paragraph.source.style.removeProperty("font-size")
            } else {
                paragraph.source.style.setProperty(
                    "font-size",
                    paragraph.originalFontSize,
                    paragraph.originalFontSizePriority,
                )
            }
        }
        restoreAttribute(
            paragraph.source,
            HOST_INLINE_SIZE_ATTRIBUTE,
            paragraph.originalHostInlineSizeAttribute,
        )
        if (paragraph.originalStyleAttribute == null) {
            if (paragraph.source.getAttribute("style")?.isBlank() != false) {
                paragraph.source.removeAttribute("style")
            }
        }
        paragraph.containingBlockApplied = false
        paragraph.hostInlineSizeApplied = null
    }

    private fun clearIssue(issue: CapabilityIssue) {
        if (!issue.markerCaptured) return
        restoreAttribute(issue.element, "data-tiqian-capability-issue", issue.originalNameAttribute)
        restoreAttribute(issue.element, "data-tiqian-capability-detail", issue.originalDetailAttribute)
        issue.markerCaptured = false
    }

    private fun restoreAttribute(element: HTMLElement, name: String, value: String?) {
        if (value == null) {
            element.removeAttribute(name)
        } else {
            element.setAttribute(name, value)
        }
    }

}
