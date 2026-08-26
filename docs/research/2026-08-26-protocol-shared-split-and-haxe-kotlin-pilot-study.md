# 公共层重复实现度量与 protocol/shared 分层研究（2026-08-26）

本文是 [2026-08-23 双实现评估](2026-08-23-precompute-dual-implementation-and-protocol-evaluation.md)
的后续执行研究，完成六件事：度量现行架构的重复实现规模；清点公共层（engine、ffi、
canonical 字节形）因通信协议设计而承载的 web 独占内容；给出 `@tiqian/protocol` 与
`@tiqian/shared` 两层抽离的归属判定和 attachment（附属信息段）设计；诊断 Layout
Tree 数据模型缺失并给出 Block/Inline/Float 节点与双向同步通道设计；给出统一
二进制读写抽象（对称
Reader/Writer）；给出 Haxe 实施路径，含 Kotlin 出口先行验证的 pilot 项目设计。
材料来源：仓库内代码（行号与行数随文
标注，行数统计日期 2026-08-26）、git 历史、docs/adr/、上游 reflaxe 生态核查
（2026-08-26）。核对日期：2026-08-26。

## 1. 范围与判定基准

ADR 0056 重组与 Slice 39 收尾正在进行，目录处于变动期。2026-08-23 文档中的路径已有
三处失效，可当作变动幅度的样本：

1. `frontend/web/npm/core/` 已迁移至 `frontend/web/core/`（`@tiqian/core` 包源码）。
2. `frontend/web-precompute/npm/shared/` vendored 副本目录已于 2026-08-23 删除
   （c0d2712c，删除 1,421 行），Node 侧改为直接依赖 `@tiqian/prose` 与
   `@tiqian/core`。
3. `ParagraphWireCodec` 已从 engine 移入 `ffi/js`（7ac0f47b）。

本文的判定按模块身份（职责与对外行为）记录，不按物理路径；路径以 2026-08-26 的树
为准。业务逻辑在迁移期保持稳定，本文的归属判定不受目录变动影响。Rust 头注释仍指向
已删除文件的清单见第 2.5 节。

## 2. 重复度量

### 2.1 各侧总量

| 侧 | 位置 | 行数 | 说明 |
|---|---|---|---|
| engine 排版核心（Kotlin commonMain） | `engine/src/commonMain/kotlin/org/tiqian/` | 19,947 | core 5,266 / font 507 / linebreak 671 / clreq 740 / layout 12,557 / shaping 206 |
| ffi/js（Kotlin/JS FFI 层） | `ffi/js/src/jsMain/` | 1,425 | ParagraphWireCodec 465、WireJson 279、DTO 221、出口若干 |
| ffi/native（Kotlin/Native C ABI） | `ffi/native/src/nativeMain/` | 358 | 另有 `tiqian_plan_abi.h` 146 行 |
| 浏览器侧（`@tiqian/core` TS 源） | `frontend/web/core/core/` | 20,539 | 不含 .d.ts；同名 .js 为 G1 转换的 emit 产物 |
| 浏览器宿主（`@tiqian/prose` TS 源） | `frontend/web/npm/` | 2,638 | element.ts 2,320、api.ts 251 |
| Node 编排（`@tiqian/precompute` TS 源） | `frontend/web-precompute/npm/src/` | 3,689 | |
| Rust 编排 crate | `frontend/web-precompute/rust/tiqian-precompute/src/` | 22,919 | 43 文件 |
| Rust parity 测试 | 同上 `tests/` | 2,556 | 10 文件，仅因存在两份实现而存在 |
| Neon 边界 crate | `frontend/web-precompute/rust/tiqian-precompute-neon/src/` | 1,909 | |
| 引擎 C ABI 绑定 | `ffi/rust/tiqian/src/` | 928 | |

### 2.2 现存双实现对

legacy JS 编排已于 2026-08-20 删除（ADR 0050），此后一部分 JS 模块（字体会话、
face 选择、sfnt 名表、HTML 解析）只剩 Rust 单实现，不再计入双实现。现存的双实现
对如下，每对都是同一语义的两份手写代码，一致性由采样机制保证：

| 语义 | TS 侧 | Rust 侧 | 一致性机制 |
|---|---|---|---|
| prepared-dom lowering | prepared-dom.ts 1,277（另有 markup 103、evidence 385 分担，`@tiqian/core`） | prepared_dom.rs 2,187 | corpus fixture 双侧字节比对 |
| 快照源投影 | snapshot-source.ts 343 | snapshot_source.rs 1,115 | fixture 与两侧单测 |
| canonical 字节形 | canonical.ts 320（`@tiqian/precompute`） | canonical.rs 835 | golden vectors 双侧固定同一组字节 |
| manifest 读写 | snapshot-manifest.ts 289 | snapshot_manifest.rs 461 | 两侧单测 |
| revision 常量与 stableStringify | snapshot-schema.ts 57 | schema.rs 118 | 常量相等断言 |
| 表二进制读取 | snapshot-table-binary.ts 446（只读） | snapshot_table_binary.rs 1,526（读、写、还原三段） | 字节 fixture；TS 另有测试专用编码器 table-binary-writer.mjs 195 行复刻编码序 |
| 表组装与 valueStyles | snapshot-tables.ts 133 | snapshot_tables.rs 831 | bundle 测试 |
| replay 键 | snapshot-schema.ts 部分、replay-entry-codec.ts 248 | replay.rs 239 | golden |
| font-face 边界解析 | font-face-boundaries.ts 305 | source_boundaries.rs 604、font_face.rs 251 | 两侧单测 |

Rust 侧另有请求与 plan 的读取端：paragraph.rs 410（对应 Kotlin
`ParagraphWireCodec`）、plan.rs 1,239、plan_packed.rs 846。生产端在 Kotlin（第
2.3 节），这三份构成跨三语言的协议定义。

