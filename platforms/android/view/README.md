# 提椠 Android View 前端接入指南

`tiqian-android-view` 给使用 View 体系的 Android 应用提供两个控件：`CjkTextView`
显示一段由提椠排版的中文正文，`CjkTextSurface` 把多段正文组成一页，负责跨段选择、
复制和注音、悬挂标点这类画出段落边界的墨迹。支持 Android 6.0（API 23）及以上。

```kotlin
implementation("org.tiqian:tiqian-android-view:<version>")
```

Compose 应用请看仓库 README 的 Compose 一节，两个前端排版结果相同。

## 显示一段正文

`CjkTextView` 是一个原生 `ViewGroup`，测量时调用提椠排版，绘制时重放结果。它不使用
`TextView` 或 `StaticLayout`。

```kotlin
val paragraph = CjkTextView(context).apply {
    content = CjkTextContent(
        text = "编号 A-17 的青铜盉仍一并保留。",
        textStyle = TextStyle(fontSize = textSizePx),
        paragraphStyle = ParagraphStyle(lineHeight = lineHeightPx),
    )
}
```

字号与行高都以像素给出，行高为基线到基线的距离。sp 到像素的换算由调用方完成。
段首默认按中文正文习惯缩进，标题或独立的一段用 `firstLineIndent = 0.ic` 关掉。

XML 布局里可以直接声明：

```xml
<org.tiqian.android.view.CjkTextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/body"
    android:textSize="17sp"
    android:lineHeight="27sp"
    android:textColor="?android:textColorPrimary"
    android:textIsSelectable="true"
    app:cjkProfile="mainland"
    app:cjkOverflow="visible" />
```

支持的属性：

| 属性 | 说明 |
|---|---|
| `android:text` | 正文 |
| `android:textSize` | 字号 |
| `android:lineHeight` | 行高 |
| `android:textColor` | 正文颜色，接受 color state list |
| `android:maxLines` / `android:minLines` | 最多、最少行数 |
| `android:textIsSelectable` | 是否可选择，默认开 |
| `app:cjkProfile` | `mainland`、`taiwan`、`hongKong`，选择标点与断行规则 |
| `app:cjkOverflow` | `clip` 或 `visible`，见下文出界墨迹 |

代码里对应 `maxLines`、`minLines`、`textIsSelectable`、`clreqProfile`、`overflow`
与 `textColors`。

## 富文本

已有的 `Spanned` 可以直接提交，常用 span 会进入排版：

```kotlin
val source = SpannableStringBuilder()
    .append("中文正文的断行与标点都由提椠处理。")
    .append("粗体", StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    .append("、删除线和链接都会参与断行与绘制。")

paragraph.content = CjkTextContent(
    text = source,
    textStyle = TextStyle(fontSize = textSizePx),
)
```

会被保留的 span：`StyleSpan`、`ForegroundColorSpan`、`BackgroundColorSpan`、
`UnderlineSpan`、`StrikethroughSpan`、`RelativeSizeSpan`、以像素为单位的
`AbsoluteSizeSpan` 与 `URLSpan`。其他 span 不会报错，只是按基础样式显示。
提交前可以用 `text.cjkSpannedCompatibility()` 查看哪些语义现在保不住：

| issue | 含义 |
|---|---|
| `ParagraphSpans` | `LeadingMarginSpan` 这类作用于整段的 span，缩进请改用 `ParagraphStyle` |
| `ClickableSpanCallbacks` | 自定义 `ClickableSpan` 的回调，链接请改用 `URLSpan` 加 `onLinkClickListener` |
| `ReplacementSpans` | 图片等替换 span，请改用行内 View |
| `AbsoluteSizeInDip` | 以 dp 为单位的 `AbsoluteSizeSpan` |
| `TypefaceFamilies` | `TypefaceSpan` |
| `BaselineShift` | 上下标 |
| `UnknownSpans` | 前端不认识的 span |

这份报告只列出不能保真的 span，不会让控件退回系统排版。

### 中文正文特有的标注

