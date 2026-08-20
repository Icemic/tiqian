# ADR 0050: 原生 precompute 引擎绑定与 Rust / npm 双生态分发

- Status: Accepted
- Date: 2026-08-20
- Relates: [ADR 0001](0001-core-pipeline-and-platform-boundary.md)（核心 pipeline 与平台边界）、
  [ADR 0039](0039-web-rendering-path.md)（Web 渲染路径，Node precompute 运行时）、
  [ADR 0040](0040-build-time-web-font-snapshots.md)（构建期字体证据与快照）、
  [ADR 0042](0042-framework-web-integrations.md)（Web 框架集成包与无宽度字体证据）、
  [ADR 0045](0045-apple-kotlin-native-target.md)（Apple Kotlin/Native 目标先例）、
  [ADR 0048](0048-suite-maven-and-package-namespaces.md)（套件 Maven 坐标与包命名）

## Context

Node precompute 现状分两层。`frontend/web-precompute` 编译为 Kotlin/JS，`@JsExport` 暴露扁平
wire ABI：分隔符编码的入参、JSON plan 出参。`@tiqian/prose` 内约 3400 行 Node JS 持有字体会话、
编排、prepared DOM 渲染与 manifest。字体会话依赖 `harfbuzzjs` 1.4.0 与 `woff2-encoder` 两个
WASM 包，经 `globalThis.__TiqianFontBackend` 回调协议服务 Kotlin 的 shaping / metrics 请求。

接入 precompute 的两个真实站点为缓存各手写了数百行代码。context hash 与并发去重在两个站点
重复出现；compact 序列化、原子写、逐条目失效只在其中一个站点实现。

这个结构有四个代价：

1. precompute 与浏览器运行时混合发布于 `@tiqian/prose`。构建工具用户携带无关文件与两个 WASM
   依赖，包边界与 ADR 0042 的分层目标冲突。
2. 引擎只能经 Node 生态消费。Rust 使用者没有入口，也无法把 precompute 嵌入非 Node 工具链。
3. 缓存 key 语义在集成层各写一份，随引擎演化容易漂移。
4. 构建期 shaping 走 WASM，加载与版本对齐受 npm 包发布节奏约束。

## Decision

### `NativePrecomputeEngine`：Kotlin/Native 静态库与扁平 C ABI

`frontend/web-precompute` 增加 `linuxX64`、`linuxArm64`、`macosArm64`、`mingwX64` 四个
Kotlin/Native 目标，各产出 `staticLib` 与 C 头文件。四个目标传递性地要求 `core`、`font`、
`shaping:api`、`linebreak`、`clreq`、`layout` 补齐缺失的 native 目标。这些模块以 commonMain
为主；断词资源的两处 expect/actual 目前 native 侧只有 appleMain 实现（ADR 0045），新目标需要把 actual
归位到 nativeMain 或补写。其余改动限于构建配置。wire 解析提升到 `commonMain`；`jsMain`
保留 `@JsExport`，`nativeMain` 新增 `@CName` 入口。

C ABI 保持现有 wire 契约：`tiqian_precompute_paragraph` 接收扁平参数，经 `nativeHeap` 返回
JSON，配对 `tiqian_precompute_release_string` 释放。错误经出参以具名 capability issue 字符串
返回，Kotlin 异常不跨 C 边界；绑定层把字符串映射为错误类型，名称与现有 npm 测试及 web
capability 断言一致。C 入口允许并发调用；backend 实现负责自身的线程安全。

字体回调协议采用可安装 vtable：`tiqian_install_font_backend` 注册 shape / metrics 回调与
backend revision，证据范围与 `__TiqianFontBackend` 相同，数据形态采用打包缓冲区，见
`PackedFfiCalls`。vtable 的 Kotlin 消费者放在 `shaping:api` 的 nativeMain，与 jsMain 的
`@JsFun` 实现并列。引擎未安装 backend 时对 shaping 请求报具名错误。安装模式让绑定 crate
可以脱离 precompute 单独链接与测试。