### 2.3 三语言协议定义

plan JSON 是通信协议的核心：生产者一份（Kotlin）、读者两份（Rust、TS 间接经
prepared-dom）。同一个协议在三处各有一份手写定义：

1. **生产端（Kotlin commonMain）**：`PreparedParagraph.kt` 571 行
   （`toPreparedParagraphJson`、`toPlanWithDiagnosticsJson`）；native 路径另有
   `PlanPackedWriter.kt` 432 行输出 packed plan。
2. **读取端（Rust）**：`plan.rs`、`plan_packed.rs`，文件头注明「mirror the Kotlin
   emitter one to one」。
3. **常量重复声明**：`"tiqian-layout-v2"` 在 Kotlin（PreparedParagraph.kt:16）、TS
   （snapshot-schema.ts:3、prepare-paragraph-layout.ts:128 各一处）、Rust
   （schema.rs:12、plan.rs:16）共 5 处声明，靠测试对齐。C ABI 常量同样手工抄写：
   1a497037 在 Kotlin 测试里抄写 `FONT_BACKEND_PROTOCOL_REVISION = 2u`，注释写明
   「Must equal FONT_BACKEND_PROTOCOL_REVISION in tiqian's font_backend.rs」。

### 2.4 三份 ECMAScript 数字语义

协议要求 plan 字节与 `JSON.stringify` 输出逐字节一致，于是 ECMAScript Number 的
字符串化语义存在三份实现：

1. Kotlin commonMain `ecmaJsonNumber` 加 `canonicalTieBreak`（PreparedParagraph.kt:432-550，
   约 120 行）：按 ECMAScript Number::toString 布局重排 Kotlin 各后端的输出，含
   half-to-even 末位裁决。
2. Rust `js_compat.rs` 551 行：`js_number_string`、`js_to_fixed5`（i128 十进制展开
   复刻 toFixed(5) 舍入）、`js_trim`、UTF-16 排序比较等，被 25 个业务文件引用。
3. JS 原生 `JSON.stringify`。

`json.rs` 504 行在 Rust 侧重写了 JSON 解析与序列化（插入序键、非有限数转 null、
-0 转 0），被 20 个业务文件引用。`kotlin_to_float`（js_compat.rs:8）复刻 Kotlin
`toFloat()` 的 f64 到 f32 就近舍入，是引擎 Float 几何与序列化边界双精度数值之间的换算
约定，同样只有 Rust 一份手写副本。

### 2.5 Kotlin 内部重复与验证开销

1. `PlanPackedWriter.kt` 与 `PreparedParagraph.kt` 各有一份复制粘贴的聚合循环
   （naturalWidth、openTypeFeatures、renderFontFamily、glyphIdsByRange 的累积，
   两个文件的前 40 行结构相同），JSON 与 packed 两条输出路径共享同一语义。
2. 验证基建的规模：Rust 侧 parity 测试 2,556 行（10 文件）；corpus 构建脚本与
   fixture（`build-prepared-dom-corpus.mjs`、`prepared-dom-corpus.fixture.json`）；
   plan parity oracle（`scripts/plan-parity-oracle.mjs` 运行 Kotlin/JS bundle 产出
   oracle.json，Rust 测试字节比对）；人工规格 `docs/ts-port-assertion-checklist.md`
   283 行逐条登记断言供重写对照。这些成本仅因存在两份实现而存在。
3. 请求模型类型另有 schema 生成机制：`ffi/schema/assembly-record.schema.json`
   405 行加 tools/schema 的两个 Python 生成器（generate_ts.py 135 行、
   generate_rust.py 239 行），产出 TS 38 行与 Rust 88 行类型定义（371629a7
   引入）；Kotlin 生产端不在机制内，仍是手写 DTO。同一请求模型由此存在手写
   Kotlin、schema JSON、生成 TS、生成 Rust 四种形态。
4. corpus 覆盖现状：2026-08-23 文档列出的缺失字段（dashStrategy、
   punctuationInkFloor、ruby/bopomofo 决策、inlineEdges、emphasisRanges 等）在
   3777e5b4 扩充后已有样本；per-cell style delta（输出字段名 `"style"`）在
   fixture 中仍为 0 个样本（2026-08-26 grep）。采样验证的未覆盖路径仍然没有捕获
   机制。
5. Rust 头注释指向已删除文件的共有 6 个名字（precompute.js、precompute-fonts.js、
   precompute-html.js、font-contract.js、precompute-node-fonts.js、digest.js），
   对应内容现存点仅 `.b2-tmp/` 归档；paragraph.rs:487 引用的 `PrecomputeWire.kt`
   已不存在（现为 `ffi/js` 的 ParagraphWireCodec）。

## 3. 公共层被迫承载的 web 独占内容

plan JSON 同时是引擎与 web lowering 的通信协议。协议没有附属信息的概念，web 需要
的一切都必须成为协议一等字段，于是 web 词汇进入 engine、ffi 与缓存身份字节。逐条
清点：