行间注、着重号、专名号这些 `Spanned` 里没有的东西，用 `CjkTextContent` 的完整构造按
字符区间提交：

```kotlin
val text = "编号 A-17 的青铜盉仍一并保留。"
paragraph.content = CjkTextContent(
    content = TiqianTextContent(text),
    textStyle = TextStyle(fontSize = textSizePx),
    rubySpans = listOf(RubySpan(TextRange(11, 12), "hé")),
    decorations = listOf(DecorationSpan(TextRange(13, 17), DecorationKind.Emphasis)),
)
```

可用的标注：

- `rubySpans`：拼音（`RubyKind.Pinyin`）或注音（`RubyKind.Bopomofo`）。
- `decorations`：着重号 `Emphasis`、示亡号 `Mourning`、专名号 `ProperNoun`、
  书名线 `BookTitle`。
- `richTextSpans`：下划线、删除线、背景、行内代码与链接（`RichTextRole.Link`）。
- `colorSpans`：区间颜色。
- `content.spans`：区间字号、字重、斜体与字体族。

区间都是源文本的 UTF-16 偏移。复制、搜索和无障碍朗读得到的始终是源文本，选中整个
带注的词复制时，注文会以括号跟在正文后面。

### 链接

`URLSpan` 与 `RichTextRole.Link` 点击后默认发 `ACTION_VIEW`。要自己处理时设置
`onLinkClickListener`，返回 `true` 表示已消费。TalkBack 会把链接当作可点击元素朗读。

## 列表与段落节奏

列表项用凸排：块缩进加等量负的段首缩进，续行悬挂在编号之后。

```kotlin
val item = ParagraphStyle(blockIndent = 2.ic, firstLineIndent = (-2).ic)
paragraph.content = CjkTextContent(
    text = "一、标点不许在行首撒野：逗号句号一律避头尾。",
    textStyle = TextStyle(fontSize = textSizePx),
    paragraphStyle = item,
)
```

`ic` 是一个汉字的字身宽，随字号变化。段落之间不加间距时，跨段的行距与段内一致；
节与节之间给一个空行的高度，即字号的一点五倍。页边距用父容器或控件自身的 padding。
不要为了让注音露出来而加间距，见下文。

## 多段页面与选择

单个 `CjkTextView` 自带长按选择、拖动手柄、双击选词、系统浮动菜单（复制、分享、全选
与 `PROCESS_TEXT`）、API 28 及以上的放大镜，以及 Ctrl+C、Ctrl+A。`textIsSelectable = false` 关闭。

多段正文把 `CjkTextSurface` 放在页面根部，里面的 `CjkTextView` 会自动登记：

```kotlin
val page = CjkTextSurface(context).apply {
    addView(ScrollView(context).apply { addView(column) })
}
setContentView(page)
```

之后可以从一段拖到另一段，全选与复制覆盖整页。`selectedText`、`selectAll()`、
`copySelection()`、`clearSelection()` 与 `customSelectionActionModeCallback` 都在
`CjkTextSurface` 上，后者和 `TextView` 同名属性用法一致。

### RecyclerView

列表会回收屏外的段落，所以需要告诉页面文档的逻辑顺序，让选区不随回收丢失：

```kotlin
val page = CjkTextSurface(context).apply {
    document = CjkSelectionDocument(
        paragraphs.map { CjkSelectionDocumentFragment(key = it.id, content = it.content) },
    )
    selectionScrollHost = object : CjkSelectionScrollHost {
        override fun scrollBy(deltaPx: Float): Float {
            val before = recycler.computeVerticalScrollOffset()
            recycler.scrollBy(0, deltaPx.toInt())
            return (recycler.computeVerticalScrollOffset() - before).toFloat()
        }

        override fun viewportBoundsOnScreen(outBounds: Rect): Boolean =
            recycler.getGlobalVisibleRect(outBounds)
    }
    selectionRetentionHost = CjkSelectionRetentionHost { key -> adapter.retain(key) }
    addView(recycler)
}
```

Adapter 里绑定与解绑：

