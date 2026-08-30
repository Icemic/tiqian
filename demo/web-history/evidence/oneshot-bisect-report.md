# oneshot-bisect 报告：b649841..HEAD 的 box 变动严格台账

日期：2026-08-27。区间：`b6498412..1ad320ce`（679 个提交，含基线自身共 680 个拓扑位）。
工作树：`/tmp/tiqian-oneshot-bisect`，分支 `integrate/oneshot-bisect`。
全部证据保留在 `.agent-specs/oneshot-bisect-evidence/`，本报告每个数字都给出对应文件。

## 1. 结论

1. **one-shot 缺陷引入提交：`bed4c791`**（2026-08-26，`feat(web): export the prose host session public api`）。
   检出侧 20/20 run 出现跨侧 box 分歧（两侧各自连拍自一致），父提交 `7e2d1909` 20/20 run 零失败。
   该缺陷到 HEAD 仍存在（`9561c747`、`acdce952`、`1ad320ce` 的链式采样形状未变，缺陷轴独立于链式形状史）。
2. **链式变动史：30 个采样点、29 对相邻 diff，其中 25 对零分歧、4 个非零变化点。**
3. **持续到 HEAD 的 box 变化：1 个**（变化点 2，`6ff37b45`，分类「归属不明」）。
4. **中途出现又被修复的 box 变化：2 个**（变化点 1 由变化点 2 修复；变化点 3 由变化点 4 修复）。
5. 变化点按个数计数；box 个数只作证据，不进入计数。

## 2. 比较语义与一致性核对（冻结条款）

比较实现冻结于 `demo/web/tests/helpers/deep-geometry.mjs`：采集每个 `tiqian-prose` 根、
每个段落、每个 `data-tq-line-index` 行标与每个段落直接子节点；元素取
`getBoundingClientRect`、文本节点取 Range；每 box 记 x/y/width/height 四值，
舍入 0.01px 后逐值精确相等，容差为零，不抽样、不丢弃任何 box；计数或页高任一
不等即记分歧；计数为零判无效并重跑。

kit（`demo/web-history/oneshot-history-harness.diag.ts`）内联同一语义的三个组件，
本次核对为逐字节一致：

| 组件 | 核对结果 |
| --- | --- |
| `DEEP_GEOMETRY_HELPERS`（页内采集器模板） | 两侧各 1141 字节，完全一致 |
| `diffDeepGeometry` | 函数体逐字一致 |
| `deepGeometryCounts` | 函数体逐字一致 |

链式 diff 通过 `demo/web-history/scripts/chain-diff.ts` 直接调用 harness 导出的
`diffDeepGeometry`，不另写比较代码。全程未改比较语义，未加容差，未丢弃任何失败记录。

## 3. 阶段 0：时代表与渲染验证

固定语料页加时代表达器（`demo/web-history/`），统一调用面
`__historyEnhance` / `__historyOneShot` / `__historyTerminal`；chain 模式为
scrollTop=0 的单次协同采集加 400ms 自一致复采。时代表文件在
`demo/web-history/eras/`，逐断点渲染验证结果：

| 时代 | 断点提交 | 表达器 | 渲染验证 |
| --- | --- | --- | --- |
| E0-alpha3 | b6498412 | e0-alpha3.json | 通过 |
| E1-apiverbs | 876612ef | e1-apiverbs.json | 通过 |
| A1b | 0835074e | a1b-emphasis-prepared.json | 通过 |
| A0 | 3f2996d0 | a0-prepared-dom.json | 通过 |
| A1c | 5c76cf68 | a1c-prepared-bridge-only.json | 通过 |
| A1 | bf506b34 | a1-prosecore-split.json | 通过 |
| A2d | 88557fca | a2d-styles-ship.json | 通过 |
| A2e | de926c85 | a2e-probe-bootstrap.json | 通过 |
| A2c | 6ff37b45 | a2c-lineflow-fix.json | 通过 |
| A2b | 4370925f | a2b-replay-options.json | 通过 |
| A2a | 733d779a | a2a-worker-serial.json | 通过 |
| E2a-tsport | 73449b70 | e2a-postport.json | 通过 |
| B0 | 2aafd7f1 | b0-retire-kjs.json | 通过 |
| B1 | 51efc35a | b1-coordination-mid.json | 通过 |
| B3 | d0c5f50f | b3-rawdom-context.json | 通过 |
| B4 | 5c9d0a30 | b4-loader-state.json | 通过 |
| B2 | e99c4943 | b2-boundary-ci.json | 通过 |
| E2b-workspace | 4818b3f3 | e2b-workspace.json | 通过 |
| C9 | cd08a2c7 | c9-wildcard-subpath.json | 通过 |
| C10 | 23e36988 | c10-validator-off.json | 通过 |
| C8 | efc62a80 | c8-rawdom-context-fix.json | 通过 |
| C7 | af4f310f | c7-dto-declare.json | 通过 |
| C1 | b4f90ec3 | c1-dto-assert.json | 通过 |
| C5 | ca8eb84a | c5-metrics-dto.json | 通过 |
| C6 | 4e2b3747 | c6-replay-keys.json | 通过 |
| E2c-dtowave | 0c135ee3 | e2c-dtowave.json | 通过 |
| E3 | bed4c791 | e3-session.json | 通过 |
| E4 | 9561c747 | e4-register.json | 通过 |
| E5 | acdce952 | e5-apidissolved.json | 通过（chain 面；one-shot 面见 §7） |
| E7-head | 1ad320ce | head.json | 通过 |

