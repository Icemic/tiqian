package org.tiqian.web

import kotlinx.browser.document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.HTMLElement
import org.tiqian.shaping.web.WebCjkDashCapability

class TiqianWebSourceFidelityTest {
    @AfterTest
    fun cleanup() {
        for (root in mounted) {
            TiqianWeb.destroy(root)
            with(TiqianWeb) { workerDetach(root) }
            root.parentNode?.removeChild(root)
        }
        mounted.clear()
        restoreTestAnimationFrames()
    }

    // WorkerPolledScheduling test harness: an attached root never runs on its
    // own, so these helpers stand in for the page coordinator's per-frame
    // grants. The grant deadline defaults to 0, already in the past, so one
    // slice commits one paragraph.
    private fun attachWorker(root: HTMLElement) {
        with(TiqianWeb) { workerAttach(root) }
    }

    private fun grantWorkerSlice(root: HTMLElement, deadlineMs: Double = 0.0): Int {
        val controller = testGrantController(
            root,
            with(TiqianWeb) { workerJobGeneration(root) },
            deadlineMs,
            Int.MAX_VALUE,
        )
        return with(TiqianWeb) { workerRunSlice(controller, PROGRESSIVE_TIER_COUNT) }
    }

    private fun runWorkerJobToCompletion(root: HTMLElement, deadlineMs: Double = 0.0): Int {
        var slices = 0
        while (with(TiqianWeb) { workerHasJob(root) }) {
            grantWorkerSlice(root, deadlineMs)
            slices += 1
            if (slices > 1000) throw AssertionError("attached worker job did not settle")
        }
        return slices
    }