`macosX64` 不在目标内。ADR 0045 已决定不加该目标；Kotlin 2.3.0 起该目标进入弃用流程，官方
计划 2.4.0 移除。

### `RustPrecomputeStack`：字体会话与编排迁入 Rust

Rust 侧分两个 Cargo workspace，都在 `frontend` 下。`frontend/rust` 持有中性引擎绑定：`tiqian`
与后续的平台 crate；绑定不依赖 web 概念，Rust 使用者可不引入 precompute 直接消费引擎。
`frontend/web-precompute/rust` 持有 web 域的 `tiqian-precompute` 与 `tiqian-precompute-neon`，
以 path 加版本依赖 `tiqian`。crate 划分：

- `tiqian`：引擎绑定主包。暴露 `precompute_paragraph`、`install_font_backend` 与具名错误
  类型，链接平台 crate 提供的静态库。crates.io 上 `tiqian` 名称当前未被占用，发布前先注册。
- `tiqian-<platform>` 四个平台 crate：内含对应平台的静态库与头文件，`build.rs` 输出链接参数，
  由 `tiqian` 以 target-specific dependency 声明，cfg 谓词包含 arch 与 `target_env`。未覆盖
  目标（如 `x86_64-apple-darwin`、musl Linux）得到占位实现，调用时报具名错误。
- `tiqian-precompute`：字体会话与编排。字体会话复刻 `precompute-fonts.js`：harfrust
  shaping、
  WOFF2 解码、face 选择、unicode-range 匹配与回放证据。face 选择与 unicode-range 匹配属于
  CSS `@font-face` 的证据职责，复刻现有 JS 行为；字体 fallback policy 仍在 Kotlin `font`
  模块。编排覆盖 `precompute.js`、`prepared-dom.js`、`snapshot-manifest.js`、
  `snapshot-source.js` 与 `precompute-html.js` 的 Node 侧行为。只依赖 `tiqian`，不依赖
  napi，可独立 `cargo test`。
- `tiqian-precompute-neon`：Neon cdylib。暴露现有 precompute 入口的全部导出（兼容性约束见
  `NpmPrecomputePackage`），并新增 `createFontSession`、原始 `layoutParagraph` 入口与 JS 缓存车道的
  `cacheKey` / `cacheContext` / `serializeCachedParagraph` / `deserializeCachedParagraph`。
  Neon 打包与 CI 配置沿用同维护者 blurest 仓库验证过的 `neon dist` 与 `neon show ci github`
  流程。

Shaping 用 `harfrust`，metrics 与 extents 用 `skrifa` / `read-fonts`，WOFF2 解码用
`wuff`。四者都是纯 Rust 实现，构建不需要 C/C++ 工具链。采用版本 harfrust 0.13.0、
skrifa 0.46、read-fonts 0.43、wuff 0.2，前三者共用同一 read-fonts 版本。依据是
2026-08-20 的差分测试（见
[docs/research/2026-08-20-harfbuzz-version-differential.md](../research/2026-08-20-harfbuzz-version-differential.md)）：

- HarfBuzz 8.4 到 14.2.1 的输出逐字段一致。跨版本没有风险，将来若回到 C 路线，
  `harfbuzz-sys` 直接可用。
- rustybuzz 的输出同样一致，但它的仓库已于 2026-07-26 归档，不再维护。
- 静态字体与可变字体上 harfrust 0.13.0 与 skrifa 的全部字段与 oracle 逐字一致。extents
  按字形来源分派：静态 TrueType 读 glyf 头盒，CFF 与变实例坐标走轮廓包围盒。
- WOFF2 解码器选 `wuff` 0.2。`woff2` crate 0.3 自 2022-05 起无维护，并且严格校验
  header 的 `totalCompressedSize`，拒绝 `woff2-encoder` 生成且其自身可解的文件。
  `wuff` 解同一文件的输出与 JS 侧 wasm 解码器字节一致（sha256 相同）。依赖里的
  `bytes =1.9.0` 锁随 `woff2` crate 一并移除。

