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
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.tiqian.shaping.web.WebCjkDashCapability

class TiqianWebExactSessionTest {
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
    fun workerRequestsUseTheResponsiveLineLengthGrid() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 220px">
              <p style="font-size: 18px; line-height: 30px">同一字数网格必须复用 Worker 请求。</p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement
        TiqianWeb.install()

        val first = exactWorkerRequestMaxWidth(root, paragraph)
        root.style.width = "225px"
        val sameGrid = exactWorkerRequestMaxWidth(root, paragraph)
        root.style.width = "234px"
        val nextGrid = exactWorkerRequestMaxWidth(root, paragraph)

        assertEquals(216.0, first)
        assertEquals(first, sameGrid)
        assertEquals(234.0, nextGrid)
    }

    @Test
    fun exactFontSessionUsesSharedBackendAndCanonicalPreparedDomBridge() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  $enginePunctuationFeatureStyle
                  <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
                </div>
                """.trimIndent(),
            )

            val count = TiqianWeb.enhance(root, exactTestOptions())

            assertEquals(1, count)
            val paragraph = root.querySelector("p") as HTMLElement
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            assertEquals("true", paragraph.getAttribute("data-tq-runtime-render-font"))
            assertEquals("zh-Hans", paragraph.getAttribute("lang"))
            assertNotNull(paragraph.querySelector("[data-tq-exact-rendered]"))
            assertEnginePunctuationFeatureLock(paragraph)
            assertTrue(exactPreparedPlan().contains("\"layoutRevision\":\"tiqian-layout-v2\""))
            assertTrue(exactPreparedPlan().contains("\"height\":"))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactFontSessionReplaysSemanticParagraphsThroughRuntimePreparedDom() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
                </div>
                """.trimIndent(),
            )

            val count = TiqianWeb.enhance(root, exactTestOptions())

            assertEquals(1, count)
            val paragraph = root.querySelector("p") as HTMLElement
            assertTrue(exactFontShapeCount() > 0)
            assertEquals(1, exactPreparedRenderCount())
            assertTrue(
                exactPreparedPlan().contains("\"overlayWidth\":"),
                "rich runtime plans must carry render evidence",
            )
            assertEquals(
                "[{\"start\":2,\"end\":4,\"tagName\":\"a\",\"sourceIndex\":0,\"order\":0}]",
                exactPreparedSemanticsJson(),
            )
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            val link = paragraph.querySelector("a[href='/more']") as HTMLElement
            assertEquals("true", link.getAttribute("data-tq-source-semantic"))
            assertEquals("semantic", link.getAttribute("data-tq-fixture-seen"))
            assertNotNull(paragraph.querySelector(".tq-line"))
            assertNotNull(paragraph.querySelector("[data-tq-exact-rendered]"))
            assertEquals("中文链接正文。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactRuntimePreparedDomReplaysInlineObjectsFromLiveElements() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p data-tq-snapshot-key="object" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">正文<svg width="18" height="18" style="margin-right: 4px"><rect width="18" height="18"></rect></svg>继续。</p>
                </div>
                """.trimIndent(),
            )
            val paragraph = root.querySelector("p") as HTMLElement
            val svg = paragraph.querySelector("svg") as Element

            assertEquals(1, TiqianWeb.enhance(root, exactTestOptions()))

            assertEquals(1, exactPreparedRenderCount())
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("inline-object", svg.getAttribute("data-tq-fixture-seen"))
            assertEquals(
                "[{\"start\":2,\"end\":3,\"marginRight\":4,\"tag\":\"svg\"}]",
                exactPreparedInlineObjectsJson(),
            )
            assertTrue(
                exactPreparedPlan().contains("\"inlineObject\":"),
                "the inline-object cell must ride the plan as render evidence",
            )
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactPreparedDomAdmitsDecoratedStrongEmphasisWithCjkStrongMetadata() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="emphasis" style="font-family: 'Fixture CJK'; font-weight: 430; font-size: 18px; line-height: 30px">前<strong style="font-weight: 700; color: rgb(1, 2, 3)">强调</strong>后。</p>
                </div>
                """.trimIndent(),
            )

            assertEquals(
                1,
                TiqianWeb.enhance(
                    root,
                    exactTestOptions().copy(strongAsEmphasisMarks = true),
                ),
            )

            val paragraph = root.querySelector("p") as HTMLElement
            assertEquals(1, exactPreparedRenderCount())
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            assertNull(paragraph.getAttribute("data-tq-capability-issue"))
            assertEquals(
                "[{\"start\":1,\"end\":3,\"tagName\":\"strong\",\"sourceIndex\":0,\"order\":0}]",
                exactPreparedSemanticsJson(),
            )
            assertEquals(
                "[{\"start\":1,\"end\":3,\"weight\":430}]",
                exactPreparedCjkStrongJson(),
                "the prepared bridge must receive the strong base weight for cjk-emphasis replay",
            )
            assertTrue(
                exactPreparedPlan().contains("\"emphasisDots\":"),
                "the decorated paragraph plan must carry overlay dots",
            )
            assertTrue(exactFontShapeCount() > 0)
            assertEquals("前强调后。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactWorkerPreparedDomCarriesInlineObjectsOnRequestAndCommit() {
        installExactFontSessionFixture(failShaping = false)
        installPreparedWorkerLivePlan()
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">正文<svg width="18" height="18" style="margin-right: 4px"><rect width="18" height="18"></rect></svg>继续。</p>
                </div>
                """.trimIndent(),
            )
            val paragraph = root.querySelector("p") as HTMLElement
            val svg = paragraph.querySelector("svg") as Element

            val inlineObjectsWire = exactWorkerRequestInlineObjects(root, paragraph)
            assertTrue(
                inlineObjectsWire.startsWith("2\u001d3\u001d"),
                "the measured inline-object geometry must ride the request wire: $inlineObjectsWire",
            )

            val enhanced = TiqianWeb.enhance(
                root,
                exactTestOptions().copy(
                    paragraphSelector = "p:not([data-tq-snapshot-key])",
                    requireExactLayoutWorker = true,
                ),
            )
            assertEquals(
                1,
                enhanced,
                "issue=${paragraph.getAttribute("data-tiqian-capability-issue")}; " +
                    "detail=${paragraph.getAttribute("data-tiqian-capability-detail")}; " +
                    "html=${paragraph.innerHTML}",
            )

            assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals(
                0,
                exactFontShapeCount(),
                "an inline-object paragraph must not fall back to main-thread shaping",
            )
            assertEquals(1, exactPreparedRenderCount())
            assertEquals("inline-object", svg.getAttribute("data-tq-fixture-seen"))
            assertEquals(
                "[{\"start\":2,\"end\":3,\"marginRight\":4,\"tag\":\"svg\"}]",
                exactPreparedInlineObjectsJson(),
            )
            assertEquals("正文继续。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactFaceEvidenceDoesNotFragmentOrdinaryDomText() {
        installExactFontSessionFixture(failShaping = false, varyFaceByText = true)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 700px">
                  <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文</p>
                </div>
                """.trimIndent(),
            )
            val options = exactTestOptions().copy(paragraphSelector = "p")

            assertEquals(1, TiqianWeb.enhance(root, options))

            val paragraph = root.querySelector("p") as HTMLElement
            assertEquals(
                0,
                paragraph.querySelectorAll(
                    ":scope > span[data-tq-geometry]:not(.tq-line):not([data-tq-line-end-sentinel])",
                ).length,
                "font replay evidence must not create a visible shaping boundary: ${paragraph.innerHTML}",
            )
            assertEquals("中文正文", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun semanticParagraphFallsBackPerUnsupportedFontRunWithoutAbandoningExactLayout() {
        installExactFontSessionFixture(failShaping = false, failFamily = "Fixture Mono")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
                </div>
                """.trimIndent(),
            )

            assertEquals(1, TiqianWeb.enhance(root, exactTestOptions()))

            val paragraph = root.querySelector("p") as HTMLElement
            assertTrue(exactFontShapeCount() > 0)
            assertTrue(exactFontFallbackCount() > 0)
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertNotNull(paragraph.querySelector("code"))
            assertEquals("中文code42正文。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactWorkerFontReplayMissFallsBackOnlyForRichBrowserRun() {
        installExactFontSessionFixture(failShaping = false, failFamily = "Fixture Mono")
        installPreparedWorkerIssue("MissingServerShapingReplay:test")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
                </div>
                """.trimIndent(),
            )

            assertEquals(
                1,
                TiqianWeb.enhance(
                    root,
                    exactTestOptions().copy(requireExactLayoutWorker = true),
                ),
            )

            val paragraph = root.querySelector("p") as HTMLElement
            assertTrue(exactFontFallbackCount() > 0)
            assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertNotNull(paragraph.querySelector("code"))
            assertEquals("中文code42正文。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactWorkerUnsupportedLiveSemanticReplaysWorkerPlanFromSourceElement() {
        installExactFontSessionFixture(failShaping = false)
        installPreparedWorkerLivePlan()
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">正文<spoiler style="box-decoration-break: slice; padding-left: 4px; padding-right: 4px"><em>秘密</em></spoiler>继续。</p>
                </div>
                """.trimIndent(),
            )

            val paragraph = root.querySelector("p") as HTMLElement
            val enhanced = TiqianWeb.enhance(
                root,
                exactTestOptions().copy(
                    paragraphSelector = "p:not([data-tq-snapshot-key])",
                    requireExactLayoutWorker = true,
                ),
            )
            assertEquals(
                1,
                enhanced,
                "issue=${paragraph.getAttribute("data-tiqian-capability-issue")}; " +
                    "detail=${paragraph.getAttribute("data-tiqian-capability-detail")}; " +
                    "html=${paragraph.innerHTML}",
            )

            assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertNotNull(
                paragraph.querySelector(
                    "spoiler[data-tq-source-semantic] > em[data-tq-source-semantic]",
                ),
            )
            assertEquals(0, exactFontShapeCount(), "live semantics must not relayout on the main thread")
            assertEquals(1, exactPreparedRenderCount())
            assertEquals("正文秘密继续。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun unkeyedRuntimeCompletionKeepsExactDashWhenAnotherRunNeedsBrowserFallback() {
        installExactFontSessionFixture(failShaping = false, failText = "坏")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 260px">
                  <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">坏——正文。</p>
                </div>
                """.trimIndent(),
            )
            val options = exactTestOptions().copy(
                paragraphSelector = "p:not([data-tq-snapshot-key])",
                cjkDashCapability = WebCjkDashCapability(
                    status = "unavailable",
                    detail = "ServerShapingReplayRequired",
                ),
            )

            assertEquals(1, TiqianWeb.enhance(root, options))

            val paragraph = root.querySelector("p") as HTMLElement
            assertTrue(exactFontShapeCount() > 0)
            assertTrue(exactFontFallbackCount() > 0)
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertNotNull(paragraph.querySelector(".tq-line"))
            assertNotNull(paragraph.querySelector("[data-tq-exact-rendered]"))
            assertEquals("坏——正文。", copySelection(paragraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun unsupportedGlyphFallbackKeepsExactParagraphLineMetrics() {
        installExactFontSessionFixture(failShaping = false, failText = "a")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 300px">
                  <p data-tq-snapshot-key="exact" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
                  <p data-tq-snapshot-key="fallback" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">… and <a href="/more">more</a>.</p>
                </div>
                """.trimIndent(),
            )

            assertEquals(2, TiqianWeb.enhance(root, exactTestOptions()))

            val paragraphs = root.querySelectorAll("p")
            val exactParagraph = paragraphs.item(0) as HTMLElement
            val fallbackParagraph = paragraphs.item(1) as HTMLElement
            assertTrue(exactFontFallbackCount() > 0)
            assertEquals(2, exactPreparedRenderCount())
            assertEquals(
                exactPreparedPlanAt(0).substringAfter("\"height\":").substringBefore(','),
                exactPreparedPlanAt(1).substringAfter("\"height\":").substringBefore(','),
                "browser-fallback runs must not perturb prepared line metrics",
            )
            assertNotNull(exactParagraph.querySelector("a[data-tq-source-semantic]"))
            assertNotNull(fallbackParagraph.querySelector("a[data-tq-source-semantic]"))
            assertEquals("中文链接正文。", copySelection(exactParagraph))
            assertEquals("… and more.", copySelection(fallbackParagraph))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun exactBrowserFallbackCarriesLatinQuoteFeaturesIntoPreparedDomPlan() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">that’s James’ ’90s</p>
                </div>
                """.trimIndent(),
            )

            val count = TiqianWeb.enhance(root, exactTestOptions())

            assertEquals(1, count)
            assertTrue(
                exactPreparedPlan().contains("\"openTypeFeatures\":[\"pwid\",\"palt\"]"),
                exactPreparedPlan(),
            )
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun browserFontFallbackMeasuresAndReplaysLatinCurlyQuoteFeatures() {
        val source = "that’s；（如 ‘O’, ‘Q’）"
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 500px">
              $enginePunctuationFeatureStyle
              <p>$source</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, TiqianWeb.enhance(root, testOptions()))

        val paragraph = root.querySelector("p") as HTMLElement
        val featureRuns = paragraph.querySelectorAll(
            "span[data-tq-open-type-features='pwid,palt']",
        )
        assertEquals(3, featureRuns.length, paragraph.innerHTML)
        var quotedCodePoints = 0
        for (index in 0 until featureRuns.length) {
            val run = featureRuns.item(index) as HTMLElement
            assertEnginePunctuationFeatureLock(run, proportionalQuote = true)
            quotedCodePoints += run.textContent.orEmpty().count { it in '\u2018'..'\u201D' }
        }
        assertEquals(5, quotedCodePoints, paragraph.innerHTML)
        assertEquals(source, copySelection(paragraph))
    }

    @Test
    fun browserQuoteContextMatrixReplaysOnlyLatinQuoteFeatures() {
        data class QuoteCase(
            val source: String,
            val html: String = source,
            val proportionalQuoteCount: Int,
        )

        val cases = listOf(
            QuoteCase(source = "中“文”中", proportionalQuoteCount = 0),
            QuoteCase(
                source = "便延伸出了“乃子”“大波”“大灯”“大雷”“大扎”“对A”“波霸”这些词",
                proportionalQuoteCount = 0,
            ),
            QuoteCase(
                source = "这些太直白了是吧， “欧派”“double”“double may”呢",
                proportionalQuoteCount = 0,
            ),
            QuoteCase(source = "“Hello”", proportionalQuoteCount = 2),
            QuoteCase(source = "that’s James’ ’90s", proportionalQuoteCount = 3),
            QuoteCase(source = "中文 ‘don’t’", proportionalQuoteCount = 3),
            QuoteCase(source = "他说：“She said ‘hello’.”", proportionalQuoteCount = 2),
            QuoteCase(
                source = "中文 ‘don’t’",
                html = "中文 <strong>‘don’t’</strong>",
                proportionalQuoteCount = 3,
            ),
        )
        val root = mount(
            "<div data-tiqian-root='true' style='width: 520px'>" +
                cases.joinToString(separator = "") { "<p>${it.html}</p>" } +
                "</div>",
        )

        TiqianWeb.install()
        assertEquals(cases.size, TiqianWeb.enhance(root, testOptions()))

        fun assertCases() {
            val paragraphs = root.querySelectorAll("p")
            for ((index, case) in cases.withIndex()) {
                val paragraph = paragraphs.item(index) as HTMLElement
                val featureRuns = paragraph.querySelectorAll(
                    "span[data-tq-open-type-features='pwid,palt']",
                )
                var actualQuoteCount = 0
                for (runIndex in 0 until featureRuns.length) {
                    actualQuoteCount += featureRuns.item(runIndex)!!
                        .textContent
                        .orEmpty()
                        .count { it.isCurlyQuoteForWebTest() }
                }
                assertEquals(case.proportionalQuoteCount, actualQuoteCount, case.source)
                assertEquals(case.source, copySelection(paragraph), case.source)
            }
        }
        assertCases()

        installTestAnimationFrames()
        root.style.width = "180px"
        dispatchRelayout(root)
        flushAllTestAnimationFrames()
        assertCases()
    }

    @Test
    fun unavailableExactFaceFallsBackToTheBrowserPipeline() {
        installExactFontSessionFixture(failShaping = true)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="plain" style="font-size: 18px; line-height: 30px">中文正文。</p>
                </div>
                """.trimIndent(),
            )

            val count = TiqianWeb.enhance(root, exactTestOptions())

            assertEquals(1, count)
            val paragraph = root.querySelector("p") as HTMLElement
            assertTrue(exactFontFallbackCount() > 0)
            assertEquals(1, exactPreparedRenderCount())
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-source"))
            assertNotNull(paragraph.querySelector(".tq-line"))
            assertNotNull(paragraph.querySelector("[data-tq-exact-rendered]"))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun standingPreparedDomValidationFailureFailsEveryParagraphClosed() {
        installExactFontSessionFixture(failShaping = false)
        failExactPreparedDomValidation("fixture-line-drift")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
                  <p data-tq-snapshot-key="second" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">第二段正文。</p>
                </div>
                """.trimIndent(),
            )
            val paragraph = root.querySelector("p") as HTMLElement
            val second = root.querySelector("p[data-tq-snapshot-key='second']") as HTMLElement

            val count = TiqianWeb.enhance(root, exactTestOptions())

            // PreparedDomRenderMismatch: the bridge disagrees even with
            // browser-metric output, so both paragraphs fail closed and the
            // custody bridge restores their source.
            assertEquals(0, count)
            assertEquals("0", root.getAttribute("data-tiqian-enhanced-count"))
            assertEquals("2", root.getAttribute("data-tiqian-issue-count"))
            assertNull(paragraph.getAttribute("data-tq-rendered"))
            assertNull(paragraph.getAttribute("data-tq-canonical-plain"))
            assertNull(paragraph.querySelector(".tq-line"))
            assertEquals(
                "PreparedDomRenderMismatch",
                paragraph.getAttribute("data-tiqian-capability-issue"),
            )
            assertNull(second.getAttribute("data-tq-rendered"))
            assertNull(second.querySelector(".tq-line"))
            assertEquals(
                "PreparedDomRenderMismatch",
                second.getAttribute("data-tiqian-capability-issue"),
            )
            // The first paragraph renders twice (exact session, then the
            // browser-metric retry); the second fails on its only render.
            assertEquals(3, exactPreparedRenderCount())
            assertEquals(
                "fixture-line-drift",
                root.getAttribute("data-tiqian-exact-layout-fallback"),
            )
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun preparedDomMismatchRetriesWithBrowserMetricsThroughThePreparedBridge() {
        installExactFontSessionFixture(failShaping = false)
        failNextExactPreparedDomValidation("fixture-line-drift")
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
                  <p data-tq-snapshot-key="second" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">第二段正文。</p>
                </div>
                """.trimIndent(),
            )
            val paragraph = root.querySelector("p") as HTMLElement
            val second = root.querySelector("p[data-tq-snapshot-key='second']") as HTMLElement

            val count = TiqianWeb.enhance(root, exactTestOptions())

            // ExactSessionMetricDistrust: the first replay failed geometry
            // validation against exact-session metrics, so the paragraph
            // re-lays out with browser metrics and replays through the
            // prepared bridge; that second render validates clean.
            assertEquals(2, count)
            assertEquals("2", root.getAttribute("data-tiqian-enhanced-count"))
            assertEquals("true", paragraph.getAttribute("data-tq-rendered"))
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertNotNull(paragraph.querySelector(".tq-line"))
            assertNull(paragraph.getAttribute("data-tiqian-capability-issue"))
            assertEquals("true", second.getAttribute("data-tq-rendered"))
            assertNotNull(second.querySelector(".tq-line"))
            assertNull(second.getAttribute("data-tiqian-capability-issue"))
            assertEquals(3, exactPreparedRenderCount())
            assertEquals(
                "fixture-line-drift",
                root.getAttribute("data-tiqian-exact-layout-fallback"),
            )
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun layoutOptionOverrideCannotReuseTheSnapshotExactSession() {
        installExactFontSessionFixture(failShaping = false)
        try {
            val root = mount(
                """
                <div data-tiqian-root="true" style="width: 220px">
                  <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
                </div>
                """.trimIndent(),
            )

            val count = TiqianWeb.enhance(root, exactTestOptions().copy(fontSize = 24f))

            assertEquals(1, count)
            val paragraph = root.querySelector("p") as HTMLElement
            assertEquals(0, exactFontShapeCount())
            assertEquals(1, exactPreparedRenderCount())
            assertEquals("true", paragraph.getAttribute("data-tq-canonical-plain"))
            assertNotNull(paragraph.querySelector("[data-tq-exact-rendered]"))
            assertNotNull(paragraph.querySelector(".tq-line"))
        } finally {
            clearExactFontSessionFixture()
        }
    }

    @Test
    fun configuredFontSizeMeasuresAndPaintsTheSameHostTypography() {
        val root = mount(
            """
            <div data-tiqian-root="true" style="width: 700px">
              <p style="display: inline-block; font-size: 16px; line-height: 25px">一二三四五六七八九十甲乙丙丁戊己<a href="/more">庚辛</a></p>
            </div>
            """.trimIndent(),
        )
        val paragraph = root.querySelector("p") as HTMLElement

        assertEquals(
            1,
            TiqianWeb.enhance(
                root,
                testOptions().copy(fontSize = 19f, lineHeight = 33.25f),
            ),
        )

        val line = paragraph.querySelector(".tq-line") as HTMLElement
        val link = paragraph.querySelector("a") as HTMLElement
        assertEquals("19px", computedStyleValue(paragraph, "font-size"))
        assertEquals("19px", computedStyleValue(link, "font-size"))
        assertEquals(33.25f, cssPx(line.style.getPropertyValue("--tq-line-height")))
        assertEquals(342f, line.getAttribute("data-tq-line-width")!!.toFloat(), 0.5f)

        TiqianWeb.destroy(root)
        assertEquals("16px", computedStyleValue(paragraph, "font-size"))
        assertNotNull(paragraph.querySelector("a[href='/more']"))
    }
}
