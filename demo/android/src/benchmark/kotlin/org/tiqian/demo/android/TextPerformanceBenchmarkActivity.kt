package org.tiqian.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import org.tiqian.compose.CjkSelectionContainer
import org.tiqian.compose.CjkText

/** Release-only surface driven by :benchmark:android. It is not packaged in normal demo builds. */
class TextPerformanceBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val benchmarkCase = TextBenchmarkCase.fromWireName(
            intent.getStringExtra(EXTRA_CASE),
        )
        setContent {
            TextPerformanceBenchmarkScreen(benchmarkCase)
        }
    }

    @Composable
    private fun TextPerformanceBenchmarkScreen(benchmarkCase: TextBenchmarkCase) {
        val article = remember(benchmarkCase.hasLinks) {
            benchmarkArticle(hasLinks = benchmarkCase.hasLinks)
        }
        val scrollState = rememberScrollState()
        var recompositionEpoch by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            awaitFrame()
            awaitFrame()
            reportFullyDrawn()
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            BasicText(
                text = "重组 $recompositionEpoch",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) {
                        recompositionEpoch += 1
                    }
                    .semantics { contentDescription = "benchmark-recompose" }
                    .padding(bottom = 12.dp),
                style = TextStyle(fontSize = 12.sp, color = Color.Gray),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val articleContent: @Composable () -> Unit = {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .semantics {
                                contentDescription = "benchmark-ready-${benchmarkCase.wireName}"
                            },
                    ) {
                        article.forEach { paragraph ->
                            if (benchmarkCase.usesTiqian) {
                                CjkText(
                                    text = paragraph,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    style = ARTICLE_STYLE,
                                )
                            } else {
                                BasicText(
                                    text = paragraph,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    style = ARTICLE_STYLE,
                                )
                            }
                        }
                    }
                }
                when {
                    !benchmarkCase.hasSelection -> articleContent()
                    benchmarkCase.usesTiqian -> CjkSelectionContainer(
                        modifier = Modifier.fillMaxSize(),
                        scrollState = scrollState,
                        content = articleContent,
                    )
                    else -> SelectionContainer(content = articleContent)
                }
            }
        }
    }

    private companion object {
        const val EXTRA_CASE = "benchmark_case"

        val ARTICLE_STYLE = TextStyle(
            color = Color(0xFF202124),
            fontSize = 17.sp,
            lineHeight = 27.sp,
        )
    }
}

private enum class TextBenchmarkCase(
    val wireName: String,
    val usesTiqian: Boolean,
    val hasLinks: Boolean,
    val hasSelection: Boolean,
) {
    ComposePlain("compose-plain", usesTiqian = false, hasLinks = false, hasSelection = false),
    TiqianPlain("tiqian-plain", usesTiqian = true, hasLinks = false, hasSelection = false),
    ComposeArticle("compose-article", usesTiqian = false, hasLinks = true, hasSelection = true),
    TiqianArticle("tiqian-article", usesTiqian = true, hasLinks = true, hasSelection = true),
    ;

    companion object {
        fun fromWireName(value: String?): TextBenchmarkCase =
            entries.firstOrNull { it.wireName == value } ?: TiqianArticle
    }
}

private fun benchmarkArticle(hasLinks: Boolean): List<AnnotatedString> =
    List(48) { index -> benchmarkParagraph(index, hasLinks) }

private fun benchmarkParagraph(index: Int, hasLinks: Boolean): AnnotatedString = buildAnnotatedString {
    append("第${index + 1}段：中文正文并不只是把字依次画在屏幕上。")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("粗体") }
    append("、")
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic") }
    append("、")
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append("删除线") }
    append("和中西混排 OpenType 都会共同参与断行与绘制。")
    if (hasLinks) {
        withLink(
            LinkAnnotation.Clickable(
                tag = "link-$index",
                linkInteractionListener = LinkInteractionListener {},
            ),
        ) {
            append("这是第${index + 1}个链接")
        }
    } else {
        append("这是同样长度的普通文字")
    }
    append("，后文继续补足一段真实文章常见的长度，用于观察首轮布局、滚动绘制和暖态重组。")
}
