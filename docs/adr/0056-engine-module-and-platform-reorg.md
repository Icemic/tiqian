# ADR 0056：engine 单模块合并与按平台的仓库重组

- Status: Accepted
- Date: 2026-08-23

## Context

顶层曾有 13+ 个按关注点散布的目录（`core` / `font` / `linebreak` / `clreq` / `layout`、
`shaping/*`、`frontend/*`、`benchmark`、`tools`、`ffi`、`demo`、`test-support`）。两条压力：

- Maven Central 文件配额（ADR 0055）：engine 六模块（core / font / linebreak / clreq / layout /
  shaping-api）target 集完全一致，每个模块都产出完整 publication 集，是文件数乘法的主要来源。
- Losses 的 PR #7（Rust precompute，2026-08-23 合并）带来了 `ffi/` 顶层布局，并建议把顶层整理成
  engine / platforms / ffi / docs，platforms 按宿主平台分组。

## Decision

1. **engine 六模块真源码合并为单一发布模块 `:engine`**（artifactId `tiqian-engine`）。
   core / font / linebreak / clreq / layout / shaping-api 的源码按
   `org.tiqian.{core,font,linebreak,clreq,layout,shaping}` 包分簇进一个 Gradle 模块，包名、
   source range 与公共 API 全部保留。KMP 无法「源码分开、发布合一」（klib / metadata 合不了），
   故必须真源码合并。engine 侧 publication 从 60 份降到 10 份（含 PR #7 引入的 linux/mingw target；其中 6 份 native 不上远端，见 ADR 0055）。代价：失去六个概念层之间依赖方向的
   编译期强制，改由包结构纪律与 review 保证。

2. **顶层按宿主平台重组**：`engine/`、`platforms/{android,web,apple,jvm,compose}/`、
   `ffi/{js,native}`、`demo/`、`test-support/`、`docs/`、`tools/`。平台模块的逻辑 Gradle 路径
   随物理路径改为 `:platforms:<host>:<module>`；artifactId 由根 `build.gradle.kts` 的
   `publishedModules` 显式钉死、不绑路径。平台发布坐标同步理顺为**平台优先**命名
   （`tiqian-jvm-shaping` / `tiqian-jvm-skia` / `tiqian-android-shaping` /
   `tiqian-android-native-font`，compose 不变），见 ADR 0048 amendment。

3. **模块 leaf 采用去前缀的干净命名**（pre-release 无兼容负担）：如 `:platforms:android:shaping`
   （原 `shaping/android-adapter`）、`:platforms:web:frontend`（原 `frontend/web`）。改 project.name
   对 artifactId、Kotlin/JS `outputModuleName`、Android namespace 均无影响（三者都显式声明）。

4. **启用 cinterop commonization**（`gradle.properties` 加
   `kotlin.mpp.enableCInteropCommonization=true`）：合并后 engine 的共享 `nativeMain` 同时含
   shaping 的 cinterop 用户代码，共享元数据编译需要 commonized cinterop。

5. **Losses 的 Rust 代码不动**：`frontend/rust` 与 `frontend/web-precompute`（含其 `rust/` 与
   多个 CI 路径）归属待与 Losses 商定，本轮原地保留。

## Consequences

- 坐标变化：`tiqian-core` / `-font` / `-linebreak` / `-clreq` / `-layout` / `-shaping-api` 六个
  artifact → `tiqian-engine` 一个；平台坐标改平台优先（见 ADR 0048 amendment）。tiqian-math /
  tiqian-markdown（及 zhplus）的依赖声明需改指新坐标（pre-release，无兼容转发）。
- CI、脚本与当前状态文档中的模块任务路径 / 目录路径随之更新；ADR 属历史记录，保留当时路径不改。
- 纯移动，行为不变：golden 零 diff，engine 跨 JVM / JS / native(cinterop) / Android 编译，
  Compose / Web(npm) / Android demo / FFI(js+native) 构建均已验证。
- `platforms/` 下四个 shaping 实现共用 leaf 名 `shaping`、两个前端共用 `frontend`：因所有
  artifactId 与 JS 输出名显式，重名无冲突。
- engine 的 `jvmTest` 对 `:platforms:jvm:shaping` / `:platforms:jvm:skia` / `:test-support` 形成
  测试作用域的反向依赖边；任务图仍是 DAG，Gradle 正常处理。

## Amendment (2026-08-23)

Web 前端迁回 `frontend/web`。按平台分组的迁移与 Losses 当时开着的 PR #10
（web 平台集成架构重构，150 文件）冲突，且 web 栈被劈成 `frontend/`（precompute、rust）
与 `platforms/web/` 两半。恢复后整个 web 域重新聚在 `frontend/` 下；web 域的最终布局
（含 `platforms/web/shaping` 的去留）随 PR #10 的架构与 Losses 商定后另定。