| # | 位置 | 内容 | web 独占点 |
|---|---|---|---|
| 1 | engine commonMain PreparedParagraph.kt:200-416 | render evidence：dashStrategy、punctuationInkFloor、style delta、inlineEdges、ruby/bopomofo 决策、emphasis、decorationSegments | 注释自述「Field values and lookup orders mirror DomParagraphRenderer」；几何本身是排版数据（见 5.1 的归属修正），但字段形状由 DOM 渲染器决定 |
| 2 | engine commonMain PreparedParagraph.kt:162-196 | `zeroAdvanceEpsilonPx` 参数与 capability/advance 诊断信封 | 宿主阈值 `ZERO_ADVANCE_EPSILON = 0.01` 是 web 概念，且在 TS 侧声明两处（canvas-metrics.ts:88、canvas-shaping.ts:207） |
| 3 | engine commonMain PreparedParagraph.kt:418-550 | `ecmaJsonNumber` 与 tie-break | ECMAScript 语义进入引擎公共代码，成因是 plan 字节要与 JS 侧逐字节一致（第 2.4 节） |
| 4 | ffi/js LayoutRequestDtos.kt:205-213 | `SemanticSpanWire.tagName/attributes` | DOM 词汇经 DTO 进入 ffi 公共定义 |
| 5 | canonical.rs:31-32 与 canonical.ts | `SEM_ATTRS`、`SEM_TAG_NAME`、`SEM_ORDER` 标志 | DOM 语义进入缓存身份字节（哈希前像） |
| 6 | prepared_dom.rs:1242-1249、:37、:470-475、:607-646 | CSS 自定义属性（`--tq-line-height:...!important`）、`data-tq-*` 属性、HTML class 操作 | web DOM lowering 全量在 Rust 侧第二遍实现 |
| 7 | snapshot_tables.rs:200-232 | valueStyles 行 intern，渲染期按索引生成 `tqv-N` class | CSS 声明进入二进制表；解释端在 web |
| 8 | source_boundaries.rs / font_face.rs | CSSOM 面的 face 记录（family、localNames、unicodeRange、publicUrl）解析 | web 输入格式；产出（sourceBoundaries）本身是引擎概念 |

第 6、7、8 条的产出端与解释端都是 web；第 1 至 5 条是平台中立容器里装入 web 决定
形状的字段。5.5 节的 attachment 设计针对后者：排版数据留在协议内核，DOM 词汇移出。
块级结构层面的修正见 5.2 节的 Layout Tree。

## 4. 双实现维护 bug 的历史证据

Rust crate 诞生于 2026-08-20（6e445cde）。至 2026-08-26 共 59 个提交触及该目录，
其中 17 个（29%）主题为 fix/parity/oracle/corpus/golden/align，21 个（36%）同一
提交同时改 Rust 侧与 web TS/JS 侧。按成因分类：

| 成因类 | 提交 | 内容 |
|---|---|---|
| 移植保真错误 | aa93983c | `Number.MAX_SAFE_INTEGER` 为 2^53-1，Rust 初版写成 2^53 |
| | a9777002 | boundary span 的 style 应从 span 自身对象读取，跨语言对象形状不一致 |
| | 3bd672f3 | JS 序列化省略 undefined 字段，Rust 初版输出了该字段 |
| | 3777e5b4 | 一批遗漏：cjkStrongSemantics 重放、Object 赋值键序、`Number(ruby.ascent)` 的 NaN 回退 |
| 手写 JS 数字语义 | aa93983c、js_compat.rs 全部 | 第 2.4 节；手写层每条规则都是独立出错点 |
| vendored 副本落后 | 24e15e3a | 副本缺 `SNAPSHOT_TABLES_SCHEMA = 2` |
| | ba01d047 | 同一修复需同时改浏览器端实现与副本两份 |
| | c0d2712c | 以删除 1,421 行副本收场（2026-08-23） |
| oracle 自身的维护 | af9d80d9 | oracle 与 native 通道的 evidence 默认值分叉 |
| | 19f5f261 | 协议改 DTO 后 oracle 单文件 45+/43- 重写 |
| | f77b7aee | revision 变化导致 golden 22 行全量重写 |
| | ecbf1f98 | fixture 路径硬编码使 CI 失败，波及 4 个 Rust 源文件 |
| 常量手工抄写 | 1a497037 | C ABI revision 常量三处同步（Kotlin 测试、Rust extern、stub） |
| 单一行为修复多处同步 | 96ef18e3 | fwid 特征重放：一个修复落 5 个文件、3 种语言（副本 JS、styles.css、prepared_dom.rs、corpus builder、fixture） |
| | 6ff37b45 | `SPACING_DUST_EPSILON = 1e-6` 常量与相同注释在 JS 与 Rust 各一份，另加 corpus builder 与 fixture |

两个实例展开：

1. **96ef18e3（fwid 特征签名）**：lowering 需要重放 fwid 特征。改动必须同时落在
   浏览器端 lowering、Rust 移植、corpus 生成器、fixture，任一遗漏即两侧字节分叉。
   这是「一个行为、N 处同步」的最小样本。
2. **6ff37b45（亚 epsilon 拉伸）**：同一常量带同一段英文注释存在于两个语言的两份
   文件，再加生成器与 fixture 各一处，共四个改动点。

## 5. 两层抽离设计

### 5.1 判定标准

- `@tiqian/protocol`：非 web 独占的数据结构与公共抽象。判定按未来潜在统合评估，
  不按代码现状：只要某结构或抽象可以被两个以上平台受益地消费，即入 protocol，与
  它当前只有 web 消费者无关。受益者枚举：浏览器、Node 编排、Rust 宿主、
  Compose/Android、Apple。字体缓存、预计算、计划（plan）结构、缓存身份、二进制
  读写格式都是平台中立概念。
- `@tiqian/shared`：web 前后（浏览器与 Node 构建期）共享、不属于引擎的内容。
  判定为 web 独占的判别式：产出端与解释端都只存在于 web 技术栈（DOM、CSS、
  HTML）。

按此标准修正第 3 节第 1 条的归属：render evidence 中的几何与决策（dash 策略、
ink floor、ruby/bopomofo 几何、emphasis 位置）是 LayoutResult 的组成部分，任何
绘制器都可能消费，归 protocol；字段形状中由 DOM 渲染器决定的部分（与
DomParagraphRenderer 逐字段对应的关系）在 protocol 中改为按引擎自身语义定义，DOM
解释移入 shared。

### 5.2 Layout Tree 数据模型缺失

