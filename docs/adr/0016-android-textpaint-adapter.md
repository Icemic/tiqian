# ADR 0016: Android API 23 native 字体后端与平台 shaping oracle

- Status: Accepted
- Date: 2026-06-10
- Amendment 2026-06-25：真实应用 dogfood 暴露出 Android renderer 仍会在
  draw 阶段重新让平台理解文本，可能与 measure 阶段得到的 CJK glyph 形态
  不完全一致。renderer 不得根据 `PunctuationDecisionInfo` 做 dash 专用缩放；
  后续 slice 已让 Android backend 形状与绘制同源（shape once, draw the
  positioned glyphs），而不是在前端做标点几何修正。
- Amendment 2026-08-05：API 31 的 `TextRunShaper` / `Canvas.drawGlyphs` 不能继续作为
  Android 正确性的最低边界。Compose artifact、native backend 与验证 app 的 minSdk 改为 23；
  新增 HarfBuzz + FreeType 同源后端，API 31 adapter 只保留为经过几何对照的可选 oracle / 优化。

## 2026-08-05 决策修订：API 23 native correctness backend

Android API 23+ 的默认正确性路径改为 `shaping/native-font`：

- `shaping/api` 定义平台无关的 `FontFaceId`、`ReplayableFontCatalog`、face request / descriptor
  与结构化 `FontBackendCapabilityReport`。`FontFaceId` 由字体字节 SHA-256、TTC index 与可变字体轴实例
  稳定导出，
  已产生 `LayoutResult` 的 face 在进程内继续保留，catalog 更新不会让旧布局失去重放资源。
- 宿主通过 `AndroidFontSource.bytes/file/asset` 与 `AndroidFontFaceSpec` 明确声明 family alias、
  字重、斜体和 fallback role。HarfBuzz 从这份字节产生 cluster、glyph id、advance、placement、
  `locl` 与调用方要求的 feature；FreeType 从同一 face 取得 raw metrics、ink bounds 和 outline。
  renderer 只按 `LayoutResult` 的 glyph id / origin 重放 outline，不重新断行、重新 shaping 或猜偏移。
- API 29+ 可以通过公开 `SystemFonts` 构造默认 catalog；API 23–28 没有公开枚举能力，生产宿主应
  在第一次 `CjkText` 前安装受控 catalog。内建 AOSP 路径仅是具名设备兼容来源，并报告
  `HostFontCatalogRecommendedBelowApi29`；路径或 role 缺失继续以结构化 issue / 明确异常暴露，
  不调用 Android 私有 Minikin API，也不静默用另一字体测量或绘制。
- `SystemFonts` 会把同一个可变字体文件和 TTC face 暴露成多组轴坐标。catalog 必须按
  `file / source + TTC index + variation axes` 区分实例，并把坐标交给 FreeType 后再建立
  HarfBuzz face；不能按文件合并，也不能只把 Android 报告的 400 / 700 写进 descriptor 而让
  native face 停在字体默认轴值。有些系统家族（例如 Pixel 上的 Roboto）只枚举默认实例，再用
  Android family alias 请求其余字重；默认 catalog 需要用公开 `Font.Builder` 从该 variable face
  建立 400 / 700 实例。`SystemFonts.getAvailableFonts()` 是丢失 named-family 归属的无序集合；同一
  Roboto 文件可能同时以 `wdth=100` 的 `sans-serif` 和 `wdth=75` 的
  `sans-serif-condensed` 出现。generic sans 的选择必须确定性地优先正常宽度，派生字重时只覆盖
  `wght` 并保留基准实例其余全部轴坐标。宿主安装可变字体时同样通过
  `AndroidFontFaceSpec.variationAxes` 声明具体实例。
- outline replay 将“有效但无 contour”（例如空格）视为成功的无墨迹 glyph；已注册 face 的 glyph
  若不是可重放 outline，则以 `NativeGlyphReplayUnavailable` 明确失败。它不能落到
  `drawTextRun` / `getTextPath`，因为这会破坏测量与绘制同源。
- `frontend/compose` 对外仍只有同一套 `CjkText` / `CjkSelectionContainer`。capability report
  是宿主修复字体输入的诊断，不是 renderer 路由；API 23–30 不切回 Compose `BasicText` 或旧正文
  renderer。标点 glue、避头尾、断行、justification、rich text、selection、链接命中与语义仍只消费
  `LayoutResult`。
- API 31 `AndroidPaintTextShaper` / `Canvas.drawGlyphs` 保留为同字体几何对照和潜在优化路径，
  不是正确性依赖。其 API 31 类型用版本边界隔离，库本身 minSdk 为 23。

native bridge 目前留在提椠内，但公共边界只依赖 replayable font catalog / face contract；与
`math-compose` 共用的字体加载、shaping、outline 与 cache 待双方桥接稳定后下沉，不复制数学布局规则。

### API 23+ 验证证据

同一构建在 Android emulator 上通过 native instrumentation：API 23、27、30 各 4/4；API 31 与
当前 API 37 各通过 native 4/4 和 platform adapter 16/16。API 31+ 对照测试从同一字体字节比较
glyph id、advance、ink 数量、layout line range 与 visual width。API 23 / 27 / 30 / 31 共享 Demo
均能启动并生成正文截图，覆盖中西混排、破折号/省略号、标点压缩与双齐、装饰线、ruby / 注音、
富文本和链接/选择入口。

5040 字、169 行的 debug emulator 探针记录为 API 27 约 15.96 s、API 30 约 14.70 s、API 31
约 0.54 s；这些数字只证明长文路径完成，不能当作 release 真机性能门槛。native face、coverage、
shape、metrics 与 outline 均使用有界 cache；正式性能结论仍需 release 包和代表性真机复测。

