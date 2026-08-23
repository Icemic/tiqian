package org.tiqian.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class ParagraphDrawCacheTest {
    @Test
    fun drawingSurvivesRedrawDetachAndReattach() {
        val visible = mutableStateOf(true)
        ImageComposeScene(width = 320, height = 96) {
            if (visible.value) {
                CjkText(
                    text = AnnotatedString("中文 gyp 链接装饰"),
                    style = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
                    inlineDecorations = listOf(
                        CjkInlineDecoration(
                            range = TextRange(0, 6),
                            style = CjkInlineDecorationStyle.DottedUnderline(
                                color = Color.Black,
                                dotDiameter = 1.5.dp,
                                gapLength = 2.dp,
                            ),
                        ),
                    ),
                )
            }
        }.use { scene ->
            scene.render(0L)
            scene.render(16_000_000L)
            visible.value = false
            scene.render(32_000_000L)
            visible.value = true
            scene.render(48_000_000L)
        }
    }
}
