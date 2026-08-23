# Web prose 宿主拆分报告

本报告给出现状审计、Worker 必要性前置判定、Canvas 度量任务设计约束、目标目录结构、
逐文件处置与迁移批次。
[ADR 0053](adr/0053-web-prose-host-consolidation.md)
记录架构决策；本报告只覆盖宿主侧代码的物理拆分与通道收敛，不改变 0053 的任何取舍。

## 1. element.js 现状

`frontend/web/npm/element.js` 共 3441 行，含三段：

| 段 | 行 | 内容 |
|---|---|---|
| 模块辅助 | 1–234 | dispatch 辅助、nextFrame、选择器常量 |
| 帧协调器 | 235–934 | `TiqianLayoutCoordinator`（帧预算、可见性排序、worker 授予） |
| 元素类 | 938–3429 | `TiqianProseElement`：63 个私有字段、69 个私有方法 |

元素类承担 12 类职责：custom element 生命周期与属性反射、运行时模块加载、字体就绪判定、
快照采用与回滚、exact font 会话、布局作业状态机、观察者编排、响应式几何决策、内容漂移检测、
Kotlin 桥、诊断属性写出、custody move 采用。属于 custom element 本体的只有生命周期、属性反射与
custody move。

时序共识靠三个计数器加连接状态：`#generation`（连接代）、`#enhanceRequest`（派发序号）、
`#layoutOperation`（作业序号）。守卫为手写组合：`generation !== this.#generation` 30 处、
`!this.isConnected` 36 处、`operation !== this.#layoutOperation` 9 处、
`request !== this.#enhanceRequest` 8 处，合计 83 处。递增点 8 处，分布在 8 个方法里
（:1076、:1337–1339、:1521–1522、:1550、:1863、:2023、:3082、:3103）。

`connectedCallback` 尾部（1209–1307）是五级 then 链，两级回调体为 async 函数，最内层再定义
async 函数并排入帧队列。成因有二。其一，跨阶段共享的闭包变量无法在不新增字段的前提下拆成方法；
字段表已有 63 项。其二，协调器帧回调只做同步 try/catch，观察不到 async 任务的 rejection，
 rejection 必须在排队点手工路由回失败处理（1183–1190 注释记录了该契约缺口）。

另有死字段：`#resizeFrame`（995）、`#resizeObserverFrame`（997）只声明与清理，从不赋值。

## 2. 观察者分类

分类标准是回调结论的去向。A 类送调度事实给协调器或作业队列；B 类送失效事实给元素状态机；
C 类属引导重试。

| 监听源 | 位置 | 去向 | 类 |
|---|---|---|---|
| root IntersectionObserver | 3282 | `coordinator.update`、`refreshWorkerDeferred`/`clearWorkerDeferred` | A，兼 B（可见性转换调度 commit，3308–3310） |
| paragraph tier IntersectionObserver | 1732 | `TiqianWeb.workerSetParagraphTier`，写 Kotlin 作业队列 | A |
| ResizeObserver（root 与段落） | 2312 | `coordinator.update`（2332）；同时驱动 pre-paint commit 路径（2342） | A 与 B 双职 |
| window 与 visualViewport resize | 2356 | `geometryRevision` 与 retarget，或 `#handleResponsiveGeometryChange` | B |
| typography MutationObserver 与 fonts 事件 | 2714、2729 | rAF 签名对比后 `invalidateSnapshot` 或 `refreshRuntimeFromSource` | B |
| layoutWork typography MutationObserver 与 fonts 事件 | 3008、3052 | 在飞作业守卫：过滤渲染器自有写入，否则取消作业 | B |
| content MutationObserver 与 custody 重定向 | 2790、2806 | taint 与结构信号，经 Kotlin 身份探针判定 | B |
| initialFontRetry MutationObserver 与 fonts 事件 | 1479、1475 | 重启完整连接生命周期 | C |

结构发现：

1. A 类两件双职（root IntersectionObserver、ResizeObserver）。几何与可见性事实应进采样输入，
   commit 决策应进作业治理；拆分时先把双职分开。
2. B 类四件同构：捕获基线、事件到达、对比或判定、失效。typography、layoutWork、viewport 三件
   只差属性表；content 件以身份探针替代签名对比。可收敛为一个失效源接口
   （`start(captured)`、`stop()`、输出 kind 为 geometry、typography、content）。
3. 字体事件监听三组同形，两组属 B，一组属 C。

## 3. 快照根的内容编辑处置

快照采用后内容监听仍在（`#observeContent`，1895 与 2015）。编辑到达后的处置链：

1. 记录分类（2864–2903）：渲染器自有样式写入被过滤；custody 内 characterData 直接 taint；
   顶层 childList 仅挂旗。
2. 身份探针（`tiqian:probe-content-drift`，2944）：Kotlin 按节点身份判定 drift。引擎输出在此
   被排除，只有证明为宿主编辑才继续。
3. commit 路径内容优先（2515）。`#snapshotAdopted` 为真时走
   `#invalidateSnapshotAndEnhance({ restoreBeforeLoad: true })` 后返回（2524–2527）；该分支在
   `#dispatchContentReconcile` 之前，快照根不走逐段 reconcile，逐段路径只在 runtime 活跃时存在。
4. 整根恢复（2025–2034）：语义源 DOM 放回，快照渲染 DOM 移除，runtime 状态销毁，随后完整
   客户端增强（2042）。