```kotlin
override fun onBindViewHolder(holder: Holder, position: Int) {
    val paragraph = paragraphs[position]
    holder.textView.bindSelectionFragment(
        key = paragraph.id,
        content = paragraph.content,
        retentionKey = paragraph.id,
    )
}

override fun onViewRecycled(holder: Holder) {
    holder.textView.unbindSelectionFragment()
}
```

三个接入点的职责：

- `document`：稳定 key、源文本与行间注的列表，决定阅读顺序和复制结果。
- `selectionScrollHost`：手柄拖到边缘时替页面滚动，返回实际滚动的距离。
  普通 `ScrollView` 可以省略，页面会自己找最近的可滚动祖先。
- `selectionRetentionHost`：拖动手柄期间让端点所在的 item 暂时不被回收，
  松手后释放。不提供也能用，只是端点离屏时手柄暂时隐藏。

完整实现见 `demo/android` 的 `TiqianViewDemoActivity`。

## 注音与悬挂标点的出界墨迹

注音画在第一行上方，行尾标点可以悬挂出版心，它们会越过段落自身的边界。
`overflow = CjkTextOverflow.Visible` 允许画出去，`Clip` 裁掉。

段落放在 `CjkTextSurface` 里时，滚动容器保持默认的 `clipChildren` 即可，出界墨迹照常
显示，滚动视口之外仍然裁掉。不需要给段落加 padding 或 margin，也不需要改行高。
没有 `CjkTextSurface` 时，能不能画出去由父容器的 `clipChildren` 决定。

## 长文预排

长文列表可以共享字体测量，并在后台线程先排好每一段：

```kotlin
val session = AndroidParagraphMeasurementSession()

// 后台线程；一个 measurer 只在一个线程使用
val measurer = AndroidParagraphMeasurer(session = session)
val prepared = paragraphs.map { measurer.precompute(it.content.layoutInput(maxWidth = widthPx)) }

// 主线程
holder.textView.setMeasurementSession(session)
holder.textView.submitPrecomputedLayout(prepared[position])
```

`maxWidth` 必须等于控件最终的内容宽度，即控件宽度减去左右 padding。内容、`maxLines`
或 profile 与控件当前值不一致时 `submitPrecomputedLayout` 返回 `false`；宽度在测量时核对，
不一致就退回前台测量，不会显示旧几何。measurer 的 profile 要与控件的 `clreqProfile` 一致。

## 行内 View

图片、公式这类行内对象由调用方给出宽度与上下高度，提椠按这些数值排版，再把子 View
放到最终位置：

```kotlin
paragraph.inlineViewAdapter = object : CjkInlineViewAdapter {
    override fun createView(parent: ViewGroup, content: CjkTextContent, span: InlineObjectSpan): View =
        ImageView(parent.context)
}
paragraph.content = CjkTextContent(
    content = TiqianTextContent("正文￼继续"),
    textStyle = TextStyle(fontSize = textSizePx),
    inlineObjects = listOf(
        InlineObjectSpan(range = TextRange(2, 3), advance = 48f, ascent = 40f, descent = 8f),
    ),
)
```

子 View 自己的测量结果不会改变段落几何。不要直接对 `CjkTextView` 调用 `addView`。

## 无障碍

TalkBack 朗读源文本，选区与复制作为动作暴露，链接可以单独聚焦，API 26 及以上提供
逐字符屏幕位置。不需要额外配置。

## 现在不支持

- 编辑与输入法。需要输入时用 `EditText` 收文字，把结果提交给 `CjkTextView`，
  `demo/android` 的 `TiqianViewShowcaseActivity` 有这个用法。
- 分页。页面只处理滚动视口。
- 竖排。
- 溢出省略号。`overflow` 只有裁切与可见两种。
- 上面 `cjkSpannedCompatibility()` 列出的 span。

## 示例

`demo/android` 里有两个 View 界面：

- `TiqianViewDemoActivity`：RecyclerView 长文，后台预排、跨段选择与滚动。
- `TiqianViewShowcaseActivity`：富文本样张，含拼音、注音、着重号、专名号、书名线、
  凸排列表与一个实时重排的输入框。
