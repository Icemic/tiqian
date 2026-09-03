# ADR 0058：Android View 一级前端与共享原生 renderer

- Status: Accepted (2026-09-03 修订：补多段页面与越界墨迹)
- Date: 2026-08-31

## Context

仓库过去的 `platforms/android/view` 只有一个接口占位，Android 原生绘制实现位于 Compose source set。
应用若想在传统 View 阅读器中使用提椠，只能依赖 Compose，或在宿主侧复制测量、Canvas 重放、选择与
链接逻辑。前者把 UI framework 变成无关宿主的强依赖，后者会形成第二份 renderer，让测量/绘制、缓存与
修复规则分叉。

这个前端必须是提椠自己的公共 Android 能力，不能知道某个阅读器、分页器或业务模型。它也不能以
「能画一段字」为验收：原生静态正文还需要正确的 View 测量/失效、回收生命周期、选择、系统菜单、
链接、无障碍和性能路径。

## Decision

1. 新增 `platforms/android/rendering` / `tiqian-android-rendering`，放 Android paragraph measurer、
   Canvas renderer、paint/draw-plan cache 与 tracing。模块只依赖 engine 和 Android shaping，不依赖
   Compose 或 View。Compose Android 改为薄适配器，把 native Canvas 交给同一个
   `AndroidParagraphRenderer`；`platforms/android/view` 直接消费该 renderer。两个前端不得复制 glyph、
   ruby、注音、装饰或 rich-text 画法。
2. `LayoutResultReplayIndex`、rich-text layout projection、fitted line-pattern geometry 与合法 paint
   overhang 属于 engine。它们只从 `LayoutResult` 推导 lookup/paint 数据，不作新排版决定。
3. 公共入口沿用 Compose `CjkText` 与 Apple `CJKTextView` 的跨平台概念，命名为 `CjkTextView`；
   `org.tiqian.android.view` 已表达产品与平台归属，公共类型不再带 `Tiqian` 前缀。它是单段、只读、
   API 23+ 的原生 `ViewGroup`：`onMeasure` 构造完整 `LayoutInput` 并调用 engine，`onDraw` 只重放
   结果；禁止 `TextView` / `StaticLayout` 作为隐藏 layout truth。source、layout style、render span
   通过不可变 `CjkTextContent` 原子提交。
4. 平台 `Spanned` 的常用 span 由前端自带的 lowering 进入 `CjkTextContent`，与 Compose 前端 lower
   `AnnotatedString`（ADR 0036）同构；`cjkSpannedCompatibility` 报告未保真的 span 语义，报告
   不触发回退。`CjkTextView` 不复刻 `TextView` 的 API 面。
5. 选择与交互只把 Android 手势翻译为 engine source geometry，行为沿用 `TextView.Editor`：可触的
   window 级手柄、按词吸附与行切换滞回、浮动 `ActionMode.Callback2` 与 AOSP 同序的 Copy / Share /
   Select all / `PROCESS_TEXT`、`customSelectionActionModeCallback` 同名扩展点、API 28+
   `Magnifier`、失去 View 焦点即释放选区。系统未公开的 `SelectionActionModeHelper` / Text Assist
   不以近似实现冒充。链接命中使用 replay segment，宿主可先消费，否则走 `ACTION_VIEW`。
6. 无障碍 host node 暴露 source text、selection 与 copy action，链接由 AndroidX delegate 路由，
   API 26+ 回答逐字符屏幕矩形。不伪造另一份 platform text layout。
7. inline object 的 `advance/ascent/descent` 由宿主在 layout 前提交；`CjkInlineViewAdapter` 只
   创建/绑定/回收 child View，前端把它放在 engine 的最终 draw origin，child 的事后测量不得反向改变
   段落几何。
8. `AndroidParagraphMeasurementSession` 跨 surface 共享 width-independent shaping / metrics；后台
   预排结果 `AndroidPrecomputedParagraph` 携带具体 `ClreqProfile`，View 只接受 profile 与完整
   `LayoutInput` exact match 的结果。
9. 多段页面由 `CjkTextSurface` 持有。跨段选区沿用 ADR 0049 的逻辑文档模型：宿主提交
   `CjkSelectionDocument`，段落用 `bindSelectionFragment` 原子绑定 key 与内容，surface 校验后才
   提交，回收与重新 attach 只改变几何投影；边缘滚动经 `CjkSelectionScrollHost` 回报实际消费距离，
   端点保活经 `CjkSelectionRetentionHost` 显式返回 handle；复制走 engine 共用的
   `getTextForCopy` 投影。它不拥有分页、数据加载、编辑或 IME。
10. 注音、着重号与悬挂标点越出段落 bounds 的墨迹，权限只来自 engine 的 legal paint bounds。前端
    不扩大测量、不加 padding / margin、不改行高与断行来让它露出。`CjkTextSurface` 把每个段落的
    重放 drawable 挂到 surface 内最近一个实际启用 `clipChildren` 的祖先 `ViewGroupOverlay`，同一
    DisplayList 与滚动事务提交，扣除普通 child pass 已可见的区域；共享 renderer 首次把越界命令录成
    `Picture`，同一 layout / paint / bounds 组合只重放 recording。surface 外层滚动 viewport 仍是
    裁切边界；没有 surface 时由宿主 `clipChildren` 决定，不做补救。

## Consequences

- 传统 Android 阅读页可直接嵌入提椠，不必引入 Compose，也不需要应用专属桥接层。
- Compose 与 View 的原生绘制优化、API 23–30 run replay、API 31+ glyph replay 和修复一起演进。
- 两个前端遵循同一条接入原则：宿主平台的富文本习惯由前端自带一次 lowering，未保真的部分由能力
  报告说明。
- `demo/android` 以 RecyclerView 长文与富文本样张两个 View 界面 dogfood 这条前端，macrobenchmark
  与 baseline-profile generator 同时覆盖 Compose 与 View 路径。

## Alternatives

- **在应用仓库写阅读器专属 View**：拒绝。它不能被其他 View 宿主复用，也会让提椠无法独立验证和发布
  这条前端。
- **用 `ComposeView` 包 `CjkText`**：可作为应用迁移过渡，但不是原生 View frontend；它仍要求
  Compose runtime，并不能给 View 生命周期、RecyclerView 回收与 XML 使用提供一级契约。
- **继承 `TextView` 并覆盖 draw**：拒绝。TextView 的隐藏 layout/accessibility geometry 会与
  `LayoutResult` 并存，容易把第二份断行真值重新引回系统文本栈。
- **只提供 Canvas helper**：拒绝。测量、失效、选择、菜单、无障碍和回收仍会散落到每个宿主。
- **越界墨迹靠测量加高、宿主留 padding / margin、要求宿主层级 `clipChildren = false`，或在最外层
  `dispatchDraw` 补画**：全部拒绝。前三种改变段落几何或把裁切变成宿主的全局约定；根层补画在
  RecyclerView 独立提交 child RenderNode 时与正文差一帧。