snapshot evidence 的 `harfbuzzVersion` 只校验同一 manifest 内条目一致
（`SnapshotFontEvidenceVersionConflict`）。Rust 侧报告自己的引擎标识与版本，同一
snapshot 不混入两个引擎的证据。升级 harfrust 或 skrifa 后重跑 `LegacyJsOracleCutover`
定义的差分 harness。HTML 解析用
`html5ever`。`harfbuzzjs`、`woff2-encoder`、`linkedom` 三个 npm 依赖随之删除。
Kotlin 引擎侧保持零字体依赖。排版规则仍全部在 Kotlin 核心，Rust 只承担 ADR 0001
平台 adapter 契约允许的平台层职责：字体加载、shaping 与度量。

### `PackedFfiCalls`：打包 FFI 数据与调用预算

跨 FFI 调用次数是本次迁移的设计指标，按段落计数：

- 缓存命中路径不调用 Kotlin。key 计算与条目校验都在 Rust 完成，命中时跨 FFI 调用为零。
- 未命中路径为两次加 K 次。进入与返回各一次；K 为引擎发出的 shaping 与 metrics 请求数，
  每个请求对应一次回调。
- Neon 批处理入口按文档计数。一次调用处理全部段落，段落循环留在 Rust 内部；单段入口
  面向单段调用方。

vtable 采用打包缓冲区，jsMain 的句柄协议保持现状。shape 回调把一个 segment 的全部字形写进
调用方提供的缓冲区：每字形一条定长记录，含 glyph id、advance、x、y 与四条 ink bounds；无 ink
bounds 的字段写 NaN。faceId、script、feature 等字符串证据写入同一缓冲的字节区，头部记录
偏移。

缓冲区容量由调用方按 segment 长度加余量预置。字形数可以超过码点数，GSUB 分解替换即属
此类；容量不足时回调返回所需容量，调用方扩容后重试，单个请求的回调次数上限为两次。metrics
回调同样返回定长记录。JS 侧现行协议在每个字形上最多花费八次访问器调用；打包形态
把一个 segment 的全部字形合并为一次调用。两个协议共用同一份会话证据结构。

plan JSON 保持单次返回的 C 字符串，Rust 解析一次。差分 parity 以 JSON 字节为比对层，二进制
plan 序列化不在本 ADR 范围内。

Neon 边界以字节为主。prepared DOM、bundle 与缓存条目经 Node Buffer 传输，缓存命中条目不经过
JS 字符串编码；输入侧 HTML 与文本仍为 JS 字符串，napi 转换一次。

并发契约：字体会话的 face 数据只读共享，shaping 线程各建 shaper 实例。批处理入口在
Rust 线程池并行执行，结果按输入顺序返回。入口保持同步语义，与现有 precompute API 一致。

### `StaticVendoredLinkage`：全部静态链接，禁止系统探测

shaping（`harfrust`）、metrics（`skrifa` / `read-fonts`）、WOFF2 解码（`wuff`）均为
纯 Rust 实现，无 C/C++ 构建依赖。SQLite 缓存后端在启用 cargo feature 时经 rusqlite
bundled 静态链入，是唯一的原生编译依赖。构建禁用 pkg-config 与运行时 dlopen 探测。
CI 对每个平台产物执行 `ldd` / `dumpbin` 审计，动态依赖只允许 OS 基线库，出现
fontconfig、freetype、harfbuzz、sqlite 系统库即失败。

Windows 静态库链接必须最先完成验证。Kotlin/Native mingw 产物与 MSVC 工具链存在
CRT 与运行时符号差异；MSVC 不兼容时 Windows 产物改用 GNU 工具链构建并在 CI 增加对应 job。

### `CargoPlatformBinaryCrates`：cargo 侧平台 crate 分发

