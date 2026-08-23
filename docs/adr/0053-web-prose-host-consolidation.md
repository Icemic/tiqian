# ADR 0053: Web prose 宿主收敛：装配规格、TS 宿主与统一执行装置

- Status: Proposed
- Date: 2026-08-22
- Relates: [ADR 0039](0039-web-rendering-path.md)（Web 渲染路径与「调度架构弱点记录」一节；本 ADR 给弱点第 1 条处置方向，第 2 条维持开放）、
  [ADR 0040](0040-build-time-web-font-snapshots.md)（构建期字体证据与快照）、
  [ADR 0042](0042-framework-web-integrations.md)（Web 框架集成包）、
  [ADR 0050](0050-native-precompute-rust-bindings.md)（原生 precompute 绑定与 EngineLevelAbi）、
  [ADR 0052](0052-precompute-cache-and-batch-renderer.md)（站级表传输与批量渲染）

## Context

### 引擎单本体与三个消费面

排版引擎只有一份实现（core/font/linebreak/clreq/layout 的 Kotlin 多平台代码），以三个形态被消费：JVM 进程内直调（Compose、Android）；Kotlin/JS mjs（`:ffi:js` 编译产物，浏览器 module Worker 经 `layout-worker.js:8` 静态引入）；Kotlin/Native 静态库经 C ABI（`ffi/native/tiqian_layout_abi.h`，0050 amendment EngineLevelAbi）被 Rust 链接。Rust 侧的 `tiqian-precompute` 是编排层，不含第二套排版实现。

### @tiqian/prose 的构成与两条通道

`@tiqian/prose` 由手写 JS 与两个编译产物组成：

- `runtime/tiqian-web.js`：`:frontend:web` 的 Kotlin 主线程运行时 bundle。jsMain 共 5669 行（WebEnhancer 系列、`DomParagraphRenderer.kt` 与 Overlays 共 1335 行、`MarkdownParagraphLowering.kt` 880 行），配套 jsTest 3981 行行为测试。
- `precompute-runtime/*.mjs`：`:ffi:js` 的 Node 引擎运行时，生产消费者只有 `layout-worker.js:8` 一处静态 import。

宿主与 Kotlin 运行时之间有两条通道：11 个 document 级自定义事件（`WebEnhancer.kt:52-95`）与 `globalThis.TiqianWeb` 桥（`runtime.js:16-24` 挂 9 个轮询方法）。调度分布在两侧：element.js 的 `TiqianLayoutCoordinator` 维护 tier、generation、quota 并按帧发放凭证；Kotlin 内部另有 `ProgressiveJob` 队列与 pending 计数，两侧以轮询对齐。这些 Kotlin 代码包含领域逻辑：段落托管与回滚、渐进任务状态机、段落资格策略、响应式度量稳定化、富文本 run 的浏览器度量降级判定（`ExactSessionBrowserFallback*`，`WebEnhancerSupport.kt:135-157`）。这些逻辑目前无法被其他前端复用。

### worker 一词的三个指称

1. **真 module Worker**：`layout-worker.js`（107 行），只服务 exact-session lookahead 路径。init 必须携带 manifest 字体证据，否则 `LayoutWorkerFontContractInvalid`（layout-worker.js:21-23）。Worker 内没有 shaping：`__TiqianFontBackend` 按规范键查表并做字号缩放（browser-font-replay.js），缺键抛 `MissingServerShapingReplay`，fail-closed。它的职责是用宽度无关的服务端度量证据在任意新宽度重跑断行。
2. **TiqianWebWorkers**：Kotlin 主线程运行时上的轮询接口（`WorkerPolledScheduling`），不创建线程。
3. **无 bake 路径没有 Worker**：没有 exact font session 时 element.js 不引入 worker-layout（element.js:1668），排版在主线程由 `WebCanvasTextShaper` 以离屏 canvas `measureText` 逐 run 探测完成（`WebEnhancer.kt:265`）。

有 bake 与无 bake 解同一个问题：浏览器内有效宽度变化后重跑断行。差别只有两个变量：引擎在哪跑、度量从哪来（回放查表或现场探测）。宽度容差共用 LineLengthGridQuantization（0007/0028）语义，element.js 以 `lineLengthGridMeasure` 复刻签名判断失效（element.js:1857、3366、3411）。

### 渲染器的三份实现

「引擎几何到 paint-only DOM」的绘制规格存在三份实现：

- **DomParagraphRenderer.kt 与 Overlays（1335 行）**：输入活 `LayoutResult` 与宿主元素，建 detached fragment 后一次 `replaceChildren` 换入。持有需要活 DOM 特权的能力：宿主语义元素浅克隆并跨行连续（`ContinuousSemanticFlow`）、SVG 行间线与着重号、ruby/bopomofo span、原子换入。文件内含一块实时度量：`metricsCtx.measureText` 探注音基线（Overlays:170、200；:431 自述 "it is a renderer fallback, not a layout decision"）。
- **prepared-dom.js（821 行）**：同一规格的纯函数重述，输入 plan JSON，输出 HTML 字符串与 artifact 树。间隙载体模型（none、letter、overlap、trailing-letter）、OpenType 特性白名单（`"pwid,palt"` 与 `"fwid"`）、哨兵结构与原实现逐条对应，交叉注释记录同步关系（prepared-dom.js:676、310-315）。
- **prepared_dom.rs（1291 行）**：服务构建期编排的 Rust 移植。渲染发生在并行段内部：批量循环跑在 `std::thread::scope` 原生线程上（parallel.rs 的 `TIQIAN_PRECOMPUTE_THREADS`），每段字体证据经 thread-local 出借窗口回收，引擎回调在调用线程同步重入（precomputer.rs:392-394）；渲染在循环体内（precomputer.rs:314），HTML 与 artifact 摘要嵌入记录流入 manifest/bundle 组装。字节一致由 js_compat.rs 仿真 JS 数字格式化、UTF-16 排序与 Kotlin Float 由 f64 转入 f32 的舍入语义维持。

第三份实现由线程模型决定：渲染必须在并行段内、必须无宿主依赖，JS 版在该位置不可用（论证见 Alternatives）。

### 分散的装配逻辑

FFI 面与平台事实之间存在一层没有 schema 文件、没有单一归属的逻辑，负责把平台事实翻译成引擎可消费的纯数据、把 plan 翻译回宿主形态。四个关注点各有三到四份实现：

| 关注点 | 现有实现 |
|---|---|
| 度量证据会话 | browser-font-replay.js（Worker 回放）；session.rs 与 engine_bridge.rs（Rust 构建）；`HarfBuzzBuildBackend.kt`（ffi/js Node 遗留）；`ExactSessionBrowserFallback*`（主线程降级） |
| 输入装配与传输格式 | `WebEnhancerSupport` 手拼 JSON；worker-layout.js 字段表；PrecomputeWire 分隔符解码；C ABI 二进制缓冲。同一段旅程四种编码 |
| plan 下游消费 | prepared-dom.js、prepared_dom.rs、DomParagraphRenderer.kt |
| 会话治理与能力策略 | element.js、precomputed.js、browser-fonts.js、WebEnhancer* 分散持有 |

ffi/js 自身同时做三件事：PrecomputeWire.kt 在一个文件内做分隔符解码（`\u001e`/`\u001d`/`\u001f`，:29-31）、逐字段校验、`LayoutInput` 组装、断行器选型（:164）与 plan JSON 编码（:168）。采集、schema、编码三层耦合在同一文件，是这层难以描述的直接原因。

### 调度权的分布

主线程有三个互不感知的循环：

1. **TiqianLayoutCoordinator**（element.js:283）：rAF 帧循环，帧预算 2.5-6ms 跟随实测帧距（element.js:415）；回调队列按可见度排序并带防饿死 aging；worker slot 缓存 tier 计数、generation 与自适应 quota（element.js:597-615）；两级凭证发放：每帧 `#pollWorkers`（element.js:762）与 ResizeObserver 回调内的 `grantImmediate` pre-paint 通道（element.js:625）。
2. **worker-layout.js 准备循环**（`prepareWorkerLayouts`，worker-layout.js:205）：按视口距离排序逐段发 Worker 请求，每 8ms 让出主线程（worker-layout.js:86）。协调器不感知该循环的在途请求；该循环没有配额、没有优先级、不占帧预算。
3. **Kotlin ProgressiveJob 队列**（WebEnhancer.kt:652）：由协调器凭证驱动，逐段执行度量、断行、DOM 换入。

真 Worker 的 layout 请求不携带 generation（`LAYOUT_REQUEST_FIELDS`，worker-layout.js:18-32）。过期判定只有主线程的 `isCurrent` 闭包在 await 点丢弃（element.js:1675-1676）；plan 缓存以请求内容为键，宽度在键内，跨代不复用依赖这一点成立。协调器的 `slot.generation` 来自 Kotlin 轮询接口（element.js:777-781），与 Worker 在途请求无关。Worker 按消息到达顺序执行，主线程无法重排或撤销已发出的请求。请求与响应消息里没有优先级信息。

### 0039「调度架构弱点记录」的处置