丢弃范围：渲染 DOM 整根丢弃。服务端 replay 证据不丢弃；纯快照根首次派发重新拉取证据，
混合根在 `snapshot-ref` 不变时复用同一份表并重新验证（1807–1818）。宽度回到最大版心且
typography 未变时可重新采用快照（2601–2603）。

## 4. 双入口事实

`api.js`（包入口）与方法面直连：`withTiqianRuntime((api) => api.enhance(root, prepared))` 调
`globalThis.TiqianWeb` 的方法。`element.js` 走 document 级 tiqian:* 事件。两条入口进入同一
Kotlin 流水线，且各自持有一套会话与代数状态机：api.js 的 `rootGenerations`/`rootFontSessions`
WeakMap（11–12、37–41、89–132）对应 element.js 的 `#generation` 与 `#exactFontSession`
（964、1789–1859）。

两入口在 Kotlin 侧汇合到同一组 document 监听。方法面本身是语法糖：
`installTiqianGlobalApiBridge`（WebEnhancerSupport.kt）创建 `globalThis.TiqianWeb` 时，五个
方法各自把请求 re-dispatch 为 document 级 tiqian:* 事件（enhance、enhance-progressively、
worker-layout-request、destroy、enhance-all），由 WebEnhancer.kt:52–95 注册的 11 个监听统一
处理；element.js 则直接派发同类事件。管线只有一条，「双入口」实为单管线上的两个转发入口。
element.js 侧的事件派发可以收敛到方法面调用；方法面自身依赖事件总线这件事只能由
Kotlin 直连切片（ADR 0053）改变。

## 5. Worker 必要性前置判定

拆分开始前先确认 Worker 化的技术复杂性值得。判定进批次 0，是批次 1 的开工前置条件；
本节记录计算本体的事实、现有 benchmark 的身份与判定方案。

### 计算本体

Worker 每条消息执行一次 `precomputeParagraph`（layout-worker.js:86）：断行、标点规则、
行调整、字格量化。shaping 不在其中重算。回放数据以整数引用存进二进制站点表
（TIQTBL03），会话初始化时经 `expandReplayShapes` 从表的 string region 拼出一次
（snapshot-manifest.js:141–164）；布局期间的逐 glyph 调用（shapeGlyphId、
shapeGlyphAdvance 等）是注册表 Map 读取与属性访问（browser-font-replay.js:127–133）。

### 现有 benchmark 的身份

| 数据 | 出处 | 实际测的东西 |
|---|---|---|
| 24 段 299.1/308.9/307.6 ms，Wasm 683 ms | ADR 0039:43–46 | Kotlin/JS 对 Kotlin/Wasm 的后端选型基准。Edge「增强的安全性」（JIT 受限）下跑完整 pipeline。证明 JS 后端比 Wasm 快、无 JIT 时引擎本体最坏成本；不含 Worker 或 resize 的成本数据 |
| Firefox width-slider profile（910 次同步 reflow、365ms eventDelay 峰值） | ADR 0039:583 | 主线程路径的提交与守卫粒度问题，不含 Worker 往返 |
| 2026-08-18 Zen profile（拖动 JS 3.1%）与 demo CDP burst 基线 | ADR 0039:692、:708 | 主线程路径优化后的占比与逐帧宽度振荡下的表现 |
| sveltekit 站点 263→55s、neo 137→29s | Slice 39 benchmark matrix | 构建期 precompute，与运行时无关 |

Worker 必要性没有孤立的 benchmark。`ExactWorkerFailureMustStayNative`
（worker-layout.js:251）记录的教训：同步回退在 JIT 受限浏览器上重演滚动停顿。
该事件没有留下每段净成本数据。

### demo/web 的事实

demo/web 无 webfont（index.css:18 系统字体栈）、无快照 manifest，exactFontSession 不成立，
element.js:1668 的 Worker 准备块整体跳过。demo/web 的 resize 从未经过 Worker。它的
主线程路径：`OffscreenMeasureTextShaping`（canvas measureText，浏览器原生实现，不经过
JS JIT）加 `WebCanvasTextShaper` advance 缓存，resize 时 shaping 与度量全部命中缓存，
重跑的只有断行与行调整。demo/web 证明：shaping 便宜且缓存命中时，主线程不需要 Worker。

由此判定缩小为：二进制表查表的回放 shaping 与 canvas 原生度量同属廉价 shaping 来源，
Worker 里剩下的计算只有断行与行调整，即 demo/web 在主线程上跑得快的同一类工作。
Worker 是否值得，取决于这部分计算的成本与 Worker 往返固定开销的差额，按 JIT 状态分开算。

### 判定方案

净成本核对在 node 与 bun 顶层直接调用与 Worker 相同的函数链（这些函数不碰 DOM）。
语料取实际页面的 (manifest, tablesBytes, request) 三元组。两个形态：

1. 表回放形态：`snapshotTablesFromBytes` + `createServerReplayFontSession` +
   `precomputeParagraph`，测每段 p50/p95。node（V8）与 bun（JavaScriptCore）给出 JIT
   散布，`node --jitless` 对齐 Edge 增强安全形态。
2. 缓存重排形态：advance 缓存命中下只重跑断行与行调整（demo/web resize 的计算本体）。

另测 Worker 往返固定开销，按段计：`layoutRequestKey` 的 JSON.stringify
（worker-layout.js:100–101，take 与 issue 各付一次）、postMessage structured clone、
plan JSON 往返。

判定输出三种结论，各自约束目录方案：