cargo 用户经 crates.io 获得预编译静态库，无需 JDK 或 Gradle。`tiqian` 以
`[target.'cfg(...)'.dependencies]` 引用四个平台 crate。target 过滤使非当前平台的 crate 不进入
构建图，`cargo build` 只下载参与构建的 crate。`cargo fetch` 与 `cargo vendor` 仍会取全部
平台 crate，见 Consequences。平台 crate 与主包同版本发布，内嵌静态库携带引擎 revision，
绑定层加载时校验；revision 不一致时报具名错误。

平台矩阵按需扩展，四个初始目标不构成封闭清单。`tiqian` 绑定不绑定 web 用途，
Kotlin/Native 与 Rust 的目标交集还覆盖 iOS、tvOS、watchOS 的设备与 simulator 变体，以及
Android native 的 aarch64、x86_64、x86、armv7（需 NDK sysroot）。新增目标按既有模式扩展：
`frontend/web-precompute` 补编译目标，发布对应平台 crate，CI 补链接 job 与二进制审计。
没有消费者需求时不预先发布，也不在文档宣称支持。Intel macOS 与 Intel iOS simulator 沿用
ADR 0045 的排除。

### `NpmPrecomputePackage`：precompute 从 `@tiqian/prose` 迁出

npm 侧新增 `@tiqian/precompute`。主包持有 JS 入口、`.d.ts` 与 Neon 加载器；四个 npm 平台包
持有 `.node`，经 `optionalDependencies` 按平台安装。同维护者的 blurest 仓库验证过 Neon 多
平台 npm 分发，包结构沿用其主包与平台包的形式。npm 平台包同样内嵌 revision；加载器在
require 时校验主包与平台包的版本及 revision 配对；不一致时报具名错误。产物基于 N-API，跨
Node 大版本稳定；`engines` 沿用 `@tiqian/prose` 的 Node 22 下限。musl Linux 与 win32-arm64 不在
首版支持清单，加载时与 darwin-x64 一样报具名错误；需求出现时按同一平台包模式补充。

兼容性约束：`./precompute` 与 `./precompute-html` 的全部现有导出在 Neon 重构后同名同签名
继续提供，含 `createPrecomputer`、`createHtmlPreparer`、`renderSnapshotBundle`、
`renderFontContractBundle`、`renderSnapshotTemplate`、`renderPreparedParagraph`、
`snapshotPlainTextIssue`、`findHtmlOpeningTags`、`injectHtmlAttributes`、
`snapshotServerAssets` 与 `renderSnapshotServerAssets`。实现归属：Node 侧纯计算全部在 Rust
实现，经 Neon 导出。现有导出中只有 `renderPreparedParagraph` 例外，它与浏览器运行时共享同一份
prepared-dom 实现；浏览器无法加载 `.node`，该实现留在 JS，由主包再导出。平台加载器与类型声明
留在 JS，它们是接线代码。README 说明推荐用法：常见站点只用 `createPrecomputer` 或
`createHtmlPreparer` 配合 bundle 渲染，其余导出供调用方灵活组合。Astro / SvelteKit 集成与新
站点只改 import 来源。`@tiqian/astro` 与 `@tiqian/sveltekit` 新增
`@tiqian/precompute` 依赖，与 `@tiqian/prose`、`@tiqian/precompute` 同版本发布。

`@tiqian/prose` 删除 `./precompute`、`./precompute-html` 入口、Node 侧 precompute 文件与
三个 WASM 依赖，移除随 0.2.0-alpha 发布生效；`@tiqian/precompute` 以同一版本号起步。这是一次
breaking change，发生在 alpha 阶段，不提供兼容 re-export。浏览器运行时继续使用
`prepared-dom.js`、`snapshot-manifest.js` 等共享文件的浏览器副本。

字体会话独立公开：`createFontSession` 接受 `faces` 或 `fontStylesheets`，`createPrecomputer`
复用同一字体会话。其中一个站点为三种 typography 各建一个 precomputer，同一字体解码三遍；
共享字体会话取代此用法。

### `TwoLaneCacheContract`：缓存两车道与引擎指纹

缓存契约分两条车道。key 只在 Rust 一处实现；JS 车道经 Neon 调用同一实现。

