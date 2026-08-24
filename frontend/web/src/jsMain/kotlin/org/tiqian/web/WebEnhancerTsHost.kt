package org.tiqian.web

import org.tiqian.ffi.js.classifyFontRole
import org.tiqian.ffi.js.firstDivergentInlineShapingProperty
import org.tiqian.ffi.js.precomputeParagraphWithBrowserMetrics
import org.tiqian.ffi.js.precomputeParagraphWithDiagnostics
import org.tiqian.ffi.js.unsupportedInlineShapingProperties
import org.tiqian.web.TiqianWeb.EnhanceOptions
import org.tiqian.web.TiqianWeb.RootState
import org.w3c.dom.HTMLElement

// Interop layer between the Kotlin host (WebEnhancer.kt) and the TS
// orchestrator modules embedded through the bridge generators. Everything
// the TS side consumes is built here: the ffi facade over the Kotlin/JS
// precompute exports, the canonical TS options object, the browser
// fallback descriptor, and the engine-state descriptor shared by
// processParagraph and the progressive relayout session.
//
// The @JsFun builders below are single-expression arrows. Multi-statement
// bodies would return from the Kotlin caller, so every builder returns one
// object literal.

// ---------------------------------------------------------------------------
// ffi facade
// ---------------------------------------------------------------------------

// The five-member ffi object the TS orchestrators consume. The two
// precompute members are explicit-arity lambdas because
// prepare-paragraph-layout.js calls precomputeParagraphWithBrowserMetrics
// via apply() with a spread argument list; a direct function reference with
// trailing default parameters would not match that call shape.
@JsFun(
    "(classifyRole, unsupportedProps, divergentProp, diagnostics, browserMetrics) => " +
        "({ classifyFontRole: classifyRole, unsupportedInlineShapingProperties: unsupportedProps, " +
        "firstDivergentInlineShapingProperty: divergentProp, " +
        "precomputeParagraphWithDiagnostics: diagnostics, " +
        "precomputeParagraphWithBrowserMetrics: browserMetrics })",
)
private external fun buildFfiFacadeJs(
    classifyRole: (String, Int, Int, String) -> String,
    unsupportedProps: () -> Array<String>,
    divergentProp: (Array<String>, Array<String>) -> String?,
    diagnostics: (
        String, String, Double, String, Double, Double, String, Int, Boolean, Double, Boolean,
        String, String, String, String, String?, Double, String?, Double?, Boolean?,
    ) -> String,
    browserMetrics: (
        String, Double, String, Double, Double, String, Int, Boolean, Double, Boolean,
        String, String, String, String, String?, Double, (String) -> String, (String) -> String,
        String?, Double?, Boolean?,
    ) -> String,
): JsAny?

internal val tsFfiFacade: JsAny? by lazy {
    buildFfiFacadeJs(
        { text, start, end, locale -> classifyFontRole(text, start, end, locale) },
        { unsupportedInlineShapingProperties() },
        { elementValues, paragraphValues -> firstDivergentInlineShapingProperty(elementValues, paragraphValues) },
        { fontSessionId, text, maxWidthPx, fontFamilies, fontSizePx, lineHeightPx, locale, fontWeight,
            italic, firstLineIndentIc, lineLengthGridEnabled, sourceBoundaries, textSpans,
            inlineBoxes, lineBreakSpans, inlineObjects, zeroAdvanceEpsilonPx, decorations,
            emphasisDotGapEm, renderEvidenceOverride ->
            precomputeParagraphWithDiagnostics(
                fontSessionId, text, maxWidthPx, fontFamilies, fontSizePx, lineHeightPx, locale,
                fontWeight, italic, firstLineIndentIc, lineLengthGridEnabled, sourceBoundaries,
                textSpans, inlineBoxes, lineBreakSpans, inlineObjects, zeroAdvanceEpsilonPx,
                decorations, emphasisDotGapEm, renderEvidenceOverride,
            )
        },
        { text, maxWidthPx, fontFamilies, fontSizePx, lineHeightPx, locale, fontWeight, italic,
            firstLineIndentIc, lineLengthGridEnabled, sourceBoundaries, textSpans, inlineBoxes,
            lineBreakSpans, inlineObjects, zeroAdvanceEpsilonPx, shapeJson, metricsJson,
            decorations, emphasisDotGapEm, renderEvidenceOverride ->
            precomputeParagraphWithBrowserMetrics(
                text, maxWidthPx, fontFamilies, fontSizePx, lineHeightPx, locale, fontWeight, italic,
                firstLineIndentIc, lineLengthGridEnabled, sourceBoundaries, textSpans, inlineBoxes,
                lineBreakSpans, inlineObjects, zeroAdvanceEpsilonPx, shapeJson, metricsJson,
                decorations, emphasisDotGapEm, renderEvidenceOverride,
            )
        },
    )
}