| 结论 | 条件 | 对第 7 节目录的影响 |
|---|---|---|
| 全量保留 | JIT 受限形态下每段计算显著高于往返开销 | 维持 engine/web-worker 现方案 |
| 阈值激活 | 小任务主线程更优、大任务 Worker 更优 | exact 路径加段落数阈值，通道文件照搬 |
| 移除 | JIT 开启时表回放每段计算低于往返固定开销，JIT 受限形态由降级（保留原生 DOM）处理 | engine/web-worker 只保留通道文件作归档，exact 路径改主线程执行 |

度量存储同构化（canvas 度量数据同样进二进制表）会让两条路径的 shaping 成本结构
趋同，判定进一步缩小到断行计算与序列化开销。该方向属 ADR 0053/0052 的后续设计，
不在本报告范围。

## 6. Canvas 度量任务

Canvas 度量独立成模块，并作为 Coordinator 的调度对象。本节记录设计约束；实现属
ADR 0053 实施切片，物理拆分先按第 7 节把三处度量代码归位 measurement/。

### 现状

度量散落三处，且对 Coordinator 不可见：

1. 布局切片内部：`WebCanvasTextShaper.shapeWithCanvas` 逐 cluster 调一次
   `measureText`（WebCanvasTextShaper.kt:328–346），advance 不可用时沿 fallback 栈
   逐栈重试（:334–345），`CanvasDomAdvanceParityGate` 失败再走隐藏 DOM range
   （:421–430）。缓存键为 (actualFont, display, features, role)（:420）。
2. font session 准备：browser-fonts.js，异步，独立于切片调度。
3. 字格度量种子：element.js 签名与种子区域。

Coordinator 的帧预算只在任务之间检查（element.js:465–467，首个任务必跑）。Kotlin 侧
progressive job（`tiqian:enhance-progressively` 派发后创建的增强或重排作业）的每个
slice 由 Coordinator 凭证授予，排版循环每排完一段向凭证问一次准入；分工是 Coordinator
决定每帧给多少时间、给谁，排版循环决定这段时间怎么用（ADR 0039 的调度权收归
修订）。`MAX_PROGRESSIVE_SLICE_MS` 与 `MAX_PROGRESSIVE_ITEMS_PER_SLICE` 只是无
coordinator 路径（直调、测试、detach 收尾）的上限。准入检查点在段落之间：冷段落的
度量突发在段落内部不可断，一个段落数百次 measureText（乘 fallback 重试）可以超出
帧预算。

### 设计约束

1. **任务化**：度量成为 Coordinator 的第三类授予对象（现有两类：布局切片与 worker
   授予，element.js:489–492 已共享同一帧预算）。度量 pending 计数进 `pendingByTier`
   同表，复用三级层级与配额批次。
2. **优先级**：同 tier 内度量先于布局。度量是断行的依赖；生效场景是冷启动与字体
   变更后的失效。初始增强已经有 BoundedInitialFontGate（element.js:1217–1232）：
   `waitForTypographyFonts` 只等正文用到的 faces，超时后不转入 fallback 度量。
   增强推迟到字体完成承诺，字体/样式事件重启整个增强流程，期间 native SSR 保持权威。
   settle 后 0–400ms 的集中 relayout 属于 settle 之后允许的首次增强，不重测作废
   度量；后续字体变更走 typography 观察者失效，在飞作业由 layoutWork 守卫取消。
   度量任务沿用同一规则：settle 前不起测。稳态 resize 下 advance 全部命中（advance
   与容器宽度无关），没有度量任务，优先级不参与。
   自管 webfont 的宿主经 FontFace API 注册 faces，CSSOM 里没有对应 @font-face 规则，
   `cssFaceContract`（precomputed.js:1161）按 CSSOM 收候选集就判不匹配。该证据来源
   缺口由 ADR 0053 `DeclaredFaceEvidence` 的声明通道处置，不匹配分类见该决定。
3. **GrantController 接入与检查点密度**：度量任务的停止问题与布局循环同形态。
   Coordinator 发 GrantController（root、generation、Date.now 域 deadline、配额），
   度量批次每个检查点问一次 `shouldStop`。检查点按调用成本分两档：

   | 度量路径 | 成本量级 | 检查点单位 |
   |---|---|---|
   | canvas measureText，fallback 栈逐栈重试每次计一次调用 | 微秒级 | 每 K 次调用一个检查点 |
   | 隐藏 DOM range（`CanvasDomAdvanceParityGate` 失败的回退） | 高一档 | 每次调用后即检查点 |

   K 由第 5 节 bench 的每调用 p95 定，约束是两次 `shouldStop` 之间的全部工作
   （一个检查点区间）不超过帧预算下限 2.5ms 的设定比例，比例与 K 都在批次 0 定下。
   由此给出的保证：任何一帧内度量工作超出授予预算的部分至多一个检查点区间；
   首个区间必然执行（与布局循环「至少提交一段」同规则），每个 slice 度量有推进。
   现状（准入检查点在段落之间）下一个冷段落的全部度量突发是不可分割的超支，
   两档检查点把它压到区间粒度。

   接线形态：admission 经作业上下文传入 shaper。shaping/web-adapter 只问与服从，
   不持有时钟与策略（与 GrantAdmission 的既有注释一致）。检查点读控制器携带的
   deadline，不引入第二条时钟。度量批次按段落 tier 排序，与布局共用层级表。
   度量是独立 pass：批次停止只留下部分填充的缓存，不产生半提交段落。布局 item
   遇缓存 miss 时只做不超过一个检查点区间的内联度量，超出则该段让位给度量任务
   预填，后续 slice 再消费。布局 item 内不再出现整段内联度量突发。