Rust 车道：`PreparedParagraphCache` trait，`lookup` / `store` 返回 boxed future，兼容 dyn
分发与异步实现。异步实现自行持有 runtime `Handle` 并在其上阻塞，该约束写入 trait 文档。内建
`NoCache` 为默认。`MemoryCache` 含并发去重。`DirectoryCache` 每条目一文件，惰性失效，
原子写，compact 序列化复用 manifest 的压缩格式。SQLite 后端为 cargo feature。

JS 车道：`cacheKey(input, mode)`、`cacheContext()`、`serializeCachedParagraph` /
`deserializeCachedParagraph`。条目为不透明字节，反序列化带 revision 与 context 校验，损坏条目按
miss 处理。JS 车道统一 key 语义与条目格式；存储机制（原子写、并发去重、清理已失效条目）仍由站点
自选实现。站点的外层 bundle 组织（identity 索引、按需重建）不在契约范围内。

context 由 `engine` 与 `user` 两部分组成。`engine` 由 Rust 计算：layout / render / backend
revision、shaping 引擎版本（Rust 侧为 `harfrust` 版本标识）、解析后 face 集合的指纹与 typography。face 指纹覆盖字体二进制与
`@font-face` 描述符（family、style、weight、unicode-range、publicUrl），集成作者无需自算
字体 CSS hash。`user` 由调用者提供，内容不透明，用于其自身投影代码与常量的失效。

key 在输入归一化之后计算，语义相同的输入命中同一条目。归一化产生 canonical 形式，规则变化
经 revision 失效；归一化剔除调用方逻辑 key，命中后调用方回填，同一内容可跨条目复用。
`cacheContext()` 同时供条目级 L2 缓存构造 generation hash，对应站点已有的 identity key 与
内容指纹的组合模式。

不为 JS 提供 napi 回调式 cache adapter。回调方案让条目数据每次命中在 JS 与原生之间往返两次，
把并行排版限制在单 JS 线程，并暴露内部条目格式。远程缓存场景由调用方先查询自身存储，未命中再调用
prepare。

### `LegacyJsOracleCutover`：JS 实现先作 parity oracle，后移除

切换分三阶段：

1. Rust 侧与 JS 侧并行存在。差分 harness 以 `tiqian-precompute` 的 cargo 集成测试承载，
   语料期望值由现行 Kotlin/JS npm 产物生成并入库；语料为 npm 测试语料、layout golden 语料与
   两个站点的正文。比对前 harness 先断言两侧 layout / render / backend revision 逐字相等；
   shaping 引擎标识两侧按设计不同（JS 侧 `harfbuzzjs` 版本、Rust 侧 `harfrust` 标识），
   属差异豁免字段。此后逐层比对 shaping 证据、plan JSON、prepared DOM、manifest 与 bundle，
   豁免清单为引擎标识字段。
   byte-identical 按 canonical 序列化定义：字段顺序、浮点格式与 DOM 属性顺序由契约固定；
   浮点序列化在 Kotlin/JS 与 Kotlin/Native 间的差异是首要核对项。harness 发现差异
   时先判断属于格式还是语义：格式差异修 canonical 层，语义差异阻塞。门槛按最终支持平台全集计算；
   Windows 链接验证长期受阻时把 Windows 移出支持清单并记录，legacy 移除按剩余平台达标执行。
2. 删除 `@tiqian/prose` 的 Node precompute 与 WASM 依赖；`frontend/web-precompute` 的 js
   目标降为 oracle。
3. 删除 js 目标，发布 `@tiqian/precompute` 稳定版并更新架构文档。