0039「调度架构弱点记录」第 1 条（调度者与被调度者同线程）仍然成立：fallback 路径的度量、断行、DOM 提交与协调器帧循环全在主线程。第 2 条（取消与预算的最小作用单位是整个段落）也仍然成立：`shouldStop` 在每个段落之后询问（`WebEnhancerParagraphLifecycle.kt:131-135`），检查点未下沉断行循环。本 ADR 给第 1 条处置方向（执行移入 Worker）；第 2 条维持开放，属于未来的治理模型变更。

### 自管 webfont 宿主与 CSSOM 证据缺口

`cssFaceContract`（precomputed.js:1161）把页面 CSSOM 的 @font-face 规则与构建期证据逐字段比对（family、style、weight 区间、unicode-range 集、src local() 列表、解析后的 url、描述符缺省），候选集只从 document.styleSheets 收集。宿主自己管理 webfont 加载是常见做法。sveltekit 站点 的实测：首屏只内联 plexsc-fallback.css（20 个度量改写 faces），用户交互后的空闲期取回 CSS 文本、经 FontFace API 每帧注册 32 个 face 并写 plexsc-ready=1；回访改经 `<link>` 进 CSSOM（432 个 IBM Plex faces），校验通过。FontFace API 注册的 faces 进入 document.fonts，但不产生 CSSFontFaceRule，且 FontFace 接口不暴露 src，采集器无法从 document.fonts 读证据。结果：首次访问在任何浏览器上都判 `SnapshotExactFontContractMismatch:FontFaceContractMismatch`，命中的是命名的 fallback；回访才通过。这是证据来源缺口，与浏览器差异无关。

### 无消费者导出与 shared 副本落后

`@tiqian/precompute` 迁出（d79be52）后遗留：digest.js 与 font-contract.js 没有生产消费者；`compactSnapshotManifest`、`loadedPrecomputedSnapshots`、`validatePrecomputedExactFontReplayRuntimeContract`、`renderPreparedParagraph` 的包装导出只有测试使用。`renderPreparedParagraph` 需要注明：web-precompute 的同名导出是它自己 shared 副本上的包装（`src/precompute.ts:14` import `../shared/prepared-dom.js`），不构成 `@tiqian/prose` 导出的消费者；Kotlin 走 `__TiqianPreparedDomRenderer` 桥（`WebEnhancerSupport.kt:199`）。

shared/ 副本曾落后一个 schema-2 常量批次，ADR 0052 批次补齐后与
`frontend/web/npm/snapshot-schema.js` 逐字节一致（2026-08-22 核对，diff 为空）。
precomputed.js 重复实现 `parseUnicodeRange` 与 `cssWeightPreference`（font-face-boundaries.js 同名）；`cssWeightMatchedFaces` 只在 precomputed.js。同步机制仍是 sync:shared cp 脚本（web-precompute/npm/package.json:67）与 parity 测试，不依赖类型系统。

## Decision

### `HostDeviceTopology`：采样器、协调器、执行装置

宿主重排为三个装置：

- **采样器（主线程）**：读活 DOM，收字体证据，以 canvas 度量按规范键填度量回放表，把平台事实与度量按规格合成为五表记录。
- **协调器（主线程）**：持有唯一带优先级的任务池；帧预算、tier、quota、generation、离屏防抖、pre-paint 通道延续现状；按帧打包收发 Worker 消息；DOM 提交与语义回放作为帧任务执行。
- **执行装置（Worker）**：ffi/js 引擎只在此处运行。输入是五表记录与度量回放表，输出是 plan。

bake 与 fallback 的差别收敛为度量表的填表人不同：构建期 HarfBuzz 或运行时 canvas。执行位置只有 Worker 一处（首批视口段落的 pre-paint 同步快路径除外，见 `ExecutionInWorker`）。

### `SingleEngineFace`：@tiqian/ffi 是唯一引擎出口面

所有 JS 宿主（浏览器主线程、module Worker、Node 构建期兼容面）只经 ffi/js 调引擎。ffi/js 新增浏览器度量后端模式：TS 采集器提供基于 canvas 的度量回调，无快照路径同样经该接口产出 plan。ffi/js 改为字节进出面：线格式解析、校验与 `LayoutInput` 组装移入引擎入口，`PrecomputeWire` 的 parse 函数降为编解码器。ffi/js 改为独立 npm 包单独发包，
包产物导出类型定义与 source map：类型定义给宿主编译期检查，source map 把
宿主侧运行时栈映回源码。

### `AssemblySchemaAsContract`：五表记录的单一规格

装配输出是五张表：text、textSpans、sourceBoundaries、lineBreakSpans、inlineBoxes，外加段落级标量（maxWidth、fontSize、lineHeight、locale、fontWeight、italic、firstLineIndent、gridEnabled）。规格写成一份正式定义（IDL 或同源生成的 TS/Rust 类型）。源语义投影规则（空白折叠投影、样式边界登记、硬断行映射）属于规格定义。两侧采集器对同一输入必须产出逐位相同的记录，parity 语料是该规格的可执行规格。`sourceBoundaries` 语义按 core/TextModel.kt:6-13 保持：无样式范围也必须成为簇边界，保证精确占用几何。

### `MetricTableAsEngineInput`：引擎只读表

`shapeReplayKey` 与 `metricReplayKey` 已定义度量身份。回放表扩展为度量的通用表示：canvas 探测是同一张表的另一个填表人，主线程量一次，按同一规范键写入同一表结构。引擎只认表，不关心填表人是构建期 HarfBuzz 还是运行时 canvas。此决定是 fallback 执行移入 Worker 的前提：Worker 内没有 CSSOM 与 FontFaceSet，度量必须先成为可在消息中传输的数据。

### `ExecutionInWorker`：bake 与 fallback 统一执行位

前置条件：Worker 必要性判定（分解报告第 5 节的 node/bun/`--jitless` 净成本 bench）先行实施，测量在 ADR 0054 回填已实施的工作负载上记录。判定为「移除」时本决定与 `WireFormatPerBoundary` 的 Worker 段、`CoordinatorOwnedDispatch` 的在途窗口与紧急批发送段（任务池、deadline、generation、tier 保留）、`HostDeviceTopology` 的执行装置项与 Consequences 图示、Verification 3 的跨线程协议用例一并修订，修订条款以 ADR 0054 为准，exact 与 fallback 两路径都回主线程执行；判定为「全量保留」或「阈值激活」时本决定按原文生效，demo/web 的主线程反例（度量廉价且全缓存命中时主线程足够）由阈值激活分支吸收。

断行、行调整、plan 生成的引擎执行全部移入 Worker。fallback 路径不再在主线程执行引擎计算，首批视口段落的同步快路径除外：该快路径沿用 `grantImmediate` 的 pre-paint 通道语义，在 ResizeObserver 回调的 pre-paint 窗口内于主线程同步执行（现行 `grantImmediate` 即此形态，element.js:630），覆盖首帧视口内容。主线程其余保留采样、提交与语义回放。非首批段落比主线程同步执行晚一帧收到 plan，经 Worker。度量证据质量不变：fallback 本来就只有 advance 与 ink 级证据，canvas 度量即浏览器实际绘制所用的数据。

### `CoordinatorOwnedDispatch`：任务池、deadline 共识与在途窗口

- **池的归属**。带优先级的任务池只在协调器。Worker 侧是有界在途队列；调度器只有一个，取消、重排、配额都在主线程决定。
- **统一入池**。帧内全部工作（布局切片、度量任务、字体校验重验、DOM 提交与语义回放）经同一任务池与同一凭证形态入队。任何触发源不得自带私有节奏、私有预算或第二条唤醒执行路径；执行器自制预算的 standalone 准入（分解报告 §6 约束 5 的废除决定）与逐批触发全量重验都按此原则排除。负载动态均衡集中在这一个装置与 GrantController 凭证里，不为各类工作分别设预算与唤醒机制。
- **优先级共识用两个标量**。每条请求携带 deadline 与 generation。deadline 用 Date.now 域：两端时钟同源，且与现有 GrantController 的截止时间语义一致（`GrantClockConversion`）。Worker 收件队列按最早 deadline 先执行；generation 与当前作业不符的请求在段落之间丢弃。两侧不需要同步更多调度状态。
- **紧急请求的路径**。`grantImmediate` 打包当帧 deadline 的紧急批立即发送。紧急批的等待上界由在途窗口容量与单段成本决定；窗口按 root 自适应，反馈法沿用 `AdaptiveGrantQuota`：deadline 命中扩窗，超时缩窗。Worker 逐段执行，段间让出消息循环，晚到的紧急批在下一个段间隙重排。
- **回程不参与优先级**。Worker 完成一段即回传。协调器在消息回调内只做 generation 验收与 plan 入库，提交任务交回帧循环，按现有 tier 顺序授予。回程到达顺序不影响提交优先级。
- **plan 缓存**延续现有键语义（请求内容含宽度），入库与读取都校验 generation。

### `WireFormatPerBoundary`：传输按边界收敛、二进制为主

