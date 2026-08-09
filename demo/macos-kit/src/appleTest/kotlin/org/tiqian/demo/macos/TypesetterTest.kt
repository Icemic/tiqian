package org.tiqian.demo.macos

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke coverage for the Swift-facing SDK surface (the surface an AppKit/SwiftUI app consumes): a
 * real end-to-end run through the engine + Core Text pipeline via the authoring builder, asserting
 * the measured geometry the app reads back is sane. Drawing is covered by the renderer's bitmap
 * tests; here we validate the facade wires content → layout. The app owns the actual sample content.
 */
@OptIn(ExperimentalForeignApi::class)
class TypesetterTest {

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
}
