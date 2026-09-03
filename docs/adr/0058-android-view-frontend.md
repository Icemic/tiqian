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
4. Android 公共入口沿用 Compose `CjkText` 与 Apple `CJKTextView` 的跨平台概念，命名为
   `CjkTextView`；`org.tiqian.android.view` 已经表达产品与平台归属，公共类型不再重复 `Tiqian`
   品牌前缀。它是单段、只读、API 23+ 的原生 `ViewGroup`。`onMeasure` 构造完整
   `LayoutInput` 并调用 engine，`onDraw` 只重放结果；禁止引入 `TextView` / `StaticLayout` 作为隐藏
   layout truth。source、layout style、render spans 通过不可变 `CjkTextContent` 原子提交。
   平台 `Spanned` 的常用 span 词汇由前端自带的 lowering 进入该契约，与 Compose 前端 lower
   `AnnotatedString` 同构；`cjkSpannedCompatibility` 报告未保真的 span 语义。
5. 失效按职责分层：layout contract、宽度、profile 或 maxLines 变化清除 layout 并
   `requestLayout()`；纯颜色与 render-only paint 变化保留 `LayoutResult` identity，只重建必要 replay
   geometry 并 `invalidate()`。clip bounds、replay lookup、draw plan 与 selection box 都在其输入变化时
   缓存，`onDraw` 不扫描全文或临时创建布局对象。
6. 选择与交互只把 Android 手势翻译为 engine source geometry：长按/双击、拖柄、交叉端点、
   硬键盘 Ctrl+C / Ctrl+A、浮动 `ActionMode.Callback2` 的选区锚点与 API 28+ `Magnifier` 使用
   engine 的 UTF-16 boundary、word range、caret 和 occupied box。Android 没有公开任意
   `View` 可复用的 `TextView.Editor` 菜单 provider，因此前端以独立的
   `CjkTextSelectionActionMode` 按 AOSP 顺序和生命周期提供只读 `TextView` 的 Copy、Share、
   Select all 与可用的 `PROCESS_TEXT`；`customSelectionActionModeCallback` 只作为与
   `TextView` 同名、同顺序的增删/拦截扩展点，空值仍保留默认菜单。系统未公开的
   `SelectionActionModeHelper` / Text Assist 会话不以近似实现冒充支持。链接命中同样使用 replay segment；宿主可先消费，
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
11. 多段选择由通用 `CjkTextSurface` 持有一份文档逻辑坐标，不把选择状态放进 attached
    paragraph View。虚拟化宿主用 `CjkSelectionDocumentFragment` 提交稳定 key、source text、ruby /
    注音 span、inline-object boundary 与片段分隔符；复制始终调用 engine 与 `LayoutResult.getTextForCopy`
    共用的 range projector，不能由 View 前端手抄注文投影；holder 必须通过 `bindSelectionFragment` 原子绑定 key 和
    `CjkTextContent`；前端在提交前校验 prospective key、source text 与 attached-key 唯一性，失败的
    rebind 或 document replacement 保留旧状态。未绑定 key 的子 View 不进入该文档，回收、解绑与
    重新 attach 只改变几何投影，不改变逻辑端点。`CjkSelectionScrollHost` 必须回报实际消费距离；
    `CjkSelectionRetentionHost` 返回的每个 handle 是独立 generation，并保证活跃端点在释放前不被
    回收或重绑。宿主不提供 retention 时，逻辑端点仍按 key 存活，离屏几何与 handle 隐藏。这些契约
    不包含 RecyclerView、分页器或应用类型。
12. Ruby、注音、着重号与悬挂标点越出段落 viewport 时，仍以 engine 的 legal paint bounds 为唯一
    权限和范围。`CjkTextSurface` 负责登记后代并定义重放边界；每个段落把重放 drawable 挂到 surface
    内（含 surface 自身）最近一个实际启用 `clipChildren` 的祖先 `ViewGroupOverlay`，由该祖先在普通 children 之后、同一 DisplayList 与
    滚动事务中提交共享 renderer，并扣除从段落到该祖先的普通 child pass 实际可见区域，防止非裁切
    中间容器已保留的越界墨迹被重复着色。不得在最外层容器另做一次
    `dispatchDraw` 补画；RecyclerView 等滚动容器可独立提交 child RenderNode，根层补画会产生一帧位置
    分离。首次需要时，`AndroidParagraphRenderer` 把越界 clip 内的命令录入 API 23+ 可硬件回放的
    `Picture`；同一 layout/paint/bounds 组合后续只提交原生 recording，不再次遍历完整 glyph、ruby 与
    decoration 计划。没有实际越界的段落不录制 Picture；嵌套 scroll、translation、scale、rotation、
    alpha 沿缓存的 View 层级映射。段落 item 边界可越过，surface 外层 viewport 仍是公开裁切边界。
    不得为规避裁切增加 padding/margin、扩大测量结果或改变 engine 行高与断行几何。

## Consequences

- 传统 Android 阅读页可直接嵌入提椠，不必引入 Compose，也不需要应用专属桥接层。
- Compose 与 View 的原生绘制优化、API 23–30 run replay、API 31+ glyph replay 和修复会一起演进。
- 两个前端遵循同一条接入原则：宿主平台的富文本习惯由前端自带一次 lowering，保真缺口用能力
  报告说明。`CjkTextContent` 仍是底层 authoring contract；`Spanned` 的常用词汇（样式、
  颜色、下划线/删除线、字号、链接）lower 进该契约，回调式 `ClickableSpan`、paragraph span 与
  `ReplacementSpan` 等由 `cjkSpannedCompatibility` 报告。`CjkTextView` 不复刻 `TextView`
  的 API 面。
- `CjkTextSurface` 已提供跨 attached item 的文档选择与虚拟化契约，但不拥有分页策略、数据
  加载、编辑或 IME。应用通过窄的滚动/保活能力接入自己的 viewport，不把分页器、DAO 或业务状态
  飞线进 renderer。
- 合法越界墨迹不再要求滚动宿主关闭 `clipChildren`；使用该能力的阅读表面必须以
  `CjkTextSurface` 登记段落，实际滚动 viewport 继续裁切离屏内容，overlay 只对 engine 已批准的
  paint bounds 生效。

## Alternatives

- **在应用仓库写 Legado-specific View**：拒绝。它不能被其他 View 宿主复用，也会让提椠无法独立
  验证和发布这条前端。
- **用 `ComposeView` 包 `CjkText`**：可作为应用迁移过渡，但不是原生 View frontend；它仍要求
  Compose runtime，并不能给 View 生命周期、RecyclerView 回收与 XML 使用提供一级契约。
- **继承 `TextView` 并覆盖 draw**：拒绝。TextView 的隐藏 layout/accessibility geometry 会与
  `LayoutResult` 并存，容易把第二份断行真值重新引回系统文本栈。
- **只提供 Canvas helper**：拒绝。测量、失效、选择、菜单、无障碍和回收仍会散落到每个宿主。