主线程与 Worker 之间每帧每方向一条打包消息：请求侧五表记录按规格派生的二进制编码打包（方向与 snapshot-table-binary 一致），buffer 进 transfer list，所有权随消息转移；表字节（Uint8Array）transfer。不再手拼 JSON 字符串中转。plan 回传第一阶段保持引擎输出的 JSON 字符串（以 Uint16Array 装载并 transfer），二进制 plan 编码随引擎 ABI 演进后替换。FFI 边界沿用分隔符格式（JS）与二进制缓冲（Native），校验归引擎入口。

### `SingleCoordinator`：调度合并与通道废除

单一 TS coordinator。11 个 `tiqian:*` 自定义事件通道、`globalThis.TiqianWeb` 桥与 TiqianWebWorkers 轮询接口废除；废除的是 Kotlin 运行时与 element.js 之间的内部通道，外部触发源不变（document.fonts 事件、ResizeObserver、宿主 API 调用），直接入同一任务池；worker-layout.js 的准备循环与 pending/plans 并入协调器任务池。取消单位保持为段：本 ADR 不改变 0039「调度架构弱点记录」第 2 条的状态。

### `TsHostRuntime`：剥除 Kotlin 主线程运行时

删除 frontend/web 的 Kotlin 运行时层：WebEnhancer 系列、DomParagraphRenderer 与 Overlays、MarkdownParagraphLowering。宿主生命周期逻辑（段落托管与回滚、渐进任务状态机、内容 reconcile、复制保真）改写为 TypeScript，现有 jsTest 套件作为行为规格逐项移植后再删 Kotlin 源。引擎策略（富文本 run 降级判定、dash 能力判定）不跟随迁移，经 ABI 输出决策。

### `SinglePlanLowerer`：绘制规格唯一实现与浏览器后处理

plan-based lowerer（prepared-dom.js 改为接受 plan 对象）是绘制规格的唯一实现。DomParagraphRenderer 删除；活 DOM 特权部分成为浏览器侧后处理：占位符替换式语义克隆（复用 restoreLiveSemanticElements 模式）、SVG 行间线与着重号绘制、注音 span 挂载、原子换入。无 bake 路径同样先产 plan 再 lower，两套渲染合并为一套。本条是先行形态：ADR 0054 的失效合一随后把运行时 lowerer 的输入改为区间表条目加五表文本，plan 对象的消费面退到构建期。

### `ProseCoreLayering`：三包拓扑与集成平级

npm 发布面拆为三个独立包，依赖方向单向：web-component → core → ffi。core 包收
engine（coordinator、loaders、web-worker）、sampler、measurement、cache、utils
子目录，只含 TS 源码与测试，引擎生成物只在 ffi 包。`<tiqian-prose>` 的集成薄层
（生命周期、属性反射、custody、诊断出口）单独成 web-component 包，同样只含 TS
源码与测试，与 Compose、Android 平台接入及 SvelteKit、Astro 框架集成（ADR 0042）
平级；框架集成包依赖 web-component 包。包间引用只经各包导出面，跨包相对路径导入
在编译期失败；分层违反在构建时即暴露，无需等待 F3 的 lint 规则。三个包同次发布、
版本号一致，发布事务沿用既有 npm 多包流程。kotlin-js-store 随 Gradle JS 构建收敛到
ffi/js 后归位。

### `StrictTsDiscipline`：三包 TS 代码的类型制度

去 Kotlin 化产出的全部 TS 代码（core、web-component、ffi 三个包的 TS 面）适用同一制度，
无例外条款：禁止 `any`（显式与隐式，`noImplicitAny`）；禁止 `as unknown as` 双重
断言；禁止 `object`、`Object`、`{}` 类型；全部代码强类型（`strict: true` 全开）。
当前包没有 lint 配置，eslint（typescript-eslint）是新增工具链，配齐对应规则并设为 error：`no-explicit-any`、
`no-restricted-types` 禁宽类型（`ban-types` 已在 typescript-eslint v8 拆分废弃）、
`no-restricted-syntax` 禁双重断言。任何情况下不允许
`eslint-disable`（行级、区块级、文件级、整包级）；CI 对 `eslint-disable` 字符串
做 grep 检查，出现即失败，同时通过 lint 禁止任何形式的 inline type 出现，interface
和泛型内部禁止直接使用花括号撰写类型。

### `DeclaredFaceEvidence`：声明式字体证据与不匹配解释

- **宿主声明通道**：`declareTiqianFontFaces(cssText, options?)`，`options.baseUrl` 指明声明文本的 URL 基准。宿主把自行管理、不会进入 CSSOM 的 @font-face CSS 文本交给采集器；sveltekit 站点 一类的预热流程在注册 FontFace 的同一处调用。注册表是模块级的，不新增 globalThis 名（与 `SingleCoordinator` 的通道废除同向）。`baseUrl` 必须显式传入：从远端取回的 CSS 文本里 src 是相对该 CSS 文件地址的相对路径，构造 sheet 的 `href` 为 null；采集器现有的 `sheet.href || document.baseURI` 回退（precomputed.js:336）会把相对 URL 错按页面地址解析。
- **解析复用且无副作用**：声明文本经与 CSSOM 相同的解析器读取，以 `new CSSStyleSheet({ baseURL: options.baseUrl })` 构造（`baseURL` 是 CSSStyleSheetInit 的既定选项，相对 url() 按它解析）、`replaceSync` 装载后读 rules；构造的 sheet 不采用进 document，不触发字体加载。不支持该选项的环境相对 URL 按 document.baseURI 解析，校验比对按字段不匹配进入 miss，fail-closed 不变。`replaceSync` 抛错（语法错误、@import 触发的 NotAllowedError）按该条声明缺席处理，diagnostic 记 `DeclaredTextInvalid`，detail 携带异常名，两类原因可区分。不支持构造 sheet 的环境降级为构造 detached `<style>` 元素读取其 rules（不接进 document，不触发样式计算）；detached `<style>` 也取不到 rules 的环境同样按声明缺席处理并记录。fail-closed 不受影响：声明只补充校验候选集，后续仍有 `document.fonts.load` 与 advance 几何探测两道独立校验，伪造声明绕不过构建期证据。
- **候选集合并与顺序**：校验候选集 = 声明文本规则加 CSSOM 规则，数组里声明在前、CSSOM 在后。现有挑选用 `findLast`（precomputed.js:1177），后出现的规则胜出；该顺序下 CSSOM 覆盖声明，与「同一 face 以 CSSOM 为准」一致。`BoundedInitialFontGate` 行为不变（仍只等正文用到 faces 的完成承诺）。未声明的宿主行为不变，校验仍 fail-closed。
- **声明唤醒，重验入池**：声明注册表变更时同步通知活跃会话；通知只负责唤醒，不内联执行。既有唤起只有 `document.fonts` 的 loadingdone/loadingerror（element.js:1475-1476、2763-2764）；宿主先完成 FontFace 注册、字体已 loaded 时，之后调用声明不会再有任何字体事件，被动等待会永久停留在 fallback。重验作为任务进协调器任务池：每个 root 至多一个 pending 重验任务，同帧多次声明合并为一次，任务执行前又有新声明只保持 pending；执行时以当前合并候选集整批比对。宿主的分批节奏是宿主侧的自由（sveltekit 站点 每帧 32 个是它的注册步调；本设计不定义该常量），本设计的成本上界来自合并，不来自跟随宿主节奏。
- **不匹配解释结构化**：`SnapshotExactFontContractMismatch` 的 detail 区分两类：候选集为空（EmptyCandidateSet：页面与声明都拿不出可核对的 face）与字段不符（FieldMismatch：给出期望/实际面数与第一个不符字段）。逐字段核对顺序固定为 family → style → weight 区间 → unicode-range → src。`dataset.tiqianExactFontMiss` 保留命中名并携带该 detail；detail 字段形态进分解报告第 11 节的时序 golden。
- **注册表生命周期**：多次调用按追加处理，同一 `(cssText, baseUrl)` 判重跳过并计数递增，注销递减，计数归零才移除记录（两个组件注册同一声明时，单方注销不撤销另一方仍在用的声明）；空串与全空白是 no-op。调用返回注销函数，移除该条声明并同样触发重验；SPA 路由切换与微前端卸载（ADR 0042 框架集成的场景）需要撤走声明。不设 replace 模式，追加加注销已覆盖。

### `UnusedExportCleanup`：无消费者导出删除与 cp 机制删除

删除 digest.js、font-contract.js 与全部 test-only 兼容导出。shared/ 目录的字节拷贝删除：web-precompute 依赖 @tiqian/prose 的正式导出获取 prepared-dom 与 snapshot-source，cp 脚本删除。schema 常量由同源生成，不再依赖人工同步。

### `DualLoweringStance`：prepared-dom 维持双实现

JS 版与 Rust 移植版并存是明确决定：渲染位于原生并行循环内部，thread-local 证据出借要求引擎回调在调用线程同步重入，构建期无法调用 JS（论证见 Alternatives）。代价是持续维护 js_compat 的语义仿真；防漂移升级为两侧共享同一份 golden 语料的 CI 强制比对。若发生实际漂移或 wasm 工具链成熟，可重启「Rust 权威、浏览器加载窄体积 wasm」方案（仅此纯函数，无 DOM 依赖）。

## Consequences

收敛后的形态：

