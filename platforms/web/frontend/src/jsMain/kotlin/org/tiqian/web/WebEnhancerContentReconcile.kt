package org.tiqian.web

import org.tiqian.web.TiqianWeb.EnhancedParagraph
import org.tiqian.web.TiqianWeb.ProgressiveJob
import org.tiqian.web.TiqianWeb.ProgressiveJobKind
import org.tiqian.web.TiqianWeb.RootState
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

/**
 * HostContentReconcile: a live-DOM content change on an enhanced root
 * (framework re-render, textContent write, node insertion or removal) must
 * re-enter the layout pipeline. Classification runs per paragraph, never per
 * MutationRecord: engine commits are proven engine-owned by the
 * RenderedContentInvariant identity check, so only real host mutations act.
 *
 * The read-only [probeContentDrift] answers the same question during a
 * captured in-flight job without touching the DOM, so element.js can cancel
 * only on real drift instead of on its own render output.
 */
internal fun TiqianWeb.probeContentDrift(root: HTMLElement): String {
    val state = states.get(root) as? RootState
        ?: return """{"unknown":1,"drifted":0,"dead":0,"custody":0}"""
    var drifted = 0
    var dead = 0
    var custody = 0
    for (paragraph in state.paragraphs) {
        if (!paragraph.source.isConnected) {
            dead += 1
        } else if (!renderedContentMatches(paragraph)) {
            drifted += 1
        } else if (!custodyContentMatches(paragraph)) {
            custody += 1
        }
    }
    return """{"unknown":0,"drifted":$drifted,"dead":$dead,"custody":$custody}"""
}

/**
 * Returns a JSON verdict with the per-class paragraph counts. The "idle"
 * outcome means the observed records were engine-owned output and element.js
 * clears its flag without scheduling anything. The counts make the commit
 * decision inspectable from tests and from the page.
 */
internal fun TiqianWeb.reconcileContent(root: HTMLElement, tainted: Array<HTMLElement>): String {
    val state = states.get(root) as? RootState
        ?: return """{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}"""
    // DeadTrackedParagraphDrop: innerHTML re-projection orphans the runtime
    // onto detached originals. Such entries can never render again; drop them
    // so the re-projected clones are adopted as fresh candidates.
    val dead = state.paragraphs.filter { !it.source.isConnected }
    for (paragraph in dead) state.paragraphs.remove(paragraph)
    val drifted = state.paragraphs.filter {
        it.source.isConnected && !renderedContentMatches(it)
    }
    val driftedSources = HashSet<HTMLElement>(drifted.size * 2)
    for (paragraph in drifted) driftedSources.add(paragraph.source)
    // CustodyDriftRerendersFromCustody: a host edit inside the custody
    // fragment (an insert or removal through a held node reference) leaves
    // the live paragraph matching the rendered invariant, so only the
    // custody identity check sees it. The action matches the tainted path:
    // the semantic truth lives in custody, so restore hands it back to the
    // live DOM and processParagraph re-lowers the edited content.
    val custodyDrifted = state.paragraphs.filter {
        it.source.isConnected && renderedContentMatches(it) && !custodyContentMatches(it)
    }
    for (paragraph in custodyDrifted) driftedSources.add(paragraph.source)
    val taintedTracked = tainted
        .filter { host -> host.isConnected && hasClosest(host, ROOT_SELECTOR) }
        .mapNotNull { host -> state.paragraphs.firstOrNull { tracked -> tracked.source === host } }
        .filter { tracked -> tracked.source !in driftedSources }
    // StrandedCapabilityNoRetry: a paragraph that already carries a
    // capability marker failed lowering before; re-running it on every
    // reconcile would append duplicate issues. A clone marked data-tq-rendered
    // was rendered content and must be de-scaffolded and adopted regardless.
    val stranded = strandedSourceParagraphs(root, state).filter { element ->
        !element.hasAttribute("data-tiqian-capability-issue") ||
            element.hasAttribute("data-tq-rendered")
    }
    if (drifted.isEmpty() && custodyDrifted.isEmpty() && taintedTracked.isEmpty() && stranded.isEmpty()) {
        return """{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":${dead.size}}"""
    }
    class ReconcileAction(val element: HTMLElement, val run: () -> Unit)
    val actions = mutableListOf<ReconcileAction>()
    for (paragraph in drifted) {
        actions += ReconcileAction(paragraph.source) {
            state.paragraphs.remove(paragraph)
            prepareTrackedParagraphForRelowering(paragraph)
            processParagraph(paragraph.source, state)
        }
    }
    for (paragraph in custodyDrifted) {
        actions += ReconcileAction(paragraph.source) {
            state.paragraphs.remove(paragraph)
            restoreParagraph(paragraph)
            processParagraph(paragraph.source, state)
        }
    }
    for (paragraph in taintedTracked) {
        // TaintedEngineOutputRerendersFromCustody: an in-place text edit
        // inside engine output does not change child identity. The edited
        // node belongs to the renderer, so the semantic truth stays in
        // custody and the paragraph re-renders from it.
        actions += ReconcileAction(paragraph.source) {
            state.paragraphs.remove(paragraph)
            restoreParagraph(paragraph)
            processParagraph(paragraph.source, state)
        }
    }
    for (element in stranded) {
        actions += ReconcileAction(element) {
            stripEngineMarkupFromStrandedParagraph(element)
            processParagraph(element, state)
        }
    }
    val distances = DoubleArray(actions.size) { paragraphViewportDistance(actions[it].element) }
    val itemTierIndex = Array(actions.size) { it }.apply {
        sortWith(compareBy<Int> { distances[it] }.thenBy { it })
    }.toIntArray()
    // WidthSnapshotPerReconcileJob mirrors WidthSnapshotPerRelayoutJob: a
    // mid-job width move reports stale and element.js schedules one
    // latest-width follow-up.
    val rootWidth = elementFragmentBorderBoxInlineSize(root)
    startProgressiveJob(
        ProgressiveJob(
            state = state,
            kind = ProgressiveJobKind.Relayout,
            itemCount = actions.size,
            processItem = { index -> actions[itemTierIndex[index]].run() },
            stale = {
                kotlin.math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5f
            },
            startedAt = dateNow(),
            itemTierIndex = itemTierIndex,
            paragraphsByDoc = actions.map { it.element },
            coordinated = workerIsAttached(root),
        ),
    )
    return """{"outcome":"work","drifted":${drifted.size},"custody":${custodyDrifted.size},"tainted":${taintedTracked.size},"stranded":${stranded.size},"dead":${dead.size}}"""
}

