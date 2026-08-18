# ADR 0048：套件 Maven 坐标与包命名

- Status: Accepted
- Date: 2026-08-12

## Context

提椠、数学与 Markdown 已经形成三个独立仓库，但会以同一版本联合发布。首次发布前需要固定
Maven 坐标和公共包边界，避免消费者同时依赖多个入口时出现重复品牌前缀、平台含义不明或以后
拆分平台前端时再次迁移 import。

## Decision

三个仓库统一使用 Maven group `org.tiqian`。artifact 以产品族区分：

- 提椠核心保留 `tiqian-*`，避免 `core`、`font`、`layout` 等过泛名称；Compose 入口为
  Foundation 中立的 `tiqian-compose`，可选 Material 3 上下文适配为
  `tiqian-compose-material3`；
- 数学使用 `math-*`，Compose 入口为 `math-compose`；
- Markdown Compose 入口为 `markdown-compose`。

不把后两者写成 `tiqian-math-*` 或 `tiqian-markdown-*`，因为 group 已经表达发布者，重复前缀
不会增加归属信息。

Kotlin 包按领域和平台职责划分：

- 数学公共包保持 `org.tiqian.math.*`；
- Markdown 的平台无关文档模型、源码范围与高亮契约保持在 `org.tiqian.markdown`，Compose
  renderer、样式和交互入口放在 `org.tiqian.markdown.compose`；以后拆出 `markdown-core` 时
  中立模型无需再次迁移；
- Android native font backend 使用 `org.tiqian.shaping.android.nativefont`，对应 Gradle 模块
  `:shaping:android-native-font` 和 artifact `tiqian-shaping-android-native-font`；
- Android View 契约使用 `org.tiqian.android.view`；Apple Core Text renderer 使用
  `org.tiqian.apple.coretext`，与 `org.tiqian.shaping.coretext` 区分。

Android namespace 跟随具体发布模块，不要求 `shaping/api` 等平台打包标识与所有 Kotlin
公共类型处于同一个末级包。Gradle 根项目名使用小写仓库名；它不构成 Maven 坐标。

## Consequences

- 消费者可以从坐标直接识别产品族，同时避免重复品牌名。
- 三个 Compose 入口分别位于 `org.tiqian.compose`、`org.tiqian.math.compose` 和
  `org.tiqian.markdown.compose`。
- Material 3 适配入口位于 `org.tiqian.compose.material3`；未使用 Material 3 的宿主不必引入
  对应 artifact。
- 这次迁移发生在首次公开发布前，不提供旧包名或旧 artifact 的兼容转发。
- `org.tiqian` 必须先在 Maven Central 完成 namespace 验证，发布流程才能使用这些坐标。

## Amendment：仓库持有各自的发布流程（2026-08-12）

三个仓库各自持有 Maven staging 工作流，只上传本仓库拥有的 publication，并分别创建
user-managed Central Portal deployment。Central token 与 PGP 签名密钥由 GitHub 组织持有，
只授权给这三个发布仓库。

联合发布仍先用隔离 Maven 仓库验证同一版本的真实依赖与消费者编译；Markdown staging 在本地
准备同版本的 Tiqian 与 Math 产物，但不得代替它们上传。三个 deployment 全部通过 Central
验证后，再按 Tiqian、Math、Markdown 的依赖顺序公开。这样保留锁步版本的发布门槛，同时让
失败重试、权限和产物所有权停留在各自仓库。