```
采样器（TS 读活 DOM ／ Rust 解析 HTML）→ 五表记录 + 度量回放表
  → 单一引擎面（@tiqian/ffi，Worker 内执行）→ plan
  → 唯一 lowerer → 浏览器后处理 → 提交
度量来源（构建期 HarfBuzz ／ 运行时 canvas）是同一张表的两个填表人
执行位置收敛为 Worker 一处；主线程保留采样、调度、提交
```

正面：管道从四编码三语言双管道收敛为一规格两采样器一执行位；三个调度循环合并为一个；测试面收敛为 TS 宿主测试加共享 golden；同步防护从 cp 脚本升级为类型生成与 CI 比对；5669 行 Kotlin 宿主代码与其编译产物移除，`runtime/tiqian-web.js` 不再存在；Worker 请求获得代际与优先级表示，主线程可按窗口控制可撤销的在途量。

代价与风险：3981 行行为测试需要先行移植为 TS 规格才能删除 Kotlin 源；迁移期新旧栈共存，混合页面必须继续通过 digest 校验与既有集成测试；capability issue 名称是跨语言协议，改名是破坏性变更；无 bake 页面首批视口段落之后的首排延迟多一帧（首批视口段落本身走同步快路径）。

## Alternatives considered

### 保留 Kotlin 宿主运行时，仅合并 JS 侧协调器

拒绝。调度合并只消三个循环里的一个；装配逻辑四个关注点的多份实现、11 个事件加 globalThis 双通道、渲染器三份实现中的两份 JS 侧并存，都随双语言宿主保留。TsHostRuntime 的成本（移植 3981 行 jsTest）是一次性的，双语言宿主的装配规格维护是持续的；引擎策略（富文本 run 降级判定、dash 能力判定）留在 Kotlin/JS 继续无法被其他前端复用，与 `AssemblySchemaAsContract` 的单一规格目标冲突。

### fallback 保持主线程同步执行

拒绝。此前拒绝 fallback 进 Worker 的三个理由在本架构下重新评估。逐段 postMessage 往返延迟：按帧打包后在途请求按窗口批量发送，摊销后剩余代价是首排多一帧，由同步快路径覆盖首批视口段落。取消与失效语义需要跨线程协议：generation 与 deadline 本来就要盖到 snapshot 路径请求上，fallback 复用同一协议，没有新增。canvas 探测需要 document 的 CSSOM 与 FontFaceSet、Worker 内不可得：该约束只要求度量留在主线程；度量成为表之后 Worker 不需要字体语境。原判断的前提是 fallback 进 Worker 等于 canvas 进 Worker；度量归采样器、执行归 Worker 之后该前提不成立。

### Worker 侧第二优先级调度器

拒绝。让 Worker 持有与主线程对等的优先级任务池，两侧需要同步排序状态、交换池快照或实现分布式优先级一致，协议面大且没有对应收益。有界在途窗口加 deadline 排序把共识压缩为两个标量，Worker 侧逻辑保持在很小范围内。

### prepared-dom 单源、构建期调 JS

拒绝。渲染点在 N 个原生线程的并行循环体内，JS 是单线程堆：逐段回调把 `TIQIAN_PRECOMPUTE_THREADS` 对渲染阶段串行化为 1；攒批则因 plan 由各线程并发产出而先串行化。把循环整体搬回 JS 不可行：thread-local 证据出借的存在理由是引擎回调在调用线程同步重入，隔一层 JS 该不变量失效，还需在 worker_threads 重造 scoped-thread 顺序保证与 panic 传播，渲染产物仍须回 Rust 拼 manifest/bundle。

### 大范围 WASM 化（引擎或 HarfBuzz 入浏览器）

拒绝重启。roadmap 记载 Edge 增强安全模式实测曾促使浏览器端 Kotlin/Wasm target 删除、HarfBuzz/WOFF2 WASM 限定在 Node precompute。重提需要新的实测证据。例外是 `DualLoweringStance` 预留的窄范围：仅 prepared-dom 一个纯函数的 wasm 化。

### 第二套 Rust 排版引擎

从未列入。EngineLevelAbi 已确立单引擎原则，Rust 侧经 C ABI 链接同一份 Kotlin/Native 引擎；新增第二实现只会重建三方 parity 负担。

## Verification

1. 五表规格定义后，TS 与 Rust 类型从同一定义生成；两侧采样器在共享 parity 语料（含 prepared-dom-corpus fixture 扩充）上的输出逐字节一致，CI 强制。
2. prepared-dom 双实现的字节比对纳入 CI，golden 语料单一来源存放，禁止两侧各自维护 fixture。
3. 双侧调度协议有独立测试：紧急批晚于后台批发送、先于其完成（优先级反转用例）；generation 不符的请求在 Worker 段间丢弃；在途窗口随 deadline 命中与超时扩缩；打包二进制请求与规格派生编码 roundtrip 一致。
4. TS 宿主重写以现 jsTest 套件为验收规格：同名行为断言逐项移植并通过后才删除对应 Kotlin 源；`LayoutDumpGoldenTest` 全程零 diff。
5. 迁移期间每个中间态满足：snapshot digest 校验照常通过、`verify-package` 与 `verify-release` 通过、已部署站点（blog 参考宿主）浏览器手工检查不回归。
6. 无消费者导出清理与 shared/ 删除各自独立提交，均以全量测试绿与 golden 零 diff 为准。
7. 声明通道有独立测试：声明在前 CSSOM 在后的合并顺序（findLast 语义下 CSSOM 胜出）、`baseUrl` 相对 URL 解析、字体已 loaded 后声明仍唤醒重验（无字体事件场景）、同帧多次声明合并为一个重验任务且执行前新声明不追加、`replaceSync` 抛错按声明缺席处理并记 DeclaredTextInvalid、注销函数移除声明并触发重验、EmptyCandidateSet 与 FieldMismatch 两类 detail 的字段形态各一组用例；dataset detail 进入时序 golden。
8. 三包拓扑有 CI 检查：包间依赖方向为 web-component → core → ffi，该方向之外的包间依赖与跨包相对路径导入使 CI 失败；`StrictTsDiscipline` 的 eslint 与 grep 检查覆盖三个包的全部 TS 代码。

## 执行清单

状态标记：`[ ]` 未开始，`[x]` 完成且验收通过。完成以验收命令与断言通过为准，
不以代码写完为准；勾选时在条目尾注提交哈希，未提交的完成以工作区测试为准。
验收引用本文件 Verification 条号，所列命令为补充；测试命令都在
`nix develop -c` 内执行。每个中间态持续满足 Verification 5。依赖顺序：前置批次
在先；0054 执行清单第一组可与其并行；A、B、C、E、F 组按进程内执行形态实施；
D 组在 0054 执行清单的 54-10（回填）完成后重测判定。B7 先按 plan 对象输入
验收，54-11 实施后输入改为条目。

### 前置：分解报告批次（web-prose-host-decomposition.md §11 表）

- [x] **P0 批次 0**：基线记录、通道与 import 图核对、Worker 必要性 bench 首测
  （判定以回填后重测为准）、时序锚点 golden 冻结。
  KPI：golden 覆盖 §11 表六类锚点，锚点数记录（报告 §11，合计 151）。
  验收：npm test、jsBrowserTest、demo/web 测试全部通过；golden 套件入库。
  提交：b0b9cba、fe7febe、a5de443、35d2571、28c4a19、c0564dc、7b92e8f。
- [x] **P1 批次 1**：八个模块纯移动归位，.d.ts 随同名 JS，根路径重导出。
  验收：同 P0 命令全部通过；golden 零 diff。
  提交：bda3f77。
- [x] **P2 批次 2**：core/sampler/observers.js，失效源接口与四实例，A 类双职拆分。
  验收：npm test；demo/web resize 与 drag 系列。
  提交：6bdcfb3。
- [x] **P3 批次 3**：engine/loaders，connectedCallback 收缩到生命周期。
  验收：npm test；demo/web。
  提交：e861a99。
- [x] **P4 批次 4**：engine/exact-font.js，两套会话状态机合并。
  验收：npm test；package.test.mjs。
  提交：0feaf8f。
- [x] **P5 批次 5**：engine/face.js，派发点收拢，globalThis 读取收敛，detail.result
  改返回值（两份 loadExactFontFallback 已在批次 4 合并）。验收：npm test；jsBrowserTest；demo/web。
  提交：15509f7。
- [x] **P6 批次 6**：快照四件归位，lazy-capabilities 拆分，element 快照失效区域
  提取，sync:shared 路径更新。验收：npm test；jsBrowserTest；web-precompute parity。
  提交：a41ddc4、27525a1（6a）；befa1a3（6b）。
- [x] **P7 批次 7**：demo/web 以 @tiqian/prose 符号链接替换做 A/B 对比。
  验收：demo/web 对比数据记录。对照由 demo/web `npm-published-vs-dev` 套件执行：
  同一浏览器经 CDP 双开两页，一侧加载符号链接农场指向的工作树（含批次 0-6，
  HEAD befa1a3），一侧加载 registry 的 `@tiqian/prose@0.1.0-alpha.5`（重构前
  发布）。2026-08-23 记录：四个相位（initial@900、initial@700、
  after-dom-change@940、after-dom-change@700）两侧增强段落数相同（dev=41
  published=41），initial 相位像素逐字节一致，全程几何报告一致，宿主 DOM
  变更后按引擎真值断言一致；demo/web 全套 35/35。