4. **停止机制**：现有停止机制是 GrantController/GrantAdmission
   （WebEnhancerGrantController.kt）：每张凭证携带 root、generation、deadline、
   quota，`shouldStop` 在每个段落边界被问一次；排版循环不持有时钟、策略与身份。
   度量任务复用同一凭证形态，检查点密度见约束 3；单个 `measureText` 调用同步，
   中断点只存在于检查点。不引入 AbortController 作为第二信号形状：字体等待已有
   BoundedInitialFontGate 的完成承诺与事件重启机制，再加一层信号是双份实现。
   停止触发源是字体会话更替与 root 断连；宽度变化不触发停止。
5. **standalone 路径不自制预算**：`standaloneGrantAdmission`
   （WebEnhancerGrantController.kt:44–49）为无凭证 slice 自建 8ms/8 项上限
   （`runProgressiveSlice` 以 `admission ?: standaloneGrantAdmission()` 取缺省值，
   WebEnhancerParagraphLifecycle.kt:92）。无 coordinator 的路径（直调、测试、
   detach 收尾）按 `RunToCompletionWithoutCoordinator` 同步跑完，同步执行中时间
   上限不起让路作用；有协调需求的独立调用方自带 coordinator。执行器自制预算与
   「layout loop owns no clock, policy, or identity」原则冲突，是双份实现。处置：
   ADR 0053 实施切片删除 standalone 准入，standalone 路径仅保留 root 与 generation
   身份守卫。
6. **缓存归属**：advance 缓存归度量模块所有。缓存格式换同构二进制表后，表回放与
   canvas 度量两条 shaping 来源的成本结构收敛（第 5 节判定 bench 的缓存重排形态
   即收敛后的本体）。
7. **停止不丢任务（接续）**：工作集合不是被消费的队列，是不可变清单加游标。一个
   generation 发现的全部度量单元按 tier 顺序构成清单，清单只追加（新段落产生新
   单元）；凭证只携带读位置。完成的单元以缓存条目为进度记录，游标指向第一个未
   完成单元；停止时游标不动，下一张凭证从游标处继续。没有「退回」：单元从未离开
   清单。布局 item 内联度量的乱序完成由缓存命中吸收：游标经过已缓存单元时是
   查表，不产生重复度量。这与布局侧 slice 存活规则同构（剩余段落留在 pending），
   但形态更简：布局 pending 是动态任务池，resize 与失效会产生新任务；度量清单是
   固定发现集，游标只前进。缓存写入按 generation 校验：字体会话更替后在飞的旧
   结果不可写入；更替动作原子地失效缓存、丢弃旧清单，并以新会话的发现集重开
   清单与游标（删除必产生替代任务）。root 断连是唯一删除清单的出口（消费者已
   不存在）。机器可查的保证是计数不变量：同一 generation 下，已缓存数、未走完数
   （清单长度减游标）、在飞数三者之和始终等于清单长度，游标单调前进；该计数在
   时序 golden 的每个 token 迁移点断言（第 11 节）。检查点可以在 cluster 内部
   触发，被中断 cluster 已做的少数调用会重做，上限是一个 cluster 的 fallback
   重试次数（微秒级），任务本身不丢。约束 3 中让位给度量任务的布局段落留在
   布局 pending 队列，由既有 slice 存活规则保证不丢。

## 7. 目标目录结构

```
frontend/web/npm/            @tiqian/prose 拆为 core 与 web-component 两个 npm 包
                              （连同 ffi 包共三个，依赖方向 web-component → core → ffi
                                单向；ffi 包独立发布，见 ADR 0053 A4）
  core/                        独立 npm 包，依赖 ffi 包
    engine/
      coordinator/    帧预算、任务池、授予（现 element.js 235–934）、viewport-anchor.js
                      （渐进提交的视口锚定补偿，coordinator 逐切片 bracket 的过渡策略）
      loaders/        runtime-loader（现 runtime.js）、font-loader（字体就绪判定与重试）、styles（现 styles.js）
      web-worker/     worker-channel（现 worker-layout.js 主线程侧）、
                      worker-entry（现 layout-worker.js）、browser-font-replay.js
      face.js         引擎唯一调用面；内部保留事件派发直至直连切片
      exact-font.js   字体验证会话（element.js 与 api.js 两套状态机合并）
    sampler/          DOM 读侧：observers.js（失效源接口与四实例）、
                      snapshot/（precomputed.js、prepared-dom.js、snapshot-source.js、
                      snapshot-client.js）、signatures.js（四类签名）、
                      grid-metrics.js（段落度量种子）、font-face-boundaries.js
    measurement/
      browser-fonts.js  canvas 度量、度量任务、GrantController 检查点（§6）
    cache/            advance 缓存，归度量所有（§6 约束 6）
    utils/            copy.js（SourceFaithfulSemanticClipboard 的 payload 构建；
                      作用在任何 tiqian 渲染 DOM 上，不耦合 custom element，
                      无 web component 的静态页同样需要，全部集成共享）
  web-component/               独立 npm 包，依赖 core 包
    element.js      生命周期、属性反射、custody move
    diagnostics.js  dataset 协议收拢
```

dom 不再是根级目录：原 dom/ 的文件按职责分为两侧：DOM 读侧（observers、snapshot、
签名与度量种子）归 core 包 sampler，宿主元素生命周期薄层（element、diagnostics）归
web-component 包。viewport-anchor.js 不属于集成薄层：它的自述是
ProgressiveViewportAnchorCompensation：coordinator 逐切片 bracket 的视口过渡策略，
布局真值不动；消费点是 coordinator 的 slot 逻辑（element.js:684、798），归
engine/coordinator/。copy.js 也不属于集成薄层：它构建复制 payload 时清理的是 lowerer
的输出（data-tq-* 属性），Astro 静态页没有 web component 也要用它，归 core/utils/。

