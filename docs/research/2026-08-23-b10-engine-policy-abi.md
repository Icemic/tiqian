# B10 引擎策略出 ABI：两处策略点现状与建议形状（2026-08-23）

B9 完成后（c866219）对 ADR 0053 B10 的实施前研究。B10 要求：富文本 run 降级判定
与 dash 能力判定经 ABI 输出决策，不迁 TS；策略行为与现行判定逐例一致。
本文记录两处策略点的现状位置与建议形状，供执行时直接引用。

## 策略点 1：dash 能力判定

现状：

- 证据来源：宿主异步探针产出 status/detail，经 EnhanceOptions.cjkDashCapability
  注入（`WebEnhancerParagraphLifecycle.kt:44-50`），shaper 构造点
  `WebEnhancer.kt:209`。
- 判定位置：`shaping/web-adapter/.../WebCanvasTextShaper.kt:292-309`。source 为
  CJK dash（或 display 为两字线）时，shaper 自己命名策略结果：
  status 为 conforming 时发 `ConformingCjkDashRequiresExactFontSession`，否则发
  `NoConformingCjkDashGlyph`，detail 的组装分支也在 adapter。

问题：策略结果的命名与分支在平台 adapter 内。这违反模块边界约束（平台层
不得自行决定策略；需要平台证据的规则应把证据送回核心 decision）。

建议形状：

- adapter 只上送证据：探针 status/detail 原样进入 ShapingInput 或引擎构造时的
  capability 描述符，dash source 的识别与 issue 命名分支移入 font policy
  （或 clreq 规则层），作为命名策略进入 LayoutResult debug info 与 plan
  evidence。
- 验收：jsTest dash 组（TiqianWebEnhancerTest / SourceFidelityTest 中 dash
  相关用例）逐例不变；LayoutDumpGoldenTest 零 diff。

## 策略点 2：富文本 run 降级判定

现状：

- `frontend/web/npm/core/engine/markdown-lowering.js:511-534`：
  unsupportedInlineShapingProperties 十六属性清单与 inlineShapingStyleIssue
  命名 `UnsupportedInlineFormattingContext` / `UnsupportedInlineShapingStyle`。
  B9 第一步移植后，清单与命名都在 TS 侧。

建议形状：

- 仿 classifyRole 回调：markdown-lowering 的 helpers 增加
  inlineShapingDecision(properties) 回调，JS 只收集 computed style 事实并
  上送；十六属性清单、判定分支与 issue 命名移回 Kotlin 侧策略注册处
  （clreq 或 font 模块），MarkdownParagraphLowerer facade 组装回调。
- markdown-lowering-bridge.test.mjs 增判定组（命中属性逐类一例、未命中一例）；
  jsTest 降级组行为不变。
- 该回调与 classifyRole 同为过渡形态：B10 的终态是两项策略都由引擎在
  plan evidence 输出，宿主不再持有判定清单。

## 执行顺序建议

1. 策略点 2（回调形态，改动面窄：markdown-lowering.js、bridge test、
   MarkdownParagraphLowering.kt、策略清单新家）。
2. 策略点 1（跨 shaping/api 与 font 模块，需要 ShapingInput 或引擎构造面的
   接口扩展，golden 风险在 dash 策略路径）。

两步各自独立提交，均以 jsTest 对应组与 npm test 通过为验收。
