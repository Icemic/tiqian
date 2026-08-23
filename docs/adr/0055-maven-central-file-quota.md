# ADR 0055：Maven Central 文件配额——native 发布过滤与 SNAPSHOT 通道

- Status: Accepted
- Date: 2026-08-23

## Context

org.tiqian 组织的 Maven Central 用量三项全部超线，其中文件数是结构性瓶颈：单轮全套件发布
约 4,500 个文件，上限为每月 1,000（2026-10-01 起按三个月滑动平均强制）。根因是 Kotlin
Multiplatform 的多模块 × 多 target × 每个 publication 的 jar/pom/module/sources/javadoc × 签名
与校验和的乘法，靠降频压不进上限。三仓（tiqian / tiqian-math / tiqian-markdown）同属一个组织
账号，配额按组织合并计。

engine 六模块（core / font / linebreak / clreq / layout / shaping-api）为 Apple 与其他 native
宿主声明了 iosArm64 / iosSimulatorArm64 / macosArm64 / linuxX64 / linuxArm64 / mingwX64 目标。
这些目标的仓内消费者——ffi/native 暴露的 packed C ABI（ADR 0050）、frontend/apple、
shaping/coretext——都以 project 依赖从源码构建，不从 Central 解析制品；ffi 不在发布集内，
Apple/native 前端也尚未对外发布。

## Decision

1. **不向远端 Central 仓库上传 Kotlin/Native klib。** 在发布层过滤 engine 各模块
   ios* / macos* / watchos* / tvos* / linux* / mingw* 的 publication，对 `central`（release
   staging）与 `centralSnapshots` 两个远端仓库都不上传。target 本身保留，Maven Local 不受
   影响——仓内 native/Apple 构建与本地套件（`enable-local-suite.sh`）照常。等有 native/Apple
   制品真正对外发布，再逐 target 重新打开远端发布。

2. **alpha / 开发版走 Central Portal SNAPSHOT 通道。** 根构建新增 `centralSnapshots` 发布仓库，
   端点 `central.sonatype.com/repository/maven-snapshots/`，凭证与 release staging 同一套 Portal
   token。以 `-SNAPSHOT` 结尾的版本发这里（聚合任务 `publishTiqianToCentralSnapshots`），正式
   版本继续走 staging（`publishTiqianToCentral`）。需在 Central Portal 的 Namespaces 页对
   org.tiqian 一次性 Enable SNAPSHOTs。

## Consequences

- 每轮 Central release 实际上传的 publication 从 72 份降到 16 份（发布图 22 份，其中
  engine 的 6 份 native 对远端禁用），文件数约降至原来的两成余。
- SNAPSHOT 自动 90 天清理、不进搜索索引：博客示例、下游锁版本等任何需要长期可复现的引用都
  不得指向 snapshot。
- 消费端拉取预览版需显式加入 snapshot 仓库并限定到 `org.tiqian`（`snapshotsOnly` +
  `includeGroup`）；README 在通道启用并验证后补充示例。
- snapshot 是否计入配额官方未写死：启用后先发一次、回 Usage Center 面板确认数字未动，再全面
  切换 alpha 节奏。
- 这是第一刀。engine 六模块 target 集一致，第二刀（六模块真源码合并成一个发布单元，把
  42 份 publication 压到 7 份）与目录重组绑定、另立 ADR；豁免邮件、web alpha 走 npm、正式
  release 每月攒批 ≤2 轮是配套的流程决定。
- CI publish 工作流当前仅发 release 且显式拒绝 `-SNAPSHOT`；snapshot 的自动化触发另行决定，
  眼下可用聚合任务在本地或专用工作流发布。