/**
 * HostEditRelowering: the host replaced or edited the live children of a
 * rendered paragraph. Remove only the recorded engine-authored nodes, keep
 * every host-added node, restore the engine-owned shell, and let
 * processParagraph re-lower the surviving live content as the new custody
 * source.
 */
internal fun TiqianWeb.prepareTrackedParagraphForRelowering(paragraph: EnhancedParagraph) {
    releasePreparedParagraphDomStyles(paragraph.source)
    for (node in paragraph.renderedNodes) {
        if (node.parentNode === paragraph.source) {
            paragraph.source.removeChild(node)
        }
    }
    restoreEngineOwnedParagraphShell(paragraph)
    stampRenderedContent(paragraph)
}

/**
 * CloneDescaffoldEngineMarkup: innerHTML re-projection hands the runtime a
 * clone that still carries engine scaffolding: line markers, copy-ignore
 * spans, engine break elements, prepared value styles, and the paragraph
 * takeover attributes. Remove exactly those engine-authored artifacts so the
 * clone lowers as ordinary host content. Host elements and host inline
 * styles survive untouched.
 */
internal fun TiqianWeb.stripEngineMarkupFromStrandedParagraph(paragraph: HTMLElement) {
    releasePreparedParagraphDomStyles(paragraph)
    // The hidden data-tq-src span is the only place a cloned hard break keeps
    // its source newline. Restore it as a text node before removing engine
    // elements, or the re-lowered clone would lose the paragraph break.
    val hardBreaks = paragraph.querySelectorAll("[data-tq-hard-break]")
    for (index in 0 until hardBreaks.length) {
        val hardBreak = hardBreaks.item(index) as? Element ?: continue
        val sourceText = hardBreak.getAttribute("data-tq-src") ?: "\n"
        hardBreak.parentNode?.replaceChild(document.createTextNode(sourceText), hardBreak)
    }
    val artifacts = paragraph.querySelectorAll(
        "[data-tq-copy-ignore], [data-tq-engine-break], [data-tq-src], [data-tq-prepared-value-styles]",
    )
    for (index in 0 until artifacts.length) {
        val artifact = artifacts.item(index) as? Element ?: continue
        artifact.parentNode?.removeChild(artifact)
    }
    // Engine run spans position glyphs through --tq-* custom properties.
    // Those values are meaningless on host content and would survive
    // lowering, so strip them from every remaining descendant.
    val descendants = paragraph.querySelectorAll("*")
    for (index in 0 until descendants.length) {
        val element = descendants.item(index) as? HTMLElement ?: continue
        val engineProperties = ArrayList<String>()
        for (styleIndex in 0 until element.style.length) {
            val name = element.style.item(styleIndex)
            if (name.startsWith("--tq-")) engineProperties.add(name)
        }
        for (name in engineProperties) element.style.removeProperty(name)
    }
    paragraph.removeAttribute("data-tq-rendered")
    paragraph.removeAttribute("data-tq-canonical-plain")
    paragraph.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE)
    paragraph.removeAttribute(EXACT_PREPARED_DOM_ATTRIBUTE)
    paragraph.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE)
    paragraph.removeAttribute(HOST_INLINE_SIZE_ATTRIBUTE)
    paragraph.removeAttribute("data-tiqian-capability-issue")
    paragraph.removeAttribute("data-tiqian-capability-detail")
    // EngineInlineStyleStrippingOnClone: takeover writes position,
    // inline-size and font-size with important priority. Originals are
    // unknown on a clone, so remove exactly those engine-signed writes.
    if (
        paragraph.style.getPropertyPriority("position") == "important" &&
        paragraph.style.getPropertyValue("position") == "relative"
    ) {
        paragraph.style.removeProperty("position")
    }
    if (paragraph.style.getPropertyPriority("inline-size") == "important") {
        paragraph.style.removeProperty("inline-size")
    }
    if (paragraph.style.getPropertyPriority("font-size") == "important") {
        paragraph.style.removeProperty("font-size")
    }
    if (paragraph.getAttribute("style")?.isBlank() != false) {
        paragraph.removeAttribute("style")
    }
}