engine commonMain 没有块级结构定义（2026-08-26 grep：无 Block/Tree 类）。FFI
请求模型是单段扁平结构：text 加 textSpans、inlineBoxes、lineBreakSpans、
inlineObjects、decorations 五个平行数组（PrecomputeExports.kt:58-79）。块级
结构（哪些内容构成一个块、块之间的关系）只以 DOM 的形式存在，由 web 侧的
HTML 解析与 DOM walker 决定（precompute_html.rs、html_parse.rs、
snapshot-source.ts 的 projectedNormalFlow）。

DOM 由此同时存放三类内容：

1. 语义源：用户正文与行内语义（SAFE_SEMANTIC_TAGS 白名单内的标签与属性）。
2. 布局输出标记：data-tq-* 属性、tqv-N class、--tq-line-height 自定义属性
   （第 3 节第 6、7 条）。
3. 运行时状态与观察者：element.ts 内 MutationObserver、ResizeObserver、
   IntersectionObserver 共 13 处引用，live-replay 与调试属性的时序证据。

data-tq- 前缀在 TS core 内有 17 个文件消费（2026-08-26 grep）。前后两端都
直接读写同一组标记，引擎输入与引擎输出放在同一存放处，方向无法从标记本身
区分。

时序机器的存在本身是诊断（2026-08-26 清点）：

1. 块发现用标签选择器 `p, li`（signatures.ts:6 的 DEFAULT_PARAGRAPH_
   SELECTOR），不按 display 分类；嵌套根按 closest 划分归属（observers.ts:23），
   对应既有断言 nestedRootsOwnOnlyTheirDirectParagraphScope。非 p/li 的块与
   组件容器不进候选集，这是渐进增强不能处理复杂容器嵌套的直接原因。
2. 观察者与引擎写同一份 DOM，需要专门的命名 heuristic 区分引擎自己的输出
   与宿主信号：EnginePreparedStyleWritesAreNotContent（渲染器每轮提交重写
   自己的 style 元素文本，observers.ts:302）、RawDomCharacterDataIsHostCertain
   （observers.ts:311）、TopLevelChildListTrustsIdentityProbe（引擎提交本身
   增删段落的直接子节点，childList 记录不能证明宿主编辑，observers.ts:323）。
3. 失效粒度以 root 为单位：relayout 任务从宽度快照准备全部段落（element.ts:1167，
   CapturedMeasureFollowUpCoalescing）；输出排版签名与源签名的对比会调度
   destroy-and-enhance 重建整个 root（element.ts:1176，RenderOutputTypographyIs
   NotAnInputChange）。单个段落的微小变化在现架构里没有单段失效路径。
4. 宿主克隆内容时引擎要手工逆写自己的 DOM 签名：
   stripEngineMarkupFromStrandedParagraph 枚举 8 个 data-tq 属性
   （content-reconcile.ts:183-190）、4 类 artifact 选择器、全部 --tq-* 自定义
   属性与 3 条 !important 内联样式（content-reconcile.ts:148-207）。引擎每
   新增一个 DOM 标记都要同步维护这份逆写清单。
5. MutationObserver 微任务异步投递（element.ts:2036，
   MutationObserverDeliveryIsAsync），加上 settle 窗口、延迟 settle 与响应式
   提交路径（element.ts:438、539、612、1467），构成当前时序问题的成因清单。

设计（2026-08-26 裁定）：Layout Tree 数据模型，节点三类。

- **Block**：块级容器，对应现在的单段请求，一个 Block 进行一次排版。
- **Inline**：行内节点，配置四项：语义（tag 与属性）、临近内容是否接续、
  内部是否可断词、内部是否可折行。
- **Float**：未来扩展，含位置与形状（圆形、方形、多边形），当前 WIP，
  不进入排版路径。

同步通道与失效粒度（2026-08-26 裁定）：Layout Tree 与 DOM Tree 双向绑定，
每条信息通道单向明确。

- **DOM 到树**：MutationObserver 与 ResizeObserver 的记录进树 diff。树 diff
  采用 vdom 领域成熟的线性启发式（类型比较把树编辑距离问题从 O(n³) 收敛到
  O(n)；本文场景比通用 vdom 更简单：DOM 是输入权威，MutationRecord 自带节点
  身份，增删移直接来自记录，不需要 key 启发式）。
- **树到 DOM**：渲染器经 CSS API 写回，写回路径不进观察面，回环从结构上
  切断；上述三条「认出自己」的 heuristic 由此不再需要。
- **建树来源**：浏览器侧经 getComputedStyle 读 display 归类节点；Rust 侧解析
  HTML 建树。归类是二值判定（2026-08-26 裁定）：是否 inline。inline-block、
  inline-flex 与一个字在引擎里同地位，作为行内流的原子单位，display 子类型
  不进入模型，差别由 Inline 配置表达。服务器快照加载时按快照水合分类事实，
  不经浏览器；快照按断点逐块产出是 ADR 0054 格数区间改造后的形态（当前尚未
  完成），完成后树分类事实随快照存放，产出的排版结果不丢失。渐进增强由此
  覆盖任意容器嵌套：display 分类不依赖标签名，非 p/li 的块与组件容器进入
  候选集。
- **状态存放**：引擎输出挂树节点，DOM 标记只是渲染产物；宿主克隆内容时不
  存在引擎签名逆写清单。
- **失效粒度**：单个 Block 的变化只重排该 Block，按 root 全量
  destroy-and-enhance 的路径删除。测量职责按节点类别划分：含 inline 内容的
  Block 需要宽度测量，
  纯 block 子树与 float 由几何求交解决；float 区域耦合多个 Block 的行内
  布局，失效沿 float 区域传播，这是 Float 进入模型的原因。