oracle 期间 Rust 侧逐字复用 `snapshot-schema.js` 的既有 revision 常量。迁移完成后 revision
常量在 `@tiqian/prose` 与 Rust 侧各持一份声明，npm 测试断言两侧相等；`@tiqian/prose` 不依赖
`@tiqian/precompute`，浏览器包不引入原生依赖。`snapshot-schema.js` 还定义 replay key 函数，
Rust 会话产出相同的 replay key，该层一致性由差分 harness 与共享 golden 覆盖。
字体会话层的 parity 由 `tiqian-precompute` 的 `js_session_parity` 集成测试承载。同一
case matrix 分别经 Rust 会话与 Node 下的 `precompute-fonts.js` 执行，两侧输出 JSON
逐字节比对。2026-08-20 起矩阵输出一致：六个会话（含四个错误路径与 session 计数器语义）、
22 次 shape、10 次 metrics、renderFamilies、beginCapture 与 evidence 捕获，豁免字段
仅 `harfbuzzVersion`。

迁移完成后 prepared DOM lowering 有 Rust 与浏览器 JS 两份实现。共享 golden 语料常驻双向
断言：`cargo test` 与 npm 测试对同一语料断言字节一致，取代 ADR 0040 的单文件共享不变量。

## Consequences

- 构建工具用户获得原生 precompute，Rust 使用者获得可组合的 crate 入口，两者共享同一引擎
  revision 与字节级输出。
- CI 面扩大：macosArm64 的静态库在 macOS runner 产出，mingw 在 Windows runner 产出，两个
  Linux 目标可交叉编译；konan 产物由 CI 上传为平台 crate 与 npm 平台包的发布产物。七个
  crate 与五个 npm 包同版本发布。
- `cargo fetch` 与 `cargo vendor` 携带全部平台 crate。
- 支持清单不含 Intel macOS、musl Linux 与 win32-arm64，加载时报具名错误；Windows 支持取决于
  mingw 链接验证结果。
- 迁移后构建期走 Kotlin/Native，浏览器运行时仍走 Kotlin/JS。同一 Kotlin 源跨两个编译后端，
  revision 校验与共享 golden 语料防止两侧输出漂移。
- Node 使用者的运行依赖变为原生插件，升级时需按具名错误自查平台支持。
- `frontend/web-precompute` 在 js 目标外增加四个 native 目标，上游六个模块补齐构建配置与
  断词 actual，模块职责不变。
- flake 开发环境引入 rust-overlay；linux 与 mingw 的 Kotlin/Native 目标为仓库首次启用。
- 缓存契约成为公共 API。`CachedParagraph` 内部格式允许随 revision 演化，外部存储按不透明
  字节处理，格式变化经 key context 失效。

## Amendment (2026-08-20)：引擎级 ABI 取代 precompute wire，出口归引擎层

初版把 precompute wire 直接铺在 C ABI 上，`tiqian_precompute_paragraph` 用 15 个扁平参数
加 U+001E/U+001D/U+001F 分隔符编码入参、plan JSON C 字符串出参。这让绑定层持有 precompute
词汇，js 门面与 C ABI 门面留在 precompute 目录。本修订按当天的架构裁定重定层的边界。

### `EngineLevelAbi`：`tiqian_layout_paragraph` 打包二进制协议

- 废除初版「C ABI 保持现有 wire 契约」段与 `tiqian_precompute_paragraph`、
  `tiqian_precompute_release_string` 两个符号。新符号为
  `tiqian_layout_paragraph(const uint8_t* request, uintptr_t request_len,
  uint8_t** response_out, uintptr_t* response_len, const char** error_out)` 与
  `tiqian_release_buffer`。
- 协议沿用 `tiqian_font_backend.h` 的形式：头文件 `tiqian_layout_abi.h` 是双侧单一事实源，
  Rust 直接编译；Kotlin 侧常量镜像并以注释锚定，与 shaping 修订常量的既有形式一致。
  缓冲区带 magic 与 protocol revision，版本化演进。
- request 携带 `LayoutInput` 的引擎级字段：正文 UTF-8 字节、textStyle、paragraphStyle、
  constraints、text spans、source boundaries、line-break spans、inline boxes。所有文本
  索引按 UTF-16 code unit 定义，与引擎 `TextRange` 一致；Rust 侧不得按 UTF-8 重新编号。
