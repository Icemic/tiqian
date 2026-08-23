package org.tiqian.apple

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tiqian.core.DecorationKind
import org.tiqian.core.RubyKind
import org.tiqian.core.TextRange

/**
 * Smoke coverage for the Swift-facing SDK surface (the surface an AppKit/SwiftUI app consumes): a
 * real end-to-end run through the engine + Core Text pipeline via the authoring builder, asserting
 * the measured geometry the app reads back is sane. Drawing is covered by the renderer's bitmap
 * tests; here we validate the facade wires content → layout. The app owns the actual sample content.
 */
@OptIn(ExperimentalForeignApi::class)
class TypesetterTest {

    @Test
    fun lowersCombinedNativeAttributesWithoutDuplicatingSource() {
        val lowered = lower(
            ParagraphContent().piece(
                s = "重",
                bold = true,
                italic = true,
                sizeEm = 1.25f,
                family = "PingFang SC",
                argb = 0xFFB00020L,
                rubyReading = "zhòng",
                rubyBopomofo = false,
                emphasis = true,
                properNoun = false,
                bookTitle = true,
                mourning = false,
            ),
            baseSize = 20f,
        )

        assertEquals("重", lowered.text)
        assertEquals(listOf(TextRange(0, 1)), lowered.spans.map { it.range })
        assertEquals(listOf(25f), lowered.spans.map { it.style.fontSize })
        assertEquals(listOf(TextRange(0, 1)), lowered.colorSpans.map { TextRange(it.start, it.end) })
        assertEquals(listOf(TextRange(0, 1)), lowered.rubySpans.map { it.baseRange })
        assertEquals(listOf(RubyKind.Pinyin), lowered.rubySpans.map { it.kind })
        assertEquals(
            listOf(DecorationKind.Emphasis, DecorationKind.BookTitle),
            lowered.decorations.map { it.kind },
        )
        assertEquals(setOf(0, 1), lowered.sourceBoundaries)
    }

    @Test
    fun keepsSimplifiedBaseLocaleWhileBopomofoUsesTraditionalLocale() {
        val lowered = lower(
            ParagraphContent()
                .text("请")
                .bopomofo("坐", "ㄗㄨㄛˋ"),
            baseSize = 20f,
        )

        assertEquals("zh-Hans", lowered.locale)
        assertEquals("zh-TW", lowered.rubySpans.single().locale)
        assertEquals(RubyKind.Bopomofo, lowered.rubySpans.single().kind)
    }

    @Test
    fun laysOutPlainTextToPositiveGeometry() {
        val paragraph = Typesetter(fontSize = 18f)
            .layout("提椠中文排版示例，验证 Swift 门面能真正跑通真实布局。", width = 240f)
        assertTrue(paragraph.height > 0.0, "height should be positive")
        assertTrue(paragraph.width > 0.0, "width should be positive")
        assertTrue(paragraph.lineCount >= 2, "text at a narrow measure should wrap, got ${paragraph.lineCount} line(s)")
    }

    @Test
    fun buildsAndLaysOutARichDocument() {
        // Exercise the authoring builder the way the app does: styled runs, ruby, decorations,
        // a section break, a numbered list and a bullet list — all lowered + stacked by the kit.
        val doc = Typesetter(fontSize = 18f).documentBuilder()
            .flushParagraph(
                ParagraphContent().run("标题", bold = true, italic = false, sizeEm = 1.6f, family = null, argb = 0L),
            )
            .paragraph(
                ParagraphContent()
                    .text("正文一段，含")
                    .pinyin("提椠", "tíqiàn")
                    .text("、")
                    .emphasis("着重")
                    .text("与")
                    .colored("红字", 0xFFB00020L)
                    .text("。"),
            )
            .section()
            .numberedList(
                listOf(
                    ParagraphContent().text("第一条，续行也应与正文同列对齐，所以要写得足够长以触发换行换行换行。"),
                    ParagraphContent().text("第二条。"),
                ),
            )
            .bulletList(
                listOf(
                    ParagraphContent().text("要点甲"),
                    ParagraphContent().text("要点乙"),
                ),
            )
            .layout(width = 320f)

        assertTrue(doc.height > 0.0, "document height should be positive")
        // title + paragraph + (2 numbered: 2 marker + 2 body) + (2 bullet: 2 marker + 2 body) = 10.
        assertTrue(doc.blockCount >= 8, "expected many placed blocks, got ${doc.blockCount}")
    }

    @Test
    fun alignsListMarkerAndBodyOnTheirFirstBaselines() {
        val doc = Typesetter(fontSize = 18f).documentBuilder()
            .bulletList(listOf(ParagraphContent().text("列表正文")))
            .decimalList(listOf(ParagraphContent().text("另一项正文")))
            .layout(width = 240f)

        doc.blocks.chunked(2).forEach { (marker, body) ->
            val markerBaseline = marker.yTop + marker.result.lines.first().baseline
            val bodyBaseline = body.yTop + body.result.lines.first().baseline
            assertEquals(bodyBaseline, markerBaseline, 0.01)
        }
    }