现状对照：断词控制以 lineBreakSpans 的 policy 存在（TextModel.kt:44 的
LineBreakPolicy），语义以白名单校验的 span 存在；接续与折行没有一等字段，由
DOM walker 的空白折叠与标签行为隐式决定。walker 按 tag 赋值 policy：
buildLineBreakSpans 把每个 a 元素的整个文本区间标成 ProgressiveTechnical
（markdown-lowering.ts:1203），链接文字因此拿到链接地址的不可断 token 行为，
这是 issue #9（链接文字被当作链接地址处理）的成因；标签驱动赋值的信息量
区分不了链接文字与链接目标，Inline 的断词与折行配置直接表达该差别。树模型
把这四项集中为 Inline 节点配置。

归属：protocol。块树是平台中立结构，Compose 与 Apple 的渲染器同样消费块树。
迁移后的方向：web lowering 从 DOM 产出 Layout Tree，排版与 precompute 消费
树；DOM 标记降为渲染产物之一，运行时观察者订阅树的状态，不再从 DOM 读取
引擎输出。

### 5.3 protocol 收编清单

| 内容 | 现状位置 | 现状重复度 | 迁移形态 |
|---|---|---|---|
| plan 数据模型（行、cell、evidence）与 JSON/packed 序列化 | Kotlin 生产端 571+432 行、Rust 读取端 2,085 行 | 三语言 | Haxe 单源生成 Kotlin/TS/Rust；`ecmaJsonNumber` 随之单源化 |
| canonical 字节形（TQCS、SHA-256 前像） | TS 320 + Rust 835 | 双实现 | Haxe 生成 TS/Rust；golden vectors 保留为回归 |
| 表二进制读写（TIQTBL03 读取、写入、还原） | Rust 1,526 全量、TS 446 只读、测试编码器 195 | 读路径双实现，写路径 Rust 单实现且 TS 测试另有一份编码序复刻 | Haxe 统一 Reader/Writer 生成 TS/Rust（6.2 节）；TS 测试编码器删除 |
| ADR 0054 行长区间表条目编码 | 未实施 | 无 | 在 protocol 内首先实现（整数内容、无语义桥依赖） |
| manifest 结构与 revision 常量族 | TS 57 + Rust 118 + Kotlin 1 + ffi 常量抄写 | 五处声明 | Haxe 单源生成三语言；C ABI 常量改经生成头文件供给 |
| 字体会话与字体缓存抽象（face 选择输入、replay 键、FontContracts 缓存键、context 指纹） | Rust session/replay/context、TS snapshot-schema 部分 | 部分 | 抽象与键构造归 protocol；HarfBuzz/sfnt 实现留平台壳 |
| 宿主请求模型与校验（DTO 字段、范围检查、错误名） | Kotlin ParagraphWireCodec 465、Rust paragraph.rs 410 | 双实现 | Haxe 生成 Kotlin/Rust（+TS） |
| 错误命名（NamedError 族） | 各语言各份 | 三语言 | Haxe 枚举生成 |
| Layout Tree 数据模型（Block/Inline/Float） | 未实施：块级结构只存在于 DOM 与 web walker | 隐式 | 在 protocol 内首先定义（5.2 节）；web lowering 产出树，DOM 标记降为渲染产物 |

### 5.4 shared 收编清单

| 内容 | 现状位置 | 迁移形态 |
|---|---|---|
| prepared-dom lowering（DOM 序列化、class 生成、data-tq 属性、CSS 变量行高） | TS 1,765（三文件）+ Rust 2,187 移植 | Haxe 生成 TS；Rust 移植删除，Node 经生成的 TS 或 protocol 的 Kotlin/Rust 产物组装 |
| 快照源投影（SAFE_SEMANTIC_TAGS、属性校验、折叠） | TS 343 + Rust 1,115 | 同上 |
| HTML 解析与注入（precompute-html） | Rust 955+760、TS 214 助手 | 同上 |
| font-face CSSOM 解析 | TS 305 + Rust 855（两文件） | 解析归 shared；边界数据结构归 protocol |
| valueStyles 解释与 `tqv-N` class 生成规则 | Rust 表组装内、TS 渲染内 | 归 shared，经 attachment 消费 |

### 5.5 attachment 段设计

二进制包络增加附属信息段（attachment）：一组 `(kind: u16, length: u32, bytes)`
条目，跟在平台中立的核心节之后。规则：

1. protocol 代码对 attachment 只做三件事：搬运、计入长度校验、计入内容哈希
   （canonical 身份覆盖全部 attachment 字节）。kind 之外零解释。
2. 未识别的 kind 原样保留，前向兼容。
3. shared 是 web kind 的唯一解释端。非 web 平台可注册自己的 kind（例如 Compose
   挂绘制提示），互不解析。
4. 迁移映射：第 3 节第 7 条（valueStyles 行）改为 attachment kind；第 4 条
   （tagName/attributes）随 Layout Tree 成为 Inline 节点的语义配置（5.2 节）；
   第 5 条的 SEM_* 标志由 canonical 写入端从树内语义生成，读取端不再抄写标志
   含义；第 6、8 条整体归 shared 后不再出现在 protocol。

现有代码已有该形态的雏形：valueStyles 以不透明字符串行的形式写入表
（snapshot_tables.rs:219），canonical 的 SEM_* 标志把 DOM 语义折进位标志，plan 的
可选字段默认省略。attachment 把这些既有做法统一为包络机制，并给出「protocol
不解释」的边界规则。

### 5.6 与既有裁定的关系

1. **JsWorkspaceMonorepo（2026-08-20）**：其 web-core 包裁定（plan JSON 格式与
   snapshot revision/replay 定义独立成包、prose 与 precompute 共同依赖、消解
   revision 常量重复）被 protocol/shared 两分吸收：上述内容全部落 protocol，
   目标一致，包数从一个变两个。ffi/rust 同时绑定引擎与 protocol 的许可延续。
   workspace 重组时需补一处依赖声明：`@tiqian/precompute` 经
   `import.meta.resolve("@tiqian/core/styles.css")` 隐性依赖 `@tiqian/core`
   （precompute.ts:17、precompute-html.ts:23），package.json 未声明。