// Hands the Kotlin shaping facade to the TypeScript root-state module so the
// engine entry paths can call currentFfi() without a host-provided binding.
@JsFun("(ffi) => { if (globalThis.__TiqianRootState && typeof globalThis.__TiqianRootState.bindFfi === 'function') globalThis.__TiqianRootState.bindFfi(ffi); }")
internal external fun bindTsRootStateFfi(ffi: JsAny?)

// ---------------------------------------------------------------------------
// options
// ---------------------------------------------------------------------------

// Raw option bag in the public TiqianWeb option-key shape; lifecycle.js
// optionsFromJs canonicalizes it into the nested engine shape.
@JsFun(
    "(cjk, latin, monospace, cjkSerif, latinSerif, fontSize, lineHeight, firstLineIndentIc, " +
        "emphasisDotGapEm, strongAsEmphasisMarks, paragraphSelector, requireExactLayoutWorker, " +
        "dashStatus, dashDetail, sessionStatus, sessionId, sessionDetail) => " +
        "({ cjkFontFamily: cjk, latinFontFamily: latin, monospaceFontFamily: monospace, " +
        "cjkSerifFontFamily: cjkSerif, latinSerifFontFamily: latinSerif, fontSize: fontSize, " +
        "lineHeight: lineHeight, firstLineIndentIc: firstLineIndentIc, " +
        "emphasisDotGapEm: emphasisDotGapEm, strongAsEmphasisMarks: strongAsEmphasisMarks, " +
        "paragraphSelector: paragraphSelector, requireExactLayoutWorker: requireExactLayoutWorker, " +
        "cjkDashCapability: (dashStatus == null && dashDetail == null) ? null : " +
        "{ status: dashStatus, detail: dashDetail }, " +
        "exactFontSession: sessionStatus == null ? null : " +
        "{ status: sessionStatus, sessionId: sessionId, detail: sessionDetail } })",
)
private external fun buildOptionsBagJs(
    cjk: String?,
    latin: String?,
    monospace: String?,
    cjkSerif: String?,
    latinSerif: String?,
    fontSize: Double?,
    lineHeight: Double?,
    firstLineIndentIc: Double?,
    emphasisDotGapEm: Double?,
    strongAsEmphasisMarks: Boolean?,
    paragraphSelector: String?,
    requireExactLayoutWorker: Boolean?,
    dashStatus: String?,
    dashDetail: String?,
    sessionStatus: String?,
    sessionId: String?,
    sessionDetail: String?,
): JsAny?

// Canonicalize the Kotlin options into the TS engine shape. The Kotlin
// options are already root-defaulted, so withRootDefaults re-resolves the
// same families and stays idempotent.
internal fun EnhanceOptions.toTsOptions(root: HTMLElement): JsAny? =
    lifecycleBridge().withRootDefaults(toTsCanonicalOptions(), root)

// Canonical options without root defaults. The TS worker-request module
// runs its own snapshot-eligibility gate and withRootDefaults; feeding it
// root-defaulted families would fail that gate on every call.
internal fun EnhanceOptions.toTsCanonicalOptions(): JsAny? =
    lifecycleBridge().optionsFromJs(
        buildOptionsBagJs(
            fontFamilies.cjk,
            fontFamilies.latin,
            fontFamilies.monospace,
            fontFamilies.cjkSerif,
            fontFamilies.latinSerif,
            fontSize?.toDouble(),
            lineHeight?.toDouble(),
            firstLineIndentIc.toDouble(),
            emphasisDotGapEm.toDouble(),
            strongAsEmphasisMarks,
            paragraphSelector,
            requireExactLayoutWorker,
            cjkDashCapability?.status,
            cjkDashCapability?.detail,
            exactFontSession?.status,
            exactFontSession?.sessionId,
            exactFontSession?.detail,
        ),
    )

// ---------------------------------------------------------------------------
// browser fallback descriptor
// ---------------------------------------------------------------------------

@JsFun(
    "(cjk, latin, monospace, cjkSerif, latinSerif) => " +
        "({ cjk: cjk, latin: latin, latinMonospace: monospace, cjkSerif: cjkSerif, latinSerif: latinSerif })",
)
private external fun buildFontFamiliesConfigJs(
    cjk: String?,
    latin: String?,
    monospace: String?,
    cjkSerif: String?,
    latinSerif: String?,
): JsAny?

