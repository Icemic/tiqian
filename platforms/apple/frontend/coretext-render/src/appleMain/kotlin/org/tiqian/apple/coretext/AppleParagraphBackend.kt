package org.tiqian.apple.coretext

import org.tiqian.layout.TiqianParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.ParagraphLayoutEngine
import org.tiqian.shaping.coretext.CachingFontMetricsResolver
import org.tiqian.shaping.coretext.CachingTextShaper
import org.tiqian.shaping.coretext.CoreTextFontMetricsResolver
import org.tiqian.shaping.coretext.CoreTextShaper

/**
 * Apple paragraph backend — the Core Text peer of `DesktopParagraphBackend`. Builds a
 * Tiqian layout engine wired to the Core Text shaper + font-metrics resolver so
 * `engine.layout(input)` measures real macOS fonts. The engine's other seams
 * (clreq profile, justifier, hyphenator, role classifier, fallback) use their
 * built-in defaults, matching the Desktop backend's minimal construction.
 *
 * [cjkFamily] / [latinFamily] MUST match the families the renderer draws with, or measure
 * and draw diverge (AGENTS.md #5 测量与绘制同源) — hence they are threaded through here.
 */
fun appleParagraphEngine(
    cjkFamily: String = "PingFang SC",
    latinFamily: String = "Helvetica Neue",
): ParagraphLayoutEngine =
    TiqianParagraphLayoutEngine(
        lineBreaker = LookaheadLineBreaker(),
        // Bounded caches (Core Text peer of the Compose backend's caches): a reflow reuses
        // width-independent shaping instead of re-measuring every cluster through Core Text.
        textShaper = CachingTextShaper(CoreTextShaper(cjkFamily, latinFamily)),
        fontMetricsResolver = CachingFontMetricsResolver(CoreTextFontMetricsResolver(cjkFamily, latinFamily)),
    )
