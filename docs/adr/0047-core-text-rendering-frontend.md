# ADR 0047: Core Text 渲染前端

- Status: Accepted
- Date: 2026-08-08
- Refines: 与 [0017](0017-compose-desktop-renderer.md) / [0035](0035-android-compose-gallery.md) / [0039](0039-web-rendering-path.md) 并列(前端);消费 [ADR 0046](0046-core-text-shaping-adapter.md)

## Context

[ADR 0046](0046-core-text-shaping-adapter.md) 让 Apple 能产出 `LayoutResult`,但还需要一个前端把它画出来——对标 Compose([0017](0017-compose-desktop-renderer.md)/[0035](0035-android-compose-gallery.md))与 Web([0039](0039-web-rendering-path.md))。按 contributing.md 与 [ADR 0001](0001-core-pipeline-and-platform-boundary.md) 的边界,前端只**呈现 `LayoutResult`**,不实现标点挤压 / 避头尾 / 两端对齐等排版规则。

## Decision

`CoreTextRenderingFrontend`:新增 `:frontend:coretext-render`(`macosArm64`)。

- **`CoreTextLayoutRenderer`**:用 `CTFontDrawGlyphs` 画 `LayoutResult`——按字形 id 重放,pen 按 `Cluster.advance` 步进,`clusters` 与 `glyphRuns` 平行对应(逐 cluster index 取对应 run),镜像 `SkiaLayoutRenderer`。坐标:`LayoutResult` 为左上原点 / y 向下,`CGContext` 为左下 / y 向上,故 `layoutY → canvasHeight - layoutY`,用默认 text matrix 让字形正立(无需翻转上下文)。
- **`AppleParagraphBackend`**:把引擎接到 `CoreTextShaper` + `CoreTextFontMetricsResolver` + `LookaheadLineBreaker`,其余 seam(clreq profile / justifier / hyphenator / role classifier / fallback)用引擎默认,对标 `DesktopParagraphBackend` 的最小构造。
- **命名 `coretext-render`(而非 `coretext`)**:与 `:shaping:coretext` 同 leaf 名会在共享 `group` 下产生同一 capability `org.tiqian:coretext`,触发 Gradle 重复能力解析,使依赖回指消费者自身、导致 `compileKotlinMacosArm64` **自环**。不同 leaf 名规避之。命名启发式:`PlatformFrontendDistinctCapability`。

## Consequences

- Apple 端到端管线打通:**文字 → 整形/度量 → 布局 → 分页 → 画到像素**。
- 渲染器绘制正文字形 + 拼音/注音行间注 + 着重号点 + 专名号/书名号/示亡号线(`drawRuby`/`drawBopomofo`/`drawEmphasisDots`/`drawDecorationSegments`);拼音与注音都按 run 自身字体重整形绘制,避免用新建字体回放回退字体的字形 id(否则错配成乱码);注音按角色用 `vert` 竖排字形 + 声调 ink 缩放到框。
- 代价(诚实记录):`draw` 假设 **y-up 上下文**(macOS AppKit 非翻转视图);iOS/UIKit 的翻转上下文需调用方先翻转坐标系,否则字形上下颠倒——iOS 目标已可编译,但渲染路径尚无消费者/测试。逐 cluster 字体解析走引擎 `debug.fontDecisions` + 共享 `usesLatinFace` 规则(与 shaping/度量同源,不再解析 `fontKey`);书名号甲式画波浪线(与 Compose 一致,不与专名号混同);面向应用的 **XCFramework 打包不在本前端**——那是应用集成(AthenaReader)的事,避免把打包依赖引入上游。

## Alternatives considered

- **WebKit 渲染 EPUB。** 否决:非原生手感,且无法直接重放 `LayoutResult` 的字形/位置决策(WebKit 会用自己的排版,丢弃引擎决策)。
- **Compose Multiplatform on macOS。** 否决:非原生手感,且已有 Compose 前端([0017](0017-compose-desktop-renderer.md))。

## Verification

`:frontend:coretext-render:macosArm64Test`:

- **端到端**:`appleParagraphEngine().layout(中文)` 产出含 clusters / glyphRuns / lines、正尺寸的 `LayoutResult`,窄宽度下正确换行,`shapingDecisions` 的 source 为 `CoreText`。
- **渲染**:`CoreTextLayoutRenderer` 画到离屏 `CGBitmapContext` 后,位图含真实字形墨迹(断言非白像素数)。
- **CLREQ 合规**(真实 Core Text 测量驱动):避头尾(无一行以行首禁则标点开头)、两端对齐(`justificationDecisions` 触发且非末行填满行宽)、中西文间距(`autoSpaceDecisions`)、标点挤压(标点带可压缩 glue)。