阻塞与不可构建记录（不静默跳过）：

- **阻塞窗口 `6f5e0316..11067981`**：`6f5e0316` 删除了 `frontend/web` 的
  `build.gradle.kts`，浏览器可渲染的产物配方消失，直到 `73449b70` 用
  ts-runtime 恢复。窗口内断点（含 `9f799c97`，已备 A2 表达器）记录阻塞后跳过。
  `e8752ae4` 是窗口边界的初判点，其后收窄为上述范围。
- **暂态不可构建的中介提交**（tsc 参数与类型断裂，构建失败原样记录后取下一候选）：
  `b5397a85`、`336d1ad7`、`05752c8c`。

## 4. 阶段 1：one-shot 缺陷引入点

二分区间 `[首个能表达 __historyOneShot 的断点, HEAD]`。判据：任一 run 出现跨侧
box 分歧（coordinated 对 one-shot）且两侧连拍自一致差异为 0；每探测点 N=10，
边界两侧加跑到 20。

| 拓扑位 | 提交 | 探测结果 | 证据 |
| --- | --- | --- | --- |
| 598 | f4371956 | 10/10 零失败 | `f4371956/{1..10}.json` |
| 609 | 70b576f6 | 10/10 零失败 | `70b576f6/{1..10}.json` |
| 615 | 0e46a072 | 10/10 零失败 | `0e46a072/{1..10}.json` |
| 616 | 30a783cb | 10/10 零失败 | `30a783cb/{1..10}.json` |
| 617 | 6446b33d | 10/10 零失败 | `6446b33d/{1..10}.json` |
| 618 | 7e2d1909（父） | **20/20 零失败（稳定，满足 ≥10 连跑）** | `7e2d1909/{1..20}.json` |
| 619 | bed4c791 | **20/20 分歧** | `bed4c791/{1..20}.json` |

检出侧 20 个 run 全部有效、全部分歧，分歧理由均为「滚动位 640..3200 各档
两侧采集各自自一致但跨侧 box 分歧」，即 one-shot 再增强触发了重排。干净侧
20 连跑零失败满足边界「稳定」的最低 10 连跑要求。结论：
**首个检出提交 = `bed4c791`，缺陷由该提交引入，不是「自始存在」。**

## 5. 阶段 2：链式变动史

30 个采样点按拓扑序链式 diff（相邻对共 29 对，全部结果持久化于
`.agent-specs/oneshot-bisect-evidence/chain-diffs/pair-01..pair-29.json`）。
形状阶梯（lineMarks/runEls/textNodes，页高全程 4926）：

- **S0** = 187/1056/346：b6498412..3f2996d0
- **S1** = 172/1106/303：5c76cf68..de926c85
- **S2** = 187/1318/369：6ff37b45..HEAD（扣除 S3 窗口）
- **S3** = 159/1210/346：5c9d0a30..cd08a2c7

### 5.1 采样点全表

拓扑位取自 `git rev-list --reverse --topo-order b6498412^..1ad320ce`（共 680 位）。