### A 规格与引擎面

- [ ] **A1 五表规格定义**（`AssemblySchemaAsContract`）：单一 schema 定义文件，
  TS 与 Rust 类型同源生成；源语义投影规则写进规格定义。
  KPI：两侧手写类型文件 0 份；字段与 ADR 清单一致。
  验收：Verification 1 前半；类型生成进 CI。
- [ ] **A2 parity 语料扩充**：prepared-dom-corpus fixture 扩充，两侧采样器对同一
  输入输出逐字节一致。验收：Verification 1 后半，CI 强制。
- [x] **A3 ffi/js 改为字节进出面**（`SingleEngineFace`）：线格式解析、校验与 `LayoutInput`
  组装移入引擎入口，`PrecomputeWire` parse 降为编解码器。
  KPI：ffi/js 内装配逻辑（非编解码）行数归零。
  验收：jsNodeTest 全部通过；golden 零 diff。
  提交：bc19f8c（整体移入 `layout` 模块 `ParagraphWireFace`，`PrecomputeWire.kt`
  删除，ffi/js 只剩导出面转发与会话接线）。
- [x] **A4 ffi/js 独立 npm 包**：单独发包，产物导出类型定义与 source map。
  KPI：.d.ts 与 .js.map 覆盖全部导出面；@tiqian/prose 依赖切换完成。
  验收：包产物检查；消费者构建与测试绿。
  包位于 `ffi/js/npm`（`@tiqian/ffi` 0.1.0-alpha.1）。`generateTypeScriptDefinitions()`
  产出 `Tiqian-tiqian-ffi-js.d.mts`，声明两个 `@JsExport` 函数；九个引擎模块各带
  嵌入 sources 的 `.mjs.map`（dom-api 兼容层无源文件可嵌）。`@tiqian/prose` 声明
  精确依赖 `0.1.0-alpha.1`，`layout-worker.js` 与 worker bench 改从 `@tiqian/ffi`
  导入；开发侧以 `npm run link:ffi` 建符号链接指向工作树。发布工作流
  `publish-ffi.yml` 与 prose 相同，两包同一提交锁步发版，ffi 先发。2026-08-23
  验证：ffi 包测试 4/4，verify:package 通过；prose npm test 183/183；jsNodeTest
  与 jsBrowserTest 通过；两包 tarball 在隔离消费者成对安装并导入；demo/web 35/35。
  module worker 读取不到文档 import map，无打包器宿主无法加载 worker；现有宿主均经
  打包器消费，demo 的两个 import map 服务型用例在服务层把 worker 内对 `@tiqian/ffi`
  的导入改写为绝对 URL。提交：8346920（ffi 包）、b6d9cff（prose 依赖切换）。
- [ ] **A5 度量回放表扩展**（`MetricTableAsEngineInput`）：canvas 探测按同一规范键
  写同一表结构，引擎只认表；无 bake 路径经 ffi 唯一接口产出 plan。
  KPI：度量表示结构份数 1。
  验收：无快照路径端到端 plan 测试；MissingServerShapingReplay 语义不变。

### B TS 宿主重写（`TsHostRuntime`）

统一验收：对照清单同名断言移植通过；对应 Kotlin 源文件删除；npm test、
jsBrowserTest、jsNodeTest 全部通过；golden 零 diff。统一 KPI：对照清单移植覆盖率
100%，对应 Kotlin 文件行数归零。

- [x] **B1 行为断言清点**：jsTest 按主题清点断言，产出原名到 TS 用例名的移植
  对照清单。KPI：清单条目数与 jsTest 断言分组计数一致。验收：清单入库。
  产出：docs/ts-port-assertion-checklist.md（104 条、717 断言；content-reconcile
  主题在现 jsTest 无对应用例，清单留空节说明）。
- [x] **B2 段落托管与回滚**（custody，含 CustodyMoveAdoption 行为）。
  产出：custody 引擎（提交去重、状态快照、原子换入与回滚、
  CustodyMoveAdoption 重连采纳）以 custody.js（382 行）进 npm/core/engine，
  经通用化 gradle 生成器嵌入运行时 bundle；Kotlin 侧删除对应实现 451 行
  （WebEnhancerParagraphLifecycle、ParagraphPipeline 与 Support 的 custody
  段），WebEnhancerCustodyBridge.kt 桥接调用。断言经 f13233b 移植
  （custody-bridge.test.mjs，347 行）。提交：f13233b、7140d88、f92bfbe。
  验证：npm test 与 jsBrowserTest 在提交后历次批次运行与当前树复验
  （315/315 两次）均通过。
- [x] **B3 渐进任务状态机**（ProgressiveJob 队列与 pending 计数）。
  产出（2026-08-23）：断言半段 9af4470（progressive.test.mjs 456 行，
  runtime-host.mjs 补 worker 作业驱动与滚动几何助手）；实现半段 e04daa7，
  状态机整体迁入 `npm/core/engine/progressive-job.js`（302 行 plain script，
  经 generateProgressiveJobBridge 嵌入运行时），按 custody 模式经
  `WebEnhancerProgressiveJobBridge.kt` 调用。作业注册表、generation、
  tier 跟踪与 pending 计数、切片执行、stale 守卫、parking、完成与失败
  迁移全部在 JS；作业构造（候选、measure 快照、引擎闭包）与事件派发留在
  Kotlin（WebEnhancerProgressiveJob.kt 的 startProgressiveJob helper 与
  finish/fail 回调）。`TiqianWebWorkers` 签名不变，方法改为一行桥转发；
  WebEnhancer.kt 删 progressiveJobs/generation/workerRoots 与
  ProgressiveJob/Kind 类，relayout 头部改经 jobKind 加 states WeakMap 取
  运行中作业（enhance 在 startProgressiveJob 前 states.set，两者同对象）。
  commitSkipped 无写者，完成事件的 stale 不再含常假臂（CommitSkippedRemoved）；
  MAX_PROGRESSIVE_IDLE_ITEMS_PER_SLICE 无消费者，一并删除。嵌入式单测 9 条
  （npm/progressive-job-bridge.test.mjs），覆盖 tier 门槛跳过后作业保持
  打开、stale 跳过、过期 generation 拒绝与 detach 当场跑完。jsTest 两个测试类的内部 harness
  改调 TiqianWebWorkers 对外方法（原 internal 扩展函数已删）。
  验证：npm test 324/324 两次（产物刷新前后各一次）；
  assembleNpmPackage 与 jsBrowserTest 通过；grep 复核 progressiveJobs、
  progressiveJobGeneration、workerRoots、GrantAdmission、
  PROGRESSIVE_TIER_COUNT、jobPayload 在 Kotlin 源内 0 命中。
- [ ] **B4 段落资格策略与响应式度量稳定化**。
  进度（2026-08-23）：资格策略半段已完成。三个资格谓词
  （shouldTryParagraph、isPureBlockImageParagraph、hasOpaqueInlineCandidate）与
  常量（NON_TEXT_INLINE_TAGS、OPAQUE_INLINE_DISPLAYS、SKIPPED_ANCESTOR_SELECTOR）
  迁入 `npm/core/engine/eligibility.js`，按 custody 模式经通用化 gradle 生成器
  嵌入运行时，Kotlin 侧与 MarkdownParagraphLowering 经
  `WebEnhancerEligibilityBridge.kt` 调用；嵌入式单测 6 条
  （npm/eligibility-bridge.test.mjs）。响应式度量半段依赖管线度量助手
  （effectiveLineMeasure、sourceParagraphWidth 属 WebEnhancerParagraphPipeline），
  随 B7 管线批次迁移。提交：57f3f70。
- [x] **B5 内容 reconcile**。
  产出（2026-08-23）：断言半段此前已随行为测试批完成；实现半段 4c08198，
  分类（dead、drifted、custody、tainted、stranded 与
  StrandedCapabilityNoRetry 门槛）、只读探针 probeContentDrift 与两个 DOM
  准备助手（HostEditRelowering、CloneDescaffoldEngineMarkup，含
  EngineInlineStyleStrippingOnClone）迁入
  `npm/core/engine/content-reconcile.js`，按 custody 模式经
  generateContentReconcileBridge 嵌入运行时，Kotlin 侧经
  `WebEnhancerContentReconcileBridge.kt` 调用；WebEnhancerContentReconcile.kt
  只保留作业组装、段落跟踪与引擎动作（processParagraph、托管恢复），JSON
  判定串由 JS 侧拼接返回，与原字符串逐字节一致。嵌入式单测 6 条
  （npm/content-reconcile-bridge.test.mjs）。动作执行、视口距离排序与作业
  启动留在 Kotlin，随 B7 管线批次迁移。
  验证：npm test 330/330（含 content-reconcile 行为测试 9 条与嵌入式
  单测 6 条）；assembleNpmPackage 与 jsBrowserTest 通过；grep 复核
  prepareTrackedParagraphForRelowering 与
  stripEngineMarkupFromStrandedParagraph 在 Kotlin 源内除桥定义与调用点外
  0 残留；content-reconcile.js 嵌入方言检查（可选链、空值合并、展开、
  for-of、裸 catch、美元符、三引号）0 命中。
