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
import org.w3c.dom.Text
import org.tiqian.shaping.web.WebCjkDashCapability

class TiqianWebEnhancerTest {
    @AfterTest
    fun cleanup() {
        for (root in mounted) {
            TiqianWeb.destroy(root)
            root.parentNode?.removeChild(root)
        }
        mounted.clear()
        restoreTestAnimationFrames()
    }

    @Test
    fun plainRuntimeFlowUsesTextNodesUntilGeometryActuallyNeedsASpan() {
        val source = "很长时间没写"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>",
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals(source, directTextContent(paragraph), paragraph.innerHTML)
        val selectionEnd = assertNotNull(paragraph.querySelector("span[data-tq-selection-end='true']"))
        assertEquals("\u200B", selectionEnd.textContent)
        assertEquals("true", selectionEnd.getAttribute("data-tq-copy-ignore"))
        assertEquals("true", selectionEnd.getAttribute("aria-hidden"))
        assertEquals(
            0,
            paragraph.querySelectorAll(
                "span[data-tq-geometry]:not(.tq-line):not([data-tq-line-end-sentinel])",
            ).length,
        )
        val generated = paragraph.querySelectorAll("[data-tq-geometry][style]")
        for (index in 0 until generated.length) {
            val style = (generated.item(index) as? HTMLElement)?.getAttribute("style").orEmpty()
            assertFalse(style.contains("all:"), style)
            assertFalse(style.contains("text-spacing-trim:"), style)
        }
        assertFalse(paragraph.getAttribute("style").orEmpty().contains("white-space"))
        assertFalse(paragraph.getAttribute("style").orEmpty().contains("text-autospace"))
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun enhancesSupportedMarkdownInlineParagraphInPlace() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>中文<strong>粗体</strong><em>italic</em><code>code</code><a class="host-link" href="/target/">链接</a><br>换行。</p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as? HTMLElement
        assertNotNull(paragraph)
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(root.querySelector(".tq-paragraph"))
        assertEquals("中文粗体italiccode链接\n换行。", copySelection(paragraph))
        assertNotNull(paragraph.querySelector("strong"))
        assertNotNull(paragraph.querySelector("em"))
        assertNotNull(paragraph.querySelector("code"))
        assertNotNull(paragraph.querySelector("a.host-link[href='/target/']"))
        assertTrue(paragraph.style.display.isEmpty())
        assertNull(paragraph.getAttribute("data-tq-copy-ignore"))
    }