| # | 拓扑位 | 提交 | 形状 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 12 | b6498412 | S0 | `b6498412/chain-p0.json` |
| 2 | 275 | 876612ef | S0 | `876612ef/chain-p0.json` |
| 3 | 314 | 0835074e | S0 | `0835074e/chain-p0.json` |
| 4 | 321 | 3f2996d0 | S0 | `3f2996d0/chain-p0.json` |
| 5 | 323 | 5c76cf68 | S1 | `5c76cf68/chain-p0.json` |
| 6 | 343 | bf506b34 | S1 | `bf506b34/chain-p0.json` |
| 7 | 355 | 88557fca | S1 | `88557fca/chain-p0.json` |
| 8 | 356 | de926c85 | S1 | `de926c85/chain-p0.json` |
| 9 | 361 | 6ff37b45 | S2 | `6ff37b45/chain-p0.json` |
| 10 | 363 | 4370925f | S2 | `4370925f/chain-p0.json` |
| 11 | 377 | 733d779a | S2 | `733d779a/chain-p0.json` |
| 12 | 422 | 73449b70 | S2 | `73449b70/chain-p0.json` |
| 13 | 423 | 2aafd7f1 | S2 | `2aafd7f1/chain-p0.json` |
| 14 | 477 | 51efc35a | S2 | `51efc35a/chain-p0.json` |
| 15 | 486 | d0c5f50f | S2 | `d0c5f50f/chain-p0.json` |
| 16 | 488 | 5c9d0a30 | S3 | `5c9d0a30/chain-p0.json` |
| 17 | 494 | e99c4943 | S3 | `e99c4943/chain-p0.json` |
| 18 | 530 | 4818b3f3 | S3 | `4818b3f3/chain-p0.json` |
| 19 | 531 | cd08a2c7 | S3 | `cd08a2c7/chain-p0.json` |
| 20 | 534 | 23e36988 | S2 | `23e36988/chain-p0.json` |
| 21 | 538 | efc62a80 | S2 | `efc62a80/chain-p0.json` |
| 22 | 540 | af4f310f | S2 | `af4f310f/chain-p0.json` |
| 23 | 549 | b4f90ec3 | S2 | `b4f90ec3/chain-p0.json` |
| 24 | 572 | ca8eb84a | S2 | `ca8eb84a/chain-p0.json` |
| 25 | 573 | 4e2b3747 | S2 | `4e2b3747/chain-p0.json` |
| 26 | 574 | 0c135ee3 | S2 | `0c135ee3/chain-p0.json` |
| 27 | 619 | bed4c791 | S2 | `bed4c791/chain-p0.json` |
| 28 | 621 | 9561c747 | S2 | `9561c747/chain-p0.json` |
| 29 | 639 | acdce952 | S2 | `acdce952/chain-p0.json` |
| 30 | 680 | 1ad320ce | S2 | `1ad320ce/chain-p0.json` |

29 对相邻 diff 中 25 对零分歧；非零的 4 对为
`pair-04`、`pair-08`、`pair-15`、`pair-19`（下表）。另有 3 个窗口 C 细化期间
加采的中间点（`08a7ebb8`、`f12eba9f`、`83d99d02`，拓扑位 563/569/570）全部与
相邻点零分歧，证据保留在对应目录的 `chain-p0.json`，不计入台账链。

### 5.2 四个变化点与分类裁定

分类按裁定规则：`frontend/web/**`、`demo/web/**`、ffi JS 侧改动不得改
box；仅 `engine/`、`platforms/`、ffi 的 Kotlin/Rust 允许改；纯重构区间出现
box 变化判「重构引发的缺陷」；区间多种提交并存且不可拆分判「归属不明」，
不默认归入允许。

**变化点 1 —— `5c76cf68`（2026-08-23，refactor(web): render every paragraph via the prepared bridge and drop the native renderer）**

- pair-04（`3f2996d0 → 5c76cf68`）：S0 → S1，1159/1642 box 分歧；丢 15 个行标
  （187→172），runEls 1056→1106，textNodes 346→303。典型例子：`p1:1`、`p2:1`、
  `p6:0` 的行标消失。
- 区间 `3f2996d0..5c76cf68` 内只有 `e47f6522`（docs）与该提交本身，无引擎侧改动。
- **分类：重构引发的缺陷。** 由变化点 2 修复（行标数复原至 187）。

**变化点 2 —— `6ff37b45`（2026-08-23，fix(prepared-dom): keep sub-epsilon justified stretch in the line flow identity）**

- pair-08（`de926c85 → 6ff37b45`）：S1 → S2，539/1634 box 分歧；`p1:1`（0→6）、
  `p2:1`（0→5）、`p6:0`（0→4）行标复原，但 runEls 1106→1318、textNodes 303→369，
  相对 S0 基线净增 262 个 run 元素与 23 个文本节点。