// The canvas modules own their probe nodes; attachProbe keeps the probe in
// the document without duplicating it across measures.
@JsFun(
    "() => ({ createCanvasContext: () => document.createElement('canvas').getContext('2d'), " +
        "createProbeElement: () => document.createElement('span'), " +
        "attachProbe: (node) => { if (!node.parentNode) document.body.appendChild(node); } })",
)
private external fun browserMetricsEnvJs(): JsAny?

@JsFun(
    "(fonts, dashStatus, dashDetail, env) => " +
        "({ fonts: fonts, cjkDashCapability: dashStatus == null ? null : " +
        "{ status: dashStatus, detail: dashDetail }, env: env })",
)
private external fun buildBrowserFallbackConfigJs(
    fonts: JsAny?,
    dashStatus: String?,
    dashDetail: String?,
    env: JsAny?,
): JsAny?

@JsFun("(bridge) => ({ bridge: bridge })")
private external fun wrapBrowserFallbackJs(bridge: JsAny?): JsAny?

// The {bridge} descriptor every TS layout lane consumes. The inner bridge
// adapts the canvas shaper and metrics resolver to the two JSON callbacks
// of precomputeParagraphWithBrowserMetrics. Built once per root.
internal fun buildBrowserFallbackDescriptor(options: EnhanceOptions): JsAny? {
    val fonts = canvasFontsBridge().createFontFamilies(
        buildFontFamiliesConfigJs(
            options.fontFamilies.cjk,
            options.fontFamilies.latin,
            options.fontFamilies.monospace,
            options.fontFamilies.cjkSerif,
            options.fontFamilies.latinSerif,
        ),
    )
    return wrapBrowserFallbackJs(
        browserMetricsBridge().createBrowserMetricsBridge(
            buildBrowserFallbackConfigJs(
                fonts,
                options.cjkDashCapability?.status,
                options.cjkDashCapability?.detail,
                browserMetricsEnvJs(),
            ),
        ),
    )
}

// ---------------------------------------------------------------------------
// engine-state and argument descriptors
// ---------------------------------------------------------------------------

@JsFun("(sessionId) => ({ sessionId: sessionId })")
internal external fun buildExactSessionDescriptorJs(sessionId: String): JsAny?

@JsFun(
    "(ffi, options, preparedDomEnabled, exactSession, browserFallback, onIssue, " +
        "onParagraphCommitted, onDisableExactPreparedDom, paragraphs, issues) => " +
        "({ ffi: ffi, options: options, preparedDomEnabled: preparedDomEnabled, " +
        "exactSession: exactSession, browserFallback: browserFallback, onIssue: onIssue, " +
        "onParagraphCommitted: onParagraphCommitted, " +
        "onDisableExactPreparedDom: onDisableExactPreparedDom, paragraphs: paragraphs, " +
        "issues: issues })",
)
private external fun buildEngineStateJs(
    ffi: JsAny?,
    options: JsAny?,
    preparedDomEnabled: Boolean,
    exactSession: JsAny?,
    browserFallback: JsAny?,
    onIssue: (JsAny?) -> Unit,
    onParagraphCommitted: (JsAny?) -> Unit,
    onDisableExactPreparedDom: (String) -> Unit,
    paragraphs: JsLiveList<EnhancedParagraphJs>,
    issues: JsLiveList<JsAny?>,
): JsAny?

@JsFun("(ffi, paragraph, engineState) => ({ ffi: ffi, paragraph: paragraph, state: engineState })")
private external fun buildProcessParagraphArgumentJs(ffi: JsAny?, paragraph: HTMLElement, engineState: JsAny?): JsAny?

@JsFun(
    "(paragraph, options, exactSession, browserFallback, widthOverride) => " +
        "({ paragraph: paragraph, options: options, exactSession: exactSession, " +
        "browserFallback: browserFallback, widthOverride: widthOverride })",
)
private external fun buildPrepareArgumentJs(
    paragraph: EnhancedParagraphJs,
    options: JsAny?,
    exactSession: JsAny?,
    browserFallback: JsAny?,
    widthOverride: Double?,
): JsAny?

@JsFun("(paragraphs, engineState) => ({ paragraphs: paragraphs, state: engineState })")
private external fun buildSessionArgumentJs(
    paragraphs: JsLiveList<EnhancedParagraphJs>,
    engineState: JsAny?,
): JsAny?

// ---------------------------------------------------------------------------
// paragraphs and issues as live JS arrays
// ---------------------------------------------------------------------------

// Structural view of the paragraph items the TS orchestrators create and
// mutate. The session module splices and pushes these arrays by reference,
// so the Kotlin host must mutate the same storage, never a copy.
internal external interface EnhancedParagraphJs {
    val source: HTMLElement
    val lowered: JsAny?
    var lastMeasure: Double?
}

