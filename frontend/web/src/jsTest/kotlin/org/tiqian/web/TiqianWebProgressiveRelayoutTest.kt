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
            TiqianWebWorkers.detach(root)
            root.parentNode?.removeChild(root)
        }
        mounted.clear()
        restoreTestAnimationFrames()
    }

    // WorkerPolledScheduling test harness: an attached root never runs on its
    // own, so these helpers stand in for the page coordinator's per-frame
    // grants. The grant deadline defaults to 0, already in the past, so one
    // slice commits one paragraph, which keeps a mid-job width change
    // constructible between slices.
    private fun attachWorker(root: HTMLElement) {
        TiqianWebWorkers.attach(root)
    }

    private fun grantWorkerSlice(root: HTMLElement, deadlineMs: Double = 0.0): Int {
        val controller = testGrantController(
            root,
            TiqianWebWorkers.jobGeneration(root),
            deadlineMs,
            Int.MAX_VALUE,
        )
        return TiqianWebWorkers.runSlice(controller, 3)
    }

    private fun runWorkerJobToCompletion(root: HTMLElement, deadlineMs: Double = 0.0): Int {
        var slices = 0
        while (TiqianWebWorkers.hasJob(root)) {
            grantWorkerSlice(root, deadlineMs)
            slices += 1
            if (slices > 1000) throw AssertionError("attached worker job did not settle")
        }
        return slices
    }

    // One grant whose quota and deadline never bite, so the slice loop walks to
    // itemCount instead of breaking on shouldStop. That walk is where a
    // tier-gated item must survive: the gate advances the cursor without
    // marking the item done, and the job may only finish when nothing is left.
    private fun grantUnboundedSlice(root: HTMLElement, minTier: Int): Int {
        val controller = testGrantController(
            root,
            TiqianWebWorkers.jobGeneration(root),
            Double.MAX_VALUE,
            Int.MAX_VALUE,
        )
        return TiqianWebWorkers.runSlice(controller, minTier)
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
        // A zero computed font-size never reaches shaping: the ffi wire face
        // rejects the input and process-paragraph reports the throw as a
        // WebEnhancementFailure whose detail carries the rejection reason.
        assertEquals(
            "WebEnhancementFailure",
            paragraph.getAttribute("data-tiqian-capability-issue"),
        )
        assertTrue(paragraph.getAttribute("data-tiqian-capability-detail")?.contains("InvalidFontSize") == true)
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

        attachWorker(root)
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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())

        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
        runWorkerJobToCompletion(root)
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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())

        var progressiveSlices = 0
        var previousRenderedCount = 0
        while (TiqianWebWorkers.hasJob(root)) {
            grantWorkerSlice(root)
            val renderedCount = root.querySelectorAll("p[data-tq-rendered='true']").length
            assertTrue(renderedCount >= previousRenderedCount)
            assertTrue(paragraphs.indices.all { index ->
                val paragraph = paragraphs[index]
                paragraph.firstChild === sourceChildren[index] ||
                    paragraph.getAttribute("data-tq-rendered") == "true"
            }, "each paragraph must be either intact source or a complete Tiqian result")
            if (TiqianWebWorkers.hasJob(root)) {
                progressiveSlices += 1
                assertTrue(renderedCount in 1 until paragraphs.size)
                assertEquals(renderedCount.toString(), root.getAttribute("data-tiqian-enhanced-count"))
                assertEquals(0, readyCount)
            }
            previousRenderedCount = renderedCount
        }

        assertTrue(progressiveSlices >= 2)
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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())
        grantWorkerSlice(root)

        assertEquals("true", paragraphs.last().getAttribute("data-tq-rendered"))
        assertTrue(root.querySelectorAll("p[data-tq-rendered='true']").length < paragraphs.size)
        runWorkerJobToCompletion(root)
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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())
        dispatchTestProgressiveScroll()
        grantWorkerSlice(root)

        assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
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

        attachWorker(root)
        TiqianWeb.enhanceProgressively(root, testOptions())
        grantWorkerSlice(root)
        assertTrue(
            root.querySelectorAll("p[data-tq-rendered='true']").length < paragraphs.size,
        )
        TiqianWeb.destroy(root)
        runWorkerJobToCompletion(root)

        paragraphs.forEachIndexed { index, paragraph ->
            assertEquals(originalHtml[index], paragraph.innerHTML)
            assertNull(paragraph.getAttribute("data-tq-rendered"))
        }
    }

    @Test
    fun progressiveEnhancementReportsStaleAcrossWidthChangeWithoutTearingCommittedParagraphs() {
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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())
        grantWorkerSlice(root)
        root.style.width = "120px"
        runWorkerJobToCompletion(root)

        // StaleFinishKeepsCommittedParagraphs: paragraphs committed while the
        // captured measure still held stay rendered; the drifted ones keep
        // their semantic source for the follow-up job at the live width.
        val renderedCount = root.querySelectorAll("p[data-tq-rendered='true']").length
        assertTrue(renderedCount in 1 until 18)
        assertTrue(paragraphs.indices.all { index ->
            paragraphs[index].getAttribute("data-tq-rendered") == "true" ||
                paragraphs[index].firstChild === sourceChildren[index]
        })
        assertEquals(1, readyCount)
        assertTrue(stale)

        TiqianWeb.enhanceProgressively(root, testOptions())
        runWorkerJobToCompletion(root)

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
        attachWorker(root)

        TiqianWeb.enhanceProgressively(root, testOptions())
        root.style.width = "120px"
        dispatchRelayout(root)

        assertEquals("0", root.getAttribute("data-tiqian-enhanced-count"))

        runWorkerJobToCompletion(root)

        assertEquals(2, root.querySelectorAll("p[data-tq-rendered='true']").length)
        assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
        assertEquals(1, readyCount)
    }

    @Test
    fun newerRelayoutReplacesPendingWorkAndUsesTheLatestWidth() {
        val source = "连续 resize 只应提交最新宽度的分帧重排结果。".repeat(4)
        val markup = (0 until 10).joinToString("") { "<p>$source</p>" }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        val expectedRoot = mount(
            "<div data-tiqian-root='true' style='width: 100px'>$markup</div>",
        )
        TiqianWeb.install()
        assertEquals(
            10,
            TiqianWeb.enhance(root, testOptions()),
            "issue=${root.querySelector("p")?.getAttribute("data-tiqian-capability-issue")}; " +
                "detail=${root.querySelector("p")?.getAttribute("data-tiqian-capability-detail")}",
        )
        assertEquals(10, TiqianWeb.enhance(expectedRoot, testOptions()))
        val paragraphs = (0 until 10).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val initialChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        val initial = renderedLineSignature(paragraphs[0])
        val expected = renderedLineSignature(expectedRoot.querySelector("p") as HTMLElement)
        assertNotEquals(initial, expected)
        var relayoutReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { relayoutReadyCount += 1 })

        installTestAnimationFrames()
        attachWorker(root)
        root.style.width = "180px"
        dispatchRelayout(root)
        grantWorkerSlice(root)
        root.style.width = "100px"
        dispatchRelayout(root)

        // The first job committed part of the root at 180px inside its granted
        // slice. The second dispatch must replace that pending work. The
        // superseded job must not keep committing results prepared for an old
        // width. A granted slice is budget-bound, so the test asserts the
        // committed count only as a range.
        val replacedAtLatestWidth = paragraphs.indices.count { index ->
            paragraphs[index].firstChild !== initialChildren[index]
        }
        assertTrue(replacedAtLatestWidth in 1 until paragraphs.size)

        runWorkerJobToCompletion(root)

        for (paragraph in paragraphs) {
            assertEquals(expected, renderedLineSignature(paragraph))
        }
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun relayoutSwapsParagraphDomAtomicallyWithoutAFrameDelay() {
        val root = mount(
            "<div data-tiqian-root='true' style='width: 320px'>" +
                "<p>第一段在原子替换中直接换上新排版。</p>" +
                "<p>第二段也在同一个分片里完成交换。</p>" +
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

        // SyncFirstSlice: the first slice runs inside the dispatch task, so a
        // two-paragraph root is fully re-laid out before any animation frame
        // is flushed. Each paragraph swaps its children as one atomic unit,
        // so no frame can catch a paragraph with its old line boxes already
        // removed but the new ones not yet attached.
        assertFalse(first.firstChild === firstRenderedChild)
        assertFalse(second.firstChild === secondRenderedChild)
        assertEquals(0, pendingTestAnimationFrameCount())
        assertEquals(1, relayoutReadyCount)

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
        attachWorker(root)
        root.style.width = "120px"
        dispatchRelayout(root)
        grantWorkerSlice(root)

        // A granted slice commits a budget-bound batch of paragraphs. A long
        // root is therefore only partially re-laid out after one grant; the
        // remaining paragraphs are committed by later granted slices.
        val committedBeforeAnyFrame = paragraphs.indices.count { index ->
            paragraphs[index].firstChild !== previousChildren[index]
        }
        assertTrue(committedBeforeAnyFrame in 1 until paragraphs.size)

        var progressiveSlices = 0
        var previousUpdatedCount = committedBeforeAnyFrame
        while (TiqianWebWorkers.hasJob(root)) {
            grantWorkerSlice(root)
            val updatedCount = paragraphs.indices.count { index ->
                paragraphs[index].firstChild !== previousChildren[index]
            }
            assertTrue(updatedCount >= previousUpdatedCount)
            if (TiqianWebWorkers.hasJob(root)) {
                progressiveSlices += 1
                assertTrue(updatedCount in 1 until paragraphs.size)
                assertEquals(0, relayoutReadyCount)
            }
            previousUpdatedCount = updatedCount
        }

        assertTrue(progressiveSlices >= 1, "a long root must still yield during relayout")
        assertTrue(paragraphs.indices.all { index ->
            paragraphs[index].firstChild !== previousChildren[index]
        })
        assertEquals(1, relayoutReadyCount)
    }

    @Test
    fun sliceWalkingPastTierGatedParagraphKeepsJobOpenInsteadOfAbandoningIt() {
        // TierGatedItemKeepsJobOpen: reproduces the stuck one-cell-width
        // sidebar paragraph. A narrow-dwell job commits every paragraph at the
        // narrow width; the follow-up wide job then sees one of them flipped
        // to a far tier. A granted slice that walks past that gated item
        // without breaking must keep the job open — finishing it there
        // strands the narrow commit forever with a stale=false ready event,
        // and no later job ever comes because the host width is stable.
        val source = "拖动经过窄区后回宽，被门槛挡住的段落不能被当作完成遗弃。".repeat(3)
        val markup = (0 until 3).joinToString("") { "<p>$source</p>" }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        TiqianWeb.install()
        assertEquals(3, TiqianWeb.enhance(root, testOptions()))
        val paragraphs = (0 until 3).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val wideSignatures = paragraphs.map { renderedLineSignature(it) }
        var relayoutReadyCount = 0
        var staleReadyCount = 0
        root.addEventListener("tiqian:relayout-ready", { event ->
            relayoutReadyCount += 1
            if (relayoutEventIsStale(event)) staleReadyCount += 1
        })

        installTestAnimationFrames()
        attachWorker(root)

        // Narrow dwell: every paragraph commits at the narrow width.
        root.style.width = "120px"
        dispatchRelayout(root)
        runWorkerJobToCompletion(root)
        assertEquals(1, relayoutReadyCount)
        val narrowChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        assertTrue(paragraphs.indices.all { index ->
            renderedLineSignature(paragraphs[index]) != wideSignatures[index]
        })

        // Back to wide. The middle paragraph is offscreen for the coordinator.
        root.style.width = "320px"
        dispatchRelayout(root)
        assertTrue(TiqianWebWorkers.setParagraphTier(root, 1, 3))

        // One unbounded slice walks item 0, gates item 1, walks item 2.
        val committed = grantUnboundedSlice(root, minTier = 1)
        assertEquals(2, committed)

        // The gated paragraph must keep the job open; it must not be reported
        // as a completed (stale=false) relayout while still narrow.
        assertTrue(
            TiqianWebWorkers.hasJob(root),
            "a tier-gated paragraph must keep its job open instead of being abandoned as finished",
        )
        assertEquals(0, staleReadyCount)
        assertEquals(1, relayoutReadyCount)
        assertEquals(1, TiqianWebWorkers.pendingInTier(root, 3))
        assertTrue(paragraphs[1].firstChild === narrowChildren[1])
        assertTrue(paragraphs[0].firstChild !== narrowChildren[0])
        assertTrue(paragraphs[2].firstChild !== narrowChildren[2])

        // A later grant with a wider gate reaches the gated paragraph, and
        // only then does the job finish.
        grantUnboundedSlice(root, minTier = 3)
        assertFalse(TiqianWebWorkers.hasJob(root))
        assertEquals(2, relayoutReadyCount)
        assertEquals(0, staleReadyCount)
        assertTrue(paragraphs[1].firstChild !== narrowChildren[1])
        assertEquals(wideSignatures[1], renderedLineSignature(paragraphs[1]))
        assertTrue(paragraphs.indices.all { index ->
            renderedLineSignature(paragraphs[index]) == wideSignatures[index]
        })
    }

    @Test
    fun relayoutNeverCommitsPreparedMeasureOneGridCellBehindCurrentWidth() {
        val source = "任务执行中再次跨格时不能提交落后最终宽度的排版。".repeat(2)
        val markup = (0 until 10).joinToString("") { "<p>$source</p>" }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        val intermediateRoot = mount(
            "<div data-tiqian-root='true' style='width: 180px'>$markup</div>",
        )
        val finalRoot = mount(
            "<div data-tiqian-root='true' style='width: 162px'>$markup</div>",
        )
        TiqianWeb.install()
        assertEquals(10, TiqianWeb.enhance(root, testOptions()))
        assertEquals(10, TiqianWeb.enhance(intermediateRoot, testOptions()))
        assertEquals(10, TiqianWeb.enhance(finalRoot, testOptions()))
        val paragraphs = (0 until 10).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val initialChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        val initial = renderedLineSignature(paragraphs[0])
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
        attachWorker(root)
        root.style.width = "180px"
        dispatchRelayout(root)
        grantWorkerSlice(root)
        root.style.width = "162px"
        runWorkerJobToCompletion(root)

        // The first granted slice committed part of the root at 180px; a
        // granted slice is budget-bound, so the test asserts the committed
        // count only as a range. The later drift to 162px is detected at the
        // next slice head: StaleMeasureGuardPerSlice skips the remaining
        // items and reports the job as stale. The skipped paragraphs keep
        // the previously rendered DOM, because committing the 180px measure
        // would leave them one grid cell behind the live width.
        val committed = paragraphs.indices.count { index ->
            paragraphs[index].firstChild !== initialChildren[index]
        }
        assertTrue(committed in 1 until paragraphs.size)
        for ((index, paragraph) in paragraphs.withIndex()) {
            val expected = if (paragraph.firstChild === initialChildren[index]) initial else intermediate
            assertEquals(expected, renderedLineSignature(paragraph))
        }
        assertEquals(1, readyCount)
        assertEquals(1, staleCount)

        dispatchRelayout(root)
        runWorkerJobToCompletion(root)

        for (paragraph in paragraphs) {
            assertEquals(final, renderedLineSignature(paragraph))
        }
        assertEquals(2, readyCount)
        assertEquals(1, staleCount)
    }

    @Test
    fun relayoutDiscardsPreparedMeasureMoreThanOneGridCellBehindCurrentWidth() {
        val source = "长文 resize 不能把相差多个字格的历史结果逐级播放出来。".repeat(2)
        val markup = (0 until 10).joinToString("") { "<p>$source</p>" }
        val root = mount("<div data-tiqian-root='true' style='width: 320px'>$markup</div>")
        val intermediateRoot = mount(
            "<div data-tiqian-root='true' style='width: 180px'>$markup</div>",
        )
        TiqianWeb.install()
        assertEquals(10, TiqianWeb.enhance(root, testOptions()))
        assertEquals(10, TiqianWeb.enhance(intermediateRoot, testOptions()))
        val paragraphs = (0 until 10).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val initialChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
        val initial = renderedLineSignature(paragraphs[0])
        val intermediate = renderedLineSignature(intermediateRoot.querySelector("p") as HTMLElement)
        assertNotEquals(initial, intermediate)
        var readyCount = 0
        var staleCount = 0
        root.addEventListener("tiqian:relayout-ready", { event ->
            readyCount += 1
            if (relayoutEventIsStale(event)) staleCount += 1
        })

        installTestAnimationFrames()
        attachWorker(root)
        root.style.width = "180px"
        dispatchRelayout(root)
        grantWorkerSlice(root)
        root.style.width = "144px"
        runWorkerJobToCompletion(root)

        // The first granted slice committed its paragraphs at 180px, which
        // was the live width at commit time; a granted slice is budget-bound,
        // so the test asserts the committed count only as a range. The later
        // jump to 144px crosses multiple grid cells, so the remaining slices
        // must stop; replaying the stale 180px results one cell at a time
        // would fall behind the live grid.
        val committed = paragraphs.indices.count { index ->
            paragraphs[index].firstChild !== initialChildren[index]
        }
        assertTrue(committed in 1 until paragraphs.size)
        for ((index, paragraph) in paragraphs.withIndex()) {
            val expected = if (paragraph.firstChild === initialChildren[index]) initial else intermediate
            assertEquals(expected, renderedLineSignature(paragraph))
        }
        assertEquals(1, readyCount)
        assertEquals(1, staleCount)
    }

    @Test
    fun relayoutDiscardsPreparedMeasureAfterOvershootOrDirectionReversal() {
        val source = "反向 resize 或越过当前目标时不能提交旧方向的排版。".repeat(2)
        val markup = (0 until 10).joinToString("") { "<p>$source</p>" }
        TiqianWeb.install()
        installTestAnimationFrames()

        fun assertStaleAt(currentWidth: String, reason: String) {
            val root = mount(
                "<div data-tiqian-root='true' style='width: 320px'>$markup</div>",
            )
            assertEquals(10, TiqianWeb.enhance(root, testOptions()))
            val paragraphs = (0 until 10).map { index ->
                root.querySelectorAll("p").item(index) as HTMLElement
            }
            val initialChildren = paragraphs.map { paragraph -> assertNotNull(paragraph.firstChild) }
            val initial = renderedLineSignature(paragraphs[0])
            var readyCount = 0
            var staleCount = 0
            root.addEventListener("tiqian:relayout-ready", { event ->
                readyCount += 1
                if (relayoutEventIsStale(event)) staleCount += 1
            })

            attachWorker(root)
            root.style.width = "180px"
            dispatchRelayout(root)
            grantWorkerSlice(root)
            root.style.width = currentWidth
            runWorkerJobToCompletion(root)

            // The first granted slice committed its paragraphs at the
            // then-live width of 180px; a granted slice is budget-bound, so
            // the test asserts the committed count only as a range.
            // Overshooting or reversing the target width must stop the
            // remaining slices from committing the measure prepared for
            // 180px.
            val committed = paragraphs.indices.count { index ->
                paragraphs[index].firstChild !== initialChildren[index]
            }
            assertTrue(committed in 1 until paragraphs.size, reason)
            for ((index, paragraph) in paragraphs.withIndex()) {
                if (paragraph.firstChild === initialChildren[index]) {
                    assertEquals(initial, renderedLineSignature(paragraph), reason)
                }
            }
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
            "19→20 cells is a real measure change even though the raw width delta is below 0.5px; " +
                "error=${root.getAttribute("data-tiqian-relayout-error")}; " +
                "issue=${paragraph.getAttribute("data-tiqian-capability-issue")}; " +
                "detail=${paragraph.getAttribute("data-tiqian-capability-detail")}",
        )
    }

    @Test
    fun destroyCancelsPendingRelayoutBeforeItCanRestoreRenderedDom() {
        val markup = (0 until 10).joinToString("") { "<p>取消 resize job 后必须保持原生正文。</p>" }
        val root = mount("<div data-tiqian-root='true' style='width: 260px'>$markup</div>")
        val paragraphs = (0 until 10).map { index ->
            root.querySelectorAll("p").item(index) as HTMLElement
        }
        val originalHtmls = paragraphs.map { paragraph -> paragraph.innerHTML }
        TiqianWeb.install()
        assertEquals(10, TiqianWeb.enhance(root, testOptions()))

        installTestAnimationFrames()
        attachWorker(root)
        root.style.width = "100px"
        dispatchRelayout(root)
        // One granted slice has already committed part of the root. The
        // destroy below must cancel the remaining pending slices and roll
        // every paragraph, whether committed or not, back to native source.
        grantWorkerSlice(root)
        assertTrue(TiqianWebWorkers.hasJob(root))

        TiqianWeb.destroy(root)
        assertFalse(TiqianWebWorkers.hasJob(root))

        for ((index, paragraph) in paragraphs.withIndex()) {
            assertEquals(originalHtmls[index], paragraph.innerHTML)
            assertNull(paragraph.getAttribute("data-tq-rendered"))
        }
        assertNull(root.getAttribute("data-tiqian-enhanced"))
    }
}