core 与 web-component 是两个独立 npm 包，连同 ffi 包共三个，依赖方向
web-component → core → ffi 单向，包间引用只经各包导出面（ADR 0053
ProseCoreLayering）；core 包下设 engine、sampler、measurement、cache、utils，
engine 下设 coordinator、loaders、web-worker。`<tiqian-prose>` 的集成薄层在
web-component 包，与 Compose、Android 平台接入（frontend/compose、
frontend/android-view）及 SvelteKit、Astro 框架集成（ADR 0042）平级；框架集成包
依赖 web-component 包。web-worker 目录的
形态随第 5 节判定结论调整（全量保留、阈值激活或通道归档）。快照表文件
（snapshot-manifest、snapshot-tables、snapshot-table-binary、snapshot-schema、
table-binary-writer）属 ADR 0052 批次，本报告期间不移动。

### TS 代码制度

core、web-component、ffi 三个包的全部 TS 代码适用同一制度，无例外条款（ADR 0053
`StrictTsDiscipline`）：

- 禁止 `any`：显式与隐式都不允许（`noImplicitAny`）。
- 禁止 `as unknown as`：双重断言一律非法。
- 禁止 `object` 类型：`object`、`Object`、`{}` 不得作为类型使用。
- 全部代码强类型：tsconfig `strict: true` 全开，导出签名不得省略参数与返回类型。
- linter 配齐且规则为 error：`@typescript-eslint/no-explicit-any`、
  `@typescript-eslint/ban-types`（宽类型）、`no-restricted-syntax`（双重断言，
  `TSAsExpression` 嵌套 `TSUnknownKeyword` 选择器）。
- 禁止 `eslint-disable`：行级、区块级、文件级、整包级在任何情况下不允许；CI 对
  `eslint-disable` 字符串本身做 grep 检查，出现即失败。

## 8. 逐文件处置

| 文件 | 行数 | 去向 | 动作 |
|---|---|---|---|
| element.js | 3441 | 见区域映射表 | 分批拆分 |
| api.js | 166 | 保留为包入口；会话逻辑并入 engine/exact-font.js | 收敛 |
| runtime.js | 38 | engine/loaders/runtime-loader.js | 移动并改造（返回 facade） |
| worker-layout.js | 268 | engine/web-worker/worker-channel.js | 移动并改造 |
| layout-worker.js | 107 | engine/web-worker/worker-entry.js | 移动；:8 对 `./precompute-runtime/Tiqian-tiqian-ffi-js.mjs` 的相对 import 随目录深度更新 |
| browser-font-replay.js | 201 | engine/web-worker/ | 移动 |
| browser-fonts.js | 684 | core/measurement/ | 移动 |
| precomputed.js | 2325 | core/sampler/snapshot/ | 移动；`__TiqianPreparedDomValidator` 安装点（:1029）随之归位，全局名不变 |
| prepared-dom.js | 821 | core/sampler/snapshot/ | 移动；renderer 桥安装点随之归位（:812）；web-precompute sync:shared 路径同步更新 |
| snapshot-source.js | 262 | core/sampler/snapshot/ | 移动；web-precompute sync:shared 路径同步更新 |
| snapshot-client.js | — | core/sampler/snapshot/ | 移动 |
| lazy-capabilities.js | 235 | engine/loaders/；restoreAdoptedSnapshot 归 core/sampler/snapshot | 拆分 |
| styles.js | — | engine/loaders/ | 移动 |
| copy.js | 179 | core/utils/ | 移动（不耦合 custom element，静态快照页与 Kotlin 桥同为消费方） |
| viewport-anchor.js | 250 | engine/coordinator/ | 移动（coordinator 逐切片 bracket 的视口过渡策略，消费点 element.js:684、798） |
| font-face-boundaries.js | 233 | core/sampler/ | 移动 |
| digest.js | — | 删除（无生产消费者，ADR 0053 UnusedExportCleanup） | 删除 |
| font-contract.js | — | 同上 | 删除 |
| snapshot-manifest.js 等 5 件 | — | 原位 | 暂缓（ADR 0052 批次） |
| build-runtime.mjs、verify-package.mjs、verify-release.mjs、prepare-release.mjs | — | npm 根 | 保留，更新路径引用 |
| api.d.ts、element.d.ts、snapshot-client.d.ts | — | 随同名 JS 归位，根路径重导出保留 | 移动 |
| styles.css | — | npm 根 | 不移动（第 11 节发布约束） |
| package.json | — | exports 与 files 保持根路径重导出 | 兼容转发 |

element.js 区域映射：

| 现区域 | 行 | 去向 |
|---|---|---|
| dispatch 辅助与常量 | 1–234 | engine/face.js 与 web-component 包工具 |
| TiqianLayoutCoordinator | 235–934 | engine/coordinator/ |
| 生命周期与属性反射 | 938–1051、1310–1540 | web-component 包 element.js |
| connectedCallback 的字体就绪判定 | 1209–1233、1462–1505 | engine/loaders/font-loader.js |
| 派发与作业状态机 | 1542–1698、1861–2017 | engine/coordinator/jobs.js |
| worker 接线与 tier 观察 | 1700–1787 | engine/web-worker/ 与 engine/coordinator/ |
| exact font 会话 | 1789–1859 | engine/exact-font.js |
| 快照失效与再采用 | 2019–2246 | core/sampler/snapshot/ 与 engine/coordinator/jobs.js |
| 宽度观察与 commit 路径 | 2277–2704 | core/sampler/observers.js 与 engine/coordinator/ |
| typography 与内容观察 | 2712–3170 | core/sampler/observers.js |
| 签名与度量种子 | 3172–3428 | core/sampler/ |
| 诊断属性写出（散布） | — | web-component 包 diagnostics.js |