internal external interface JsLiveList<T> {
    var length: Int
    fun push(item: T): Int
    fun indexOf(item: T): Int
}

// Element access must go through @JsFun indexers: an operator get on an
// external interface compiles to a .get() method call, and a plain JS
// array has no such method.
@JsFun("(list, index) => list[index]")
internal external fun <T> jsLiveListGet(list: JsLiveList<T>?, index: Int): T?

@JsFun("(list, index, value) => { list[index] = value; }")
internal external fun <T> jsLiveListSet(list: JsLiveList<T>?, index: Int, value: T)

internal fun <T> jsLiveListOf(): JsLiveList<T> = js("[]").unsafeCast<JsLiveList<T>>()

// In-place compaction: keep storage identity for the TS session, which
// holds a reference to the same array.
internal fun <T> JsLiveList<T>.removeAllMatching(predicate: (T) -> Boolean) {
    var write = 0
    for (read in 0 until length) {
        val item = jsLiveListGet(this, read) ?: continue
        if (predicate(item)) continue
        if (write != read) jsLiveListSet(this, write, item)
        write += 1
    }
    length = write
}

internal fun JsLiveList<EnhancedParagraphJs>.sourcesToArray(): Array<HTMLElement> {
    val result = arrayOfNulls<HTMLElement>(length)
    for (index in 0 until length) {
        result[index] = jsLiveListGet(this, index)!!.source
    }
    @Suppress("UNCHECKED_CAST")
    return result as Array<HTMLElement>
}

// ---------------------------------------------------------------------------
// issues
// ---------------------------------------------------------------------------

// lifecycle.reportIssue and clearIssue mutate the issue object itself
// (markerCaptured, original attribute snapshots), so every recorded issue
// must be the TS-shaped plain object the lifecycle module owns.
@JsFun(
    "(name, detail, element, reportToConsole) => " +
        "({ name: name, detail: detail, element: element, reportToConsole: reportToConsole, " +
        "markerCaptured: false })",
)
internal external fun tsCapabilityIssueJs(
    name: String,
    detail: String,
    element: HTMLElement,
    reportToConsole: Boolean,
): JsAny?

@JsFun("(issue) => ((issue && issue.name) || '')")
internal external fun tsIssueNameJs(issue: JsAny?): String

// ---------------------------------------------------------------------------
// engine-state assembly
// ---------------------------------------------------------------------------

// The single engine-state descriptor consumed by processParagraph (as
// argument.state) and by the progressive relayout session (as
// argument.state). Both TS modules read the same live arrays.
internal fun RootState.engineStateDescriptor(): JsAny? = buildEngineStateJs(
    tsFfiFacade,
    tsOptions,
    preparedDomEnabled,
    activeExactSessionDescriptor(),
    browserFallback,
    { issue -> issues.push(issue) },
    { item -> paragraphs.push(item.unsafeCast<EnhancedParagraphJs>()) },
    { detail -> disableExactPreparedDom(detail) },
    paragraphs,
    issues,
)

internal fun RootState.processParagraphArgument(paragraph: HTMLElement): JsAny? =
    buildProcessParagraphArgumentJs(tsFfiFacade, paragraph, engineStateDescriptor())

internal fun RootState.sessionArgument(): JsAny? = buildSessionArgumentJs(paragraphs, engineStateDescriptor())

internal fun RootState.prepareArgument(
    paragraph: EnhancedParagraphJs,
    widthOverride: Double?,
): JsAny? = buildPrepareArgumentJs(
    paragraph,
    activeTsOptions(),
    activeExactSessionDescriptor(),
    browserFallback,
    widthOverride,
)

internal fun paragraphWidthTs(paragraph: EnhancedParagraphJs): Float =
    responsiveMeasureBridge().sourceParagraphWidth(paragraph.source).toFloat()

// ---------------------------------------------------------------------------
// entry points over the embedded orchestrators
// ---------------------------------------------------------------------------

// The TS process-paragraph module owns lowering, custody, prepare, commit,
// issue reporting and paragraph tracking; the engine-state descriptor
// carries the live arrays back into Kotlin.
internal fun processParagraphTs(paragraph: HTMLElement, state: RootState) {
    processParagraphBridge().processParagraph(state.processParagraphArgument(paragraph))
}

// Worker request serialization for the public engine export. The TS module
// applies the root-scope gate, snapshot eligibility and root defaults.
internal fun TiqianWeb.workerLayoutRequest(
    root: HTMLElement,
    paragraph: HTMLElement,
    options: EnhanceOptions,
): String? = workerRequestBridge().workerLayoutRequestForRoot(
    tsFfiFacade,
    root,
    paragraph,
    options.toTsCanonicalOptions(),
)
