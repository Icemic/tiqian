# Web 预计算双实现与共享协议评估（2026-08-23）

本文记录 web-precompute 双实现现状、单一事实源方案（Haxe 协议）的成本构成、
reflaxe.rust 稳定性研究、boring 子集可行性、JSON 需求边界与二进制表双边读写
的调研结论。材料来源：仓库内代码（行号随文标注）、docs/adr/、
reflaxe.rust 仓库文档（版本 v0.93.0 前后，2026-07-16 发布）。核对日期：
2026-08-23。

## 1. 双实现现状

`prepared_dom.rs` 文件头注释记录：该文件是对 `prepared-dom.js` 中
`renderPreparedParagraphArtifact` 的移植（ADR 0050）。JS 模块保留浏览器渲染器
与 parity oracle 角色，Rust 移植服务于 Node 编排，覆盖全部 lowering 逻辑：
plan JSON 输入、spacing 判定、run 合并、语义容器嵌套、HTML 序列化、artifact
树、计数输出。

移植范围不止这一个文件。crate 共 40 个 src 文件，其中 25 个在文件头点名
对应 JS/TS 模块：

| Rust 文件 | 对应 JS/TS 模块 |
|---|---|
| prepared_dom.rs | prepared-dom.js |
| precompute_html.rs、html_parse.rs | precompute-html.js |
| precomputer.rs、snapshot_bundle.rs、normalize.rs、cache.rs、renderer.rs、parallel.rs、context.rs | precompute.js |
| session.rs、shaping.rs、selection.rs、policy.rs、metrics.rs、font_record.rs、font_face.rs、font_source.rs、replay.rs、js_compat.rs | precompute-fonts.js |
| snapshot_source.rs | snapshot-source.js |
| schema.rs | snapshot-schema.js |
| snapshot_manifest.rs | snapshot-manifest.js |
| source_boundaries.rs、font_contract.rs | font-face-boundaries.js、font-contract.js |

两侧输出的一致性校验分两层：

1. 语料层：`frontend/web/npm/prepared-dom-corpus.fixture.json` 由
   `scripts/build-prepared-dom-corpus.mjs` 从 JS 生成，Rust 测试
   `tests/prepared_dom_corpus.rs` 与 JS 测试 `frontend/web/npm/prepared-dom-corpus.test.mjs`
   对同一 fixture 断言相同字节。
2. plan 层：`scripts/plan-parity-oracle.mjs` 运行 Kotlin/JS 预计算 bundle 写出
   `build/plan-parity/oracle.json`，Rust 测试 `tests/plan_parity.rs` 字节比对。

双实现之间已经出现过漂移。浏览器端真身
`frontend/web/npm/core/sampler/snapshot/prepared-dom.js` 与 vendored 副本
`frontend/web-precompute/npm/shared/core/sampler/snapshot/prepared-dom.js`
的字段存在差异：`dashStrategy`、`punctuationInkFloor`、`styleDelta`、
`punctuationBodyWidth`、ruby 决策、bopomofo 决策、`inlineEdges`、
`emphasisRanges` 只存在于真身。Rust 移植对齐的是 vendored 副本。语料
（fixture 内搜索）不含上述字段的样本，比对只覆盖两方共有的子集。

## 2. 语言特性对照

以 vendored 副本与 Rust 移植为样本：