    @Test
    fun listMarkersStayFlushAndDoNotAddTheBodyFirstLineIndentToTheGutter() {
        val fontSize = 18f
        val doc = Typesetter(fontSize = fontSize).documentBuilder()
            .bulletList(listOf(ParagraphContent().text("项目符号正文")))
            .decimalList(listOf(ParagraphContent().text("十进制正文")))
            .numberedList(listOf(ParagraphContent().text("汉字编号正文")))
            .layout(width = 240f)

        val rows = doc.blocks.chunked(2)
        assertEquals(3, rows.size)
        rows.forEach { (marker, _) ->
            assertEquals(0f, marker.result.lines.first().indent, 0.01f)
        }
        assertEquals(
            0.0,
            rows[0][0].x + rows[0][0].result.lines.first().indent.toDouble(),
            0.01,
            "bullet marker box should align with the left edge of its column",
        )
        assertEquals(fontSize.toDouble(), rows[0][1].x, 0.01, "bullet gutter should be one 字")
        assertTrue(rows[1][1].x <= fontSize * 2.0, "decimal gutter must not include a two-字 body indent")
        assertTrue(rows[2][1].x <= fontSize * 2.0, "CJK marker gutter must not include a two-字 body indent")
    }

    @Test
    fun extremelyNarrowListDoesNotPassANegativeBodyWidthToTheCore() {
        val doc = Typesetter(fontSize = 18f).documentBuilder()
            .numberedList(listOf(ParagraphContent().text("正文")))
            .layout(width = 18f)

        assertTrue(doc.height > 0.0)
        assertEquals(2, doc.blockCount)
    }

    @Test
    fun clipboardTextMatchesWebRubyAndListSemantics() {
        val doc = Typesetter(fontSize = 18f).documentBuilder()
            .numberedList(
                listOf(
                    ParagraphContent()
                        .pinyin("提椠", "tíqiàn")
                        .text("与")
                        .bopomofo("您", "ㄋㄧㄣˊ"),
                    ParagraphContent().text("正文"),
                ),
            )
            .layout(width = 240f)

        assertEquals("一、提椠与您\n二、正文", doc.text)
        assertEquals("一、提椠（tíqiàn）与您（ㄋㄧㄣˊ）\n二、正文", doc.clipboardTextInRange(0, doc.text.length))
        assertEquals("提", doc.clipboardTextInRange(2, 3), "partial ruby base must not emit a detached reading")
        assertEquals("提椠（tíqiàn）", doc.clipboardTextInRange(2, 4))
    }

    @Test
    fun nativeLinkUsesExactSourceGeometryWithoutChangingText() {
        val target = "https://www.w3.org/TR/clreq/"
        val doc = Typesetter(fontSize = 20f).documentBuilder()
            .flushParagraph(
                ParagraphContent()
                    .text("读「")
                    .piece(
                        s = "CLREQ",
                        bold = false,
                        italic = false,
                        sizeEm = 1f,
                        family = null,
                        argb = 0xFF007AFFL,
                        rubyReading = null,
                        rubyBopomofo = false,
                        emphasis = false,
                        properNoun = false,
                        bookTitle = false,
                        mourning = false,
                        linkTarget = target,
                    )
                    .text("」。"),
            )
            .layout(width = 240f)

        assertEquals("读「CLREQ」。", doc.text)
        assertEquals(doc.text, doc.clipboardTextInRange(0, doc.text.length))
        val box = doc.linkBoxes().single()
        assertEquals(2, box.start)
        assertEquals(7, box.end)
        assertEquals(target, doc.linkAt((box.left + box.right) / 2, (box.top + box.bottom) / 2))
        assertNull(doc.linkAt(doc.width + 1, doc.height + 1))
    }

    @Test
    fun exposesSourceFaithfulSelectionQueriesForNativeViews() {
        val doc = Typesetter(fontSize = 20f).documentBuilder()
            .flushParagraph(ParagraphContent().text("提椠中文"))
            .flushParagraph(ParagraphContent().text("second line"))
            .layout(width = 220f)

        assertEquals("提椠中文\nsecond line", doc.text)
        val initialCaret = doc.caretBox(0)
        val hit = doc.selectionOffset(
            x = initialCaret.left + 5.0,
            y = (initialCaret.top + initialCaret.bottom) / 2.0,
        )
        assertTrue(hit in 0..1, "first glyph hit should resolve to one of its insertion edges")

        val word = assertNotNull(doc.selectionWord(initialCaret.left + 5.0, initialCaret.top + 5.0))
        assertEquals(0, word.start)
        assertEquals(1, word.end, "Han selection expands to exactly one source unit")

        val boxes = doc.selectionBoxes(start = 0, end = doc.text.length)
        assertTrue(boxes.size >= 2, "a two-paragraph selection should expose multiple visual rows")
        assertTrue(boxes.all { it.right > it.left && it.bottom > it.top })
        assertEquals("椠中文\nsecond", doc.textInRange(1, 11))
    }

    @Test
    fun selectionBoundaryNeverSplitsASurrogatePair() {
        val doc = Typesetter(fontSize = 20f).documentBuilder()
            .flushParagraph(ParagraphContent().text("😀a"))
            .layout(width = 220f)

        assertEquals(0, doc.selectionBoundary(offset = 1, forward = false))
        assertEquals(2, doc.selectionBoundary(offset = 1, forward = true))
    }
}
