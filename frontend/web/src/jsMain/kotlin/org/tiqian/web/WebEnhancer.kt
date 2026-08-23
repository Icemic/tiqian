package org.tiqian.web

import kotlin.js.js
import kotlinx.browser.document
import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.core.LayoutResult
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.shaping.HarfBuzzSessionFontMetricsResolver
import org.tiqian.shaping.HarfBuzzSessionTextShaper
import org.tiqian.shaping.web.WebCanvasFontMetricsResolver
import org.tiqian.shaping.web.WebCanvasTextShaper
import org.tiqian.shaping.web.WebCjkDashCapability
import org.tiqian.shaping.web.WebFontFamilies
import org.w3c.dom.HTMLElement

/**
 * Browser embed API for ADR 0039 dogfood.
 *
 * Host pages keep their SSR markdown for no-JS / SEO / Pagefind. Tiqian enhances
 * eligible paragraphs in place: the source `<p>` remains the semantic and CSS
 * owner while only its inline children are replaced with pre-broken line DOM.
 */
object TiqianWeb {
    internal const val ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]"
    internal const val DEFAULT_PARAGRAPH_SELECTOR = "p, li"

    private var installed = false
    // DetachedRootWeakOwnership: navigation can discard a rendered article
    // without reconstructing its semantic DOM. Weak ownership retains the
    // source fragments only if a host later reconnects that exact element.
    internal val states: dynamic = js("new WeakMap()")

    fun install() {
        if (installed) return
        installed = true
        installTiqianCopyHandler()
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
        enhanceProgressively(root, options, "Enhance")

    private fun enhanceProgressively(
        root: HTMLElement,
        options: EnhanceOptions,
        kind: String,
    ) {
        installTiqianCopyHandler()
        destroy(root)
        val state = createRootState(root, options)
        val sourceCandidates = paragraphCandidates(root, state.options.paragraphSelector)
        if (rejectMissingSharedRuntimeStyles(state, sourceCandidates)) return
        // Work order sorts by viewport distance; itemTierIndex keeps the
        // document-order index of each work item, so a coordinator tier flip
        // arriving in document order gates its item in work order in O(1).
        val distances = DoubleArray(sourceCandidates.size) {
            paragraphViewportDistance(sourceCandidates[it])
        }
        val itemTierIndex = Array(sourceCandidates.size) { it }.apply {
            sortWith(compareBy<Int> { distances[it] }.thenBy { it })
        }.toIntArray()
        val candidates = itemTierIndex.map { sourceCandidates[it] }
        val capturedMeasures = candidates.map { paragraph ->
            responsiveSourceMeasure(paragraph, state.options.fontSize)
        }
        var stale = false
        fun liveMeasure(index: Int): Float =
            responsiveSourceMeasure(candidates[index], state.options.fontSize)
        states.set(root, state)
        publishState(state, keepEmpty = true)
        startProgressiveJob(
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
                // StaleFinishKeepsCommittedParagraphs: the per-item guard already
                // refuses to commit a paragraph whose measure drifted, so the
                // committed ones were current when they landed. Rolling them back
                // here would tear the root to native source whenever a coordinated
                // job spans frames across a width change; the stale report
                // hands the follow-up to element.js, which dispatches one
                // latest-width relayout.
                stale = stale || candidates.indices.any { index ->
                    liveMeasure(index) != capturedMeasures[index]
                }
            },
            stale = { stale },
            itemTierIndex = itemTierIndex,
            paragraphsByDoc = sourceCandidates,
        )
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

    init {
        // Install the embedded custody, eligibility, and progressive-job scripts
        // eagerly so every world that reaches this object has the globals available.
        custodyBridge()
        eligibilityBridge()
        progressiveJobBridge()
    }

    fun destroy(root: HTMLElement) {
        progressiveJobBridge().cancelJob(root)
        val state = states.get(root) as? RootState
        states.delete(root)
        if (state != null) {
            for (paragraph in state.paragraphs) {
                custodyBridge().restoreParagraph(paragraph.source)
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
        progressiveJobBridge().cancelJob(root)
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
                    eligibilityBridge().shouldTryParagraph(paragraph)
                ) add(paragraph)
            }
        }
    }

    internal fun publishState(state: RootState, keepEmpty: Boolean = false) {
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

    internal fun strandedSourceParagraphs(root: HTMLElement, state: RootState): List<HTMLElement> {
        val candidates = paragraphCandidates(root, state.options.paragraphSelector)
        if (state.paragraphs.isEmpty()) return candidates
        val renderedSources = HashSet<HTMLElement>(state.paragraphs.size * 2)
        for (paragraph in state.paragraphs) renderedSources.add(paragraph.source)
        return candidates.filter { it !in renderedSources }
    }

    internal fun relayout(root: HTMLElement) {
        if (progressiveJobBridge().jobKind(root) == "Enhance") {
            val running = states.get(root) as? RootState
            if (running != null) {
                enhanceProgressively(root, running.options)
                return
            }
        }
        val state = states.get(root) as? RootState ?: run {
            enhanceProgressively(root, EnhanceOptions(), "Relayout")
            return
        }
        val activeOptions = state.activeOptions()
        val activeEngine = state.activeEngine()
        val activeExactFallbackEngine = state.activeExactFallbackEngine()
        progressiveJobBridge().cancelJob(root)
        if (state.issues.any { it.name in WIDTH_DEPENDENT_CAPABILITY_ISSUES }) {
            // WidthDependentCapabilityTransitionRetry: only named
            // capabilities whose eligibility depends on line count need to be
            // lowered again at the new width. Restore semantic source once,
            // then let viewport-near paragraphs take over atomically in bounded
            // slices just like any other source refresh.
            enhanceProgressively(root, state.options, "Relayout")
            return
        }
        val rendered = state.paragraphs
        // StrandedEnhanceResume: a stale enhance finish leaves the paragraphs
        // it skipped in semantic source, and this follow-up relayout is the
        // only job that will reach them. Fold them into the work set at the
        // live width; the rendered ones keep the snapshot path below.
        val stranded = strandedSourceParagraphs(root, state)
        val renderedCount = rendered.size
        val count = renderedCount + stranded.size
        val workOrder = if (paragraphViewportDistance(root) <= 0.0) {
            IntArray(count) { it }
        } else {
            val distances = DoubleArray(count) { mixIndex ->
                if (mixIndex < renderedCount) {
                    paragraphViewportDistance(rendered[mixIndex].source)
                } else {
                    paragraphViewportDistance(stranded[mixIndex - renderedCount])
                }
            }
            Array(count) { it }.apply {
                sortWith(compareBy<Int> { distances[it] }.thenBy { it })
            }.toIntArray()
        }
        // WidthSnapshotPerRelayoutJob: every paragraph is prepared against the
        // geometry seen when the job starts. If the host changes again while
        // slices are running, element.js schedules one latest-width follow-up
        // instead of allowing a queue of obsolete widths to replay.
        val widths = FloatArray(renderedCount) { paragraphWidth(rendered[it]) }
        val commitSession = ProgressiveRelayoutSession(
            paragraphs = rendered,
            state = state,
        )
        val rootWidth = elementFragmentBorderBoxInlineSize(root)
        startProgressiveJob(
            state = state,
            kind = "Relayout",
            itemCount = count,
            processItem = { index ->
                if (commitSession.stale) {
                    return@startProgressiveJob
                }
                val mixIndex = workOrder[index]
                if (mixIndex >= renderedCount) {
                    processParagraph(stranded[mixIndex - renderedCount], state)
                    return@startProgressiveJob
                }
                val paragraph = rendered[mixIndex]
                val preparation = prepareParagraphLayout(
                    paragraph = paragraph,
                    options = activeOptions,
                    engine = activeEngine,
                    semanticExactEngine = state.activeSemanticExactEngine(),
                    browserFallbackEngine = activeExactFallbackEngine,
                    widthOverride = widths[mixIndex],
                )
                commitSession.processItem(mixIndex, preparation)
            },
            onItemsFinished = commitSession::finish,
            onFailure = commitSession::rollback,
            stale = {
                commitSession.stale || kotlin.math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5f
            },
            itemTierIndex = workOrder,
            paragraphsByDoc = rendered.map { it.source } + stranded,
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

    private class ProgressiveRelayoutSession(
        paragraphs: List<EnhancedParagraph>,
        private val state: RootState,
    ) {
        private val paragraphs = paragraphs.toList()
        private val snapshots = LinkedHashMap<EnhancedParagraph, CustodyLiveSnapshotJs>()
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
                    snapshots[paragraph] = custodyBridge().captureLive(paragraph.source, paragraph.lastMeasure)
                    unsupported += paragraph to preparation.issue
                    custodyBridge().restoreParagraph(paragraph.source)
                }
                is ParagraphLayoutPreparation.Ready -> {
                    snapshots[paragraph] = custodyBridge().captureLive(paragraph.source, paragraph.lastMeasure)
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
                            custodyBridge().restoreParagraph(paragraph.source)
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
            val snapshotsArray = snapshots.values.toTypedArray()
            val results = custodyBridge().asDynamic().rollback(snapshotsArray).unsafeCast<Array<CustodyRollbackResultJs>>()
            val paragraphBySource = paragraphs.associateBy { it.source }
            for (result in results) {
                paragraphBySource[result.source]?.lastMeasure = result.lastMeasure
            }
        }
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

    internal data class RootState(
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


    internal sealed class ParagraphLayoutPreparation {
        data object Unchanged : ParagraphLayoutPreparation()

        data class Ready(
            val result: LayoutResult,
            val width: Float,
            val measure: Float,
            val exactPreparedDom: Boolean,
        ) : ParagraphLayoutPreparation()

        data class Unsupported(val issue: CapabilityIssue) : ParagraphLayoutPreparation()
    }

    internal sealed class ParagraphCommitResult {
        data class Success(val measure: Float) : ParagraphCommitResult()
        data class Unsupported(val issue: CapabilityIssue) : ParagraphCommitResult()
    }

    internal data class EnhancedParagraph(
        val source: HTMLElement,
        val lowered: LoweredParagraph,
        var lastMeasure: Float? = null,
    )

    internal data class SourceInlineSize(
        val borderBoxWidth: Double,
        val contentBoxWidth: Double,
        val borderBoxSizing: Boolean,
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

}