2. **ADR 0054**：其开工条件未满足前，protocol 的 JSON 边界保持现状（构建期
   plan JSON 解析、manifest JSON 传输）；条件满足后按 0054 收缩为整数条目编码，
   protocol 的划分不变。
3. **ADR 0056**：npm 包布局不受 Gradle 约束；protocol/shared 以 workspace 成员
   加入，物理位置随 JsWorkspaceMonorepo 的源码布局裁定。

### 5.7 保留手写的平台壳

- Rust：Neon 边界（1,909 行）、engine ABI 桥（engine_bridge.rs 333）、harfrust
  shaping（shaping.rs 412）、sfnt 与名表（sfnt.rs 284、name_table.rs 430、
  name_language.rs 385 为 HarfBuzz 14.2.1 头文件的手抄表）、worker 池与线程展开
  （renderer.rs、parallel.rs）、`kotlin_to_float` 等 Rust 特有饱和转换。
- TS/Kotlin JS：element.ts DOM 宿主（2,320 行）、observers、live-replay、样式桥。
- Kotlin：engine 排版核心自身（迁移期，见第 6 节顺序）。

## 6. Haxe 实施路径

### 6.1 自维护 emitter 的依据与框架裁定

2026-08-26 生态核查：awesome-haxe-targets 清单没有 Kotlin 条目；reflaxe 系仅有
Java 源码 target（EliteMasterEric/reflaxe_javasources，标记死亡）与 Swift target
（开发中）；社区对 Kotlin target 只有讨论。reflaxe.rust 为 0.x 发布策略（08-23
文档第 4 节）。据此裁定（2026-08-26）：ts、rust、kotlin 三个 target 的 boring
子集 emitter 自维护，只用 MIT 的 reflaxe 框架实现，不走完全自写的路线。

子集与对齐方向（2026-08-26 裁定）：boring 子集取 Haxe、Kotlin、Rust、
TypeScript 四语言交集（循环、数组、interface、枚举、结构体；不用函数值、
Dynamic、继承、null、异常捕获）；TS 出口是 TypeScript 源码，与 G1 转换的产物
形态一致，不直接输出 JavaScript。先实现 Kotlin target，Rust 与 TS 的行为对齐
Kotlin：引擎现存生产端是 Kotlin，Kotlin 产物先与手写版达到 golden 零 diff，
作为行为基准；Rust 与 TS 产物对同一组 Haxe 测试向量重放，三方 golden 来自
同一组字节。

交集可控的依据（2026-08-26 核查）：engine commonMain 与协议代码内 grep 无
suspend 与 coroutine；Rust crate src 内 grep 无 async fn（并行只存在于编排层
worker 池，属 5.7 节的手写平台壳）。全部逻辑是数据操作与数值计算。数据
immutable 或应 immutable：Haxe 侧结构声明为只读字段，出口映射 Kotlin val、
Rust 无 mut 字段的结构体、TS readonly；无共享可变状态时 Rust 所有权不构成
阻碍。

许可证：reflaxe 框架 MIT；emitter 自维护使产物不含 reflaxe.rust 的 GPL-3.0
运行时（hxrt），08-23 文档结论 7 的 rust_no_hxrt 验证层简化为 emitter 自检
（生成物零运行时支撑即零 GPL 引用，判据见 6.3 第 5 条），MPL-2.0 兼容。

boring 子集以 CI 规则强制：源内出现禁用特性即失败，不依赖自觉（08-23 文档 3.4）。

### 6.2 统一二进制读写抽象

08-23 文档 6.3 节已给出依据：haxe.io.BytesInput/BytesOutput 与 DataView 同构，
浏览器、Node、Rust 三端共享一份 codec 的实现路径只有生成代码。本文将其定为
实施裁定（2026-08-26）：每个二进制格式在 Haxe 源内是一个模块，Reader 与
Writer 对称维护；互逆性质在 Haxe 测试内闭合（写后读还原相等、读后写重写
字节相等、golden 向量固定同一组字节）；出口语言零手写读写代码，只重放
golden 向量。

现状的读写不对称实例：

1. 表二进制：Rust 侧读、写、还原三段共 1,526 行；TS 侧只读 446 行；TS 测试
   另有专用编码器 table-binary-writer.mjs 195 行复刻编码序。写路径只有 Rust
   一份，TS 侧要验证写入只能再抄一份。
2. 请求模型类型：schema JSON 405 行加两个 Python 生成器共 374 行，产出 TS
   38 行与 Rust 88 行（第 2.5 节第 3 条）；779 行机制维护 126 行产物，且只
   覆盖三个语言中的两个。

收益：table-binary-writer.mjs 与 tools/schema 的两个生成器删除；写路径不再
单语言独有；端序漏参、偏移校验遗漏这类读写差异缺陷在 Haxe 互逆测试内一次
捕获，双侧 fixture 数量随双实现删除而下降。

### 6.3 pilot 项目设计（Kotlin 出口先行）

按既定路径开独立新项目，用一个小的引擎包验证 Kotlin 出口。设计：

- **对象**：`org.tiqian.linebreak`（671 行，依赖仅 `org.tiqian.core.TextRange`）
  与 `org.tiqian.clreq`（740 行，依赖 core 的 BuiltInLayoutProfiles 与
  LayoutProfileId）。两者是依赖最浅的叶子簇，且有 commonTest 全平台测试
  （EnglishHyphenationTest、MandatoryBreakTest、LiangHyphenatorTest、
  UnicodePunctuationLineBreakTest、KinsokuLevelTest、
  PunctuationGluePlacementTest、NumberSymbolCohesionTest、BopomofoParserTest）。