- 区间内行为提交仅此一个，但其内容同时触碰 `frontend/web-precompute` 的 Rust
  （`prepared_dom.rs`：SPACING_EPSILON 0.01 收紧为 SPACING_DUST_EPSILON 1e-6）、
  `frontend/web` 的 JS 镜像与 fixture，不属于允许改 box 的引擎路径，且多种
  内容并存不可拆分。
- **分类：归属不明。** 该残差持续到 HEAD，是唯一持续变化。

**变化点 3 —— `5c9d0a30`（2026-08-25，refactor(web): replace the engine global slots with loader state and options）**

- pair-15（`d0c5f50f → 5c9d0a30`）：S2 → S3，334/1927 box 分歧；`p7:1`、`p8:4`
  行标清零（187→159），runEls 1318→1210，textNodes 369→346。
- 区间 `d0c5f50f..5c9d0a30` 内另一提交 `3f6db017` 只改注释；改动全部落在
  `frontend/web/**`。
- **分类：重构引发的缺陷。** 由变化点 4 修复。

**变化点 4 —— `23e36988`（2026-08-25，fix(web): stop running the snapshot validator on live commits）**

- pair-19（`cd08a2c7 → 23e36988`）：S3 → S2，175/1768 box 分歧；把
  `precomputed.preparedDomValidator` 从生产 loader state 移除（validator → null），
  `p7:1`、`p8:4` 行标复原，形状完整回到 S2。
- 区间内另两提交为 docs 与 CI；行为改动仅 `frontend/web/core/core/engine/loaders/runtime-loader.ts`。
- **分类：修复（消除变化点 3 的重构缺陷）。** 本身不引入新分歧：其后 10 对相邻
  diff 全部为零。

## 6. 持续与中途修复台账（按变化点个数）

**持续到 HEAD（1 个）：**

| 引入提交 | 分类 | 证据 |
| --- | --- | --- |
| 6ff37b45 | 归属不明 | `chain-diffs/pair-08.json`、`6ff37b45/chain-p0.json`、`1ad320ce/chain-p0.json` |

**中途出现又被修复（2 个）：**

| 引入提交 | 修复提交 | 分类 | 证据 |
| --- | --- | --- | --- |
| 5c76cf68 | 6ff37b45 | 重构引发的缺陷 | `chain-diffs/pair-04.json`、`pair-08.json` |
| 5c9d0a30 | 23e36988 | 重构引发的缺陷 | `chain-diffs/pair-15.json`、`pair-19.json` |

HEAD（S2）与区间起点（S0）相比：页高与行标数相同（4926、187），但 runEls
1056→1318、textNodes 346→369，差异全部归入变化点 2 的残差；全部 41 段的
逐段行标计数在基线与 HEAD 间两两相等。

## 7. 能力缺口与阻塞记录

- `acdce952`（E5，npm 包 api 入口解散）：one-shot 调用面不再可表达，one-shot
  侧采集记录为 INVALID；chain 面（协同增强加终态）仍可表达，证据有效。
- 阻塞窗口 `6f5e0316..11067981`：产物配方缺失，窗口内断点记录阻塞后跳过（§3）。
- 不可构建中介：`b5397a85`、`336d1ad7`、`05752c8c`（§3）。

## 8. 构建点预算

阶段 2 台账采样点 30 个（30 ≤ 30 上限）；窗口 C 细化另加采 3 个零分歧中间点
（§5.1）；阶段 1 二分探测 5 点加边界 2 点各 10/20 连跑；阻塞与不可构建尝试
原样记录。时代表达器、构建配方按时代切换（Kotlin bundle、`:ffi:js` 打包、
workspace tsc 三段式），全部命令包在 `nix develop -c bash -c` 内。

## 9. 交付物

- `demo/web/tests/oneshot-geometry-history.test.mjs`：以最终基线（`1ad320ce`
  chain 采集）为冻结基线，断言当前树零分歧；头部注释内嵌完整台账。
  该测试在当前树上通过（含真空门：计数为零判无效）。
- `demo/web/tests/fixtures/oneshot-geometry-history.json`：冻结基线
  （187/1318/369，页高 4926，全量几何）。
- 证据目录 `.agent-specs/oneshot-bisect-evidence/`：逐点链式采集、逐 run
  探测记录与 29 对相邻 diff 结果。
- kit（`demo/web-history/`）的修改以新提交带入 `integrate/oneshot-bisect`。
