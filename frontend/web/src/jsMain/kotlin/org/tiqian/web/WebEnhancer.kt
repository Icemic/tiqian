package org.tiqian.web

import kotlin.js.js
import kotlinx.browser.document
import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
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
            processParagraphTs(paragraph, state)
        }
        publishState(state)
        return state.paragraphs.length
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
                    processParagraphTs(candidates[index], state)
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
            val issue = tsCapabilityIssueJs(
                name = "MissingSharedRuntimeStyles",
                detail = "Load @tiqian/prose/styles.css before TiqianWeb.enhance",
                element = paragraph,
                reportToConsole = true,
            )
            state.issues.push(issue)
            lifecycleBridge().reportIssue(issue)
        }
        publishState(state)
        return true
    }

    init {
        // Install every embedded engine script eagerly so any world that
        // reaches this object finds the globals available. browserMetricsBridge
        // installs the canvas fonts, metrics, shaping and bridge scripts in
        // their load order.
        custodyBridge()
        eligibilityBridge()
        progressiveJobBridge()
        lifecycleBridge()
        workerRequestBridge()
        markdownLoweringBridge()
        responsiveMeasureBridge()
        prepareParagraphLayoutBridge()
        commitPreparedParagraphBridge()
        processParagraphBridge()
        preparedMetadataBridge()
        canvasFontsBridge()
        browserMetricsBridge()
        progressiveRelayoutSessionBridge()
    }

    fun destroy(root: HTMLElement) {
        progressiveJobBridge().cancelJob(root)
        val state = states.get(root) as? RootState
        states.delete(root)
        if (state != null) {
            for (index in 0 until state.paragraphs.length) {
                custodyBridge().restoreParagraph(jsLiveListGet(state.paragraphs, index)!!.source)
            }
            for (index in 0 until state.issues.length) {
                lifecycleBridge().clearIssue(jsLiveListGet(state.issues, index))
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
        return RootState(
            root = root,
            options = resolved,
            tsOptions = resolved.toTsOptions(root),
            browserFallback = buildBrowserFallbackDescriptor(resolved),
            paragraphs = jsLiveListOf(),
            issues = jsLiveListOf(),
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
        val hasWork = state.paragraphs.length > 0 || state.issues.length > 0
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
            "${state.paragraphs.length + observableSnapshotCount(state.root)}",
        )
        if (state.issues.length == 0) {
            state.root.removeAttribute("data-tiqian-issue-count")
        } else {
            state.root.setAttribute("data-tiqian-issue-count", "${state.issues.length}")
        }
    }

    internal fun strandedSourceParagraphs(root: HTMLElement, state: RootState): List<HTMLElement> {
        val candidates = paragraphCandidates(root, state.options.paragraphSelector)
        if (state.paragraphs.length == 0) return candidates
        val renderedSources = HashSet<HTMLElement>(state.paragraphs.length * 2)
        for (index in 0 until state.paragraphs.length) {
            renderedSources.add(jsLiveListGet(state.paragraphs, index)!!.source)
        }
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
        progressiveJobBridge().cancelJob(root)
        val hasWidthDependentIssue = (0 until state.issues.length).any { index ->
            tsIssueNameJs(jsLiveListGet(state.issues, index)) in WIDTH_DEPENDENT_CAPABILITY_ISSUES
        }
        if (hasWidthDependentIssue) {
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
        val renderedCount = rendered.length
        val count = renderedCount + stranded.size
        val workOrder = if (paragraphViewportDistance(root) <= 0.0) {
            IntArray(count) { it }
        } else {
            val distances = DoubleArray(count) { mixIndex ->
                if (mixIndex < renderedCount) {
                    paragraphViewportDistance(jsLiveListGet(rendered, mixIndex)!!.source)
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
        val widths = FloatArray(renderedCount) { paragraphWidthTs(jsLiveListGet(rendered, it)!!) }
        val commitSession = progressiveRelayoutSessionBridge().createProgressiveRelayoutSession(
            state.sessionArgument(),
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
                    processParagraphTs(stranded[mixIndex - renderedCount], state)
                    return@startProgressiveJob
                }
                val paragraph = jsLiveListGet(rendered, mixIndex)!!
                val preparation = prepareParagraphLayoutBridge().prepareParagraphLayout(
                    ffi = tsFfiFacade,
                    argument = state.prepareArgument(paragraph, widths[mixIndex].toDouble()),
                )
                commitSession.processItem(mixIndex, preparation)
            },
            onItemsFinished = { commitSession.finish() },
            onFailure = { commitSession.rollback() },
            stale = {
                commitSession.stale || kotlin.math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5f
            },
            itemTierIndex = workOrder,
            paragraphsByDoc = rendered.sourcesToArray().toList() + stranded,
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
        // Canonical TS options and the browser fallback descriptor are built
        // once per root and consumed by every embedded TS orchestrator.
        val tsOptions: JsAny?,
        val browserFallback: JsAny?,
        // Live JS arrays: the TS session module splices and pushes these by
        // reference, so the Kotlin host mutates the same storage.
        val paragraphs: JsLiveList<EnhancedParagraphJs>,
        val issues: JsLiveList<JsAny?>,
        // PreparedDomLane: every paragraph renders through the prepared DOM,
        // including roots that never configured an exact font session. After
        // a replay fails geometry validation the flag distrusts the exact
        // session metrics for the whole root; paragraphs keep rendering
        // through the prepared bridge with browser metrics, and the
        // per-paragraph validator still guards every render.
        var preparedDomEnabled: Boolean = true,
        var preparedDomFallback: String? = null,
    ) {
        fun activeOptions(): EnhanceOptions =
            if (preparedDomEnabled) options else options.withoutExactFontSession()

        fun activeTsOptions(): JsAny? =
            if (preparedDomEnabled) tsOptions else lifecycleBridge().withoutExactFontSession(tsOptions)

        fun activeExactSessionDescriptor(): JsAny? =
            activeOptions().conformingExactFontSessionId()?.let(::buildExactSessionDescriptorJs)

        fun disableExactPreparedDom(detail: String) {
            if (!preparedDomEnabled) return
            preparedDomEnabled = false
            preparedDomFallback = detail.take(CAPABILITY_DETAIL_LIMIT)
            root.setAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE, preparedDomFallback!!)
        }
    }

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