- [x] **B6 复制保真**（copy.js 投影语义保持）。
  产出（2026-08-23）：断言半段 4e3d3f7（copy-fidelity.test.mjs 经 runtime-host
  驱动运行时的 copy 事件）；实现半段 d733286，`npm/core/utils/copy.js` 改为
  plain script，安装 globalThis.__TiqianCreateClipboardPayload 与
  __TiqianInstallCopyHandler，按 custody 模式经 generateCopyBridge 嵌入运行时，
  Kotlin 侧经 `WebEnhancerCopyBridge.kt` 在原有三个调用点安装，
  WebEnhancerSupport.kt 内 133 行重复实现删除；api.js 与 element.js 改为
  副作用导入后调用全局安装函数，根 copy.js 保留名导出维持既有导入面；
  TiqianWebCopyTest.kt 删除，断言由 copy-fidelity.test.mjs 承接。Kotlin raw
  string 与 JsParser 不接受可选链、空值合并、展开实参与 for-of，copy.js 按
  嵌入方言重写：可选链与空值合并展开为显式条件，展开实参改 apply，for-of
  改下标循环，语义逐处等价。
  验证：npm test 324/324；assembleNpmPackage 与 jsBrowserTest 通过两次。
  jsTest 世界没有 npm 模块导入，处理器此前由内联重复实现提供，删除后
  15 个 copySelection 断言失败，嵌入后 0 失败。grep 复核
  installTiqianCopyHandler 在源内仅余 copy.js 定义、根 copy.js 名导出与
  copy-fidelity.test.mjs 注释。
- [ ] **B7 lowerer 统一**（`SinglePlanLowerer` 先行形态）：prepared-dom.js 改接受
  plan 对象，成为绘制规格唯一实现；DomParagraphRenderer 删除。
  产出（B7.1，2026-08-23）：f54e869，`toPreparedParagraphJson(renderEvidence)` 以
  默认省略的可选字段追加 cell 级与段落级绘制证据；默认路径字节不变，schema 仍为
  1，两个既有读者（prepared-dom.js、tiqian-precompute plan.rs）按字段名读取、
  容忍未知字段，无需改动。`:layout:jvmTest`（含 LayoutDumpGoldenTest 零 diff）与
  `:frontend:web:jsBrowserTest` 通过。B7.2 起改 prepared-dom.js 读取这些字段。
  产出（B7.2，2026-08-23）：ebb65df。prepared-dom.js 读取 B7.1 的证据字段：dash 属性
  （strategy/advance/font-family/face/glyph-ids/evidence 与 lang）、标点 ink-floor 与
  body-width、样式增量（font-size/weight/style 带 important）、latin 在着重范围内补
  italic、行内对象占位 span（data-tq-inline-object="pending" 加 object-range）、
  ruby/bopomofo 注文 span、SVG 行间线与着重圆点；断 run 与合并键扩展（dash run 不合并、
  样式与标点签名、italic、证据字体）。行构造改为按 children 有序遍历，行内对象与注音
  边界先冲刷 pending run；流宽校验改按 children 求和，bopomofo 占用 base 的松量
  （非末字 max(布局尾隙,0)，末字 max(advance−naturalWidth−行内边,0)，有注音时 base
  尾隙归零）。plan.inlineEdges 非空时优于 options.inlineBoxes。ruby ascent 无画布取
  fontSize×0.8 的降级值，B7.4 重估是否入 plan。为守 1000 行约束拆出
  prepared-dom-markup.js（8 个字符串标记构件，纯移动）与 prepared-dom-evidence.js
  （注音、占位与覆盖层构件）。验证：npm test 349/349（新增 10 条，含无证据精简输出、
  dash 属性、标点属性与断 run、样式增量、latin 着重 italic 正反例、占位流宽、ruby
  比例 ascent、bopomofo 松量与声调字号公式、覆盖层、inlineEdges 优先级）；
  `:frontend:web:jsBrowserTest` 通过。
- [x] **B8 浏览器后处理**：占位符替换式语义克隆、SVG 行间线与着重号、
  ruby/bopomofo span 挂载、原子换入。SVG 行间线与着重号、ruby/bopomofo span
  已随 B7.2 进入 lowered HTML（ebb65df）。
  产出（克隆换入原语，2026-08-23）：45efc96。占位 span 在尾隙绝对值 ≥ 0.01 时
  增加 data-tq-object-trailing-margin（值为该尾隙）；renderPreparedParagraphInto
  在 innerHTML 换入后把 data-tq-inline-object="pending" 占位替换为调用方经
  options.inlineObjects 按 start-end 对位传入的源元素深克隆，克隆带
  data-tq-inline-object="true" 与 data-tq-object-range，尾隙存在时写
  margin-right = marginRight + 尾隙（important），与
  DomParagraphRenderer.appendInlineObject 逐项一致；缺源抛
  InlineObjectSourceUnavailable，重复区间抛 ConflictingInlineObjectRange，
  无占位的传入项忽略（与 Kotlin associateBy 后未被命中的条目同行为）。
  调用方接线未动：workerLayoutRequest 仍排除含行内对象的段落，exact 运行时路径
  仍限于 canonical plain 段落，两条路径都到不了含行内对象的 plan，接线随本项
  原子换入一起改（移除该排除，把 domInlineObjects 的元素与 margin 经桥传入）。
  产出（运行时 exact 路径扩展到富段落，2026-08-23）：exact 运行时路径的
  renderPreparedParagraphDom 桥接收与 Worker 换入桥同形的 options：sourceSpans 以
  sourceIndex（列表序）加 order（嵌套深度，并列区间的 tie-break）序列化为
  live-source semantics，元素数组同序传入；domInlineObjects 以
  {start, end, marginRight} 元数据配对元素数组传入，换入原语按区间对位深克隆。
  纯段落两条数组皆空，桥传 undefined options，渲染输出与扩展前逐字节一致。
  准入从 isCanonicalPlainParagraph 放宽为 isRuntimeExactPreparedDomEligible
  （排除 decorations、clone 边缘装饰与 locale 不匹配 span，与 Worker 请求的排除
  项一致但不再排除行内对象）；富段落照旧走 semanticExactEngine 的逐 run 降级，
  严格 exact session 仍只属于纯段落；plan 对富段落开 renderEvidence（逐 cell
  renderFontFamily、inlineObject advance、inlineEdges）。canonical-plain 属性仅对
  纯段落设置，富段落只带 canonical-source，重排经活克隆重降。Worker 请求的
  行内对象排除与 domInlineObjects 经 Worker 桥的传入属下一步（B8.2）。
  产出（Worker 路径行内对象接入，2026-08-23）：workerLayoutRequest 撤销行内对象
  排除，其余排除项与 isRuntimeExactPreparedDomEligible 对齐。请求线新增
  inlineObjects 字段：每条记录五段（start、end、advance、ascent、descent），分隔符
  与既有 wire 相同，经 ParagraphWireFace 解析进 LayoutInput；ascent 与 descent 为
  必填，行内对象的逐行度量扩张由引擎按值计算。缓存键（LAYOUT_REQUEST_FIELDS）
  纳入该字段，advance 变化即新键。plan 的 renderEvidence 判定加入行内对象非空
  （无 span、无行内盒、无行内对象的纯段落 plan 与扩展前逐字节一致）。
  ffi 的 precomputeParagraph 增补第 16 个可空参数：旧 15 参 JS 调用方缺参得到
  undefined，Kotlin 侧以空串补齐，包版本错位双向兼容。commit 桥
  renderPreparedWorkerParagraphDom 与运行时桥同形：元素数组配 {start, end,
  marginRight} 元数据，换入原语按区间对位深克隆；worker 分支的 canonical-plain
  判定从 sourceSpans.isEmpty() 改为 isCanonicalPlainParagraph()，行内对象段落
  重排时重测对象，不再误标纯段落。
  产出（着重圆点颜色、cjk-emphasis 标记与 dash 面校验进 prepared 路径，
  2026-08-23）：5dc7ec1。着重圆点颜色在渲染时解析：取覆盖该 cluster 且 order
  （嵌套深度）最大的 semantic 的 getComputedStyle 颜色，无命中回落
  currentColor，与 native 渲染器 sourceSpans.maxByOrNull{depth} 的取值一致；
  无 live 元素时圆点维持 currentColor，artifact 输出字节不变。npm 侧新增
  cjkStrongSemantics 选项：按区间相等匹配 semantic，artifact 分支写
  data-tq-cjk-emphasis 与 font-weight important，live-source 分支在
  restoreLiveSemanticElements 换入克隆时补同样的标记与样式。校验器新增
  renderedDashFaceIssue：[data-tq-dash-font-family] 的 computed font-family
  与属性值比对（首个逗号前 token、去引号、小写比较），不一致时报
  RenderedPreparedParagraphDashFaceMismatch，位于段 advance 校验之后。npm test
  360/360（新增着重圆点颜色、深度优先、cjk-strong 标记与 dash 面四组）。
  产出（cjk-strong 线接线与装饰段落准入，B8.3b 首批，2026-08-23）：
  preparedCjkStrongSemanticsJson 从带 cjkStrongBaseWeight 的 sourceSpans 序列化
  {start, end, weight}，经运行时桥与 Worker 换入桥的 options.cjkStrongSemantics
  传入，上一批的 npm 侧由此接通。isRuntimeExactPreparedDomEligible 撤销
  decorations 排除：装饰段落的 plan 无条件序列化 decorationSegments 与
  emphasisDots，运行时 LayoutInput 本就携带 decorations，富段落照常开
  renderEvidence，commit 走 prepared 分支。Worker 请求仍排除装饰段落：请求线
  没有装饰输入字段，此类段落主线程降层后经同一 prepared 桥提交。新增 jsTest：
  exact 会话下 strong-as-emphasis 段落经 prepared 路径，semantics 与 cjk-strong
  线、emphasisDots、canonical-source 与复制保真逐项断言。无 strongAsEmphasisMarks
  时 decorations 为空，默认页面行为不变。
  产出（全段落 prepared 路由与 native 渲染器删除，B8.3c，2026-08-23）：
  DomParagraphRenderer.kt 与 DomParagraphRendererOverlays.kt 删除，运行时不再
  保有第二份绘制路径。prepare 阶段 fail-closed：桥缺失报
  PreparedDomBridgeUnavailable（detail 记 expectedLayoutRevision），locale 不匹配
  span 报 SpanLocaleMismatchUnsupported（detail 记 spanRange、spanLocale、
  paragraphLocale）。isRuntimeExactPreparedDomEligible 只再排除 locale 不匹配
  span：单行克隆边缘装饰经 plan 的 inlineEdges 重放，跨行的仍由布局后的
  InlineCloneDecorationBreakUnsupported 拦截；workerLayoutRequest 维持自身更严的
  排除清单（请求线没有行数信息）。commit 校验失败时先摘属性再分支：exact 会话
  度量且浏览器回退引擎可用则以浏览器度量重排并仍经 prepared 桥重放（重试传
  browserFallbackEngine=null，递归深度有界）；否则报 PreparedDomRenderMismatch
  Unsupported，段落保持输入原样。RootState.preparedDomEnabled 只表示 exact
  会话度量是否可信，段落绘制一律走 prepared 桥。
  validatePreparedParagraphDom 的校验器全局只在测试环境存在，生产缺失时返回
  null（PreparedDomValidatorIsTestOnly），不再判每个段落为
  PreparedDomRenderMismatch。plain host 的
  桥安装走 font-loader 的 ensurePreparedDomBridge（memoized，槽位已被占用时让位
  给已装实例），element.js 的 enhance 派发与 api.js 的 withTiqianWeb 等待它。
  demo 页经 gradle Sync 把 snapshot-schema.js 与四个 snapshot 文件按相对路径
  装进资源，index.html 的 module script 自装桥后派发
  tiqian-demo-prepared-bridge-ready，Main.kt 的首次布局等该事件。测试迁移：
  karma 102/102（run-spacing 两测删除，npm 双生覆盖同一行为）；npm 366/366，
  installPreparedRendererFixture 从 exact fixture 抽出并装进 loadHostRuntime，
  五项断言迁到 prepared 词汇（data-tq-engine-hyphen 选择器、首缩进的
  line-shift 加 --tq-line-flow-start、着重号字重落在 strong 本体、currentColor
  按 color 链解析、超脚本行宽自检改行标记双属性）。demo 页浏览器复测：可见
  段落全部 prepared 渲染，基准段 24/24，含 CJK dash 的 1 段按
  NoConformingCjkDashGlyph 保持输入原样。
