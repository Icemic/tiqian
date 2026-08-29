# tests-ts 迁移与 lint 清算报告

日期：2026-08-28。范围：frontend/web/{core,npm,react}、demo/web、ffi/js/npm
测试套件的 TypeScript 迁移与 ts-discipline lint 达标。

## 提交序列

| 提交 | 内容 |
| --- | --- |
| 368035f4 / a1e1c44f | core 测试套件迁移与 ts discipline 收编 |
| acab7364 | react 测试套件迁移 |
| ee7e409a | npm 测试套件迁移（243/243） |
| 3248d230 | Wave 2 改名的 check 引用 follow-up（boundary-check 豁免表、package-topology 扫描后缀、CI workflow 路径） |
| aef1956d | demo/web 测试套件迁移（44 测） |
| 37165fdf | npm 测试套件 lint 清扫 |
| 38965cc1 | react 与 demo/web/tests 纳入 lint 范围并清零 |

## lint 清算（879 → 0）

迁移验收时曾记录「eslint rc=0」，实际是把 eslint 跑在包目录下、files 模式
匹配到空文件集，工具对空集返回成功，该记录不成立。用户质询后实测揭穿。
起点与终点：

- 起点（2026-08-28 实测，CI 调用形态，仓库根）：879 error
  （423 双断言、228 内联对象形状、189 内联函数形状），全部集中在
  frontend/web/npm；规则（ADR 0053 StrictTsDiscipline 与 G1 code standard
  的三条 no-restricted-syntax）自 f8a5f3b4 起存在且有效。
- 终点：全量 eslint exit 0；react 加入 targetPackages；demo/** 一刀切忽略
  改为精确清单，demo/web/tests 进入覆盖，demo 其余目录保持忽略。
- 覆盖实证（防止空文件集再次造成误判）：向
  demo/web/tests/justify-grid.test.ts 临时注入 `1 as unknown as number`，
  lint 立即报 ADR 0053 双断言 error；还原后 exit 0。

修整方式（行为零变化约束下）：

1. `X as unknown as Element` → `X as Element`（FakeElement 与 Element 结构
   重叠足够，单断言直接编译通过；双断言从来不是必需的）。
2. 探针形状（宿主鸭子探测的内联形状断言）具名化为 types.ts 里的 interface
   后单断言引用。
3. 无重叠必须强转的统一经 `probe<T>(value: unknown): T` 处理（函数体内
   unknown → T 单断言；各测试支撑文件一处定义，调用点不限）。
4. annotation 位置内联对象/函数形状全部具名化（跨文件共享进 tests/types.ts，
   单文件局部放同文件类型声明区）。

## 测试套件复验矩阵（全部中央独立复跑）

| 套件 | 计数 | 备注 |
| --- | --- | --- |
| frontend/web/npm | 243/243 | |
| frontend/web/core | 444/444 | |
| frontend/web/react | 6/6 | |
| ffi/js/npm | 7/7 | |
| demo/web | 43/44 | 唯一失败是 npm-published-vs-dev 已知 drift（已发布包落后工作树的排版几何 diff 签名，发布后自愈，非回归） |

行为审计结论：两清扫提交均为类型层修改。timing-golden-host 的
`documentElement.clientHeight = 800` 搬家保留；parent 赋值方向与
isConnected 置位语义不变（仅 cast 形式重写）；react/binding.ts 为纯类型
提升；测试标题与断言值逐字未动。

## 偏差记录

1. react binding.test.ts 一处 assert 消息文本被派发任务改写
   （"Must find the blank page target" → "must find a page target"）。
   断言语义与期望值未动，无测试匹配该文本；属超出授权的改动，记入本报告。
2. demo 全量复跑中 drag-responsiveness-metrics 曾在系统负载下挂过一次
   debounce 窗口断言（单独复跑 3/3 过，后续两次全量亦过）；定性为负载敏感
   时序 flake，若复发升级为正式调查。
3. 首条清扫派发任务死于 agy 网关单响应超时（wrapper 已带 2h
   print-timeout，死因在网关层），现场 5 文件部分改动经续作条款由第二条
   派发任务接手完成。

## 教训记录

lint 类工具对空文件集返回成功；rc=0 只证明「跑完了」，不证明「查了东西」。
lint 验收必须用 CI 调用形态（仓库根）并带非空文件集证据。该教训与执行
细节已入会话记忆（lint-vacuous-green-lesson）。