| 语义问题 | JS | Rust |
|---|---|---|
| 可空值 | `?.`、`??`、`== null` | `Option<T>`、`unwrap_or`、`is_some_and` |
| 属性容器 | 动态对象，`Object.entries` 过滤后按名排序 | `Vec<(String, Option<String>)>`，`sorted_entries` 按 `cmp_utf16` 排序 |
| 错误 | `throw new Error(字符串)` | `NamedError(String)`、`Result`、`?` |
| 宿主回调 | 函数参数 `styleClassFor(declaration)` | `&mut dyn FnMut(&str) -> String` |
| JS Number 语义 | `Number()`、`toFixed(5)`、`Number.isSafeInteger` | `js_compat` 层：`js_to_fixed5`（i128 二进制小数展开复刻 ECMAScript 舍入）、`trunc_sat_i64`、`js_number_string`、`js_int_to_number` |
| Map 键语义 | `Map`（SameValueZero：`-0` 与 `0` 同键、NaN 可作键） | `HashMap<i64, f64>`，`normalized_key` 用 `to_bits` 位模式 |
| 序列化时机 | getter 访问时计算（`get html()`、`get artifact()`） | 构建时完成树，`html()`、`artifact()` 两次遍历 |
| 分支与判等 | 字符串常量 + `===` + 三元 | `enum SpacingKind`、`enum Semantics`（带数据）、`match`、`if let` |
| 集合操作 | `Set`、`Map`、`WeakMap`、`Array.from`、`flatMap`、`.at(-1)` | `Vec`、`HashMap`、迭代器链、切片 |
| 鸭子类型校验 | `typeof source.cloneNode === "function"` | 类型化反序列化 + `validate_live_semantic_elements` 校验 |
| 字符串 | 模板字符串、`replaceAll`、`trim`、`toLowerCase` | `format!`、`replace`、`trim`、`to_lowercase` |
| 默认参数 | `options = {}` | `PreparedRenderOptions::new()`、`impl Default` |
| 不可变性 | `Object.freeze`（运行时） | `&`、`&mut` 借用（编译期） |
| 哨兵值 | `undefined` | `usize::MAX`（ROOT） |

JS 侧语义对齐集中在三个文件：`js_compat.rs`（551 行）、`json.rs`（504 行）、
`snapshot_source.rs`（1115 行）。

## 3. 单一事实源方案评估

### 3.1 方案定义

方案只覆盖两方重复的 lowering 部分（prepared_dom、snapshot_source、normalize、
canonical、plan、json、schema 一类）。这些部分用 Haxe 书写，boring 子集限定
在循环、数组、interface、枚举、结构体，不用函数值、Dynamic、继承、null；
经 Haxe 官方 JS target 生成 JS 侧代码，经 reflaxe 生成 Rust 侧代码，代码统一
放在 tiqian/protocol。平台壳不进入协议范围：JS 侧 DOM 宿主、live-replay、
样式桥保持手写 JS；Rust 侧 Neon 边界、engine ABI、字体会话、sfnt 解析保持
手写 Rust。平台壳在两方各只有一份实现，不在重复范围之内。

### 3.2 双实现漂移的成因

语料比对属于采样验证：被语料覆盖的路径，两侧一致可以得到验证；语料之外的
路径，两侧是否一致没有验证。每次 JS 侧行为变化，都需要手工 re-port 一次；
未覆盖路径上的行为差异没有捕获机制，即使 CI 全部通过，差异也可以长期存在
（第 1 节漂移实例即属此类：语料不含 evidence 字段样本，两侧在字段层面已经
分叉）。编译器输出是确定性的：同一份源输入永远产生同一份目标输出。两侧代码
都从同一份源生成，跨实现一致由生成过程本身保证，比对只需要验证源的行为。
两者的差别在于：语料比对只能证明当前被覆盖的样本一致，编译器生成保证未来
任何时候两侧都一致。

### 3.3 注意事项

1. 现有 JS 侧代码不满足 boring 子集约束（`?.`、`??`、spread、getter、
   `Array.from`、`.at(-1)`、`flatMap` 均在用），因此以受限 JS 子集为源的
   方案同样需要一次协议重写，重写成本与 Haxe 方案相同；以全 JS 语义为源
   需要覆盖全部 JS 特性的 emitter，覆盖面与受限子集 emitter 不同。
2. 方案成立的前提是协议为单一权威：浏览器端消费 Haxe 生成的 JS（lowering
   部分），手写 JS 只保留 DOM 宿主、live-replay、样式桥。协议只生成 Rust
   而 JS 侧保留手写权威时，双源问题会在协议与 JS 之间重建。

### 3.4 设计约束

1. boring 子集以 CI 规则强制：源内出现函数值、Dynamic、继承、异常捕获、
   null 字面量即失败，不依赖自觉。
2. 语义桥（js_compat 551 行）保留为 Rust 侧业务逻辑，protocol 经 extern 缝
   调用；JS 侧使用对应原生实现。语义桥不进协议。

