package org.tiqian.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.tiqian.compose.CjkBlock
import org.tiqian.compose.CjkInlineBackground
import org.tiqian.compose.CjkInlineDecoration
import org.tiqian.compose.CjkInlineDecorationStyle
import org.tiqian.compose.CjkSelectionContainer
import org.tiqian.compose.CjkText
import org.tiqian.compose.CjkTextStyle
import org.tiqian.compose.ListMarker
import org.tiqian.compose.addTechnicalInlineAnnotation
import org.tiqian.compose.bookTitle
import org.tiqian.compose.bopomofo
import org.tiqian.compose.emphasis
import org.tiqian.compose.mourning
import org.tiqian.compose.properNoun
import org.tiqian.compose.ruby
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.RubyLineHeightMode
import org.tiqian.core.ic
import kotlin.time.TimeSource

private class DemoLayoutTiming {
    var physicalContentWidth = -1
}

@Composable
fun TiqianDemoScreen() {
    val textStyle = CjkTextStyle(fontSize = 15.sp)
    val scrollState = rememberScrollState()
    val layoutTiming = remember { DemoLayoutTiming() }
    CjkSelectionContainer(
        modifier = Modifier.fillMaxSize(),
        scrollState = scrollState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(24.dp)
                .layout { measurable, constraints ->
                    val physicalContentWidth = constraints.maxWidth
                    val logLayout = layoutTiming.physicalContentWidth != physicalContentWidth
                    val startedAt = if (logLayout) TimeSource.Monotonic.markNow() else null
                    val placeable = measurable.measure(constraints)
                    if (logLayout) {
                        layoutTiming.physicalContentWidth = physicalContentWidth
                        println(
                            "layout demo page with physical_content_width=$physicalContentWidth in " +
                                startedAt!!.elapsedNow(),
                        )
                    }
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                },
        ) {
            RustDemoSample(textStyle)
        }
    }
}

private val BLUE = Color(0xFF2563EB)
private val GREEN = Color(0xFF1A6E3C)
private val RED = Color(0xFFB00020)
private val PURPLE = Color(0xFF7E22CE)
private val YELLOW = Color(0xFFFDE68A)

