# ADR 0058：Android View 一级前端与共享原生 renderer

- Status: Accepted
- Date: 2026-08-31

## Context

仓库过去的 `platforms/android/view` 只有一个接口占位，Android 原生绘制实现则位于 Compose
source set。应用若想在传统 View 阅读器中使用提椠，只能依赖 Compose，或在宿主侧复制测量、Canvas
重放、选择与链接逻辑。前者把 UI framework 变成无关宿主的强依赖，后者会形成第二份 renderer，最终让
测量/绘制、缓存与修复规则分叉。

这个前端必须是提椠自己的公共 Android 能力，不能知道 Legado、某个阅读器分页器或特定业务模型。
同时它不能以“能画一段字”为验收：原生静态正文还需要正确的 View 测量/失效、回收生命周期、选择、
系统菜单、链接、无障碍和真实性能路径。

## Decision

1. 新增 `platforms/android/rendering` / `tiqian-android-rendering`。Android paragraph measurer、
   Canvas renderer、paint/draw-plan cache 与 tracing 放在这里；模块只依赖 engine 和 Android shaping，
   不依赖 Compose 或 View。
2. `LayoutResultReplayIndex`、rich-text layout projection、fitted line-pattern geometry 与合法 paint
   overhang 属于 engine。它们只从 `LayoutResult` 推导 lookup/paint 数据，不作新排版决定。
3. Compose Android 改为薄适配器，把 `ContentDrawScope` 的 native Canvas 交给同一个
   `AndroidParagraphRenderer`；`platforms/android/view` 也直接消费该 renderer。两个前端不得复制
   glyph、ruby、注音、装饰或 rich-text 画法。
4. `CjkTextView` 是单段、只读、API 23+ 的原生 `ViewGroup`。`onMeasure` 构造完整
   `LayoutInput` 并调用 engine，`onDraw` 只重放结果；禁止引入 `TextView` / `StaticLayout` 作为隐藏
   layout truth。source、layout style、render spans 通过不可变 `CjkTextContent` 原子提交。
   平台 `Spanned` 的常用 span 词汇由前端自带的 lowering 进入该契约，与 Compose 前端 lower
   `AnnotatedString` 同构；`cjkSpannedCompatibility` 报告未保真的 span 语义。
5. 失效按职责分层：layout contract、宽度、profile 或 maxLines 变化清除 layout 并
   `requestLayout()`；纯颜色与 render-only paint 变化保留 `LayoutResult` identity，只重建必要 replay
   geometry 并 `invalidate()`。clip bounds、replay lookup、draw plan 与 selection box 都在其输入变化时
   缓存，`onDraw` 不扫描全文或临时创建布局对象。
6. 选择与交互只把 Android 手势翻译为 engine source geometry：长按/双击、拖柄、交叉端点、copy、
   share、`PROCESS_TEXT`、硬键盘 Ctrl+C / Ctrl+A、浮动 `ActionMode.Callback2` 与 API 28+
   `Magnifier` 使用 engine 的 UTF-16 boundary、word range、caret 和 occupied box。链接命中同样使用 replay segment；宿主可先消费，
   否则走 `ACTION_VIEW`。
7. 无障碍 host node 暴露 source text、selection 与 copy action。链接使用 ClickableSpan 并由
   AndroidX delegate 路由；API 26+ 回答逐字符屏幕矩形，API 36+ 同时回答 window 坐标矩形。
   不伪造另一份 platform text layout。
8. inline object 的 `advance/ascent/descent` 仍由宿主在 layout 前明确提交。
   `CjkInlineViewAdapter` 只创建/绑定/回收 child View，前端把它放在 engine 的最终 draw origin；
   child 的事后测量不得反向改变段落几何。
9. `AndroidParagraphMeasurementSession` 跨文档 surface 共享 width-independent shaping / metrics；
   measurer 本身仍按线程约束使用。后台预排结果封装为 `AndroidPrecomputedParagraph`，连同具体
   `ClreqProfile` provenance 一起传递；View 只接受 profile 与完整 `LayoutInput` exact match 的结果。
10. `demo/android` 提供 RecyclerView dogfood surface，复用一个 measurement session，并在拿到精确
    viewport 后于 lifecycle worker 预排整篇、由 holder 提交 exact-match result；steady-state scroll 的
    ready signal 只在文档缓存完成后发布。macrobenchmark 与 baseline-profile generator 同时覆盖 Compose
    与原生 View 路径。

## Consequences

- 传统 Android 阅读页可直接嵌入提椠，不必引入 Compose，也不需要应用专属桥接层。
- Compose 与 View 的原生绘制优化、API 23–30 run replay、API 31+ glyph replay 和修复会一起演进。
- 两个前端遵循同一条接入原则：宿主平台的富文本习惯由前端自带一次 lowering，保真缺口用能力
  报告说明。`CjkTextContent` 仍是底层 authoring contract；`Spanned` 的常用词汇（样式、
  颜色、下划线/删除线、字号、链接）lower 进该契约，回调式 `ClickableSpan`、paragraph span 与
  `ReplacementSpan` 等由 `cjkSpannedCompatibility` 报告。`CjkTextView` 不复刻 `TextView`
  的 API 面。
- 单段 View 不拥有文档分页、跨 RecyclerView item 选择、编辑或 IME。将来需要这些能力时，应新增
  文档逻辑坐标或编辑契约，而不是把业务分页器、DAO 或应用状态飞线进 renderer。

## Alternatives

- **在应用仓库写 Legado-specific View**：拒绝。它不能被其他 View 宿主复用，也会让提椠无法独立
  验证和发布这条前端。
- **用 `ComposeView` 包 `CjkText`**：可作为应用迁移过渡，但不是原生 View frontend；它仍要求
  Compose runtime，并不能给 View 生命周期、RecyclerView 回收与 XML 使用提供一级契约。
- **继承 `TextView` 并覆盖 draw**：拒绝。TextView 的隐藏 layout/accessibility geometry 会与
  `LayoutResult` 并存，容易把第二份断行真值重新引回系统文本栈。
- **只提供 Canvas helper**：拒绝。测量、失效、选择、菜单、无障碍和回收仍会散落到每个宿主。
