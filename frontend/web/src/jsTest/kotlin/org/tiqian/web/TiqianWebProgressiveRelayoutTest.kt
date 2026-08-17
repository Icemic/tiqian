@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

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

class TiqianWebProgressiveRelayoutTest {
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
    fun negativeGapAfterMultiCharacterRunUsesOverlapInsteadOfBeingDropped() {
        assertEquals(DomRunSpacing.Overlap(-9f), resolveDomRunSpacing("C++", -9f))
    }

    @Test
    fun positiveGapAfterMultiCharacterRunUsesSelectableCarrierWithoutBreakingShaping() {
        assertEquals(DomRunSpacing.TrailingLetter(9f), resolveDomRunSpacing("C++", 9f))

        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 700px">
              <p>中文<a href="/target/" style="padding: 4px; margin: -4px">bug</a>中文。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val link = root.querySelector("p a") as HTMLElement
        assertEquals(4f, cssPx(computedStyleValue(link, "padding-right")))
        assertEquals(-4f, cssPx(computedStyleValue(link, "margin-right")))
        val fragments = link.querySelectorAll(":scope > span")
        var spacingFragment: HTMLElement? = null
        for (index in 0 until fragments.length) {
            val fragment = fragments.item(index) as HTMLElement
            val carrier = fragment.querySelector("[data-tq-spacing-carrier]") as? HTMLElement
            if (carrier != null && elementWidth(carrier) > 0.1) {
                spacingFragment = fragment
            }
        }
        val fragment = assertNotNull(spacingFragment)
        val carrier = assertNotNull(fragment.querySelector("[data-tq-spacing-carrier]") as? HTMLElement)
        assertEquals("bug", fragment.firstChild?.textContent)
        assertEquals("", fragment.getAttribute("data-tq-shaping-boundary"))
        assertEquals("\u00A0", carrier.textContent)
        assertEquals("true", carrier.getAttribute("data-tq-copy-ignore"))
        assertEquals("true", carrier.getAttribute("aria-hidden"))
        assertEquals("inline-block", computedStyleValue(carrier, "display"))
        assertEquals(0f, cssPx(computedStyleValue(carrier, "height")))
        assertEquals(0f, cssPx(computedStyleValue(carrier, "line-height")))
        assertEquals(0f, cssPx(computedStyleValue(fragment, "padding-right")))
        assertTrue(
            selectionCoversElement(fragment, carrier),
            "engine spacing must remain inside the native Range selection: ${fragment.outerHTML}",
        )
        assertEquals("bug", copySelection(link))
    }

    @Test
    fun westernShapingBoundariesRemainInNativeInlineSelectionFlow() {
        val source = "这里的 Powershell 与 pwsh7 都保持连续选择。"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 700px">
              <p>$source</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val boundaries = paragraph.querySelectorAll("[data-tq-shaping-boundary]")
        assertTrue(boundaries.length > 0, paragraph.innerHTML)
        for (index in 0 until boundaries.length) {
            val boundary = boundaries.item(index) as HTMLElement
            assertEquals(
                "inline",
                computedStyleValue(boundary, "display"),
                "a shaping run must not become an atomic selection island: ${boundary.outerHTML}",
            )
        }
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun keepsNativeParagraphWhenVisibleGlyphsHaveNoMeasuredAdvance() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p style="font-size: 0px">不可生成零宽行盒。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val original = paragraph.innerHTML

        val count = TiqianWeb.enhance(root)

        assertEquals(0, count)
        assertEquals(original, paragraph.innerHTML)
        assertEquals("InvalidWebShapingAdvance", paragraph.getAttribute("data-tiqian-capability-issue"))
        assertTrue(paragraph.getAttribute("data-tiqian-capability-detail")?.contains("advance=0") == true)
    }

    @Test
    fun combiningMarksAreShapedWithTheirBasesInsteadOfRejectingTheParagraph() {
        val source = "合法组合标记༎ຶ与螺丝Ỏ̷仍应保留在正文中。"
        val root = mount(
            "<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>",
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun plainBodyTextUsesSparseRunsRatherThanOneNodePerCluster() {
        val text = "中文排版需要保留语义与宿主样式，同时由引擎负责断行和标点几何。".repeat(8)
        val root = mount("<div data-tiqian-root='true' style='width: 320px'><p>$text</p></div>")

        val count = TiqianWeb.enhance(root, testOptions())

        assertEquals(1, count)
        val paragraph = root.querySelector("p") as HTMLElement
        val renderedNodes = paragraph.querySelectorAll("*").length
        assertTrue(renderedNodes < text.length / 2, "renderedNodes=$renderedNodes chars=${text.length}")
        assertTrue(paragraph.querySelectorAll(".tq-line").length > 1)
        assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
    }

    @Test
    fun destroyRestoresOriginalChildrenAndHostAttributes() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p data-tq-rendered="host-owned" data-tq-canonical-source="host-owned" data-tq-copy-ignore="host-owned">需要<strong>增强</strong>。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val originalHtml = paragraph.innerHTML

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        assertEquals("host-owned", paragraph.getAttribute("data-tq-copy-ignore"))
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))