@Composable
private fun RustDemoSample(textStyle: CjkTextStyle) {
    val density = LocalDensity.current
    val bodyFontSize = with(density) { textStyle.fontSize.toPx() }
    val lineHeight = bodyFontSize * 1.5f
    val sectionHeight = with(density) { lineHeight.toDp() }
    val narrowProofWidth = with(density) { (4f * bodyFontSize).toDp() }
    val narrowHyphenationWidth = with(density) { (8f * bodyFontSize).toDp() }
    val ligatureWordStyle = textStyle.copy(fontSize = 17.sp, fontFamily = FontFamily.Serif)
    val ligatureSymbolStyle = textStyle.copy(fontFamily = FontFamily.Monospace)
    val indented = remember(lineHeight) { ParagraphStyle(lineHeight = lineHeight) }
    val flush = remember { ParagraphStyle(firstLineIndent = org.tiqian.core.Ic.Zero) }
    val blockQuote = remember {
        ParagraphStyle(blockIndent = 2.ic, firstLineIndent = org.tiqian.core.Ic.Zero)
    }
    val uniformRuby = remember { ParagraphStyle(rubyLineHeightMode = RubyLineHeightMode.UniformParagraph) }
    val titleStyle = remember {
        ParagraphStyle(firstLineIndent = org.tiqian.core.Ic.Zero, lastLineAlignment = LastLineAlignment.Center)
    }
    val signatureStyle = remember {
        ParagraphStyle(firstLineIndent = org.tiqian.core.Ic.Zero, lastLineAlignment = LastLineAlignment.End)
    }
    @Composable
    fun paragraph(text: AnnotatedString, style: ParagraphStyle = indented) {
        CjkText(text, Modifier.fillMaxWidth(), textStyle, style, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
    }

    paragraph(rustProof(), indented)
    Spacer(Modifier.height(20.dp))
    paragraph(rustTitle(), titleStyle)
    Spacer(Modifier.height(20.dp))
    paragraph(rustOverview(), indented)
    Spacer(Modifier.height(20.dp))
    paragraph(AnnotatedString(RUST_PUNCTUATION), indented)
    paragraph(rustMixedText(), indented)
    paragraph(AnnotatedString(RUST_LIST_INTRO), flush)
    CjkText(
        blocks = rustNumberedList(),
        modifier = Modifier.fillMaxWidth(),
        textStyle = textStyle,
        paragraphStyle = flush,
    )
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustPinyin(), uniformRuby)
    paragraph(rustBopomofo(), indented)
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustDecorations(), indented)
    paragraph(rustQuote(), blockQuote)
    CjkText(
        text = rustRichText(),
        modifier = Modifier.fillMaxWidth(),
        textStyle = textStyle,
        paragraphStyle = indented,
        inlineDecorations = rustRichTextDecorations(),
        inlineBackgrounds = listOf(rustBackground(RUST_RICH_TEXT, "校样状态")),
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    CjkText(
        text = rustFileNote(),
        modifier = Modifier.fillMaxWidth(),
        textStyle = textStyle,
        paragraphStyle = indented,
        inlineBackgrounds = listOf(
            rustBackground(RUST_FILE_NOTE, "Review 3"),
            CjkInlineBackground(
                range = rangeOf(RUST_FILE_NOTE, "editorial-notes.md"),
                color = Color(0xFFE5E7EB),
                horizontalPadding = 2.dp,
                verticalPadding = 1.dp,
                cornerRadius = 2.dp,
                adjacentSameStyleClearance = 0.dp,
            ),
        ),
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    paragraph(AnnotatedString(RUST_BULLET_INTRO), flush)
    CjkText(
        blocks = rustBulletList(),
        modifier = Modifier.fillMaxWidth(),
        textStyle = textStyle,
        paragraphStyle = flush,
    )
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustClosing(), indented)
    paragraph(AnnotatedString(RUST_SIGNATURE), signatureStyle)
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustAppendixTitle("附录：窄栏断词与行尾标点"), flush)
    CjkText(
        text = RUST_NARROW_PROOF,
        modifier = Modifier.width(narrowProofWidth),
        textStyle = textStyle,
        paragraphStyle = flush,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    CjkText(
        text = rustNarrowHyphenation(),
        modifier = Modifier.width(narrowHyphenationWidth),
        textStyle = textStyle,
        paragraphStyle = flush,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustAppendixTitle("附录：Emoji 组合字形"), flush)
    paragraph(AnnotatedString(RUST_EMOJI_APPENDIX), indented)
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustAppendixTitle("附录：连字字形"), flush)
    CjkText(
        text = RUST_LIGATURE_WORDS,
        modifier = Modifier.fillMaxWidth(),
        textStyle = ligatureWordStyle,
        paragraphStyle = flush,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    CjkText(
        text = RUST_LIGATURE_SYMBOLS,
        modifier = Modifier.fillMaxWidth(),
        textStyle = ligatureSymbolStyle,
        paragraphStyle = flush,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
    Spacer(Modifier.height(sectionHeight))
    paragraph(rustAppendixTitle("附录：其他语言示例文本"), flush)
    Spacer(Modifier.height(sectionHeight))
    paragraph(AnnotatedString(RUST_JAPANESE), flush)
    Spacer(Modifier.height(sectionHeight))
    paragraph(AnnotatedString(RUST_KOREAN), flush)
    Spacer(Modifier.height(sectionHeight))
    paragraph(AnnotatedString(RUST_ENGLISH), flush)
    Spacer(Modifier.height(sectionHeight))
    paragraph(AnnotatedString(RUST_SPANISH), flush)
    Spacer(Modifier.height(sectionHeight))
    paragraph(AnnotatedString(RUST_RUSSIAN), flush)
}

private fun rustProof() = buildAnnotatedString {
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("「第三次校样」") }
    append("据编辑批注修订，日期为二〇二六年八月二十六日。")
}

private fun rustTitle() = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 1.9.em, fontWeight = FontWeight.Bold)) {
        ruby("提椠", "tíqiàn")
        append("中文正文排版样张")
    }
}

private fun rustOverview() = buildAnnotatedString {
    append("汉字排版讲究的不只是字形端正，也包括")
    emphasis { append("行列疏密") }
    append("、标点位置与")
    emphasis { append("段落节奏") }
    append("。本页选取书刊校样中常见的文字形式，集中呈现简体中文横排、中西文混排、行间注文和传统标注。窗口宽度改变时，文字会依照新的版心重新成行；标题、列表与注文也随正文一同调整。")
}

private fun rustMixedText() = buildAnnotatedString {
    append("中文书刊经常夹用 Latin letters、")
    withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("OpenType") }
    append(" 字体名称、")
    withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("Unicode") }
    append(" 字符编号和 ")
    withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("HTTP/2") }
    append(" 协议名称。汉字与西文字母或数字相邻时，应留有细微而稳定的间隔；行首与行尾则不额外添空。较长的英文词如 internationalization 和 interoperability，可以在合适的音节处使用连字符转行，但不应任意拆开。")
}