    @Test
    fun enhancesInlineCodeParagraphWithBrowserResolvedMonospaceFont() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>中文<code style='font-family: "MissingFixtureMono", monospace'>code</code>正文。</p>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(
            root,
            TiqianWeb.EnhanceOptions(fontSize = 18f, lineHeight = 30f),
        )

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertNotNull(paragraph.querySelector("code"))
        assertEquals("中文code正文。", copySelection(paragraph))
    }

    @Test
    fun longInlineCodeTokenUsesRuntimeEmergencyBreaksWithoutOverflow() {
        val token = "eeeeeeeebad9a5e4b24e74cb55e829fb82c8244c0a5a3bae585179575af33bb0"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 240px">
              <p style="font-size: 18px; line-height: 30px">域名是 <code style="padding-left: 4px; padding-right: 4px">$token</code>，它不会消失。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        val code = assertNotNull(paragraph.querySelector("code") as? HTMLElement)
        assertEquals(1, paragraph.querySelectorAll("code").length)
        assertEquals("slice", computedStyleValue(code, "box-decoration-break"))
        assertNull(code.getAttribute("data-tq-inline-open-start"))
        assertNull(code.getAttribute("data-tq-inline-open-end"))
        assertTrue(paragraph.querySelectorAll(".tq-line").length > 1, "Expected more than 1 tq-line")
        assertTrue(paragraph.scrollWidth <= paragraph.clientWidth + 1, "scrollWidth ${paragraph.scrollWidth} should be <= clientWidth ${paragraph.clientWidth} + 1")
        assertTrue(
            paragraph.querySelector("span[data-tq-copy-ignore][aria-hidden='true']:not(.tq-line)")
                ?.textContent != "-",
            "Should not have hyphen",
        )
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals("域名是 $token，它不会消失。", copySelection(paragraph))
    }

    @Test
    fun longLinkTokenUsesTheSameCleanEmergencyBreakPolicy() {
        val token = "eeeeeeeebad9a5e4b24e74cb55e829fb82c8244c0a5a3bae585179575af33bb0"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p style="font-size: 18px; line-height: 30px">详情见 <a href="/docs/$token">$token</a>，请核对。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            1,
            TiqianWeb.enhance(root, testOptions()),
            "issue=${root.querySelector("p")?.getAttribute("data-tiqian-capability-issue")}; " +
                "detail=${root.querySelector("p")?.getAttribute("data-tiqian-capability-detail")}",
        )

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNotNull(paragraph.querySelector("a"))
        assertTrue(paragraph.querySelectorAll(".tq-line").length > 1)
        val clientWidth = paragraph.clientWidth.toDouble()
        for (index in 0 until paragraph.querySelectorAll(".tq-line").length) {
            val line = paragraph.querySelectorAll(".tq-line").item(index) as HTMLElement
            val lineWidth = line.getAttribute("data-tq-line-width")?.toDoubleOrNull() ?: continue
            assertTrue(lineWidth <= clientWidth + 1, "line=$lineWidth client=$clientWidth")
        }
        assertTrue(
            paragraph.querySelector("span[data-tq-copy-ignore][aria-hidden='true']:not(.tq-line)")
                ?.textContent != "-",
        )
        assertEquals("详情见 $token，请核对。", copySelection(paragraph))
    }

    @Test
    fun enhancesLeafListItemsWithoutReplacingListContainers() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <ul>
                <li id="outer">外层<ul><li id="inner">内层<strong>正文</strong>。</li></ul></li>
                <li id="plain">普通列表项。</li>
              </ul>
            </div>
            """.trimIndent(),
        )

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(2, count)
        val outer = root.querySelector("#outer") as HTMLElement
        val inner = root.querySelector("#inner") as HTMLElement
        val plain = root.querySelector("#plain") as HTMLElement
        val outerList = root.querySelector("ul") as HTMLElement
        val innerList = outer.querySelector(":scope > ul") as HTMLElement
        assertNull(outer.getAttribute("data-tq-rendered"))
        assertNotNull(outer.querySelector(":scope > ul"))
        assertNull(outerList.getAttribute("data-tq-list-layout"))
        assertNull(innerList.getAttribute("data-tq-list-layout"))
        assertEquals("true", inner.getAttribute("data-tq-rendered"))
        assertEquals("true", plain.getAttribute("data-tq-rendered"))
        assertNotNull(inner.querySelector("strong"))
        assertEquals("list-item", computedStyleValue(inner, "display"))
        assertEquals("内层正文。", copySelection(inner))
    }

    @Test
    fun progressiveEnhancementDoesNotMeasureSkippedAutoSizedListContainers() {
        for (display in listOf("flex", "grid")) {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <ol>
                    <li id="outer" style="display: $display">
                      <p id="child">脚注正文应由客户端接管，而且接管前后不能改变 auto-sized item 的宿主宽度。</p>
                      <a href="#note">↩</a>
                    </li>
                  </ol>
                </div>
                """.trimIndent(),
            )
            val outer = root.querySelector("#outer") as HTMLElement
            val child = root.querySelector("#child") as HTMLElement
            val sourceWidth = elementWidth(child)
            var stale = false
            root.addEventListener("tiqian:ready", { event ->
                stale = relayoutEventIsStale(event)
            })
            installTestAnimationFrames()

            TiqianWeb.enhanceProgressively(root, testOptions())
            flushAllTestAnimationFrames()

            assertNull(outer.getAttribute("data-tq-rendered"))
            assertEquals("true", child.getAttribute("data-tq-rendered"))
            assertEquals(sourceWidth, elementWidth(child), 0.5)
            assertEquals("true", child.getAttribute("data-tq-host-inline-size"))
            assertEquals("1", root.getAttribute("data-tiqian-enhanced-count"))
            assertFalse(stale)

            TiqianWeb.destroy(root)
            assertNull(child.getAttribute("data-tq-host-inline-size"))
            assertEquals(sourceWidth, elementWidth(child), 0.5)
        }
    }

    @Test
    fun progressiveEnhancementPreservesWidthDerivedThroughShrinkToFitAncestor() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 576px">
              <figure style="display: inline-block; margin: 0; max-width: 100%">
                <div style="width: 500px"></div>
                <figcaption>
                  <p style="margin: 0">ContentSizedParagraphWithoutNativeBreakOpportunitiesMustKeepTheHostMeasureWhileItsSourceNodesAreInCustody</p>
                </figcaption>
              </figure>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val sourceWidth = elementWidth(paragraph)
        var stale = false
        root.addEventListener("tiqian:ready", { event ->
            stale = relayoutEventIsStale(event)
        })
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())
        flushAllTestAnimationFrames()

        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertEquals("true", paragraph.getAttribute("data-tq-host-inline-size"))
        assertEquals(sourceWidth, elementWidth(paragraph), 0.5)
        assertFalse(stale)

        TiqianWeb.destroy(root)
        assertNull(paragraph.getAttribute("data-tq-host-inline-size"))
        assertEquals(sourceWidth, elementWidth(paragraph), 0.5)
    }

    @Test
    fun orderedListKeepsNativeMarkersOnATwoIcBodyIndent() {
        val tenthSource = "第十项正文足够长，换行以后仍然沿着同一正文列继续排列。"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 230px; font-size: 18px; line-height: 30px">
              <style>
                ol { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
              </style>
              <p id="body">正文足够长，第一行应当填满与列表相同的版心网格，然后继续换行。</p>
              <ol start="8">
                <li id="eight">第八项。</li>
                <li id="nine">第九项。</li>
                <li id="ten">$tenthSource</li>
              </ol>
            </div>
            """.trimIndent(),
        )

        assertEquals(4, TiqianWeb.enhance(root, testOptions()))

        val list = root.querySelector("ol") as HTMLElement
        val body = root.querySelector("#body") as HTMLElement
        val tenth = root.querySelector("#ten") as HTMLElement
        assertNull(tenth.querySelector(":scope > [data-tq-list-marker]"))
        assertNull(list.getAttribute("data-tq-list-layout"))
        assertNull(list.getAttribute("data-tq-list-gutter-ic"))
        assertNull(list.getAttribute("role"))
        assertFalse(tenth.textContent.orEmpty().contains("10."))
        assertEquals("36px", computedStyleValue(list, "padding-inline-start"))
        assertEquals("list-item", computedStyleValue(tenth, "display"))

        val proseLine = body.querySelector("[data-tq-line-width]") as HTMLElement
        val listLine = tenth.querySelector(":scope > [data-tq-line-width]") as HTMLElement
        val proseMeasure = proseLine.getAttribute("data-tq-line-width")!!.toDouble()
        val listMeasure = listLine.getAttribute("data-tq-line-width")!!.toDouble()
        assertTrue(kotlin.math.abs((36.0 + listMeasure) - proseMeasure) < 0.5)
        assertEquals(tenthSource, copySelection(tenth))

        val wideLines = renderedLineSignature(tenth)
        TiqianWeb.install()
        installTestAnimationFrames()
        root.style.width = "176px"
        dispatchRelayout(root)
        flushAllTestAnimationFrames()
        assertNotEquals(wideLines, renderedLineSignature(tenth))
        assertNull(tenth.querySelector(":scope > [data-tq-list-marker]"))
        assertEquals(tenthSource, copySelection(tenth))

        TiqianWeb.destroy(root)
        assertNull(list.getAttribute("data-tq-list-layout"))
        assertNull(list.getAttribute("data-tq-list-gutter-ic"))
        assertNull(list.getAttribute("role"))
        assertNull(tenth.getAttribute("data-tq-list-item"))
        assertNull(tenth.querySelector("[data-tq-list-marker]"))
        assertEquals(tenthSource, tenth.textContent)
    }

    @Test
    fun unorderedListUsesTwoIcNativeMarkerColumnAndNoParagraphIndent() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 216px; font-size: 18px; line-height: 30px">
              <style>
                ul { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
              </style>
              <p>普通正文保留显式的两字段首缩进，用于证明列表没有继承它。</p>
              <ul><li id="bullet">项目正文不会再叠加段首缩进，续行只服从共享标记列。</li></ul>
            </div>
            """.trimIndent(),
        )
        val options = TiqianWeb.EnhanceOptions(
            fontSize = 18f,
            lineHeight = 30f,
            firstLineIndentIc = 2f,
        )

        assertEquals(2, TiqianWeb.enhance(root, options))

        val list = root.querySelector("ul") as HTMLElement
        val paragraph = root.querySelector("p") as HTMLElement
        val item = root.querySelector("#bullet") as HTMLElement
        val paragraphLine = paragraph.querySelector(":scope > .tq-line") as HTMLElement
        val itemLine = item.querySelector(":scope > .tq-line") as HTMLElement
        assertNull(list.getAttribute("data-tq-list-gutter-ic"))
        assertNull(item.querySelector(":scope > [data-tq-list-marker]"))
        assertEquals("36px", computedStyleValue(list, "padding-inline-start"))
        assertEquals("list-item", computedStyleValue(item, "display"))
        assertEquals(36f, cssPx(paragraphLine.style.getPropertyValue("--tq-line-flow-start")), 0.5f)
        assertEquals("true", paragraphLine.getAttribute("data-tq-line-shift"))
        assertTrue(itemLine.style.getPropertyValue("--tq-line-flow-start").isEmpty())
    }

    @Test
    fun cssMultiColumnFragmentsUseOneFragmentainerAsTheLineMeasure() {
        val source = "多栏正文的段落即使跨过多个栏片段，也始终只在单栏版心内完成断行。".repeat(10)
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 400px; height: 120px; columns: 180px auto; column-gap: 40px; column-fill: auto; font-size: 18px; line-height: 30px">
              <p style="margin: 0">$source</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val fragmentWidths = elementFragmentWidths(paragraph)

        assertTrue(fragmentWidths.size > 1, "fixture must fragment across CSS columns")
        assertTrue(elementWidth(paragraph) > fragmentWidths.max())
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val firstLine = paragraph.querySelector(":scope > .tq-line") as HTMLElement
        val lineMeasure = assertNotNull(firstLine.getAttribute("data-tq-line-width")).toDouble()
        assertTrue(lineMeasure <= fragmentWidths.max() + 0.5)
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun listItemPaddingIsExcludedFromTheAvailableLineMeasure() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 216px; font-size: 18px; line-height: 30px">
              <style>
                ul { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
                li { box-sizing: border-box; padding-inline-start: 7px; }
              </style>
              <ul><li id="padded">列表项自己的 padding 不能被正文版心重复占用。</li></ul>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val item = root.querySelector("#padded") as HTMLElement
        val line = item.querySelector(":scope > [data-tq-line-width]") as HTMLElement
        val lineMeasure = line.getAttribute("data-tq-line-width")!!.toDouble()
        val contentWidth = item.clientWidth -
            cssPx(computedStyleValue(item, "padding-left")) -
            cssPx(computedStyleValue(item, "padding-right"))
        assertTrue(lineMeasure <= contentWidth + 0.5)
        assertTrue(
            kotlin.math.abs(lineMeasure - 162.0) < 0.5,
            "173px content box should expose nine 18px cells, was $lineMeasure",
        )
        assertEquals("列表项自己的 padding 不能被正文版心重复占用。", copySelection(item))
    }

    @Test
    fun canonicalPreparedParagraphCanFallBackIntoRuntimeWithoutTreatingGeometryAsHostObjects() {
        val source = "甲’乙\n丙"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 180px; font-size: 18px; line-height: 30px">
              <p data-tq-rendered="true" data-tq-canonical-plain="true" data-tq-canonical-source="true"><span data-tq-geometry="true">甲</span><span data-tq-src="’" data-tq-geometry="true">＇</span><br data-tq-engine-break="AutoWrap"><span data-tq-geometry="true">乙</span><span data-tq-src="&#10;" data-tq-hard-break="true"></span><br data-tq-engine-break="MandatoryBreak"><span data-tq-geometry="true">丙</span></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun canonicalPreparedFallbackSamplesHostLineHeightBeforeRuntimeLowering() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 180px">
              <style>
                #prepared-fallback { font-size: 18px; line-height: 30px; white-space: normal; }
                #prepared-fallback[data-tq-rendered="true"][data-tq-canonical-plain="true"] {
                  line-height: 0 !important;
                  white-space: pre !important;
                }
              </style>
              <p id="prepared-fallback" data-tq-rendered="true" data-tq-canonical-plain="true" data-tq-canonical-source="true"><span data-tq-geometry="true">第一行正文</span><br data-tq-engine-break="AutoWrap"><span data-tq-geometry="true">第二行正文</span></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root))

        val paragraph = root.querySelector("#prepared-fallback") as HTMLElement
        val line = paragraph.querySelector(":scope > .tq-line") as HTMLElement
        assertEquals(30f, cssPx(line.style.getPropertyValue("--tq-line-height")))
        assertEquals("第一行正文第二行正文", copySelection(paragraph))
    }

    @Test
    fun variationSelectorStaysWithItsVisibleBaseDuringWebShaping() {
        val source = "返回正文 ↩︎"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 220px'><p>$source</p></div>",
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        val paragraph = root.querySelector("p") as HTMLElement
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun leavesStrongAsBoldByDefault() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p style="font-weight: 430">前<strong style="font-weight: 700">强调，CSharp</strong>后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val strong = assertNotNull(paragraph.querySelector("strong") as? HTMLElement)
        assertNull(strong.getAttribute("data-tq-cjk-emphasis"))
        assertEquals(0, paragraph.querySelectorAll("circle").length)
        assertEquals("700", computedStyleValue(strong, "font-weight"))
        assertEquals("前强调，CSharp后。", copySelection(paragraph))
    }

    @Test
    fun jsOptionsCanExplicitlyMapStrongToEmphasisMarks() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p style="font-size: 18px; line-height: 30px">前<strong>强调</strong>后。</p>
            </div>
            """.trimIndent(),
        )
        TiqianWeb.install()

        dispatchEnhanceWithStrongAsEmphasisMarks(root)

        val paragraph = root.querySelector("p") as HTMLElement
        assertNotNull(paragraph.querySelector("strong[data-tq-cjk-emphasis]"))
        assertEquals(2, paragraph.querySelectorAll("circle").length)
    }

    @Test
    fun explicitlyRendersOnlyCjkContentInStrongAsEmphasisMarks() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 320px">
              <p style="font-weight: 430">前<strong style="font-weight: 700; color: rgb(1, 2, 3)">强调，CSharp 42🙂</strong>后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            1,
            TiqianWeb.enhance(
                root,
                testOptions().copy(strongAsEmphasisMarks = true),
            ),
        )

        val paragraph = root.querySelector("p") as HTMLElement
        val strong = paragraph.querySelector("strong[data-tq-cjk-emphasis]") as? HTMLElement
        assertNotNull(strong, paragraph.innerHTML)
        assertEquals("430", computedStyleValue(strong, "font-weight"))
        assertEquals(2, paragraph.querySelectorAll("circle").length)
        val overlay = assertNotNull(paragraph.querySelector("svg[data-tq-geometry='true']"), paragraph.innerHTML)
        val firstDot = assertNotNull(paragraph.querySelector("circle"), paragraph.innerHTML)
        assertEquals("rgb(1, 2, 3)", firstDot.getAttribute("fill"))
        assertFalse(overlay.getAttribute("style")?.contains("position:absolute") == true)
        assertTrue(overlay.getAttribute("style")?.startsWith("--tq-overlay-width:") == true)
        assertEquals("--tq-decoration-color:rgb(1, 2, 3)", firstDot.getAttribute("style"))

        val descendants = strong.querySelectorAll("span")
        var latinRun: HTMLElement? = null
        for (index in 0 until descendants.length) {
            val element = descendants.item(index) as? HTMLElement ?: continue
            val content = element.textContent ?: continue
            if (content.contains("CSharp")) latinRun = element
        }
        // Emphasis normalization returns CJK runs to the paragraph weight, so
        // they carry no style delta and stay native text nodes in the clone.
        assertTrue(
            (strong.firstChild as? Text)?.textContent?.startsWith("强调") == true,
            strong.innerHTML,
        )
        assertNotNull(latinRun, strong.innerHTML)
        assertEquals("700", computedStyleValue(latinRun, "font-weight"))
        assertEquals("前强调，CSharp 42🙂后。", copySelection(paragraph))
    }

    @Test
    fun exposesExplicitEmphasisDotGap() {
        fun enhanceWithGap(gap: Float): Float {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 320px">
                  <p style="font-size: 18px">前<strong>强调</strong>后。</p>
                </div>
                """.trimIndent(),
            )
            assertEquals(
                1,
                TiqianWeb.enhance(
                    root,
                    testOptions().copy(
                        emphasisDotGapEm = gap,
                        strongAsEmphasisMarks = true,
                    ),
                ),
            )
            return root.querySelector("circle")!!.getAttribute("cy")!!.toFloat()
        }

        val defaultCenter = enhanceWithGap(0.10f)
        val adjustedCenter = enhanceWithGap(0.25f)
        assertEquals(18f * 0.15f, adjustedCenter - defaultCenter, 0.01f)
    }

    @Test
    fun enhanceEventWithoutOptionsUsesComputedParagraphMetrics() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p style="font-size: 18px; line-height: 32px">无配置事件也必须继承宿主字号。</p>
            </div>
            """.trimIndent(),
        )
        TiqianWeb.install()

        dispatchEnhanceWithoutOptions(root)

        val paragraph = root.querySelector("p") as HTMLElement
        val line = paragraph.querySelector(".tq-line") as? HTMLElement
        assertNotNull(line)
        assertEquals(32f, cssPx(line.style.getPropertyValue("--tq-line-height")))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
    }

    @Test
    fun directRuntimeKeepsSourceNativeWhenSharedStylesAreMissing() {
        val root = mount(
            "<div data-tiqian-root='true' style='width: 220px'><p>没有共享样式时不能静默接管断行。</p></div>",
            sharedStylesReady = false,
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val original = paragraph.innerHTML

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))

        assertEquals(original, paragraph.innerHTML)
        assertNull(paragraph.getAttribute("data-tq-rendered"))
        assertEquals(
            "MissingSharedRuntimeStyles",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
    }

    @Test
    fun typographyRefreshRelowersCurrentHostMetrics() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p style="font-size: 16px; line-height: 28px; font-weight: 400">宿主样式加载后需要重新度量。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root))
        var paragraph = root.querySelector("p") as HTMLElement
        assertEquals(
            28f,
            cssPx(
                (paragraph.querySelector(".tq-line") as HTMLElement)
                    .style.getPropertyValue("--tq-line-height"),
            ),
        )

        paragraph.style.fontSize = "18px"
        paragraph.style.lineHeight = "32px"
        paragraph.style.fontWeight = "460"
        TiqianWeb.refresh(root, progressively = false)

        paragraph = root.querySelector("p") as HTMLElement
        val line = paragraph.querySelector(".tq-line") as? HTMLElement
        assertNotNull(line)
        assertEquals(32f, cssPx(line.style.getPropertyValue("--tq-line-height")))
        assertEquals("18px", computedStyleValue(paragraph, "font-size"))
        assertEquals("460", computedStyleValue(paragraph, "font-weight"))
    }

    @Test
    fun enhanceAllFindsCustomElementRoots() {
        val root = mount(
            """
            <tiqian-prose style="display: block; width: 220px">
              <p>命令式 API 也必须找到 custom element。</p>
            </tiqian-prose>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhanceAll(testOptions()))
        assertEquals("1", root.getAttribute("data-tiqian-enhanced-count"))
        assertNotNull(root.querySelector(".tq-line"))
    }

    @Test
    fun nestedRootsOwnOnlyTheirDirectParagraphScope() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p class="outer">外层正文。</p>
              <div data-tiqian-root="true"><p class="inner">内层正文。</p></div>
            </div>
            """.trimIndent(),
        )

        assertEquals(2, TiqianWeb.enhanceAll(testOptions()))

        val innerRoot = root.querySelector("[data-tiqian-root]") as? HTMLElement
        assertNotNull(innerRoot)
        assertEquals("1", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals("1", innerRoot.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, root.querySelectorAll("p.outer[data-tq-rendered='true']").length)
        assertEquals(1, root.querySelectorAll("p.inner[data-tq-rendered='true']").length)
        TiqianWeb.destroy(innerRoot)
    }

    @Test
    fun reportsStatefulInlineObjectAndKeepsOriginalParagraph() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p>中文<button style="display: inline-block">unsupported</button>。</p>
            </div>
            """.trimIndent(),
        )
        val original = (root.querySelector("p") as HTMLElement).innerHTML

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(0, count)
        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals(original, paragraph.innerHTML)
        assertEquals("UnsupportedStatefulInlineObject", paragraph.getAttribute("data-tiqian-capability-issue"))
    }

    @Test
    fun lowersMeasurableUnknownInlineElementsAsOpaqueObjects() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 260px">
              <p style="font-size: 18px; line-height: 30px">前<span class="badge" style="display:inline-block;width:42px;height:20px">badge</span><img class="icon" src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20' height='20'/%3E" alt="icon" width="20" height="20" style="display:inline-block;padding-bottom:4px"><svg class="raw-svg" width="18" height="20" viewBox="0 0 18 20" style="display:inline-block"><circle cx="9" cy="10" r="8"></circle></svg>后。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals(3, paragraph.querySelectorAll("[data-tq-inline-object]").length)
        assertNotNull(paragraph.querySelector("span.badge[data-tq-inline-object]"))
        assertNotNull(paragraph.querySelector("img.icon[data-tq-inline-object][alt='icon']"))
        assertNotNull(paragraph.querySelector("svg.raw-svg[data-tq-inline-object] circle"))
        assertEquals("前badge后。", copySelection(paragraph))
        assertTrue(paragraph.textContent?.contains('\uFFFC') == false)
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))

        val objectLine = paragraph.querySelector(".tq-line") as? HTMLElement
        assertNotNull(objectLine)
        assertTrue(cssPx(objectLine.style.getPropertyValue("--tq-line-height")) >= 30f)
    }

    @Test
    fun enhancesParagraphWhoseOnlyContentIsAnInlineObject() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p><svg class="only-object" width="24" height="20" viewBox="0 0 24 20" style="display:inline-block"><circle cx="12" cy="10" r="8"></circle></svg></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNotNull(paragraph.querySelector("svg.only-object[data-tq-inline-object] circle"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
    }

    @Test
    fun ignoresParagraphWhoseOnlyContentIsABlockImage() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p><img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='20'/%3E" alt="sample" width="24" height="20" style="display:block"></p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val original = paragraph.innerHTML

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))

        assertEquals(original, paragraph.innerHTML)
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-detail"))
        assertNull(paragraph.getAttribute("data-tq-rendered"))
        assertNull(root.getAttribute("data-tiqian-issue-count"))
    }

    @Test
    fun textMixedWithABlockImageStillFallsBackAtomically() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>图片说明<img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='20'/%3E" alt="sample" width="24" height="20" style="display:block"></p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val original = paragraph.innerHTML

        assertEquals(0, TiqianWeb.enhance(root, testOptions()))

        assertEquals(original, paragraph.innerHTML)
        assertEquals(
            "UnsupportedInlineFormattingContext",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertEquals("img:block", paragraph.getAttribute("data-tiqian-capability-detail"))
        assertNull(paragraph.getAttribute("data-tq-rendered"))
    }

    @Test
    fun lowersTextualInlineElementsByFormattingContextInsteadOfTagWhitelist() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p>中<span class="host-span">span</span><mark>mark</mark><del>delete</del><spoiler>秘密</spoiler>文。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNotNull(paragraph.querySelector("span.host-span[data-tq-source-semantic]"))
        assertNotNull(paragraph.querySelector("mark[data-tq-source-semantic]"))
        assertNotNull(paragraph.querySelector("del[data-tq-source-semantic]"))
        assertNotNull(paragraph.querySelector("spoiler[data-tq-source-semantic]"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
    }
}