Kotlin 侧本报告不动。阶段 5 之前 jsMain 的事件监听注册保持原样；直连与 TsHostRuntime 由
ADR 0053 的实施切片接手。

## 9. globalThis 通道处置

现状全局名十三个（八个桥名、三个 copy 安装名、两个诊断 trace 名），安装点与读取点
（手写源安装其中十个；`TiqianWeb` 与 `web` 由生成物安装；`__tqTrace` 由宿主或测试注入）：

| 全局名 | 安装点 | 读取点 |
|---|---|---|
| `TiqianWeb` | Kotlin 生成物延迟初始化：`installTiqianGlobalApiBridge`（WebEnhancerSupport.kt，生成物 byte ≈580952；守卫查 `__tiqianKotlinBridge` 标记，无标记对象会被整体覆盖）；runtime.js:16–28 随后在其上绑定 9 个 worker 方法（attach、detach、hasJob、jobGeneration、runSlice、pendingInTier、paragraphCount、paragraphAt、setParagraphTier） | 手写源 7 处：runtime.js:14、37；worker-layout.js:212；element.js:1366、1705、1713、1733。api.js 全部经 runtime.js:37 间接 |
| `web` | UMD 包装分支（非模块环境 `n.web=t()`，生成物 byte 152–187） | runtime.js:12 缺省分支 |
| `__TiqianFontBackend` | browser-font-replay.js:98；layout-worker.js:1 与 browser-fonts.js:1 都引入该模块，Worker 与主线程两个 realm 各自安装 | Kotlin 生成物 shaping adapter 逐调用读取（byte 213753–218195，约 30 处：shapeGlyph、metricValue、releaseShape 等）；安装点自检 :92–93 |
| `__TiqianFontBackendRevision`、`__TiqianFontBackendReplayRegistry` | browser-font-replay.js:96–97（伴生版本号与注册表） | 安装点自检 :93 |
| `__TiqianLayoutWorker` | worker-layout.js:158（defineProperty，主线程） | Kotlin 生成物 take/issue（byte 525485–525743，typeof 守卫）；worker-layout.js:148、150；browser-fonts.js:500（release） |
| `__TiqianPreparedDomRenderer` | prepared-dom.js:812（defineProperty；element.js:223 与 api.js:31 的两份 `loadExactFontFallback` 都调 `installPreparedDomRendererBridge`） | Kotlin 生成物 custody render/release（byte 507946–533870，存在性守卫） |
| `__TiqianPreparedDomValidator` | precomputed.js:1029（Object.freeze） | Kotlin 生成物 issue（byte 527911–528129；缺失时得到 "PreparedDomValidatorUnavailable"） |
| `__TiqianCreateClipboardPayload`、`__TiqianInstallCopyHandler` | copy.js:178–179（Kotlin 可回调面，与 `__TiqianPreparedDomRenderer` 同型） | Kotlin 生成物；`__tiqianCopyHandlerInstalled`（copy.js:141）是安装去重旗标 |
| `__tqTrace`、`__tqFrameTrace` | `__tqTrace` 由宿主注入（element.js:509 读）；`__tqFrameTrace` 由 element.js:511 写（`??` 内联赋值，帧级 ring，默认不开） | element.js:505–511 帧循环 trace |

另有 `__tqCustodyEngineWrites`：挂在段落宿主元素上而非 globalThis。增减与读取全部在
Kotlin 侧：WebEnhancerSupport.kt 的 JsFun 体在 custody 写入前后增减（:197、:212、:525、
:529），内容观察者按它过滤引擎自有写入（:483）；element.js 没有引用。

Symbol.for 键控通道另有两个：worker-layout.js:33
（`@tiqian/prose.layout-worker-coordinator.v1`，主线程 layout-worker coordinator 单例）与
browser-font-replay.js:8（`org.tiqian.web.font-replay.<revision>`，replay registry）。Symbol.for
跨 realm 共享注册表，性质与 globalThis 赋值相同，处置随对应模块归位评估。

问题：十三个全局名分属五个方向：JS→Kotlin 调用（`TiqianWeb`）、Kotlin→JS 回调
（`__TiqianPreparedDomRenderer`、`__TiqianPreparedDomValidator`、`__TiqianFontBackend`、
copy 两名）、worker 通道（`__TiqianLayoutWorker`）、UMD 导出（`web`）、诊断
（trace 两名，默认关闭）。element 可在运行时加载前活动，
读取必须可选链；测试需全局 mock（browser-fonts.test.mjs:418–722 整段替换 `TiqianWeb`）；
`TiqianWeb` 由两个安装方先后共管（Kotlin 基座五方法，runtime.js 再绑九个 worker 方法）；
`loadExactFontFallback` 在 element.js:218 与 api.js:26 各有一份。