- [ ] **B9 MarkdownParagraphLowering 迁移**（880 行）。
- [ ] **B10 引擎策略出 ABI**：富文本 run 降级判定与 dash 能力判定经 ABI 输出
  决策，不迁 TS。验收补充：策略行为与现行判定逐例一致（jsTest 对应组）。

### C 调度合并（`SingleCoordinator`）

- [x] **C1 通道废除**：11 个 `tiqian:*` 事件、`globalThis.TiqianWeb` 桥（9 个轮询
  方法）、TiqianWebWorkers 轮询接口删除，外部触发源直入任务池；verify-package 的
  marker 检查改为锚定桥存在的形式。
  KPI：`tiqian:` 派发点 0；globalThis 挂载 0。
  验收：grep 双零；verify-package 与 verify-release 通过；时序 golden 更新后零 diff。
  产出：`TiqianEngine` JsExport 面（11 个入口方法）成为宿主到引擎的唯一调用面。
  事件监听、`installTiqianGlobalApiBridge` 与 `__tiqianKotlinBridge` 自运行时删除，
  `install()` 只安装复制处理器；element.js、worker 通道、node 宿主与测试经
  `core/engine/face.js` 与 `runtime-loader.js` 直调，测试以 `setEngineOverride`
  替换引擎。时序 golden 以 `engineCalls` 记录替代 3 个内部事件；断言清单登记
  engine-api 3 条（docs/ts-port-assertion-checklist.md）。verify-package 的 marker
  改为锚定 `TiqianEngine`；verify-release 的隔离消费者改为成对安装工作树打出的
  `@tiqian/ffi` tarball，与锁步发版同源。TiqianWebWorkers 轮询接口删除与外部触发
  直入任务池在 C2/C3 中完成：element.js 的授予循环是该接口的现行消费者。
  验证：运行时 grep，11 个内部事件名与 globalThis 挂载均为 0（`tiqian:` 剩余命中为
  element 级公开事件 ready/relayout-ready/relayout-error）；npm test 307/307 两次；
  jsBrowserTest 通过；verify-package 与 verify-release 通过；golden 更新后零 diff。
  提交：584e083、0d16190、92eca33、262fffa、f3a72c2。
  消费者核对（2026-08-23 补记）：demo/web 全套 31/35。四项失败在 15d8d66（A4 提交）
  与 f13233b 的净检出 worktree 复测中逐字节相同，C1 与 B4c 的提交不引入这些失败；
  此前两次 35/35 记录产生于含未提交改动的夜间工作树。两项按如下处置：
  resize-destroy 的 relayout 计数从已删的 `tiqian:relayout` 迁到 element 级
  `tiqian:relayout-ready`（事件记录器同时从事件 target 取 root），迁移后该套件
  10/10。oneshot-equivalence 的选项捕获与 one-shot 重放仍依赖已删的
  `tiqian:enhance-progressively`/`tiqian:enhance` 通道，公开面上没有逐 root 已解析
  options 的读取口，待公开 API 决定后迁移。late-enhance 第 3 条暴露既有缺口：页面
  内禁用后启用的增强在单次同步提交内完成，coordinator 的逐切片视口补偿从未
  触发（scrollTop 拦截为零次写入），滚动位置完全由浏览器原生 scroll anchoring
  承担；无动画时原生承担方向正确、入场动画进行中会反向跳变（scrollY 5022 到
  5911，锚点移动 1006.69px）。该缺口属调度路径（增强未经授予路径），随 C2/C3
  处置。
- [x] **C2 任务池统一入池**（`CoordinatorOwnedDispatch` 进程内段）：帧内全部工作
  经同一池与同一凭证；executor 私有节奏与 standalone 准入排除。
  验收：时序 golden 授予轮锚点更新后绿。
  产出（C2a，提交 696975b）：worker-channel 准备循环删除自有节拍
  （MAIN_SLICE_BUDGET_MS 与 yieldMainIfNeeded），逐段向 coordinator 申请共享准入；
  grantImmediate 的立即窗口提取为 #admitMainSlice，prepaint 与 prepare 两条帧外
  主线程路径共用同一份额；凭证加 `lane` 字段（polled 授予 grant、pre-paint 授予
  prepaint），帧 trace 末列记录窗口已花费；准备循环只把同步段计入窗口
  （SyncOnlySliceAccounting），worker 往返等待不计入。element.js 传入
  coordinator 单例；browser-fonts 与 timing-golden 的通道测试改传总是准许的池。
  产出（C2b，提交 14be60f）：standaloneGrantAdmission 与两个上限常量删除，无
  coordinator 的直调宿主作业一次跑完；宽度正确性由 processItem 内逐条 measure
  守卫承担，切片头部 stale 检查保留为协调路径的中途放弃。
  验证：npm test 313/313 两次（两批各自跑）；jsBrowserTest 通过（两批各自）；
  golden 仅加 `lane` 字段与 trace 末列（39 行插入），event-dispatch、
  token-transitions、dataset-first-writes、cache-invalidation 四条 journey 零
  diff；grep MAIN_SLICE_BUDGET_MS、yieldMainIfNeeded、standaloneGrantAdmission、
  两个上限常量均 0 命中。demo/web 的 scroll-adaptive-quota 由失败转为通过
  （滚动驱动增强期间最大事件循环延迟 257.40ms 降到 4.10ms，覆盖 36/36）。