## 4. reflaxe.rust 稳定性研究

### 4.1 版本与发布线

`docs/semver-release-posture.md`（2026-07-13 决定）记录：发布姿态定为 0.x
pre-1.0；生产可用性评级 READY_WITH_BOUNDED_SCOPE，稳定 1.0 评级 NOT_READY
（2026-07-13 独立审计）。事实如下：

- 2026-07-14 至 07-16 三天内发布 v0.85.24 至 v0.93.0 约 30 个 minor 版本。
- 0.x 姿态下，minor 版本可以携带破坏性变更，只要求附迁移说明。
- 2026-03 曾把版本元数据改为 1.0.0（Milestone 29），未创建 tag，2026-07
  撤销该决定。
- 1.0 毕业门共 8 类条件，含逐 operation/signature/transitive-type 的语义
  分类、公共 API 兼容性审查、独立复核，当前未满足。
- 采用该依赖，需要固定 haxelib 与生成 cargo 的版本，升级前审计 release
  notes。

### 4.2 证据分级

`docs/feature-support-matrix.md` 定义三级证据：

| 证据层 | 证明内容 | 不证明内容 |
|---|---|---|
| snapshot | 生成 Rust 形状确定 + 定向冒烟 | 模块族运行时语义 |
| semantic-diff | 覆盖 fixture 上与 Haxe --interp 的运行时一致 | fixture 外的一致 |
| tier1/tier2 sweep | 上游 std 模块的编译/格式检查覆盖 | 运行时语义 |

文档明确声明：compile/inventory 覆盖不构成运行时语义一致；`haxe.*` 与
`sys.*` 各家族的证据分级不同。唯一标注 full-harness 语义证据的面是基础语言
lowering（控制流、类、继承、属性、枚举、异常、泛型、函数值）。
`reflaxe.std` 的 Option/Result 是首批 admitted 共享面，可以直接落为 Rust
原生 `Option`/`Result`。

### 4.3 运行时表示

`docs/rust-representation-plan.md` 与 `docs/null-option.md` 记录表示选择：

- `Null<T>`：值类型落为 `Option<T>`；引用型运行时类型（类句柄、数组、
  可空字符串、函数值、Dynamic）自带 null 哨兵，不再额外包 Option。
- Array：一律使用 `hxrt::array::Array<T>`（runtime_array），没有裸 Vec
  路径，原因是 Haxe Array 的别名共享与可变语义需要共享容器。
- String：两条路径，ordinary owned Rust string 与运行时可空字符串。
- 函数值：`HxDynRef<dyn Fn(...) + Send + Sync>`，move 闭包加共享句柄，
  可变捕获经共享 cell。
- hxrt 运行时语义面：Dynamic、反射、异常、对象身份、匿名结构记录、nullable
  兼容、平台抽象。
- 枚举：owned reusable Rust enum，无 runtime 依赖。

### 4.4 含射范围映射

protocol 需求与 reflaxe.rust 状态的对照：

| protocol 需求 | reflaxe.rust 状态 |
|---|---|
| typedef/class 结构体、带数据枚举、for/while、`Null<T>` | 基础语言 lowering，有语义证据 |
| Array 操作 | hxrt 包装，行为有证据，形状非裸 Vec |
| String 方法（split/join/replace/trim/toLowerCase） | 逐方法特判；substring 的 clamp/swap 语义有专门 snapshot |
| 宿主回调（styleClassFor） | 函数值有语义证据，生成 `HxDynRef<dyn Fn>` |
| NamedError 异常 | 非泛型层级 catch 有证据；泛型 catch 不承诺（protocol 不使用） |
| Math.abs、数值运算 | 基础面 |
| haxe.Json | 运行时一致证据对的是 Haxe --interp；protocol 需要的是 JS JSON 语义，该面不匹配 |
| Dynamic、反射、sys.*、haxe.io.Bytes（sfnt）、threads | 实验区或各家族证据分级不同；protocol 不使用 |

结论：protocol 的 boring 子集全部落在基础语言 lowering 证据带内；
reflaxe.rust 不稳定的面（Dynamic 构造、反射、sys 家族、async、Windows）在
含射范围内均不触及。

## 5. boring 子集可行性