处置分两段。第一段（批次 5）：`globalThis.TiqianWeb` 的读取点从现有 7 处收敛到两处
（engine/face.js 与 engine/web-worker/worker-channel.js）；`__TiqianPreparedDomRenderer`
与 `__TiqianPreparedDomValidator` 的安装职责随文件移动归入 core/sampler/snapshot/，全局名不变
（读取方是 Kotlin 生成物，改名即断）；两份 `loadExactFontFallback` 合并为
engine/loaders/font-loader.js 的单一入口。第二段（ADR 0053 切片）：`loadTiqianRuntime`
返回 facade 实例，调用方持有模块绑定；runtime.js 已证明 `module.TiqianWebWorkers.getInstance()`
可用（runtime.js:10–13）。`__TiqianFontBackend` 与 `__TiqianLayoutWorker` 的读取方是
Kotlin 生成物（逐 shape 调用、逐作业 take/issue），移除这两个全局必须在 Kotlin 侧把
backend 与 worker 通道改为构造注入，属直连切片，不在本报告批次内。globalThis 挂载
保留一个发布周期后移除，移除时同步更新 verify-package.mjs 的 marker 检查（见第 11 节）。

## 10. 事件通道改回调

对外契约保留：宿主监听的 `tiqian:ready`、`tiqian:relayout-ready`（元素级、冒泡）与
document 级事件在兼容期内照常派发。这两个事件及 `tiqian:error`、`tiqian:relayout-error`
的派发点在 Kotlin 侧（WebEnhancerSupport.kt:544–568，从 root 派发、bubbles+composed），
本报告不动。Kotlin 侧在 WebEnhancer.kt:52–95 注册 11 个 document 监听：enhance、
enhance-progressively、destroy、detach、enhance-all、relayout、reconcile-content、
probe-content-drift、cancel-layout-work、worker-layout-request、refresh。其中 refresh 在
手写 JS 源内没有派发点（宿主扩展位）；enhance、enhance-progressively、destroy、
enhance-all、worker-layout-request 的派发点在 Kotlin 桥方法内部
（`installTiqianGlobalApiBridge` re-dispatch，WebEnhancerSupport.kt:583–610），
worker-layout.js:237 经 `api.workerLayoutRequest(root, element, options)` 进入。

| 事件 | 现派发点（element.js） | 目标接口 |
|---|---|---|
| tiqian:enhance-progressively | 1695 | `face.enhanceProgressively(root, options)` |
| tiqian:relayout | 2239 | `face.relayout(root)` |
| tiqian:reconcile-content | 2978（detail.result 同步回传） | `face.reconcileContent(root, paragraphs)` 返回值 |
| tiqian:probe-content-drift | 2944（detail.result 同步回传） | `face.probeContentDrift(root)` 返回值 |
| tiqian:cancel-layout-work | 3094、3108、3141 | `face.cancelLayoutWork(root)` |
| tiqian:destroy | 1066、1534、2031、2101、2259、3138 | `face.destroy(root)` |
| tiqian:detach | 1362 | `face.detach(root)` |
| tiqian:enhance、enhance-all、worker-layout-request | Kotlin 桥方法内（见上） | 归 face 与 worker-channel 内部 |
| tiqian:refresh | 无派发点（监听在 WebEnhancer.kt:95） | 保留现状 |

实现分两阶段。阶段一（批次 5）：face.js 收拢 element.js 的全部派发点。桥上已有方法
（enhance、enhanceProgressively、destroy、enhanceAll、workerLayoutRequest）可直接改调
方法面；relayout、reconcile-content、probe-content-drift、cancel-layout-work、detach 不在
桥的五方法内，阶段一在 face 内部保留 dispatchEvent 实现，元素与 worker-layout 仍只见
方法。`detail.result` 的同步回传习语共三处（element.js:2944、2978 与桥内
workerLayoutRequest），阶段一在 face 内改为返回值并完成 JSON 解析与失败处理。阶段二
（ADR 0053 切片）：桥方法补齐缺口并改为直接调用 Kotlin 入口，document 监听注册移除。
方法面自身 re-dispatch 事件总线（第 4 节），所以监听移除必须与 Kotlin 直连放在同一批次实施，
不能由 JS 侧单方面完成。

## 11. 行为一致性

原则：每批次一个可独立回退的提交边界；不修改测试期望，测试失败即批次未通过。现有测试
是 60 余个具名 heuristic 的行为记录。

### 时序锚点 golden

重构开始前把全部关键时序锚点严格记录为测试 golden，是批次 0 的必产出；此后每批次
结束 golden 零 diff（与 JVM `LayoutDumpGoldenTest` 的零 diff 纪律同构）。冻结原则：
锚点宁多勿漏，任何一对不上即批次未通过。

| 锚点类别 | 记录内容 |
|---|---|
| 事件派发序 | tiqian:ready、tiqian:relayout-ready、tiqian:error 等的触发顺序与 detail 字段形态 |
| dataset 首写序 | dataset.tiqian* 属性的出现顺序（DOM 属性顺序即首次设置顺序，本身携带时序信息）与值 |
| token 转换点 | `#generation`、`#enhanceRequest`、`#layoutOperation` 的变更点与其后首个守卫判定 |
| 授予轮 | Coordinator 每帧的 tier 计数、批大小决策、DeadlineGate 停点；具体锚：每张 GrantController 凭证的 root、generation、deadline、quota、processedInSlice 终值，以及帧级 trace ring（element.js:505–511 `__tqFrameTrace`，现有机制，默认关闭）逐帧行 |
| worker 消息序 | init/layout/release 的 request id 顺序与 plan 缓存写入删除序；具体锚：layoutRequestKey 的 take/issue 顺序（worker-layout.js:100–101）、preparedPlanKey 的写入与逐出、release(session.id)（browser-fonts.js:500） |
| 缓存失效序 | advance 缓存与 plan 缓存的失效触发源顺序；具体锚：typography 变更（element.js:3094）、几何变更（:3108）、字体会话更替（#releaseExactFontSession → releaseBrowserFontSession）三类触发源作用于 MeasurementKey（WebCanvasTextShaper.kt:420）与 plan 缓存键（请求内容含宽度）的先后 |