- **判据**（全部满足才继续后续阶段）：
  1. 生成的 Kotlin 编译进 `:engine` 的 KMP target 集（jvm、js、linuxX64 等），
     替换原手写源码后 Gradle 构建通过。
  2. 行为一致：上述 commonTest 与 jvmTest 全部通过；
     `LayoutDumpGoldenTest` golden 零 diff。
  3. 导入导出正确：package 声明为 `org.tiqian.linebreak`/`org.tiqian.clreq`；
     对 `org.tiqian.core` 的跨包 import 正确生成；对外 API 与原 Kotlin 同名同
     签名（public/internal 可见性保留）；生成物可被 `ffi/js`、`ffi/native`、
     platforms 各消费者按原路径引用。
  4. 生成确定性：同一份 Haxe 源两次生成，输出逐字节相同。
  5. 零运行时依赖：生成物不含 haxe 标准库引用与任何运行时支撑（无 hxrt 类比物）。
- **验证命令**（pilot 仓库内可重放）：
  `./gradlew :engine:jvmTest`、`./gradlew :ffi:js:jsNodeTest`、
  `./gradlew :platforms:compose:compose:jvmTest`、
  `TIQIAN_UPDATE_GOLDEN=1` 对照更新前后 golden 逐项检查（应为零 diff）。

### 6.4 迁移顺序

| 阶段 | 内容 | 回退边界 |
|---|---|---|
| 0（本文） | 分层判定、attachment 设计、Layout Tree 设计 | 文档 |
| 1 | pilot：linebreak/clreq 的 Haxe 源 + Kotlin emitter，判据见 6.3 | 独立项目，不影响主仓 |
| 2 | protocol 首个模块：ADR 0054 条目编码或 canonical（整数/字节内容、无语义桥依赖、golden 可固定），生成 TS/Rust 替换双侧手写；统一 Reader/Writer 抽象（6.2 节）随之建立，tools/schema 生成机制删除 | 每模块一提交，golden 保留 |
| 3 | plan 模型与序列化生成三语言，替换 Kotlin 生产端与 Rust 读取端；revision 常量单源化 | 同上 |
| 4 | Layout Tree 定义入 protocol；web lowering 拆为 DOM 到树、树到标记两步，观察者记录进树 diff，失效粒度收敛到单 Block，对外输出字节不变 | lowering 内部重构，corpus 字节比对为证据 |
| 5 | shared 生成 TS（prepared-dom lowering 等），删除 Rust 侧移植（prepared_dom.rs 2,187 行等）与 TS 测试编码器 | 同上 |
| 6 | engine 内部按 08-23 文档 9.3 节推进（叶子、数据、调度、多端消费），plan 生产者最终由 Haxe 生成 | 每阶段以 golden 与模块测试为证据 |

顺序依据：风险从低到高（独立项目、纯整数 codec、三语言协议、树定义与
lowering 重构、web lowering、引擎自身）；每步以既有测试与 golden 为行为证据，
可独立回退。

### 6.5 迁移期的路径纪律

1. 归属判定按模块身份生效，后续目录变动不需要重新判定；Rust 头注释中指向已删除
   JS 文件的 6 个名字在阶段 5 删除对应文件时一并清理。
2. 生成物入库（与 G1 的 emit 入库先例一致），以「emit 与基线逐字节 diff」为强
   校验；review 对象是 Haxe 源与生成 diff，不要求通读生成物。
3. corpus 与 oracle 的角色收缩：从「证明两份手写实现一致」变为「证明 emitter 与
   基线一致」，机制保留，数量随双实现删除而下降。

## 7. 风险与未决

1. **Float 与 Double 边界**：引擎几何是 binary32，协议序列化值与 canonical 是
   binary64；
   `kotlin_to_float` 的存在说明换算是一个语义点。protocol 层的数值类型需要显式
   声明 binary32/64 边界，生成代码不得依赖目标语言的隐式转换。
2. **生成代码的编译器差异**：三语言的「同行为」在浮点字符串化（已有
   ecmaJsonNumber 处理）、整数除法取模（08-23 文档 7.3 已列）上需要微语义
   fixture 固定；pilot 判据 2 只覆盖 Kotlin 侧，TS/Rust 侧在阶段 2 引入各自的
   微语义测试。
3. **Kotlin 出口的惯用形态**：生成物要落在 `:engine` 的既有包结构与可见性之下，
   data class、sealed interface 的生成形态（构造、copy、判等）需要在 pilot 中
   确认与手写版二进制兼容或至少源码兼容；这是判据 3 的细化项。
4. **与 Slice 39 收尾的顺序**：D 批（平台包发布与 legacy 移除）未完成前，npm 包
   边界还会变动；protocol/shared 的 package.json 位置在 workspace 重组时一次
   定稿，避免在旧布局上反复搬移。
5. **corpus 未覆盖路径**：per-cell style delta 至今无样本；阶段 2 起每个 protocol
   模块的 golden 需要覆盖该模块全部字段，否则 emitter 回归存在采样空洞。
6. **Layout Tree 迁移的中间状态**：lowering 输入从 DOM 改为 Block/Inline 树后，
   DOM 与树两种形状并存到渲染器全部切换为止；以 corpus 字节不变与既有 golden
   为回归证据，先在 lowering 内部完成两步化，对外行为不变。不迁移的代价已有
   实例：issue #9 的链接断行错误来自按 tag 赋断行 policy，标签信息量区分不了
   链接文字与链接目标，同类错误随宿主形态继续出现。
7. **生成读写代码的性能**：统一 Reader/Writer 生成的 TS 代码经 DataView 访问，
   Rust 代码经字节转换；与手写版的差异需以 0052 表 bench 对照，超出预算时在
   emitter 内做目标语言特化，语义不变。
