# A5 度量回放表统一：现状两套结构与分片建议（2026-08-23）

ADR 0053 A5（`MetricTableAsEngineInput`）的实施前研究。目标形态出自 0053
背景一节：bake 与 fallback 的差别只剩度量表的填表人（构建期 HarfBuzz 或
运行时 canvas），执行位置在 Worker 一处。本文记录现状两套结构的精确位置、
统一面临的约束，以及可分片执行的顺序。

## 现状：引擎的两路字体输入

### 表路（bake）

- 规范键与条目结构：`frontend/web/npm/snapshot-schema.js`
  （`shapeReplayKey`、`metricReplayKey`、`FONT_REPLAY_REVISION`）。
  shape 条目为 em 单位的 glyphs/features/unsafeBreakCount/advanceEm；
  metric 条目为 `valuesEm` 五元数组。
- 会话与读侧：`frontend/web/npm/browser-font-replay.js` 安装
  `__TiqianFontBackend`，读时按 fontSize 把 em 缩放为 px
  （`scaledShape`/`scaledMetrics`），miss 即
  `MissingServerShapingReplay:shape|metrics:<key>`。
- Worker 消费：`frontend/web/npm/layout-worker.js:12-52` 只建 replay 会话；
  manifest 无 `fontReplay` 时抛 `LayoutWorkerFontContractInvalid`，
  Worker 没有 bake 之外的路径。
- 主线程 exact 会话消费：`shaping/api/.../HarfBuzzSessionBackend.kt`
  （HarfBuzzSessionTextShaper/MetricsResolver 经同一 backend 读表）。

### canvas 路（无 bake）

- `frontend/web/src/jsMain/kotlin/org/tiqian/web/WebEnhancer.kt:209-234`：
  无 exact 会话时构造 browserEngine =
  `WebCanvasTextShaper` + `WebCanvasFontMetricsResolver`，主线程直接跑引擎。
- `shaping/web-adapter/.../WebCanvasTextShaper.kt:282-406`：每 cluster 一个
  单 glyph run（id=0、advance 为测量时的 px 值）、ink bounds 靠栅格化
  （:497-499 一段注释）、feature 测量走隐藏 DOM Range（:421-429 的
  CanvasDomAdvanceParityGate）、测量有 memo 缓存（:420）、dash 能力 issue
  命名在 adapter（:297-309，B10 策略点 1 的对象）。
- 该路没有键空间、没有条目、没有表；单位制与表路不同（px 对 em）。

## 统一的约束与待决点

1. Worker 无 DOM。canvas 路的三件事在 Worker 里不可原样搬：隐藏 DOM Range
   的 advance 校验与 feature 测量、dash 能力的宿主异步探针、任何依赖
   document 的回退。OffscreenCanvas 的 measureText 与栅格化可用。
2. 空表会话今天被拒。`createServerReplayFontSession` 对空 shapes/metrics
   抛 `ServerShapingReplayEmpty`；无 bake 引导需要新的会话形态（空表 +
   探测回填），是 schema 层面的决定（`FONT_REPLAY_REVISION` 是否升版）。
3. 条目没有 capability 字段。dash 与 feature 证据今天经
   ShapingDecisionInfo 主线程内传递；若探测回填要保留这些证据，条目结构
   需要扩展，或由引擎侧策略（B10 策略点 1 的方向）在表外命名。
4. em 换算与容差。表条目以 em 存储、读时乘 fontSize；canvas 在具体 px
   字号下测量，写入时除以字号。两侧浮点表示需同一通路（写 f64、读 Float
   截断与 bake 路一致），parity 测试覆盖。
5. `MissingServerShapingReplay` 语义保持：键在表中缺失且不可探测时同样
   报错；可探测与否是策略决定，策略需命名并进测试。

## 分片建议

1. **A5a 条目编码器共享模块**：shape/metric 条目的 em 编码与规范键组成
   单一模块（snapshot-schema.js 旁），构建期写入器与运行时探测共用；
   roundtrip 与损坏输入测试。机械性强，可委托。
2. **A5b 探测回填 backend**：表 miss 时经 OffscreenCanvas measureText 探测
   （advance 条目、bounds 空、证据来源命名），按规范键把 em 条目写回会话
   表再缩放返回；同键再访走表。可探测键集与不可探测集的策略命名并测试。
3. **A5c Worker 无 bake 引导**：layout-worker 接受空表会话（新会话形态），
   装 A5b backend；端到端测试：无快照输入经 `precomputeParagraph` 出 plan，
   断言与既有主线程 canvas 路的 plan 逐字段一致（允许的浮点差单列）。
4. **A5d 主线程 canvas 引擎移除**：worker 可用路径统一走表；保留
   首批视口段落的 pre-paint 同步快路径（ADR 0053 `ExecutionInWorker`
   一节既定范围）。

A5b 与 A5c 依赖 A5a；A5d 依赖 A5c。B10 策略点 1（dash 命名入引擎策略）
建议先于或随 A5b 做，避免条目证据字段两道设计。

## 验收对应

- 度量表表示结构份数 1：A5a 后写入器单点；A5b 后两路填表同构。
- 无快照路径端到端 plan 测试：A5c。
- `MissingServerShapingReplay` 语义不变：A5b 的不可探测键测试。