private fun rustNumberedList() = listOf(
    CjkBlock.List(
        items = listOf(
            AnnotatedString("每个非末行在版心内保持齐整，末行则依段落用途自然收束。"),
            AnnotatedString("标点临近行首或行尾时，系统优先调整可用空隙，避免出现突兀的断行。"),
            buildAnnotatedString {
                append("中文可使用")
                withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("黑体") }
                append("或")
                withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append("宋体") }
                append("；英文可能为 ")
                withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("sans-serif") }
                append(" 或 ")
                withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append("serif") }
                append("，亦可能为 ")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("monospace") }
                append(" （等宽字体）。混排时，仍须保持稳定的基线和行距。")
            },
        ),
        marker = ListMarker.CjkNumber(),
    ),
)

private fun rustPinyin() = buildAnnotatedString {
    append("地名、术语或生僻字可以附加拼音。例如，“")
    ruby("提椠", "tíqiàn")
    append("”二字读作 tíqiàn；")
    emphasis { append("注文") }
    append("居于基字上方，既帮助读者辨音，也不打乱正文原有的行列。相邻注文较长时，字间距离可以适度调整，使注音清楚而不显拥挤。")
}

private fun rustBopomofo() = buildAnnotatedString {
    append("为照顾使用注音符号的读者，本页另以“")
    bopomofo("您", "ㄋㄧㄣˊ")
    bopomofo("好", "ㄏㄠˇ")
    append("”为例：您字右侧标注 ㄋㄧㄣˊ，好字右侧标注 ㄏㄠˇ。声母、韵母与调号依字身排列，注文与正文之间保持")
    emphasis { append("清楚而稳定") }
    append("的对应关系。")
}

private fun rustDecorations() = buildAnnotatedString {
    append("讨论现代中文排版时，")
    properNoun { append("北京大学") }
    append("的研究者常会参阅")
    bookTitle { append("《中文排版需求》") }
    append("以及相关字体排印著作。书名可用波浪线标示，专有名称则用直线区别。已故语言学家")
    mourning { append("朱德熙") }
    append("先生对现代汉语研究贡献深远；在特定出版物中，其姓名可以示亡号标明。需要读者")
    emphasis { append("格外留意") }
    append("的词句，还可以加着重号。")
}

private fun rustQuote() = buildAnnotatedString {
    append("编校札记：版面宽阔时，正文宜")
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("从容舒展") }
    append("；\n栏宽收窄时，段首缩进与行间距离也应保持协调。")
}

private fun rustRichText() = buildAnnotatedString {
    append("校样状态")
    append("分为：")
    withStyle(SpanStyle(color = GREEN)) { append("已核") }
    append("、")
    withStyle(SpanStyle(color = BLUE)) { append("待校") }
    append("、")
    withStyle(SpanStyle(color = RED)) { append("旁注") }
    append("与撤销。")
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("新增词句") }
    append("加实线下划线，")
    append("存疑内容")
    append("加虚线下划线，")
    append("补充说明")
    append("加点线下划线，")
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append("已经撤销的文字") }
    append("则保留删除线，以便追溯修改过程。")
}

private fun rustRichTextDecorations() = listOf(
    CjkInlineDecoration(
        rangeOf(RUST_RICH_TEXT, "存疑内容"),
        CjkInlineDecorationStyle.DashedUnderline(Color.Black, 1.dp, 3.dp, 2.dp, 0.dp),
    ),
    CjkInlineDecoration(
        rangeOf(RUST_RICH_TEXT, "补充说明"),
        CjkInlineDecorationStyle.DottedUnderline(dotDiameter = 1.5.dp, gapLength = 1.5.dp, adjacentSameStyleClearance = 0.dp),
    ),
)

private fun rustFileNote() = buildAnnotatedString {
    append("本次校样依据 ")
    val codeStart = length
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("editorial-notes.md") }
    addTechnicalInlineAnnotation(codeStart, length)
    append(" 整理，参考版本为 ")
    withStyle(SpanStyle(color = PURPLE)) { append("Review 3") }
    append("。文件名采用等宽字体，版本名称加浅色背景；两者夹在中文正文中时，前后仍应保留舒适的阅读间隔。")
}

private fun rustBulletList() = listOf(
    CjkBlock.List(
        items = listOf(
            AnnotatedString("调整窗口宽度，比较宽栏与窄栏中的断行、缩进和标点位置；"),
            AnnotatedString("改变系统缩放比例，检查正文、注文、线条与留白是否同步变化；"),
            AnnotatedString("对照标题、列表、引文和校样标记，确认不同层级仍保持清楚的视觉秩序。"),
        ),
        marker = ListMarker.Bullet(),
    ),
)