## Context

Slice 6 的平台收尾：AWT（ADR 0013）与 Skiko（ADR 0015）已交叉验证了标点几何，
还差 Android 平台真值。Android 的文本栈（Minikin/HarfBuzz）不暴露 script 控制，
且 typeface 永远带不可关闭的内部 fallback 链——contract 上「单一字体测量」在
Android 只能近似为「该 locale 下的平台文本栈测量」。

## Decision

历史上的第一阶段新增 `shaping/android-adapter`（当时 minSdk 31）：

- `AndroidPaintTextShaper`：advance 来自 `Paint.getRunAdvance`，per-glyph
  id/位置/Font 来自 `TextRunShaper.shapeTextRun`（API 31+），ink bounds 来自
  `Paint.getTextBounds`（仅单 glyph cluster）。`Glyph.x/y` 保留 glyph origin，
  `Glyph.renderFontKey` 通过有界的 Android 专用 registry 指回 shaping 阶段的
  `Font`；旧 key 淘汰后 renderer 回到同一上下文字符串绘制路径，不让进程级
  registry 无限持有平台字体对象。
- `LocaleTaggedShaping`：`Paint.textLocale` 取 `TextStyle.locale`。
- `FontHaltMeasurement`：第二次测量用 `fontFeatureSettings = "'halt' on"`，
  产出 `Glyph.haltAdvance` / `haltPlacementX`，feature 不进渲染几何。
- `SystemAndroidFontProbe`：CJK role 显式解析 CJK typeface
  （`NotoSansCJK-Regular.ttc` 取 **ttcIndex 2** = SC face，与 AOSP fonts.xml
  对 zh-Hans 的映射一致）。不显式指定时 Roboto 在 fallback 链首位，会接管
  `—` `…` 等共用码点。

### HanContextShaping（关键决策）

孤立的 `—` 是 script-COMMON 码点，HarfBuzz 对单字符 buffer 解析为 OpenType
DFLT script，而 Noto Sans CJK 的 `locl` 规则注册在 hani/latn/cyrl/… 下
**唯独不含 DFLT**——上下文无关的逐 cluster shaping 会静默拿到西文形破折号
（0.89em）。桌面 adapter 用 `TrivialScriptRunIterator` 强制 `Hani` 解决；
Android 没有公开的 script 控制，且 `getRunAdvance`/`shapeTextRun` 的
context 参数不参与 HB 的 script 推断（buffer 只含 run 本身）。

因此 CJK role 的 cluster 统一放进 `中<cluster>中` buffer 整体 shaping，再按
offset 切回该 cluster 的 glyph 与 advance（pen 原点用 `getRunAdvance` 差分，
不用 glyph x——`halt` 的 placement 位移正是要单独上报的量）。这正是真实
Android 段落里 Minikin 给这些字符的环境，不是 hack。glyph↔字符无法 1:1
对应时（连字）回退到上下文无关 shaping。

### 平台限制（已记录，golden 需容忍）

- `Paint.getTextBounds` 无 context 参数：`locl` 替换后的破折号 ink bounds
  量到的是替换前的 glyph，仅诊断用途受影响。
- typeface fallback 链不可关闭：缺字时由系统兜底而非报 .notdef，
  `missingGlyphs` 改用 `Paint.hasGlyph` 上报。
- 多 glyph cluster 不报 per-glyph ink bounds（走 `MissingInkBoundsFallback`）。

### 设备实测（emulator API 37, Noto Sans CJK 2.004）

instrumentation 测试（`connectedAndroidTest`）复现桌面双引擎的全部不变量：
全宽标点 1em；`halt` body 0.5em 且 placement 方向正确（`。`→0、`（`→-8）；
`locl` 破折号整 em；ink 落在 profile glue 侧的对侧；缺字上报。

真实应用 dogfood 暴露出一类 renderer 层错位：Android measure 与 draw
路径虽然都试图提供 Han context，但 draw 阶段仍以 `Canvas.drawTextRun`
重新 shaping 文本，而不是重放 measure 阶段已经得到的 glyph id/position。
这类问题已经收敛到 Android backend 的同源 shaping/drawing 能力：API 31+
renderer 优先用 `Canvas.drawGlyphs` 按 `LayoutResult.glyphRuns` 里的 glyph
id、origin、Font 绘制；只有缺少平台 Font key 时，才退回同一 renderer 内的
字符串绘制 containment（例如 registry 中过旧的 Font key 已被淘汰）。
`AndroidLayoutRenderer` 不再按标点类型做几何变形。

## Consequences

- 标点几何的「三平台互证」完成：AWT、Skia、Android 在同一字体家族上给出
  一致的 body/glue/方向结论。
- Android 的测量与主文本绘制在 API 31+ 上同源：layout 消费的 glyph id /
  origin / Font 被 renderer 直接重放，避免 draw 阶段再次让平台重新理解文本。
- Android 真机/模拟器是唯一需要外部环境的测试路径，不进默认 `build`；
  按需跑 `:shaping:android-adapter:connectedAndroidTest`。
- `local.properties`（sdk.dir）为本机配置，不入库。
- 当前 Android Compose artifact 的最低版本为 API 23；API 23+ 的正确性由 native backend 提供，
  上述 API 31 platform 路径只作为 oracle / 可选优化理解。

## Alternatives considered

- **接受西文形破折号作为平台差异。** 否决：真实 Android 渲染在 Han 段落里
  就是中文形，上下文无关测量的西文形是测量方法的伪差异，不是平台真值。
- **Typeface.CustomFallbackBuilder 构造带语言属性的字体链。** 否决：构造出的
  字体仍不携带 fonts.xml 的 lang 属性，无法影响 HB 的 script/language 解析。