- response 是 plan JSON：UTF-8、NUL 结尾、nativeHeap 分配，经 `tiqian_release_buffer`
  释放。plan JSON 的序列化只有 Kotlin 一份实现（`toPreparedParagraphJson`），prose 的
  js 路径与 Rust 的原生路径共同消费。在 Rust 侧重写序列化被否决：双实现存在行为漂移
  风险。这是本修订认可的唯一层间扭曲，引擎出口携带 web-core 的 plan JSON。浮点格式
  经 Kotlin/JS 与 Kotlin/Native 两个编译后端，统一为 `PlanNumberCanonicalForm` 描述的
  ECMAScript 形式。
- `PackedFfiCalls` 中「plan JSON 保持单次返回的 C 字符串，Rust 解析一次」段继续有效。

### `PrecomputeInRust`：precompute 词汇全部回到 Rust 消费侧

初版把 wire 解析、入参校验与 `LayoutInput` 组装放在 Kotlin commonMain。修订后这三项移植为
`tiqian-precompute` 的 Rust 代码：typed 请求结构、具名校验错误（错误名与 npm 测试断言
一致）、ABI request 打包与调用。plan JSON 序列化留在 Kotlin 单点；Rust 侧只做反序列化，
供后续 prepared DOM 下放消费，不提供发射器。分隔符 wire 解析不移植；该编码只在 js 门面
内部继续服务浏览器路径。

### `EngineFfiModules`：Kotlin FFI 门面归引擎层

- `frontend/web-precompute` 的 Kotlin 全部迁出。C ABI 门面进入新模块 `ffi/native`，
  四个 Kotlin/Native 目标与 `linkReleaseStatic*` 产物随之迁移；`tiqian_install_font_backend`
  的重导出留在该模块。js 门面（`@JsExport`、wire、`HarfBuzzBuildBackend`）进入新模块
  `ffi/js`，npm precompute-runtime 组装任务跟随。`frontend/web-precompute` 只保留
  Rust workspace 与 npm 包，不再含一行 Kotlin。
- `RustPrecomputeStack` 中「`frontend/rust` 持有中性引擎绑定」的表述修正为：`tiqian` crate
  是 sys 绑定，声明 `tiqian_layout_abi.h` 的符号并链接平台静态库。ABI 升级为引擎级之后，
  「绑定不依赖 web 概念」才真实成立。sys 层允许同时承载 web-core 契约的绑定，当前修订
  未行使该许可；plan JSON 的 schema 常量在 `tiqian-precompute`。
- precompute 域对引擎的全部访问只经 `frontend/rust` 的绑定。Kotlin 出口与 sys 同属引擎
  出口面，不留在 precompute 目录。

### `JsTargetStaysBrowserSide`：LegacyJsOracleCutover 第 3 阶段修正

初版第 3 阶段「删除 js 目标」与浏览器 `layout-worker.js` 的依赖冲突。修正为：js 目标长期
保留，承担浏览器 exact-font 回退 worker 与 parity oracle 两个角色；删除的是 Node 生产路径
对 js 产物的消费。`ffi/js` 模块因此是常驻出口，非过渡产物。

### `PlanNumberCanonicalForm`：plan JSON 浮点统一为 ECMAScript 形式

`appendJsonNumber` 原样使用 `Float.toString`，三个 Kotlin 后端输出三套字节：Kotlin/JS 打印
f64 加宽值（`20.34000015258789`、整数无小数点），JVM 与 Kotlin/Native 打印 f32 最短形式
（`20.34`、整数带 `.0`）。数值本身一致，分歧只在表示。修订后 plan 数字在 commonMain 单点
规范化为 ECMAScript `Number::toString` 形式：位数取自 `Double.toString`，布局按 ECMA 阈值
重排，末位从 Float 的精确十进制展开按 half-even 取整。选择 ECMAScript 形式使两个 JS 消费
lane（npm 生产路径与浏览器 worker）字节不变，只影响 JVM 与 Native 输出；dtoa 库在精确
十进制半值处的舍入差异由精确展开消除。JVM golden dump 不含 plan JSON，无 fixture
变化。