    @Test
    fun unverifiedCanvasEllipsisKeepsSourceDisplayAndCopyText() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p>中文……中文。</p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as HTMLElement
        assertTrue(paragraph.textContent?.contains("……") == true)
        assertTrue(paragraph.textContent?.contains("⋯⋯") == false)
        assertEquals("中文……中文。", copySelection(paragraph))
    }

    @Test
    fun keepsDashParagraphNativeWithoutAVerifiableFontSource() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 420px">
              <p style="font-family: Arial, sans-serif">中文——中文。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertTrue(paragraph.textContent?.contains("中文——中文。") == true)
        assertTrue(paragraph.textContent?.contains('⸺') == false)
        assertEquals("NoConformingCjkDashGlyph", paragraph.getAttribute("data-tiqian-capability-issue"))
        assertNull(paragraph.getAttribute("data-tq-rendered"))
        assertEquals("中文——中文。", copySelection(paragraph))
    }

    @Test
    fun conformingDashEvidenceWithoutAnExactSessionReportsTheActualMissingCapability() {
        val root = mount(
            "<div data-tiqian-root='true'><p>中文——中文。</p></div>",
        )
        val options = testOptions().copy(
            cjkDashCapability = WebCjkDashCapability(
                status = "conforming",
                detail = "FixtureDashFace",
            ),
        )

        assertEquals(0, TiqianWeb.enhance(root, options))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals(
            "ConformingCjkDashRequiresExactFontSession",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertTrue(
            paragraph.getAttribute("data-tiqian-capability-detail")
                ?.contains("status=conforming") == true,
        )
        assertNull(paragraph.getAttribute("data-tq-rendered"))
    }

    @Test
    fun expandsCjkContextCurlyQuotesButKeepsLatinPairsProportional() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 600px">
              <p style="font-family: Arial, sans-serif; font-size: 20px; line-height: 32px"><span class="cjk-quotes">中“文”中</span></p>
              <p style="font-family: Arial, sans-serif; font-size: 20px; line-height: 32px"><span class="latin-quotes">A“A”A</span></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(2, TiqianWeb.enhance(root))

        val cjk = root.querySelector(".cjk-quotes[data-tq-source-semantic]") as? HTMLElement
        val latin = root.querySelector(".latin-quotes[data-tq-source-semantic]") as? HTMLElement
        assertNotNull(cjk)
        assertNotNull(latin)
        // Three Han glyphs + two context-CJK quote boxes = 5em. The same
        // source quote codepoints in Latin prose retain the face's narrow
        // proportional advances instead of being globally widened.
        assertEquals(100.0, elementWidth(cjk), 1.0)
        assertTrue(elementWidth(latin) < 80.0, "Latin quote pair was widened: ${elementWidth(latin)}px")
        assertEquals("中“文”中", copySelection(cjk))
        assertEquals("A“A”A", copySelection(latin))
    }

    @Test
    fun copyKeepsHardBreakAndSourceTextButOmitsSoftWraps() {
        val source = "第一行中文需要自动换行。第二段内容继续占满宽度。"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 120px">
              <p>$source<br>显式换行之后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        val paragraph = root.querySelector("p") as HTMLElement
        assertTrue(paragraph.querySelectorAll(".tq-line").length > 2)

        val visualBreaks = paragraph.querySelectorAll(
            "br[data-tq-engine-break]:not([data-tq-engine-break='MandatoryBreak'])",
        )
        assertTrue(visualBreaks.length > 0)
        for (index in 0 until visualBreaks.length) {
            val visualBreak = visualBreaks.item(index) as HTMLElement
            assertEquals("true", visualBreak.getAttribute("aria-hidden"))
            assertEquals("true", visualBreak.getAttribute("data-tq-copy-ignore"))
        }
        val sourceBreak = paragraph.querySelector("br[data-tq-engine-break='MandatoryBreak']") as HTMLElement
        assertNull(sourceBreak.getAttribute("aria-hidden"))
        assertNull(sourceBreak.getAttribute("data-tq-copy-ignore"))

        assertEquals("$source\n显式换行之后。", copySelection(paragraph))
    }

    @Test
    fun collapsesHostFormattingWhitespaceAndKeepsReflowDeterministic() {
        val expected = "第一句。 第二句。 第三句。\n第四句。"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 220px'>" +
                "<p>第一句。\n<strong>第二句。\n第三句。</strong><br>\n第四句。</p>" +
                "</div>",
        )
        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals(expected, nativeInnerText(paragraph))

        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        assertEquals(expected, copySelection(paragraph))
        assertEquals(1, paragraph.querySelectorAll("[data-tq-hard-break]").length)
        assertEquals(0, emptyRenderedLineCount(paragraph))
        assertNotNull(paragraph.querySelector("strong[data-tq-source-semantic]"))

        val initial = renderedLineSignature(paragraph)
        installTestAnimationFrames()
        root.style.width = "120px"
        dispatchRelayout(root)
        // SyncFirstSlice: the relayout commits inside the dispatch task. The
        // narrow result is already live with no frame delay, and there is no
        // intermediate state where the old line boxes are gone but the new
        // ones are not attached yet.
        val narrow = renderedLineSignature(paragraph)
        assertNotEquals(initial, narrow, "narrow width must exercise a real reflow")
        assertEquals(0, pendingTestAnimationFrameCount())
        flushAllTestAnimationFrames()
        assertEquals(narrow, renderedLineSignature(paragraph))

        root.style.width = "220px"
        dispatchRelayout(root)
        flushAllTestAnimationFrames()
        assertEquals(initial, renderedLineSignature(paragraph))
        assertEquals(expected, copySelection(paragraph))
        assertEquals(0, emptyRenderedLineCount(paragraph))
    }

    @Test
    fun normalizesPreservedCrLfToOneSegmentBreak() {
        val root = mount(
            "<div data-tiqian-root='true' style='width: 220px'><p style='white-space: pre-wrap'></p></div>",
        )
        val paragraph = root.querySelector("p") as HTMLElement
        paragraph.textContent = "前\r\n后"

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        assertEquals("前\n后", copySelection(paragraph))
        assertEquals(1, paragraph.querySelectorAll("[data-tq-hard-break]").length)
        assertEquals(0, emptyRenderedLineCount(paragraph))
    }

    @Test
    fun widthDependentCapabilityRetryRestartsProgressivelyFromNativeSource() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 520px">
              <p class="clone"><span style="box-decoration-break: clone; -webkit-box-decoration-break: clone; padding: 0 6px">跨行复制盒模型只在窄行失去保真能力</span></p>
              <p class="plain">普通段落在 capability retry 时不能跨帧暴露原生正文。</p>
            </div>
            """.trimIndent(),
        )
        val cloneParagraph = root.querySelector("p.clone") as HTMLElement
        val plainParagraph = root.querySelector("p.plain") as HTMLElement
        val originalHtml = cloneParagraph.innerHTML
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })
        TiqianWeb.install()

        assertEquals(2, TiqianWeb.enhance(root, testOptions()))
        assertEquals("true", cloneParagraph.getAttribute("data-tq-rendered"))
        assertEquals("true", plainParagraph.getAttribute("data-tq-rendered"))

        installTestAnimationFrames()
        attachWorker(root)
        root.style.width = "90px"
        dispatchRelayout(root)
        runWorkerJobToCompletion(root)

        assertEquals(originalHtml, cloneParagraph.innerHTML)
        assertNull(cloneParagraph.getAttribute("data-tq-rendered"))
        assertEquals(
            "InlineCloneDecorationBreakUnsupported",
            cloneParagraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertEquals("true", plainParagraph.getAttribute("data-tq-rendered"))
        assertEquals("1", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, relayoutReadyCount)
        val narrowRenderedChild = assertNotNull(plainParagraph.firstChild)

        root.style.width = "520px"
        dispatchRelayout(root)

        assertEquals(1, relayoutReadyCount)
        assertNull(cloneParagraph.getAttribute("data-tq-rendered"))
        assertNull(plainParagraph.getAttribute("data-tq-rendered"))
        assertEquals(originalHtml, cloneParagraph.innerHTML)
        assertFalse(plainParagraph.firstChild === narrowRenderedChild)
        assertEquals("0", root.getAttribute("data-tiqian-enhanced-count"))

        runWorkerJobToCompletion(root)

        assertEquals(2, relayoutReadyCount)
        assertEquals("true", cloneParagraph.getAttribute("data-tq-rendered"))
        assertEquals("true", plainParagraph.getAttribute("data-tq-rendered"))
        assertNull(cloneParagraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
    }

    @Test
    fun stableCapabilityIssueStaysNativeWhileEnhancedParagraphsRelayoutNormally() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p class="issue" style="font-size: 0px">零 advance 是稳定 capability issue。</p>
              <p class="plain" style="font-size: 18px; line-height: 30px">普通正文仍应走 off-DOM 响应式重排。</p>
            </div>
            """.trimIndent(),
        )
        val issueParagraph = root.querySelector("p.issue") as HTMLElement
        val plainParagraph = root.querySelector("p.plain") as HTMLElement
        val issueSourceChild = assertNotNull(issueParagraph.firstChild)
        TiqianWeb.install()

        assertEquals(1, TiqianWeb.enhance(root))
        assertEquals("InvalidWebShapingAdvance", issueParagraph.getAttribute("data-tiqian-capability-issue"))
        val renderedChild = assertNotNull(plainParagraph.firstChild)
        val initial = renderedLineSignature(plainParagraph)
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })

        installTestAnimationFrames()
        root.style.width = "120px"
        dispatchRelayout(root)

        // SyncFirstSlice: both paragraphs are handled inside the dispatch
        // task. The plain paragraph swaps its rendered DOM atomically, while
        // the paragraph with a stable capability issue keeps its native
        // source child.
        assertFalse(plainParagraph.firstChild === renderedChild, "relayout must commit its first slice synchronously")
        assertTrue(issueParagraph.firstChild === issueSourceChild)
        assertEquals(0, pendingTestAnimationFrameCount())

        flushAllTestAnimationFrames()

        assertNotEquals(initial, renderedLineSignature(plainParagraph))
        assertTrue(issueParagraph.firstChild === issueSourceChild)
        assertNull(issueParagraph.getAttribute("data-tq-rendered"))
        assertEquals("InvalidWebShapingAdvance", issueParagraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals("1", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun inlineShapingFeatureThatLayoutResultCannotModelStaysNative() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p>阅读 <span style="font-feature-settings: 'hwid'; font-variant-east-asian: proportional-width">Font size</span> 以了解更多。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val originalHtml = paragraph.innerHTML

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))
        assertEquals(originalHtml, paragraph.innerHTML)
        assertNull(paragraph.getAttribute("data-tq-rendered"))
        assertEquals(
            "UnsupportedInlineShapingStyle",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertEquals(
            "span:font-feature-settings",
            paragraph.getAttribute("data-tiqian-capability-detail"),
        )
    }

    @Test
    fun generatedInlineContentOnSemanticElementsUsesMeasuredBoxGeometry() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <style>
                .generated-footnote::before { content: "["; }
                .generated-footnote::after { content: "]"; }
                .absolute-decoration::before { content: "•"; position: absolute; }
              </style>
              <p class="generated">正文<a class="generated-footnote" href="#note">1</a>继续。</p>
              <p class="plain">正文<a href="#plain">1</a>继续。</p>
              <p class="decorated">正文<span class="absolute-decoration">装饰</span>继续。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p.generated") as HTMLElement

        assertEquals(3, TiqianWeb.enhance(root, testOptions()))
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        val footnote = assertNotNull(paragraph.querySelector("a.generated-footnote") as? HTMLElement)
        assertEquals("[", computedPseudoContent(footnote, "::before"))
        assertEquals("]", computedPseudoContent(footnote, "::after"))
        assertEquals("正文1继续。", copySelection(paragraph))
        val plain = root.querySelector("p.plain") as HTMLElement
        val generatedWidth = assertNotNull(
            paragraph.querySelector(".tq-line")
                ?.getAttribute("data-tq-line-width")
                ?.toFloat(),
        )
        val plainWidth = assertNotNull(
            plain.querySelector(".tq-line")
                ?.getAttribute("data-tq-line-width")
                ?.toFloat(),
        )
        assertTrue(generatedWidth > plainWidth + 1f)
        val decorated = root.querySelector("p.decorated") as HTMLElement
        assertEquals("true", decorated.getAttribute("data-tq-rendered"))
        assertNull(decorated.getAttribute("data-tiqian-capability-issue"))
    }

    @Test
    fun generatedContentDirectlyOnParagraphStaysNativeWithoutASourceRange() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <style>.generated-root::before { content: "※"; }</style>
              <p class="generated-root">正文保持原生。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val original = paragraph.innerHTML

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))

        assertEquals(original, paragraph.innerHTML)
        assertNull(paragraph.getAttribute("data-tq-rendered"))
        assertEquals(
            "UnsupportedGeneratedInlineContent",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertTrue(
            paragraph.getAttribute("data-tiqian-capability-detail")
                ?.startsWith("p::before:") == true,
        )
    }

    @Test
    fun zeroWidthSpaceSoftBreakEnhancesAndCopiesSourceFaithfully() {
        val source = "A.\u200B.\u200B.Complete？AaFont？"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 120px'><p>$source</p></div>",
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertTrue(paragraph.querySelectorAll(".tq-line").length > 1)
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun copyOmitsEngineOwnedHyphenGlyphs() {
        val source = "中Network"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 64px'><p>$source</p></div>",
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val hyphen = paragraph.querySelector(
            "span[data-tq-copy-ignore][aria-hidden='true']:not(.tq-line)",
        )
        assertNotNull(hyphen)
        assertEquals("-", hyphen.textContent)
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun preservesOneNativeLinkAcrossEngineOwnedLines() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 120px">
              <p><a class="host-link" href="/target/" target="_self" rel="author" title="Host title" style="color: rgb(10, 11, 12); text-decoration-style: dotted; transition: text-decoration-color 200ms">一段足够长而且确定会跨过许多视觉行的链接文字</a>。<a class="other-link" href="/other/">其他</a></p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val links = root.querySelectorAll("p a.host-link[href='/target/']")
        assertEquals(1, links.length, "one source link must remain one DOM link across soft wraps")
        val link = links.item(0) as HTMLElement
        assertTrue(link.parentElement === root.querySelector("p"), "top-level source link must stay a direct child")
        assertEquals("_self", link.getAttribute("target"))
        assertEquals("author", link.getAttribute("rel"))
        assertEquals("Host title", link.getAttribute("title"))
        assertEquals("rgb(10, 11, 12)", link.style.getPropertyValue("color"))
        assertEquals("dotted", link.style.getPropertyValue("text-decoration-style"))
        assertEquals("text-decoration-color 200ms", link.style.getPropertyValue("transition"))
        assertNull(link.getAttribute("data-tq-link-group"))
        assertTrue(link.querySelectorAll("br[data-tq-engine-break]").length > 1)
        assertEquals("一段足够长而且确定会跨过许多视觉行的链接文字", link.textContent)

        TiqianWeb.refresh(root, progressively = false)
        val refreshedLinks = root.querySelectorAll("p a.host-link[href='/target/']")
        assertEquals(1, refreshedLinks.length)
        assertNull((refreshedLinks.item(0) as HTMLElement).getAttribute("data-tq-link-group"))
    }

    @Test
    fun keepsOneLinkAcrossConsecutiveEmptyHardBreakLines() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p><a class="host-link" href="/target/">甲<br><br>乙</a></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val links = paragraph.querySelectorAll("a.host-link[href='/target/']")
        assertEquals(1, links.length)
        val link = links.item(0) as HTMLElement
        assertEquals(2, link.querySelectorAll("[data-tq-hard-break]").length)
        assertEquals(2, link.querySelectorAll("br[data-tq-engine-break='MandatoryBreak']").length)
        assertEquals("甲\n\n乙", copySelection(paragraph))
    }

    @Test
    fun keepsSemanticLinkContinuousAcrossGeometryFragments() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 700px">
              <p style="font-size: 18px; line-height: 30px">对比（来自<a class="host-link" href="/pull/4479">添加windows-reactor的PR</a>）：</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val links = paragraph.querySelectorAll("a.host-link[href='/pull/4479']")
        assertEquals(1, links.length, "one source link must stay one semantic wrapper per line")
        val link = links.item(0) as HTMLElement
        assertEquals("添加windows-reactor的PR", copySelection(link))
        assertTrue(link.children.length > 1, "geometry fragments should live inside the host link")
    }

    @Test
    fun keepsHostFontFamiliesAsTheMeasureAndPaintSource() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p style='font-family: "CP-hashed", "HostFace", sans-serif; font-size: 21px; line-height: 33px; font-weight: 460; font-style: italic;'>中<a href="/target/" style='font-family: "LinkFace", sans-serif; font-size: 22px; font-weight: 520; font-style: normal;'>链接</a><code style='font-family: "CodeFace", monospace; font-size: 13px; font-weight: 430; font-style: normal;'>code</code></p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(
            root,
            TiqianWeb.EnhanceOptions(
                fontFamilies = TiqianWeb.FontFamilyOptions(
                    cjk = "ConfiguredCjk, sans-serif",
                    latin = "ConfiguredLatin, sans-serif",
                    monospace = "ConfiguredMono, monospace",
                ),
            ),
        )

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as HTMLElement
        assertTrue(paragraph.style.fontFamily.contains("CP-hashed"))
        assertTrue(paragraph.style.fontFamily.contains("HostFace"))
        val line = paragraph.querySelector(".tq-line") as? HTMLElement
        assertNotNull(line)
        assertEquals(33f, cssPx(line.style.getPropertyValue("--tq-line-height")))
        assertEquals(computedStyleValue(paragraph, "font-family"), computedStyleValue(line, "font-family"))
        assertEquals("21px", computedStyleValue(line, "font-size"))

        val link = paragraph.querySelector("a") as HTMLElement
        assertTrue(link.style.fontFamily.contains("LinkFace"))
        assertEquals("22px", link.style.fontSize)
        assertEquals("520", link.style.fontWeight)

        val code = paragraph.querySelector("code") as HTMLElement
        assertTrue(code.style.fontFamily.contains("CodeFace"))
        assertEquals("13px", code.style.fontSize)
        assertEquals("430", code.style.fontWeight)
    }

    @Test
    fun preservesHostInlineRenderStylesOnSemanticTags() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p>中<strong class="host-strong" style='color: rgb(1, 2, 3); text-decoration-line: underline; text-decoration-color: rgb(4, 5, 6); text-decoration-style: dotted; text-decoration-thickness: 2px; text-underline-offset: 3px;'>强调</strong></p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val strong = root.querySelector("p strong.host-strong") as? HTMLElement
        assertNotNull(strong)
        assertEquals("rgb(1, 2, 3)", strong.style.getPropertyValue("color"))
        assertEquals("underline", strong.style.getPropertyValue("text-decoration-line"))
        assertEquals("rgb(4, 5, 6)", strong.style.getPropertyValue("text-decoration-color"))
        assertEquals("dotted", strong.style.getPropertyValue("text-decoration-style"))
        assertEquals("2px", strong.style.getPropertyValue("text-decoration-thickness"))
        assertEquals("3px", strong.style.getPropertyValue("text-underline-offset"))
    }

    @Test
    fun measuresHostInlineBoxEdgesIntoLayout() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>中<code style="padding-left: 4px; padding-right: 4px">code</code>文。</p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as HTMLElement
        val code = paragraph.querySelector("code") as? HTMLElement
        assertNotNull(code)
        assertEquals("4px", computedStyleValue(code, "padding-left"))
        assertEquals("4px", computedStyleValue(code, "padding-right"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
    }

    @Test
    fun semanticSuperscriptAndSubscriptAreEnhancedInsteadOfStayingNative() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>公式 x<sup>2</sup> 与 H<sub>2</sub>O 仍然参与中文段落排版。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNotNull(paragraph.querySelector("sup"))
        assertNotNull(paragraph.querySelector("sub"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals("公式 x2 与 H2O 仍然参与中文段落排版。", copySelection(paragraph))
    }

    @Test
    fun enhancesSuperscriptGeneratedContentAndPreservesUniqueId() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 520px">
              <style>
                .fn { padding-left: 4px; padding-right: 4px; margin-left: -4px; margin-right: -4px; }
                .fn::before { content: "["; }
                .fn::after { content: "]"; }
              </style>
              <p>这里有脚注<sup style="position: relative; top: -5px; font-size: 12px; line-height: 0"><a class="fn" id="fnref-1" href="#fn-1">1</a></sup>并继续正文。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val sup = paragraph.querySelector("sup") as? HTMLElement
        assertNotNull(sup)
        assertEquals("-5px", computedStyleValue(sup, "top"))
        assertEquals(1, paragraph.querySelectorAll("#fnref-1").length)
        assertNotNull(paragraph.querySelector("a.fn[href='#fn-1']"))
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals("这里有脚注1并继续正文。", copySelection(paragraph))
        assertEquals(1, paragraph.querySelectorAll(".tq-line").length)
        val declaredWidth = assertNotNull(
            paragraph.querySelector(".tq-line")
                ?.getAttribute("data-tq-line-width")
                ?.toFloat(),
        )
        assertEquals(declaredWidth, renderedSingleLineFlowWidth(paragraph), 0.75f)
    }

    @Test
    fun keepsInlineBoxAsOneNativeElementAcrossEngineLines() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 90px">
              <p>前<spoiler style="padding-left: 4px; padding-right: 4px; border: 1px solid">一段足够长并且必然跨行的语义内容</spoiler>后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val inline = root.querySelectorAll("p spoiler")
        assertEquals(1, inline.length)
        val spoiler = inline.item(0) as HTMLElement
        assertTrue(spoiler.querySelectorAll("br[data-tq-engine-break]").length > 1)
        assertEquals("4px", computedStyleValue(spoiler, "padding-left"))
        assertEquals("4px", computedStyleValue(spoiler, "padding-right"))
        assertNull(spoiler.getAttribute("data-tq-inline-open-start"))
        assertNull(spoiler.getAttribute("data-tq-inline-open-end"))
    }

    @Test
    fun engineGeometrySpansAreNeutralToHostSpanRules() {
        val root = mount(
            """
            <div class="host" data-tiqian-root="true" style="width: 320px">
              <style>
                .host p span { display: block !important; padding: 19px !important; font-size: 40px !important; }
                [data-tq-rendered="true"] span[data-tq-geometry="true"] {
                  all: unset !important;
                  display: inline !important;
                  text-spacing-trim: space-all !important;
                }
              </style>
              <p style="font-size: 18px; line-height: 30px">引擎生成的几何节点不能继承宿主对真实 span 的盒模型。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertNull(paragraph.querySelector(".tq-flow"))
        val run = paragraph.querySelector(":scope > [data-tq-geometry]:not(.tq-line)") as? HTMLElement
        assertNotNull(run)
        assertEquals("inline", computedStyleValue(run, "display"))
        assertEquals(0f, cssPx(computedStyleValue(run, "padding-left")))
        assertEquals(18f, cssPx(computedStyleValue(run, "font-size")))
    }

    @Test
    fun engineAnnotationsAreNeutralToHostSpanAndSvgRules() {
        val root = mount(
            """
            <div class="host" data-tiqian-root="true" style="width: 320px">
              <style>
                [data-tq-rendered="true"] svg[data-tq-geometry="true"] {
                  all: unset !important;
                  display: block !important;
                }
                [data-tq-rendered="true"] svg[data-tq-geometry="true"] circle[data-tq-decoration-dot] {
                  fill: var(--tq-decoration-color) !important;
                }
                .host p span { display: block !important; padding: 19px !important; }
                .host p svg { display: none !important; }
                .host p svg circle { fill: rgb(255, 0, 255) !important; }
              </style>
              <p style="color: rgb(1, 2, 3)">前<strong>强调</strong>后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            1,
            TiqianWeb.enhance(root, testOptions().copy(strongAsEmphasisMarks = true)),
        )

        val paragraph = root.querySelector("p") as HTMLElement
        val svg = paragraph.querySelector("svg[data-tq-geometry]")
        val circle = paragraph.querySelector("circle")
        assertNotNull(svg)
        assertNotNull(circle)
        assertEquals("block", computedStyleValueElement(svg, "display"))
        assertEquals("rgb(1, 2, 3)", computedStyleValueElement(circle, "fill"))
    }

    @Test
    fun emitsFinalAndLatinAdjacentPunctuationSpacingWithoutClippingInk() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p style="font-size: 18px; line-height: 30px">你想要开发一个小软件（单文件），那么你现在应该选择C++（MFC）、Rust（Winio）。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val lines = paragraph.querySelectorAll(".tq-line")
        assertTrue(lines.length > 1)
        for (index in 0 until lines.length) {
            val line = lines.item(index) as HTMLElement
            assertTrue(line.style.getPropertyValue("--tq-line-height").isNotEmpty())
            assertTrue(line.style.getPropertyValue("--tq-line-baseline-offset").isNotEmpty())
            assertEquals("", line.style.getPropertyValue("display"))
            assertEquals("", line.style.getPropertyValue("width"))
            assertEquals("", line.style.getPropertyValue("height"))
            assertEquals("", line.style.getPropertyValue("line-height"))
            assertEquals("", line.style.getPropertyValue("vertical-align"))
            assertEquals("", line.style.getPropertyValue("overflow"))
            assertEquals("", line.style.getPropertyValue("pointer-events"))
            assertNotNull(line.getAttribute("data-tq-line-width"))
        }

        val last = assertNotNull(lastTextLeaf(paragraph))
        assertTrue(
            cssPx(computedStyleValue(last, "letter-spacing")) < -0.1,
            "expected final-cluster compression: ${paragraph.innerHTML}",
        )
    }

    @Test
    fun browserPunctuationTrimDoesNotDoubleCompressClosingCommaOpeningSequence() {
        val source = "前句「甲」、「乙」后句。"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 700px">
              $enginePunctuationFeatureStyle
              <style>
                [data-tq-rendered="true"] [data-tq-geometry] {
                  text-spacing-trim: space-all !important;
                }
              </style>
              <p style="font-size: 18px; line-height: 30px">$source</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val closingCommaRun = assertNotNull(geometryLeafWithText(paragraph, "」、"))
        assertEquals("space-all", computedStyleValue(closingCommaRun, "text-spacing-trim"))
        assertEnginePunctuationFeatureLock(paragraph)
        assertEnginePunctuationFeatureLock(closingCommaRun)
        val characterWidths = textNodeCharacterWidths(closingCommaRun)
            .split(',')
            .mapNotNull(String::toDoubleOrNull)
        assertEquals(2, characterWidths.size)
        assertTrue(
            characterWidths.all { it >= 8.25 },
            "browser punctuation trimming consumed a second half-em: $characterWidths; ${paragraph.innerHTML}",
        )
        assertTrue(
            kotlin.math.abs(elementWidth(closingCommaRun) - 18.0) < 0.75,
            "closing-comma run must replay one em, was ${elementWidth(closingCommaRun)}; ${paragraph.innerHTML}",
        )
        assertEquals(source, copySelection(paragraph))
    }
}
