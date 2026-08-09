# ADR 0046: Core Text shaping 与度量适配器

- Status: Accepted
- Date: 2026-08-08
- Refines: [ADR 0008](0008-shaping-adapter-contract.md)（Shaping adapter contract）；与 [0013](0013-jvm-awt-shaping-adapter.md) / [0015](0015-skiko-shaping-adapter-cross-check.md) / [0016](0016-android-textpaint-adapter.md) 并列

## Context

[ADR 0045](0045-apple-kotlin-native-target.md) 让组合核心跑上 Apple,但引擎要产出真实 `LayoutResult`,还需要平台的 `TextShaper`(整形)与 `FontMetricsResolver`(字体度量),即 ADR 0008 的适配器契约。JVM 有 AWT([0013](0013-jvm-awt-shaping-adapter.md))与 Skia([0015](0015-skiko-shaping-adapter-cross-check.md)),Android 有 TextPaint/native([0016](0016-android-textpaint-adapter.md));Apple 缺一个对等适配器。

## Decision

`CoreTextShapingAdapter`:新增 `:shaping:coretext`(`macosArm64`),用 Kotlin/Native **内置的 `platform.CoreText` / `platform.CoreFoundation` 绑定**——无需自定义 cinterop def。

- **`CoreTextShaper`**:以 `CFAttributedString` + `CTLineCreateWithAttributedString` 整形,遍历 `CTRun` 用 `CTRunGetGlyphs` / `CTRunGetPositions` 抽取字形,产出**一个 cluster + 一个 glyph run**(镜像 AWT/Skia 适配器的契约:消费 layout 决定的 `displayText`,不做 fallback / CLREQ 替换 / 标点决策)。ink bounds 用 `CTFontGetBoundingRectsForGlyphs`,并把 CG(+y 向上,基线相对)转成核心约定(+y 向下、基线上方为负)。逐字形 advance 由相邻 position 差分,末字形取 `CTLineGetTypographicBounds` 的总宽——与 Skia 适配器一致。
- **`CoreTextFontMetricsResolver`**:`CTFontGetAscent/Descent/Leading`(hhea 盒)+ 读 `OS/2` `sTypoAscender/Descender`(`CTFontCopyTable`)得到 CJK **字身框**,对标 `SkiaFontMetricsResolver` 与 [ADR 0002](0002-script-aware-font-metrics.md) amendment。
- **测绘同源**(contributing.md 约束 / [ADR 0016](0016-android-textpaint-adapter.md) 北极星):整形与绘制使用**同一个 `CTFont`**,前端按同一字形 id 重放。
- 新增 `ShapingSource.CoreText`。默认 face:`PingFang SC`(CJK)/ `Helvetica Neue`(Latin)。

## Consequences

- Apple 上有真实的 shaping 与字体度量;引擎的 CLREQ 规则(避头尾、标点挤压、两端对齐、注音)以**真实 Core Text 测量**驱动、端到端生效。
- 代价(诚实记录):`halt`(标点半角实测,ADR 0014 follow-up)与 OpenType 特性**施加**暂缓——特性透传给 `GlyphRun.openTypeFeatures` 供前端,但未在 shape 时施加(如 `locl` 的 CJK 破折号变体);第一版未做逐 cluster 的多 face 处理(单 face 段,符合常见 CJK/Latin 正文)。
- 代价(诚实记录,`LocaleTaggedShaping` 未应用):本适配器未给 `CFAttributedString` 打 `kCTLanguageAttributeName`,而 Skia/Android 适配器按 [ADR 0015](0015-skiko-shaping-adapter-cross-check.md) 会打(locale 影响 `locl` 变体)。实测 zh-Hans/zh-TW 与 PingFang SC/TC 都**不**改变注音字形;但破折号 `—`/省略号/引号等的 CJK `locl` 变体形式**尚未验证**——若 PingFang 对这些字形 locale 敏感,会复现 ADR 0015 修过的缺陷。列为已知待办(加 `kCTLanguageAttributeName`,或补测证明无需)。

## Alternatives considered

- **在 Native 上接 HarfBuzz + FreeType。** 否决:Core Text 是 Apple 的平台原生文本栈,符合 [ADR 0016](0016-android-textpaint-adapter.md)「尽量复用平台已有能力」的北极星,且免去第三方依赖与字体加载重实现。
- **自定义 cinterop def(手写头文件绑定)。** 不必要:Kotlin/Native 内置的 `platform.CoreText` 绑定已覆盖所需 API。

## Verification

- `:shaping:coretext:macosArm64Test`(6 项,均在真实系统字体上运行):中/西文 shaping 产出正字形与正 advance;**逐字形 advance 求和 = cluster advance**;句号 `。` 带 ink bounds;CJK 的 `OS/2` sTypo 字身框读取正确且为正;拉丁度量为正。
- 关于 **golden**:`LayoutDumpGoldenTest` **有意**使用确定性 stub shaper(平台字体会让 golden 机器相关),因此平台适配器**不建机器精确 golden**——这与 [0013](0013-jvm-awt-shaping-adapter.md) / [0015](0015-skiko-shaping-adapter-cross-check.md) 一致(0015 亦记录了 AWT↔Skia 的合理分叉,用结构断言 + 交叉一致而非逐值 golden)。上游 stub golden 套件仍全绿,证明引擎未被本适配器改动。
