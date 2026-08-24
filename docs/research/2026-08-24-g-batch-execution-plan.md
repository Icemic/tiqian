# G 批次执行计划（ADR 0053 section G 代码品控）

日期：2026-08-24。状态：计划。范围：ADR 0053 section G 三个待办（G1 TypeScript
落实、G2 模块边界与副作用、G3 ffi 包边界）的执行顺序与技术方案。Slice 7
（73449b70..81ca3ddb）已完成 target 退役，本计划是其登记的后续品控入口。

## 1. 现状度量

- 运行时手写 JS：两包合计 68 文件、18,935 行（`@tiqian/prose-core` 62 文件、
  `@tiqian/prose` 6 文件）。度量脚本按静态与动态 import 建图，55 文件构成
  无环叶先序，13 文件处于 import 环（element.js、worker-channel.js、
  font-loader.js、browser-fonts 两份、precomputed.js、loaded-snapshots.js、
  prepared 系、grid-metrics、observers、signatures、api.js、worker-layout.js）。
  TypeScript 允许类型环（编译期擦除），环不阻塞转换。
- 最大文件：precomputed.js 2429 行、element.js 2351 行、markdown-lowering.js
  1203 行、prepared-dom.js 1059 行。
- `__Tiqian*` 全局：30 个名字、213 处引用（G2 消除面）。
- Node v22.23.1 默认启用 type stripping（`.ts` 可直接执行）；该能力只用于
  测试与工具，发布路径不依赖它。

## 2. 执行顺序：G3 → G1 → G2

- G3（ffi 面清理）先行：独立于两包内部结构，先收敛 ffi 边界，G1/G2 的
  类型与 import 改造不再波及 ffi 面。
- G1（TypeScript 落实）居中：类型先行把每个模块的输入输出固定下来，G2 的
  全局消除与状态收回在类型约束下进行，签名改动由编译器核对。
- G2（模块边界）最后：涉及行为重构（全局改 import、ffi 参数线程化、实例
  状态收回、测试读取机制重设），放在类型化之后风险最低。

## 3. G1 方案

### 3.1 编译策略：就地 emit

- 源文件 `.js` 改名 `.ts`，逐文件补类型；tsc 就地输出同名 `.js` 与
  `.d.ts`（不设 outDir，默认写回源旁）。两包 package.json 的 exports/files
  路径全部不变（指向的 `.js` 从手写变为编译产物）。
- import 说明符保持 `.js` 后缀（`moduleResolution: nodenext`）：emit 产物、
  浏览器 raw 服务、符号链接农场、demo fixture 全部零路径改动。测试与工具
  若直接引 `.ts`，说明符用 `.ts`（仅限 Node type stripping 路径）。
- `allowJs` 全程 `false`。实测证据（scratch 探针）：`allowJs: true` 时 tsc
  会把被 import 的手写 `.js` 一并重发（TS5055 覆盖输入报错，且先落了
  伴生 `.d.ts`）。叶先序批次保证任何 `.ts` 只 import 已转换的 `.ts`，
  未转换依赖触发 TS7016，编译器因此成为批次纪律的执行者；环成员同批转换。
- 语法约束：只允许可擦除语法（标注、interface、type、`satisfies`）；禁
  enum、namespace、参数属性（Node stripping 与 emit 双路径兼容）。tsconfig
  `strict: true`，`erasableSyntaxOnly: true`。
- 产物 `.js`/`.d.ts` 与源同名共存：`.gitignore` 按目录规则忽略编译产物
  （有 `.ts` 处的 `.js` 视为产物），KPI 检查以「包内是否存在无 `.ts` 兄弟的
  手写 `.js`」为准。发布包含 `.js` 与 `.d.ts`；`.ts` 是否随包发布在第一批
  定案（默认随包发布，便于消费端调试）。
- 构建接线：两包各加 `build`（tsc）脚本；`npm test` 的 `pretest` 先构建；
  `@tiqian/prose` 依赖 prose-core 经链接农场的产物与 `.d.ts` 做类型检查，
  构建次序 core 先、prose 后。CI 与 demo fixture 依赖 `pretest` 就地产物。

### 3.2 配套迁移

- ts-discipline eslint 对象从「非生成 js/mjs/d.ts」改为「`.ts` 源 + 非生成
  mjs」；三条规则不变，后续可加泛型相关约束。
- package-topology 检查对象同步改为 `.ts`。
- A1 双侧类型生成器：prose-core 侧 `.d.ts` 产物由 tsc emit 取代，生成器
  退役或收敛到 ffi 侧（Kotlin 仍是 ffi 的类型源头）；CI 新鲜度检查相应调整。
  类型定义去重以「同一形状只允许一处定义」执行，跨包重复形状收敛到
  prose-core 单点导出。
- verify-package / prepare-release：必备文件清单从手写 `.js` 改为 emit
  产物存在性检查。

### 3.3 批次（叶先序，每批一次提交）

按依赖图叶先序推进，每批 8 到 12 文件，按目录簇对齐不拆簇；每批验收：
tsc 零错误、两包 npm 测试维持基线（prose-core 419、prose 245）、
ts-discipline 与 topology 检查通过、受影响 demo 子集通过。全量批次完成后跑
demo/web 全套与 sveltekit/astro 集成。预计 7 到 8 批（prose 的 6 文件含
element.js 2351 行，最后两批处理）。

### 3.4 不变式

- 行为逐字节等价：emit 产物与原 `.js` 在测试面等价（golden、时序 golden、
  corpus 不动）。
- 每批只做「改名、补类型、修 import 说明符」；发现设计问题登记到 G2 待办，
  不在 G1 批内顺手重构。

## 4. G2 方案要点

- 全局消除分三档：模块间互调改 ES import/export（ts-runtime 的 21 个副作用
  import 随之退化为类型与函数依赖）；ffi facade 从「全局 bind」改为
  显式参数或模块单例注入；组件实例状态（当前存全局闭包）收回实例字段。
- 测试读取内部数据的机制另行设计：测试改为经公开导出或专用 test hook
  模块读取，替代直接读 `__Tiqian*`。
- 批次按「叶模块导出化 → 调度与驱动 → element.js 与 root-state → 测试机制
  切换」推进；`engineApi()/workerApi()` 的全局解析在此阶段删除，
  runtime-loader 只保留安装与访问语义。
- 每批验收同 G1，另加 demo/web 全套（custody 与 framework-commit 路径对
  全局消除最敏感）。

## 5. G3 方案

只读审计进行中（报告 `.b2-tmp/g3-audit-report.md`）。处置按审计
结论分批：混入的 web 宿主逻辑收回 `frontend/web/npm-core`（作为 G1 批次之一
或独立小批）；Rust FFI 与 JS FFI 导出面平行性分歧逐符号处置；
HarfBuzzBuildBackend 两个 typealias 按消费点结论保留或内联删除。

## 6. 风险与回退

- 就地 emit 的 gitignore 规则可能误伤新增手写 `.js`：以 KPI grep（无 `.ts`
  兄弟的 `.js` 为零）兜底，CI 检查。
- 浏览器 fixture 依赖 `pretest` 产物在场：CI 与本地流程在 demo 前先跑
  两包 build；fixture 的存在性门与 `files` 断言同步。
- element.js 与 precomputed.js 体量大：拆批处理，各自独立提交，golden 与
  demo 全套验收。
- 每批独立提交，回退以批为单位。