### Verification 增补

- plan parity：同一语料经原生路径（Rust 打包 → ABI → 引擎 → Kotlin plan JSON）与 js oracle
  （`precomputeParagraph` ESM bundle）双路输出字节一致，进入 `LegacyJsOracleCutover` 的
  比对层清单。载体为 `tiqian-precompute` 的 `plan_parity` 集成测试与
  `frontend/web-precompute/scripts/plan-parity-oracle.mjs`；两侧语料与 fixture 字体后端
  数值一一对应，fixture 取自 `PrecomputeExportsTest` 的 canonical 数。2026-08-20 起
  九个语料（标点压缩、中西混排、缩进、span、source boundaries、断行 policy、inline box、
  ellipsis 回退、纯换行）字节一致；`plan_parity` 在无 oracle dump 时按理由跳过，
  CI 以 `TIQIAN_REQUIRE_PARITY_ORACLE=1` 强制比对。
- `tiqian` sys crate 在 `TIQIAN_NATIVE_LIB_DIR` 指向 Gradle `linkReleaseStatic*` 产物时
  链接真实引擎，`cargo test` 在 linux CI lane（`rust-engine-parity` job）跑通 plan parity。
  build script 对归档文件声明 `rerun-if-changed`，引擎归档重建后 cargo 侧强制重链接。

## Alternatives considered

- **保留 Node Kotlin/JS 与 WASM 运行时。** 否决：构建期 WASM 加载成本仍在，Rust 生态无入口，
  包边界与缓存 key 重复维持现状。
- **build.rs 构建期下载预编译二进制。** `ort` 一类模式。否决：构建依赖网络与硬编码第三方
  下载源，开发体验差；crates.io 平台 crate 获得原生缓存与离线构建。
- **全平台二进制打包进单一 crate。** 否决：crates.io 单包 10MB 上限，且所有用户全量下载全部平台。
- **napi 回调式 JS cache adapter。** 否决理由见 `TwoLaneCacheContract`。
- **字体会话留在 Kotlin/Native 内直接链接 HarfBuzz。** 否决：字体会话属于 precompute 消费层，
  Rust 编排与缓存也消费同一会话；vtable 安装模式保持引擎与现有 JS 架构同构，shaping 引擎
  版本只在 Rust 一处维护。
- **backend 函数表按每次调用传参。** 否决：要求改动扁平 wire 签名并让每次调用携带函数表；
  全局安装与现行全局对象协议同构，只需一次安装。
- **napi-rs 代替 Neon。** 否决：blurest 已在 CI 验证 Neon 多平台发布流程；napi-rs 没有本
  仓库的验证记录。
- **Kotlin/Native 直接产出 Node addon。** 否决：Kotlin/Native 无法独立产出 N-API addon；
  Rust 生态入口仍缺失。
- **precompute 继续留在 `@tiqian/prose`。** 否决：混合发布与 ADR 0042 分层冲突，breaking
  迁移趁 alpha 阶段完成。

## Verification

- `:frontend:web-precompute` 四个 native 目标编译并通过 native 测试；`jsNodeTest` 行为不变。
- `cargo test -p tiqian-precompute` 覆盖 wire 解析、face 选择、manifest 与缓存语义。
- 差分 harness：最终支持平台全集 × 全语料 byte-identical，是移除 legacy 的硬门槛。
- 差分 harness 记录每段落的跨 FFI 调用次数，断言与 segment 数线性相关。
- 迁移完成后共享 golden 语料在 `cargo test` 与 npm 测试双向断言字节一致。
- revision 常量由 `@tiqian/prose` 与 Rust 两侧声明，npm 测试断言相等。
- CI `ldd` / `dumpbin` 审计四平台 `.node` 与示例二进制。
- npm 测试套件经 Neon 路径全部通过；Astro / SvelteKit 集成测试改引 `@tiqian/precompute` 后
  全部通过。
- Windows mingw 静态库链接验证最先执行，覆盖 MSVC 与 GNU 两条工具链路径。