8. **建树的样式事实来源**：getComputedStyle 只在浏览器成立；Rust 构建期没有
   CSSOM，只能按 UA 默认样式近似，作者样式覆盖需要宿主提供（与现有 precompute
   依赖宿主测量是同一约束）。display 归约按 2026-08-26 裁定是二值判定（是否
   inline）：inline-block、inline-flex 与一个字在引擎内同地位，display 子类型
   不进入模型，差别由 Inline 配置表达，先前对中间形态归约表的顾虑取消。快照
   水合依赖逐块按断点产出快照，该形态由 ADR 0054 改造完成，当前尚未完成。
9. **float 的跨块耦合**：float 区域影响多个 Block 的行内布局，单 Block 失效
   必须定义沿 float 区域的传播规则；Float 实施前，失效粒度收益只在纯块流
   成立。

## 8. 结论

1. 现存双实现（TS 与 Rust 各一份手写）集中在 9 组模块（第 2.2 节），Rust 侧合计
   8,167 行是 TS 语义的移植；跨三语言的协议定义（plan 生产与读取、宿主请求模型、
   数字与 JSON 语义桥）合计 5,297 行处于「一份语义、多处手写」状态。
2. 维护税有实测：Rust crate 六天 59 提交中 29% 是修复两侧一致，36% 的提交需要
   双侧同步；一个行为修复最多触及 5 个文件 3 种语言（96ef18e3）。corpus 采样存在
   未覆盖字段（per-cell style delta），未覆盖路径的差异没有捕获机制。
3. 公共层被迫承载 web 独占内容共 8 处（第 3 节），成因是 plan JSON 通信协议没有
   附属信息的概念。attachment 段把「protocol 搬运、shared 解释」定为边界规则，
   现有 valueStyles 行与 SEM_* 标志是其雏形。
4. 块级结构缺失（5.2 节）：engine 无块级结构定义，FFI 请求是单段扁平
   数组，块级结构只存在于 DOM；DOM 同时存放语义源、布局输出标记与运行时状态，
   TS core 内 17 个文件消费 data-tq- 前缀。Layout Tree（Block/Inline/Float，
   Inline 配置为语义、接续、断词、折行）补齐该缺失，归 protocol。同步通道
   单向明确：观察者记录进树 diff、渲染器经 CSS API 写回、引擎状态挂树节点；
   失效粒度从按 root 全量 destroy-and-enhance 收敛到单 Block，测量按节点类别划分
   （含 inline 的 Block 测宽，纯 block 与 float 几何求交）。
5. 读写不对称有实例（6.2 节）：表二进制写路径只有 Rust 一份，TS 测试另抄 195
   行编码器；请求模型类型由 779 行 schema 与 Python 机制维护 126 行产物且不含
   Kotlin 生产端。统一二进制读写抽象（Haxe 内对称 Reader 与 Writer、互逆测试
   闭合、出口零手写）同时消解两者。
6. 两层抽离判定：protocol 收编 9 组内容（plan 模型与序列化、canonical、表二进制
   读写、0054 条目编码、manifest 与常量族、字体缓存抽象、宿主请求模型、错误
   命名、Layout Tree），shared 收编 5 组 web lowering；判定标准是「非 web 独占、
   多平台可受益」，按未来统合评估。render evidence 的几何归 protocol、DOM 词汇
   归 attachment，修正其当前由 DomParagraphRenderer 决定字段形状的因果关系。
7. Haxe 实施以自维护 emitter 为基（reflaxe 生态无可用 Kotlin target，2026-08-26
   核查），只用 MIT 的 reflaxe 框架实现；boring 子集取 Haxe、Kotlin、Rust、
   TypeScript 四语言交集，TS 出口是 TypeScript 源码，先实现 Kotlin 出口并把
   Rust 与 TS 对齐到 Kotlin；可控依据是协议
   与引擎无异步代码、数据 immutable。pilot 用 linebreak（671 行）与 clreq
   （740 行）验证 Kotlin 出口，判据五条：KMP 编译、golden 零 diff、导入导出与
   API 兼容、生成确定性、零运行时依赖。
8. 迁移顺序七个阶段（0 至 6），每阶段以既有测试与 golden 为行为证据、可独立
   回退；生成物入库并以 emit diff 为强校验，与 G1 先例一致。

## 附录：数据汇总

| 度量 | 数值 |
|---|---|
| Rust crate 提交总数（2026-08-20 至 08-26） | 59 |
| 其中主题含 fix/parity/oracle/corpus/golden/align | 17（29%） |
| 其中同一提交触及 Rust 与 web TS/JS 两侧 | 21（36%） |
| 单个行为修复的最大触及数 | 5 文件、3 语言（96ef18e3） |
| 现存双实现组数（第 2.2 节） | 9 |
| Rust 侧属移植的行数合计 | 8,167 |
| 三语言手写协议定义行数合计 | 5,297 |
| 仅因双实现存在的 Rust parity 测试 | 2,556 行（10 文件） |
| `"tiqian-layout-v2"` 声明处数 | 5（Kotlin 1、TS 2、Rust 2） |
| corpus 中 per-cell style delta 样本数 | 0（2026-08-26 grep） |
| vendored 副本存活期 | 2026-08-20 至 08-23，删除 1,421 行（c0d2712c） |
| 请求模型 schema 生成机制 | 779 行机制（schema 405、Python 374）维护产物 126 行（TS 38、Rust 88），Kotlin 生产端不在机制内 |
| TS core 内消费 data-tq- 前缀的文件数 | 17（2026-08-26 grep） |
| element.ts 内 observer 构造引用 | 13 处（2026-08-26 grep） |
| 块发现选择器 | `p, li`（signatures.ts:6），不按 display 分类 |
| 区分引擎输出与宿主信号的命名 heuristic | 3 条（observers.ts:302、311、323） |
| 引擎签名 DOM 逆写清单 | 8 个 data-tq 属性、4 类 artifact 选择器、--tq-* 属性、3 条 !important 内联样式（content-reconcile.ts:148-207） |