### 5.1 回调与 lambda 处置

protocol 范围内的闭包清单与处置方式如下：

| 位置 | 处置 |
|---|---|
| `style_class_for: &mut dyn FnMut(&str) -> String`（prepared_dom.rs:45，宿主注入，共一处） | 改为数据出：protocol 按确定性规则铸造 class 名，随 html 返回 `(class_name, declaration)` 列表，宿主事后注册声明 |
| `semantic_container_for(wrapper: impl Fn(usize) -> …)`（prepared_dom.rs:180） | 改传 `Semantics` 对象调用其方法 |
| `indices(covers: impl Fn(i64, i64) -> bool)`（prepared_dom.rs:427） | 改传枚举标志，内联分支 |
| `field_error: impl Fn(NamedError)`（plan.rs:184） | 调用点内联 |
| `predicate: impl Fn(&Json)`（snapshot_source.rs:431） | 调用点内联 |

处置之后，函数签名不再接受函数值。`style_class_for` 改为数据出后，Rust 侧
不再存在 `&mut dyn FnMut`，生成 Rust 侧不再存在函数值句柄。

### 5.2 Array 表示与开销

`docs/perf-hxrt-overhead.md` 记录实测：

| 指标 | 数值 |
|---|---|
| 数组循环 portable/metal 收敛 | 预算与 PR 硬门均为 1.08x |
| 稳态热循环 metal 对纯 Rust | 约 1.05x（80 样本，无回归信号） |
| JSON parse/stringify 对 serde_json | portable 1.19x、metal 1.23x（修复后；修复前 1.45x、1.41x） |
| 二进制体积 | hxrt 为其中固定开销来源（基线见 perf-hxrt-overhead.md） |

JSON 是文档标注的唯一 first-class 热点候选。数组的语义（别名共享、边界
检查、Haxe 强制转换）由 hxrt 承担，循环路径的开销在 1.08x 预算内。
protocol 的数组访问模式（逐行 cells/runs/spans，构建一次、顺序读）也在该
预算内。

### 5.3 函数值表示

函数值一律生成共享句柄 `HxDynRef<dyn Fn + Send + Sync>`。protocol 经 5.1
处置后不再使用函数值，该表示不进入生成物。

## 6. JSON 需求边界

### 6.1 当前使用地图

三处用途：

A. wire 解析（seam 侧）：`plan.rs` `Plan::from_json_str`（协议入口参数）、
`precomputer.rs` `PrepareInput::from_json`、`snapshot_manifest.rs`、
`font_contract.rs`、`source_boundaries.rs`、`submission.rs`、`canonical.rs`。

B. protocol 内部数据树：prepared_dom 的 options（semantics、
render_text_spans、inline_boxes）与 artifact 输出。`haxe.Json.parse` 返回
Dynamic，Dynamic 需要 hxrt 运行时载体与反射支持，该 API 在 boring 子集内
不可用。替代方案二选一：protocol 自带递归 Json 枚举（Rust 侧生成普通 enum，
零 runtime 依赖；JS 侧需自写解析器对齐 `\uXXXX` 转义、重复键、非有限数
语义，`canonical.ts` 头部注释记录该类差异）；或 Json 也留在 seam，protocol
的输入输出改用 typed 结构体。

C. 发射（输出与 `JSON.stringify` 逐字节一致）：`json.rs` 转义、
`semantic_signature`（复刻 `JSON.stringify(cell.semanticPath)` 形状）、
`snapshot_bundle.rs`（134 处引用）、`schema.rs` stableStringify、
`emit.rs` 比对 dump。

### 6.2 ADR 0054 对 JSON 需求的影响

0054 消掉的运行时半边：

- 回填条款（:222-223）把序列化从 plan JSON 往返降为条目字节的一次本地写入。
- :249：prepared-dom 运行时 HTML 字符串路径删除，只用于构建期烘焙。运行时
  的字符串转义、JSON.stringify 形状签名失去消费者。
- :251-253：运行时 lowerer 的输入从 plan 对象改为条目加五表文本，plan 对象
  的消费面退到构建期。
- :169-170 引用 0052 第四批实测：布局解码 0.12ms，JSON.parse 3.1ms。