private fun rustClosing() = buildAnnotatedString {
    append("好的中文排版不会抢在文字之前引人注意，却能让阅读更加")
    withStyle(SpanStyle(fontSize = 1.3.em, fontWeight = FontWeight.Bold, color = GREEN)) {
        append("连贯、安静而从容")
    }
    append("。字形、标点、注文和段落彼此协调，长篇正文才能在不同版面中保持稳定的节奏。")
}

private fun rustAppendixTitle(text: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 1.3.em, fontWeight = FontWeight.Bold)) { append(text) }
}

private fun rustNarrowHyphenation() = buildAnnotatedString {
    append("术语 ")
    withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) { append("internationalization") }
    append(" 可按音节转行。")
}

private fun rustBackground(text: String, needle: String) = CjkInlineBackground(
    range = rangeOf(text, needle),
    color = YELLOW,
    horizontalPadding = 2.dp,
    verticalPadding = 1.dp,
    cornerRadius = 3.dp,
    adjacentSameStyleClearance = 0.dp,
)

private fun rangeOf(text: String, needle: String): androidx.compose.ui.text.TextRange {
    val start = text.indexOf(needle)
    require(start >= 0) { "Rust demo sample is missing $needle" }
    return androidx.compose.ui.text.TextRange(start, start + needle.length)
}

private const val RUST_PUNCTUATION = "编辑在批注中写道：“排版并非把文字摆下去，而是让每一行都获得清楚、安稳而从容的秩序。”括号（包括圆括号、方括号和书名号）应与正文相接，逗号、句号、问号和感叹号都在恰当的位置。遇到“真的如此吗？！”一类连续标点时，字面仍须紧凑，不宜留下突兀的空白。"
private const val RUST_LIST_INTRO = "校阅正文时，可依次观察以下项目："
private const val RUST_RICH_TEXT = "校样状态分为：已核、待校、旁注与撤销。已核内容可以绿色标示；待校内容使用蓝色；旁注使用红色。新增词句加实线下划线，存疑内容加虚线下划线，补充说明加点线下划线，已经撤销的文字则保留删除线，以便追溯修改过程。"
private const val RUST_FILE_NOTE = "本次校样依据 editorial-notes.md 整理，参考版本为 Review 3。文件名采用等宽字体，版本名称加浅色背景；两者夹在中文正文中时，前后仍应保留舒适的阅读间隔。"
private const val RUST_BULLET_INTRO = "本页适合在以下情形中检查："
private const val RUST_SIGNATURE = "——《提椠中文正文排版样张》"
private const val RUST_NARROW_PROOF = "校样排印，宜留呼吸。"
private const val RUST_EMOJI_APPENDIX = "本附录列出可用于核对的组合字形：👩🏽‍💻、👨‍👩‍👧‍👦、🇨🇳、1️⃣ 与 ✈️。每一项都应作为完整字形参与排版，在换行、选择与绘制时保持一致。"
private const val RUST_LIGATURE_WORDS = "EB Garamond: office affinity waffle"
private const val RUST_LIGATURE_SYMBOLS = "FiraCode: -> <= := != === //"
private const val RUST_JAPANESE = "このマークアップ構文は JSX と呼ばれます。React が普及させた JavaScript の構文拡張です。JSX マークアップは関連するレンダリングロジックのすぐそばに配置できるので、React コンポーネントは簡単に作成、保守、削除ができます。"
private const val RUST_KOREAN = "이 마크업 구문을 JSX라 부릅니다. 이것은 React에 의해서 대중화된 자바스크립트 구문의 확장입니다. JSX 마크업을 관련된 렌더링 로직과 가까이 두면, React 컴포넌트를 쉽게 만들고 관리하고 삭제할 수 있습니다."
private const val RUST_ENGLISH = "Cras maximus rutrum magna in gravida. Suspendisse et varius lectus. Ut ac metus id est vehicula euismod ac a sapien. Curabitur pulvinar ornare neque. Proin mattis magna vel massa eleifend cursus. Donec elementum sollicitudin venenatis. Aenean imperdiet consectetur diam, nec mollis leo. "
private const val RUST_SPANISH = "Esta sintaxis de marcado se llama JSX. Es una extensión de la sintaxis de JavaScript popularizada por React. Al poner marcado JSX cerca de la lógica de renderizado relacionada hace que los componentes de React sean fáciles de crear, mantener y eliminar."
private const val RUST_RUSSIAN = "Этот синтаксис разметки называется JSX. Это расширение синтаксиса JavaScript, которое стало популярным благодаря React. Размещение разметки JSX рядом с соответствующей логикой рендеринга упрощает создание, сопровождение и удаление компонентов React."