虚拟时钟下（performance.now 与 Date.now 双 mock，帧调度确定化）顺序与时长都是确定
值，golden 对到数值；真时钟场景只记字段形态与顺序，不记毫秒值。golden 更新走显式
开关，逐项检查后入库。

| 批次 | 内容 | 验证 |
|---|---|---|
| 0 | 基线与判定：全量测试通过并记录；核对通道清单与 import 图；完成第 5 节 Worker 必要性判定，结论写入 ADR 0053；冻结时序锚点 golden | `nix develop -c npm test`；`./gradlew :frontend:web:jsBrowserTest`；demo/web 测试；node/bun/`--jitless` 净成本 bench；时序锚点 golden 套件 |
| 1 | 纯移动：coordinator、signatures、grid-metrics、styles、copy、viewport-anchor、font-face-boundaries、browser-fonts 归位；.d.ts 随同名 JS；根路径重导出 | 同上 |
| 2 | core/sampler/observers.js：失效源接口与四实例；A 类双职拆分 | npm test；demo/web resize 与 drag 系列 |
| 3 | engine/loaders：font-loader 与 runtime-loader；connectedCallback 收缩到生命周期 | npm test；demo/web |
| 4 | engine/exact-font.js：两套会话状态机合并 | npm test；package.test.mjs |
| 5 | engine/face.js：派发点收拢；globalThis 读取收敛；detail.result 改返回值 | npm test；jsBrowserTest；demo/web |
| 6 | snapshot 四件归位 core/sampler/snapshot/；lazy-capabilities 拆分（restoreAdoptedSnapshot 随迁）；element 快照失效区域（2019–2246）提取；web-precompute sync:shared 路径更新 | npm test；jsBrowserTest；web-precompute parity |
| 7 | A/B 对比：demo/web 以 @tiqian/prose 符号链接替换验证 | demo/web 对比数据 |

批次 7 之后接 ADR 0053 实施切片（任务池、Worker 统一执行、Canvas 度量任务、直连 Kotlin）。

兼容手段与已核实的发布约束：

1. package.json exports 保持根路径，转发到新位置。demo/web 测试的 importmap 写的是字面
   路径（npm-published-vs-dev.test.mjs:225 与 ab/compare-refs.mjs:259 带
   `/frontend/web/npm/` 前缀；framework-commit-conflict.test.mjs:397–398 写 `/npm/`
   前缀，由测试服务器映射到包目录），根路径模块必须
   实际存在；demo/web 生产消费只有 main.js:1 的 `import '@tiqian/prose/element'` 与
   package.json 的 `file:` 依赖。
2. `globalThis.TiqianWeb` 挂载在兼容期保留；宿主可见事件不动。snapshot 表文件五个不碰。
3. verify-package.mjs:14–15 要求产物含 marker `TiqianWeb`、不含
   `__TiqianWebFontShaping` 与 `WebAssembly`（lazy-capabilities.test.mjs:33–59 同样断言
   前者不存在）。marker 是子串级检查：生成物告警字符串里本就含 "TiqianWeb"
   （byte 497683、518842），全局移除后 marker 仍会命中。第二段移除全局时需把 marker
   检查改为真正锚定桥存在的形式，避免检查空转。
4. web-precompute 的 `sync:shared`（其 package.json:67）从 web/npm 根复制
   prepared-dom.js、snapshot-source.js、snapshot-schema.js、styles.css 四件。
   prepared-dom.js 与 snapshot-source.js 移动时在同一批次更新该脚本；snapshot-schema.js 属
   暂缓批次；styles.css 不移动（exports 条目 `./styles.css` 留在根）。

## 12. 验证命令

```shell
cd frontend/web/npm && nix develop -c npm test
nix develop -c ./gradlew :frontend:web:jsBrowserTest
nix develop -c ./gradlew :ffi:js:jsNodeTest
```

demo/web 测试按该目录现有入口运行。layout golden 不涉及：本报告不改 JVM 侧。

## 附录：核对结论

两项研究核对已完成，结论并入正文：import 图与桥创建点（第 8、9、11 节引用的安装点、
读取点计数、sync:shared 与 demo/web 消费数据）；通道清单（第 9、10 节引用的 Kotlin 侧
监听注册、事件派发点、全局读写点）。

dataset.tiqian* 写点计数（收拢到 web-component 包 diagnostics.js 与
core/sampler/snapshot/ 时的验收基准）：

| 文件 | 处数 | 属性名 |
|---|---|---|
| element.js | 38 | tiqianEnhanceMs、tiqianRelayoutMs、tiqianMaxSliceMs、tiqianRelayoutMaxSliceMs、tiqianLoadMs、tiqianFontWait、tiqianCapabilityIssue、tiqianSnapshotCount、tiqianSnapshotMiss、tiqianSnapshotLiveIssue、tiqianExactFontMiss、tiqianExactLayoutIssue |
| precomputed.js | 8 | tiqianSnapshot、tiqianSnapshotFontPolicy、tiqianSnapshotMiss |
| api.js | 3 | tiqianExactFontMiss（与 element.js 的 exact font 会话并存的第三处平行状态，批次 4 一并收敛） |
| styles.js | 1 | tiqianStylesheet（link 元素标记） |