- [x] **C3 worker-layout 准备循环并入**：pending/plans 进协调器任务池，准备循环
  删除。KPI：主线程调度循环 3 收敛为 1。验收：npm test；jsBrowserTest。
  产出：worker-channel 导出 createPrepareJob(root, exactFontSession, options,
  isCurrent)。异步段完成守卫、API 检查、会话核对、候选排序与 ensureSession
  等待，返回带 done、settled、step(shouldYield) 的作业对象；step 同步构造
  请求并发送，发送不等待回复，回复在自身微任务里写入计划并递减在途计数，
  全部候选应答后作业完成。coordinator 新增 runPrepare：注册作业并返回
  promise，#pollPrepare 排在 #pollWorkers 之后按同一帧预算推进，每个作业
  每帧送达至少一个候选；worker 回复经 onSettled 再次启动帧循环；unregister
  与 remove 经 #cancelPrepare 完成成员。element 在 enhanceProgressively 前
  等待 runPrepare，派发顺序不变。审查补入 CancelledPrepareSettlesEarly：
  isCurrent 失效且候选未尽时作业以已存计划当场完成并删除该成员，消除
  relayout 顶替增强后成员滞留、帧循环持续保持的路径。
  KPI 复核：prepareWorkerLayouts、yieldToMain、grantPrepareSlice、
  yieldMainIfNeeded、MAIN_SLICE_BUDGET_MS 源内 0 命中；scheduler.yield 仅存于
  快照采样器（precomputed.js；快照采样属独立子系统）；准备路径的私有循环
  并入完成，主线程调度循环 3 收敛为 1。
  验证：npm test 315/315 两次；jsBrowserTest 通过；时序 golden fixture 零
  diff；demo/web 全套顺序执行 34/35，其中 resize-destroy 10/10、drag 3/3、
  late-enhance 3/3；唯一失败 oneshot-equivalence 为 C1 补记登记的遗留项，
  待逐 root 已解析 options 的公开读取口决定后迁移。
  late-enhance 第 3 条（C1 补记移交本批）修复与测量教训：run-to-completion
  增强与 relayout 派发原先在锚点补偿范围外，element 调用点加同任务
  capture/compensate 对并持有原生锚定（RunToCompletionAnchorBracket）；快照
  采纳的逐段提交经 anchors 参数取得同任务对
  （SnapshotAdoptionAnchorCompensation）。修复后锚点段落位移 1038.61px 降到
  0.61px。demo 断言纪律：demo 的 parcel 缓存在更换服务器后仍提供旧
  transform，三个代码状态测得逐字节相同的失败值即由此产生；跑 demo 断言前
  须停止 8888 服务器并删 demo/web/.parcel-cache。

### D Worker 判定与执行位（依赖 0054 的 54-10）

- [ ] **D1 判定重测**：node、bun、`--jitless` 净成本 bench 在回填后负载重跑，
  结论（移除、全量保留、阈值激活）记录。
  KPI：判定数据按分解报告 §5 模板记录。
  验收：0054 Verification 5 末句即本项。
- [ ] **D2 执行位实施**（按 D1 结论二选一）：
  - 移除：exact 与 fallback 回主线程；修订清单（`HostDeviceTopology` 执行装置项
    与 Consequences 图、`ExecutionInWorker` 全条、`WireFormatPerBoundary` Worker 段、
    `CoordinatorOwnedDispatch` 在途窗口与紧急批发送段、Verification 3 跨线程用例）
    随判定提交。验收：修订后全量测试绿。
  - 保留：执行移入 Worker，首批视口同步快路径保留；deadline 与 generation 双标量、
    在途窗口、紧急批实施；二进制请求打包与 transfer。验收：Verification 3 四用例；
    时序 golden 换带帧记录。

### E 字体证据（`DeclaredFaceEvidence`）

- [x] **E1 声明通道与解析**：`declareTiqianFontFaces` 与显式 `baseUrl`；
  CSSStyleSheet 构造解析、detached style 降级、缺席记录；候选集声明在前
  CSSOM 在后。验收：Verification 7 前三组用例。
  产出（2026-08-23）：7bc32d1，`npm/core/sampler/snapshot/declared-faces.js`
  模块级注册表：同一 `(cssText, baseUrl)` 引用计数，注销函数归零移除，空串与
  全空白 no-op，变更同步通知 `onDeclaredFacesChanged` 订阅者，不新增
  globalThis 名。解析阶梯为构造 CSSStyleSheet 加 replaceSync，降级构造
  detached `<style>`，规则都取不到时记 DeclaredRulesUnavailable，replaceSync
  抛错记 DeclaredTextInvalid 并携带异常名。precomputed.js 的同步与协作两个
  采集器在 styleSheets 遍历前先遍历 declaredFaceSheets()，声明规则的 URL 按
  声明 baseUrl 解析（采集器以 visit 的 fallbackBaseUrl 入参解析，构造 sheet
  的 baseURL 选项不参与该路径）；collectFontFaces 导出供测试断言合并顺序，
  api.js 公开 declareTiqianFontFaces。
  验证：npm test 337/337（declared-faces.test.mjs 7 条：空串 no-op、引用计数
  与共享声明只通知一次、声明在前与 URL 基准断言、同 family 两条 CSSOM 在后
  由 findLast 胜出、DeclaredTextInvalid、无 CSSStyleSheet 环境的
  DeclaredRulesUnavailable、通知与退订）；jsBrowserTest 通过；grep 复核
  declaredFaceSheets 在 precomputed.js 为 1 处 import 与 2 处调用，
  relevantFontFaceLiveSignature 函数体无 declared 引用（声明变更的重验走
  E2 的通知，不进 CSSOM 漂移签名）。
- [x] **E2 重验入池与注册表生命周期**：唤醒不内联执行；每 root 至多一个 pending
  重验任务、同帧合并；引用计数注销。验收：Verification 7 对应用例。
  产出（2026-08-23）：2451c34。`createTypographyInvalidationSource` 在 start() 订阅
  `onDeclaredFacesChanged`、stop() 退订；element.js 的 handler 以 force 走
  `#scheduleTypographyCheck(true)`，注册表变更无 FontFaceSetEvent 且声明 sheet 不进
  CSSOM 签名，必须 force 才能越过比较。同帧合并由 `#scheduleTypographyCheck` 既有的
  rAF 去重承担，不新增 pending 结构。测试三层：declared-faces.test.mjs 用真 source 验证
  订阅、注册与注销都唤醒、stop 后不再唤醒；element.test.mjs 用 timing-golden 的
  element drive 驱动元素本体端到端（S1 settle 后两次同帧声明合并为一次
  enhanceProgressively；补发 relayout-ready 结束首个任务后再声明触发 destroy 加
  enhanceProgressively 的一次刷新；禁用后声明既不排帧也不产生引擎调用）。驱动期间发现
  stub 引擎不会完成第一个唤醒打开的排版任务，排版观察停在停止状态；drive 以运行时
  在 root 上派发的 relayout-ready 事件补上完成信号。timing-golden-host.mjs 为此把
  S1 建场抽成 startElementDrive 供两条驱动共用，冻结 golden 零 diff 证明抽取行为
  不变。
- [ ] **E3 不匹配解释结构化**：EmptyCandidateSet 与 FieldMismatch 两类 detail、
  字段核对顺序固定、dataset detail 进时序 golden。验收：Verification 7 末组用例。

### F 收尾

- [ ] **F1 无消费者导出清理与 shared 删除**（`UnusedExportCleanup`）。
  验收：Verification 6。
- [ ] **F2 三包拆分**（`ProseCoreLayering`）：`@tiqian/prose` 拆为 core 与
  web-component 两个 npm 包，连同 ffi 包共三个；依赖方向 web-component → core →
  ffi 单向，跨包相对导入 0；kotlin-js-store 归位。可在 B 组之前执行，使 TS 移植
  全程处于包边界内，跨层引用即时暴露。
  KPI：包数 3；跨包相对导入 0。
  验收：Verification 8；verify-package；demo/web 测试。
- [ ] **F3 类型制度上 CI**（`StrictTsDiscipline`）：eslint 三规则设 error；
  CI grep `eslint-disable`。KPI：`any`、`as unknown as`、`object`/`Object`/`{}`、
  `eslint-disable` 计数均为 0（三包 TS 面）。验收：CI 任务绿。
- [ ] **F4 双实现 CI 比对**（`DualLoweringStance`）：prepared-dom 双实现共享单一
  golden 语料强制比对。验收：Verification 2。

### KPI 汇总

| 指标 | 基线 | 目标 |
|---|---|---|
| Kotlin 宿主 jsMain 行数 | 5669 | 0 |
| jsTest 行数 | 3981 | 0（断言以 TS 规格形态存在） |
| runtime/tiqian-web.js | 1 份产物 | 不存在 |
| tiqian:* 事件通道 | 11 | 0 |
| globalThis 桥挂载方法 | 9 | 0 |
| 主线程调度循环 | 3 | 1 |
| 渲染实现份数 | 3 | 2 |
| 同旅程装配编码 | 4 | 1 |
| npm 包拓扑 | 1（@tiqian/prose；ffi 未独立发包） | 3（ffi、core、web-component），依赖方向 web-component → core → ffi 单向 |
| 跨包相对导入 | 无检查 | 0 |
| core 与 web-component 包内手写 JS 源文件 | 全部为 .js | 0（全部为 .ts） |
| any、as unknown as、object/Object/{}、内联类型 与 eslint-disable | 无检查 | 全部为 0 |