        TiqianWeb.destroy(root)

        assertEquals(originalHtml, paragraph.innerHTML)
        assertEquals("host-owned", paragraph.getAttribute("data-tq-copy-ignore"))
        assertEquals("host-owned", paragraph.getAttribute("data-tq-rendered"))
        assertEquals("host-owned", paragraph.getAttribute("data-tq-canonical-source"))
        assertNull(paragraph.getAttribute("data-tq-runtime-render-font"))
        assertNull(paragraph.getAttribute("style"))
    }

    @Test
    fun copyHandlerDoesNotInterceptTextOutsideRenderedParagraphs() {
        val root = mount("<div><p>普通站点文本不属于 Tiqian。</p></div>")
        TiqianWeb.install()
        val paragraph = root.querySelector("p") as HTMLElement

        assertEquals("普通站点文本不属于 Tiqian。", copySelection(paragraph))
        assertFalse(copySelectionWasIntercepted(paragraph))
    }

    @Test
    fun destroyCancelsProgressiveWorkBeforeItTouchesNativeContent() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p>渐进增强尚未执行时仍然是原生正文。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val originalHtml = paragraph.innerHTML

        TiqianWeb.enhanceProgressively(root, testOptions())
        assertEquals("0", root.getAttribute("data-tiqian-enhanced-count"))

        TiqianWeb.destroy(root)

        assertEquals(originalHtml, paragraph.innerHTML)
        assertNull(root.getAttribute("data-tiqian-enhanced"))
        assertNull(paragraph.getAttribute("data-tq-rendered"))
    }

    @Test
    fun detachKeepsInvisibleRenderedDomButReconnectDestroyCanRestoreSource() {
        val root = mount(
            """
            <div data-tiqian-root="true">
              <p>路由移除旧文章时不应该同步重建这一段。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val originalHtml = paragraph.innerHTML

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        val renderedHtml = paragraph.innerHTML
        assertNotEquals(originalHtml, renderedHtml)

        TiqianWeb.detach(root)

        assertEquals(renderedHtml, paragraph.innerHTML)
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))

        TiqianWeb.destroy(root)

        assertEquals(originalHtml, paragraph.innerHTML)
        assertNull(paragraph.getAttribute("data-tq-rendered"))
    }

    @Test
    fun mixedSnapshotProgressReportsObservableTotalThroughoutTheRuntimeTail() {
        val root = mount(
            """
            <div data-tiqian-root="true" data-tiqian-snapshot-count="2">
              <p>只有这一段需要运行时补齐。</p>
            </div>
            """.trimIndent(),
        )
        var readyEnhancedCount = -1
        var readyRuntimeCount = -1
        var readySnapshotCount = -1
        root.addEventListener("tiqian:ready", { event ->
            readyEnhancedCount = eventDetailInt(event, "enhancedCount")
            readyRuntimeCount = eventDetailInt(event, "runtimeEnhancedCount")
            readySnapshotCount = eventDetailInt(event, "snapshotCount")
        })
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())

        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
        flushAllTestAnimationFrames()
        assertEquals("3", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(3, readyEnhancedCount)
        assertEquals(1, readyRuntimeCount)
        assertEquals(2, readySnapshotCount)

        TiqianWeb.destroy(root)
        assertEquals("true", root.getAttribute("data-tiqian-enhanced"))
        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
    }

    @Test
    fun longProgressiveEnhancementCommitsParagraphsAtomicallyAcrossFrames() {
        val markup = (0 until 18).joinToString("") { index ->
            "<p>第${index}段在自己的准备帧中原子切换。</p>"
        }
        val root = mount("<div data-tiqian-root='true' style='width: 180px'>$markup</div>")
        val paragraphs = (0 until 18).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val sourceChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        var readyCount = 0
        var stale = false
        root.addEventListener("tiqian:ready", { event ->
            readyCount += 1
            stale = relayoutEventIsStale(event)
        })
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())

        var progressiveFrames = 0
        var previousRenderedCount = 0
        while (pendingTestAnimationFrameCount() > 0) {
            assertEquals(1, flushOneTestAnimationFrame())
            val renderedCount = root.querySelectorAll("p[data-tq-rendered='true']").length
            assertTrue(renderedCount >= previousRenderedCount)
            assertTrue(paragraphs.indices.all { index ->
                val paragraph = paragraphs[index]
                paragraph.firstChild === sourceChildren[index] ||
                    paragraph.getAttribute("data-tq-rendered") == "true"
            }, "each paragraph must be either intact source or a complete Tiqian result")
            if (pendingTestAnimationFrameCount() > 0) {
                progressiveFrames += 1
                assertTrue(renderedCount in 1 until paragraphs.size)
                assertEquals(renderedCount.toString(), root.getAttribute("data-tiqian-enhanced-count"))
                assertEquals(0, readyCount)
            }
            previousRenderedCount = renderedCount
        }

        assertTrue(progressiveFrames >= 2)
        assertTrue(paragraphs.indices.all { index ->
            paragraphs[index].firstChild !== sourceChildren[index]
        })
        assertEquals("18", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, readyCount)
        assertFalse(stale)
    }

    @Test
    fun progressiveEnhancementPrioritizesViewportParagraphs() {
        val markup = (0 until 18).joinToString("") { index ->
            "<p>第${index}段用于验证视口优先顺序。</p>"
        }
        val root = mount("<div data-tiqian-root='true' style='width: 180px'>$markup</div>")
        val paragraphs = (0 until 18).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        paragraphs.forEachIndexed { index, paragraph ->
            setElementRect(paragraph, top = 1_000_000.0 - index * 1_000.0, width = 180.0)
        }
        setElementRect(paragraphs.last(), top = 0.0, width = 180.0)
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())
        assertEquals(1, flushOneTestAnimationFrame())

        assertEquals("true", paragraphs.last().getAttribute("data-tq-rendered"))
        assertTrue(root.querySelectorAll("p[data-tq-rendered='true']").length < paragraphs.size)
        assertEquals(1, flushOneTestAnimationFrame())
        flushAllTestAnimationFrames()
        assertEquals(18, root.querySelectorAll("p[data-tq-rendered='true']").length)
    }

    @Test
    fun progressiveEnhancementDoesNotWaitForHandledScrollQuietWindow() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 180px">
              <p>已处理的滚动不能再人为冻结可见段落提交。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        setElementRect(paragraph, top = 0.0, width = 180.0)
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())
        dispatchTestProgressiveScroll()

        assertEquals(1, flushOneTestAnimationFrame())
        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
        assertEquals(0, pendingTestAnimationFrameCount())
    }

    @Test
    fun destroyCancelsScheduledProgressiveTailBeforeItTouchesNativeParagraphs() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 180px">
              <p>离视口很远的第一段保持原生。</p>
              <p>离视口很远的第二段也保持原生。</p>
            </div>
            """.trimIndent(),
        )
        val paragraphs = (0 until 2).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val originalHtml = paragraphs.map { paragraph -> paragraph.innerHTML }
        paragraphs.forEach { paragraph ->
            setElementRect(paragraph, top = 1_000_000.0, width = 180.0)
        }
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())

        assertEquals(1, pendingTestAnimationFrameCount())
        TiqianWeb.destroy(root)
        assertEquals(1, cancelledTestAnimationFrameCount())
        flushAllTestAnimationFrames()

        paragraphs.forEachIndexed { index, paragraph ->
            assertEquals(originalHtml[index], paragraph.innerHTML)
            assertNull(paragraph.getAttribute("data-tq-rendered"))
        }
    }

    @Test
    fun progressiveEnhancementRollsBackPartialWorkPreparedAcrossDifferentWidths() {
        val markup = (0 until 18).joinToString("") { index ->
            "<p>第${index}段不能把旧宽度结果混入同一次整批提交。</p>"
        }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        val paragraphs = (0 until 18).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val sourceChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        var readyCount = 0
        var stale = false
        root.addEventListener("tiqian:ready", { event ->
            readyCount += 1
            stale = relayoutEventIsStale(event)
        })
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())
        assertEquals(1, flushOneTestAnimationFrame())
        root.style.width = "120px"
        flushAllTestAnimationFrames()

        assertTrue(paragraphs.indices.all { index ->
            paragraphs[index].firstChild === sourceChildren[index]
        })
        assertEquals(0, root.querySelectorAll("p[data-tq-rendered='true']").length)
        assertEquals(1, readyCount)
        assertTrue(stale)

        TiqianWeb.enhanceProgressively(root, testOptions())
        flushAllTestAnimationFrames()

        assertEquals(18, root.querySelectorAll("p[data-tq-rendered='true']").length)
        assertEquals(2, readyCount)
        assertFalse(stale)
    }

    @Test
    fun relayoutDuringInitialProgressiveWorkRestartsWithoutStrandingCandidates() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 240px">
              <p>第一段必须在重启后增强。</p>
              <p>第二段也不能被旧 job 遗漏。</p>
            </div>
            """.trimIndent(),
        )
        var readyCount = 0
        root.addEventListener("tiqian:ready", { readyCount += 1 })
        TiqianWeb.install()
        installTestAnimationFrames()

        TiqianWeb.enhanceProgressively(root, testOptions())
        root.style.width = "120px"
        dispatchRelayout(root)

        assertEquals(1, cancelledTestAnimationFrameCount())
        assertEquals(1, pendingTestAnimationFrameCount())
        assertEquals("0", root.getAttribute("data-tiqian-enhanced-count"))

        flushAllTestAnimationFrames()

        assertEquals(2, root.querySelectorAll("p[data-tq-rendered='true']").length)
        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, readyCount)
    }

    @Test
    fun newerRelayoutReplacesPendingWorkAndUsesTheLatestWidth() {
        val source = "连续 resize 只应提交最新宽度的分帧重排结果。".repeat(4)
        val root = mount("<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>")
        val expectedRoot = mount(
            "<div data-tiqian-root='true' style='width: 100px'><p>$source</p></div>",
        )
        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        assertEquals(1, TiqianWeb.enhance(expectedRoot, testOptions()))
        val paragraph = root.querySelector("p") as HTMLElement
        val initial = renderedLineSignature(paragraph)
        val expected = renderedLineSignature(expectedRoot.querySelector("p") as HTMLElement)
        assertNotEquals(initial, expected)
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })

        installTestAnimationFrames()
        root.style.width = "180px"
        dispatchRelayout(root)
        root.style.width = "100px"
        dispatchRelayout(root)

        assertEquals(1, cancelledTestAnimationFrameCount())
        assertEquals(1, pendingTestAnimationFrameCount())
        assertEquals(initial, renderedLineSignature(paragraph))

        flushAllTestAnimationFrames()

        assertEquals(expected, renderedLineSignature(paragraph))
        assertEquals(0, pendingTestAnimationFrameCount())
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun relayoutKeepsOldTiqianDomUntilItsFirstProgressiveFrame() {
        val root = mount(
            "<div data-tiqian-root='true' style='width: 320px'>" +
                "<p>第一段在所有准备完成前保持旧节点。</p>" +
                "<p>第二段也不能提前暴露新排版。</p>" +
                "</div>",
        )
        TiqianWeb.install()
        assertEquals(2, TiqianWeb.enhance(root, testOptions()))
        val first = root.querySelectorAll("p").item(0) as HTMLElement
        val second = root.querySelectorAll("p").item(1) as HTMLElement
        val firstRenderedChild = first.firstChild
        val secondRenderedChild = second.firstChild
        assertNotNull(firstRenderedChild)
        assertNotNull(secondRenderedChild)
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })

        installTestAnimationFrames()
        root.style.width = "120px"
        dispatchRelayout(root)

        assertTrue(first.firstChild === firstRenderedChild)
        assertTrue(second.firstChild === secondRenderedChild)
        assertEquals(1, pendingTestAnimationFrameCount())

        flushAllTestAnimationFrames()

        assertFalse(first.firstChild === firstRenderedChild)
        assertFalse(second.firstChild === secondRenderedChild)
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun longRelayoutYieldsAndCommitsEachParagraphAtomically() {
        val markup = (0 until 18).joinToString("") { index ->
            "<p>第${index}段在分帧提交时必须一直保持上一份提椠排版。</p>"
        }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        TiqianWeb.install()
        assertEquals(18, TiqianWeb.enhance(root, testOptions()))
        val paragraphs = (0 until 18).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val previousChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })

        installTestAnimationFrames()
        root.style.width = "120px"
        dispatchRelayout(root)

        var progressiveFrames = 0
        var previousUpdatedCount = 0
        while (pendingTestAnimationFrameCount() > 0) {
            assertEquals(1, flushOneTestAnimationFrame())
            val updatedCount = paragraphs.indices.count { index ->
                paragraphs[index].firstChild !== previousChildren[index]
            }
            assertTrue(updatedCount >= previousUpdatedCount)
            if (pendingTestAnimationFrameCount() > 0) {
                progressiveFrames += 1
                assertTrue(updatedCount in 1 until paragraphs.size)
                assertEquals(0, relayoutReadyCount)
            }
            previousUpdatedCount = updatedCount
        }

        assertTrue(progressiveFrames >= 2, "a long root must still yield during relayout")
        assertTrue(paragraphs.indices.all { index ->
            paragraphs[index].firstChild !== previousChildren[index]
        })
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun relayoutNeverCommitsPreparedMeasureOneGridCellBehindCurrentWidth() {
        val source = "任务执行中再次跨格时不能提交落后最终宽度的排版。".repeat(4)
        val root = mount("<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>")
        val intermediateRoot = mount(
            "<div data-tiqian-root='true' style='width: 180px'><p>$source</p></div>",
        )
        val finalRoot = mount(
            "<div data-tiqian-root='true' style='width: 162px'><p>$source</p></div>",
        )
        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        assertEquals(1, TiqianWeb.enhance(intermediateRoot, testOptions()))
        assertEquals(1, TiqianWeb.enhance(finalRoot, testOptions()))
        val paragraph = root.querySelector("p") as HTMLElement
        val initialChild = assertNotNull(paragraph.firstChild)
        val initial = renderedLineSignature(paragraph)
        val intermediate = renderedLineSignature(intermediateRoot.querySelector("p") as HTMLElement)
        val final = renderedLineSignature(finalRoot.querySelector("p") as HTMLElement)
        assertNotEquals(initial, intermediate)
        assertNotEquals(intermediate, final)
        var readyCount = 0
        var staleCount = 0
        root.addEventListener("tiqian:relayout-ready", { event ->
            readyCount += 1
            if (relayoutEventIsStale(event)) staleCount += 1
        })

        installTestAnimationFrames()
        root.style.width = "180px"
        dispatchRelayout(root)
        root.style.width = "162px"
        flushAllTestAnimationFrames()

        assertTrue(paragraph.firstChild === initialChild)
        assertEquals(initial, renderedLineSignature(paragraph))
        assertEquals(1, readyCount)
        assertEquals(1, staleCount)

        dispatchRelayout(root)
        flushAllTestAnimationFrames()

        assertEquals(final, renderedLineSignature(paragraph))
        assertEquals(2, readyCount)
        assertEquals(1, staleCount)
    }

    @Test
    fun relayoutDiscardsPreparedMeasureMoreThanOneGridCellBehindCurrentWidth() {
        val source = "长文 resize 不能把相差多个字格的历史结果逐级播放出来。".repeat(4)
        val root = mount("<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>")
        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))
        val paragraph = root.querySelector("p") as HTMLElement
        val initialChild = assertNotNull(paragraph.firstChild)
        val initial = renderedLineSignature(paragraph)
        var readyCount = 0
        var staleCount = 0
        root.addEventListener("tiqian:relayout-ready", { event ->
            readyCount += 1
            if (relayoutEventIsStale(event)) staleCount += 1
        })

        installTestAnimationFrames()
        root.style.width = "180px"
        dispatchRelayout(root)
        root.style.width = "144px"
        flushAllTestAnimationFrames()

        assertTrue(paragraph.firstChild === initialChild)
        assertEquals(initial, renderedLineSignature(paragraph))
        assertEquals(1, readyCount)
        assertEquals(1, staleCount)
    }

    @Test
    fun relayoutDiscardsPreparedMeasureAfterOvershootOrDirectionReversal() {
        val source = "反向 resize 或越过当前目标时不能提交旧方向的排版。".repeat(4)
        TiqianWeb.install()
        installTestAnimationFrames()

        fun assertStaleAt(currentWidth: String, reason: String) {
            val root = mount(
                "<div data-tiqian-root='true' style='width: 320px'><p>$source</p></div>",
            )
            assertEquals(1, TiqianWeb.enhance(root, testOptions()))
            val paragraph = root.querySelector("p") as HTMLElement
            val initialChild = assertNotNull(paragraph.firstChild)
            val initial = renderedLineSignature(paragraph)
            var readyCount = 0
            var staleCount = 0
            root.addEventListener("tiqian:relayout-ready", { event ->
                readyCount += 1
                if (relayoutEventIsStale(event)) staleCount += 1
            })

            root.style.width = "180px"
            dispatchRelayout(root)
            root.style.width = currentWidth
            flushAllTestAnimationFrames()

            assertTrue(paragraph.firstChild === initialChild, reason)
            assertEquals(initial, renderedLineSignature(paragraph), reason)
            assertEquals(1, readyCount)
            assertEquals(1, staleCount)
        }

        assertStaleAt("240px", "prepared measure overshot the current target")
        assertStaleAt("360px", "viewport reversed past the previously committed measure")
    }

    @Test
    fun relayoutCommitFailureRollsBackRenderedNodesAndStillCompletesTheJob() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                "<div data-tiqian-root='true' style='width: 220px'>" +
                    "<p data-tq-snapshot-key='plain' style=\"font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px\">" +
                    "原节点必须在异常后原样回来。</p></div>",
            )
            TiqianWeb.install()
            assertEquals(1, TiqianWeb.enhance(root, exactTestOptions()))
            val paragraph = root.querySelector("p") as HTMLElement
            val renderedChild = paragraph.firstChild
            val renderedHtml = paragraph.innerHTML
            val renderedStyle = paragraph.getAttribute("style")
            assertNotNull(renderedChild)
            var errorCount = 0
            var readyCount = 0
            root.addEventListener("tiqian:relayout-error", { errorCount += 1 })
            root.addEventListener("tiqian:relayout-ready", { readyCount += 1 })

            installTestAnimationFrames()
            failExactPreparedDomRender("fixture-commit-failure")
            root.style.width = "180px"
            dispatchRelayout(root)
            flushAllTestAnimationFrames()

            assertTrue(paragraph.firstChild === renderedChild)
            assertEquals(renderedHtml, paragraph.innerHTML)
            assertEquals(renderedStyle, paragraph.getAttribute("style"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            assertTrue(
                root.getAttribute("data-tiqian-relayout-error")?.contains("fixture-commit-failure") == true,
            )
            assertEquals(1, errorCount)
            assertEquals(1, readyCount, "terminal ready must release the JS in-flight state")
            assertEquals(0, pendingTestAnimationFrameCount())

            installExactFontSessionFixture(failShaping = false)
            root.style.width = "140px"
            dispatchRelayout(root)
            flushAllTestAnimationFrames()

            assertNull(root.getAttribute("data-tiqian-relayout-error"))
            assertEquals(2, readyCount)
            assertFalse(paragraph.firstChild === renderedChild)
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun fractionalWidthCrossingAFontSizeGridBoundaryRelayouts() {
        val source = "小数宽度跨字格边界不能被像素容差吞掉。".repeat(20)
        val root = mount(
            "<div data-tiqian-root='true' style='width: 305.98px'><p>$source</p></div>",
        )
        val options = testOptions().copy(fontSize = 15.3f, lineHeight = 22.95f)
        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, options))
        val paragraph = root.querySelector("p") as HTMLElement
        val nineteenCells = renderedLineSignature(paragraph)

        installTestAnimationFrames()
        root.style.width = "306.02px"
        dispatchRelayout(root)
        flushAllTestAnimationFrames()

        assertNotEquals(
            nineteenCells,
            renderedLineSignature(paragraph),
            "19→20 cells is a real measure change even though the raw width delta is below 0.5px",
        )
    }

    @Test
    fun destroyCancelsPendingRelayoutBeforeItCanRestoreRenderedDom() {
        val root = mount(
            "<div data-tiqian-root='true' style='width: 260px'><p>取消 resize job 后必须保持原生正文。</p></div>",
        )
        val paragraph = root.querySelector("p") as HTMLElement
        val originalHtml = paragraph.innerHTML
        TiqianWeb.install()
        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        installTestAnimationFrames()
        root.style.width = "100px"
        dispatchRelayout(root)
        assertEquals(1, pendingTestAnimationFrameCount())

        TiqianWeb.destroy(root)
        assertEquals(1, cancelledTestAnimationFrameCount())
        flushAllTestAnimationFrames()

        assertEquals(originalHtml, paragraph.innerHTML)
        assertNull(root.getAttribute("data-tiqian-enhanced"))
        assertNull(paragraph.getAttribute("data-tq-rendered"))
    }
}
