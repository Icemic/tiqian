package org.tiqian.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProgressiveTechnicalCurrentLineDeviceTest {
    @Test
    fun technicalBreakRefillsAfterUpstreamPushInWithoutStretchingBody() {
        val text = "Swift 这边是我最有体感的。JSONDecoder 慢是个老问题，" +
            "SR-6252[36] 那个 issue 里挖出的根因是底层走 NSJSONSerialization " +
            "再桥接回 Objective-C，swift_dynamicCast 吃掉大量时间。"
        val codeRanges = listOf(TextRange(16, 27), TextRange(67, 86), TextRange(104, 121))
        val annotated = AnnotatedString.Builder().apply {
            var cursor = 0
            codeRanges.forEach { range ->
                append(text.substring(cursor, range.start))
                inlineCode { append(text.substring(range.start, range.end)) }
                cursor = range.end
            }
            append(text.substring(cursor))
        }.toAnnotatedString()
        val density = Density(density = 3f, fontScale = 1f)
        val result = createPlatformParagraphMeasurer().measureWithInlineContent(
            text = annotated,
            constraints = LayoutConstraints(maxWidth = 1248f),
            density = density,
            style = TextStyle(fontSize = 16.sp, lineHeight = 25.6.sp),
            paragraphStyle = ParagraphStyle(
                firstLineIndent = Ic(0f),
                lineLengthGrid = LineLengthGrid(enabled = false),
            ),
            inlineObjects = emptyList(),
            inlineBackgrounds = codeRanges.map { range ->
                CjkInlineBackground(
                    range = range,
                    color = Color.Black,
                    horizontalPadding = 4.dp,
                    verticalPadding = 3.dp,
                    cornerRadius = 3.dp,
                    metricPolicy = CjkInlineBackgroundMetricPolicy.ParagraphTextStyle,
                )
            },
        )
        val lines = result.lines.map { line -> text.substring(line.range.start, line.range.end) }
        val diagnostic = buildString {
            appendLine(lines.joinToString("\n"))
            appendLine(result.debug.lineDecisions.joinToString("\n"))
            appendLine(result.debug.justificationDecisions.joinToString("\n"))
            appendLine(result.debug.breakOpportunityDecisions.joinToString("\n"))
        }

        val nsLineIndex = lines.indexOfFirst { it.contains("NSJSON") }
        assertTrue(nsLineIndex >= 0, diagnostic)
        assertTrue(lines[nsLineIndex].endsWith("NSJSONSer"), diagnostic)
        assertTrue(
            result.debug.lineDecisions[nsLineIndex].notes.contains("technical-break:Emergency"),
            diagnostic,
        )
        result.debug.lineDecisions.forEach { decision ->
            if (decision.notes.none { it.startsWith("technical-break:") }) return@forEach
            val cjkStretch = result.debug.justificationDecisions
                .firstOrNull { it.lineRange == decision.range }
                ?.allocations
                .orEmpty()
                .filter { it.kind == "CjkInterChar" }
                .maxOfOrNull { it.delta } ?: 0f
            assertTrue(cjkStretch <= 0.001f, diagnostic)
        }
    }
}