构建期与 seam 侧保留：

- plan JSON 构建期仍要解析：表写入器（54-8）与烘焙 HTML 两个消费者。
- manifest JSON：schema 升版本、`maxWidthPx` 改格数整数字段，传输形态保持
  JSON（snapshot_manifest.rs）。
- font contract、prepare options、submission、canonical、parity dump。
- C 类发射机器在构建期存活（bundle 组装、stableStringify）。

条件性：0054 第三、四组（表本体、运行时补丁、回填，即消解 JSON 的条款）
以 54-5 四项 bench 门槛为开工条件，不达标保持候选。第一组（54-1 格量化
无条件化、54-2 Double 算术、54-3 请求带格数、54-4 CSS 级联）不依赖门槛，
但不消除 JSON。当前代码库运行时 plan-JSON 路径仍为现状。

### 6.3 二进制表双边读写

现状：0052 表已有双边实现。`snapshot_table_binary.rs`（1526 行，54 个函数）
读与写全实现，固定小端（`to_le_bytes`、`from_le_bytes`），`checked_add`
偏移校验。`frontend/web/npm/snapshot-table-binary.js` 以 DataView 实现读取，
注释记录「The byte contract lives in the encoder; this file mirrors the
region order and validates every offset」。双边实现已在生产运行。

可列举的差异有三处：

1. 端序：DataView 默认大端，Rust 原生小端。仓库已固定小端，漏写端序参数
   产生错值且无报错，由 fixture 捕获。
2. 偏移校验：0054 已规定（偏移对字节长校验、损坏在任何行被读之前抛错），
   0052 已实现。
3. 浮点位：IEEE 位模式双边往返一致。JS 侧 `ABSENT_METRIC_BITS =
   0x7ff8000000000000n`（f64 NaN 位）即该约定。二进制浮点不存在十进制
   格式化语义，js_compat 的 toFixed 类问题不进入二进制面。

0054 带表与 0052 表的差异：内容全整数（1/64px 量化），无浮点、无字符串，
编码为 u16/u32 读写加偏移与校验。

抽象层选项：

- Haxe 路径：`haxe.io.BytesInput`/`BytesOutput` 提供与 DataView 同构的原语
  （`readUInt16`、`readUInt16LE`、`readUInt32`、`readFloat` 等），全 target
  存在；reflaxe.rust 特征矩阵列有 bytes_extended_api 的语义差分证据。
  codec 本体约 200-400 行（0054 带表）。
- 现状路径：Rust 编码器保持单一事实源，JS 只读实现保持，字节级 fixture 双端
  比对（现有机制）。

浏览器侧无法调用 Rust（FFI 只覆盖 Node），只能消费 JS 源码或生成代码。三端（浏览器、Node、Rust）共享一份 codec 的实现路径只有生成代码。
该 codec 的语义负担构成：无 JSON 解析语义、无浮点格式化、无字符串转义、
端序单一取值、fixture 可锁。

## 7. 结论

1. 双实现漂移的成因在于验证方式：语料比对只覆盖样本，编译器生成覆盖全部
   路径。前者在每次行为变化时都要手工重写另一侧，后者只需要保证源本身的
   语义正确。
2. Haxe protocol 方案成本 1.5-2.5 人月（第 3.3 节），前提是协议为单一权威
   且 boring 子集以 CI 规则强制。
3. reflaxe.rust 的不稳定面（0.x 版本线、hxrt 包装形状、haxe.Json 语义不
   匹配）都可以通过 seam 隔离在协议之外：语义桥留在 Rust 侧，plan JSON 解析
   留在两侧平台壳，协议本体只使用基础语言 lowering 证据带内的特性。
4. protocol 内不需要函数值：宿主回调改为数据出，内部闭包全部内联。
5. JSON 需求经 ADR 0054（门槛通过后）收敛为构建期 seam；运行时为零 JSON。
   协议边界按「构建期 lowering + 整数带条目编码」划分，0054 实施与否不改变
   该划分。
6. 带条目 codec 是共享协议优先实现的候选模块：整数内容、端序单一取值、
   无语义桥依赖、浏览器侧只能以生成代码消费。