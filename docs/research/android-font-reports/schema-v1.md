# Android 字体行为证据 schema v1

schema 名称为 `org.tiqian.android-font-evidence`，当前版本为 `1`。ZIP 是传输容器，事实模型由
包内 JSON/JSONL 条目定义；ZIP 条目顺序和压缩结果不参与比较。

## 状态

任何可能受 API、权限或运行时失败影响的层都使用以下状态：

- `observed`：字段来自本次设备观测；
- `unsupported`：当前 API 或应用权限不能观测，必须保留为未知；
- `error`：设备声明支持，但本次调用失败，并附异常类型与消息。

禁止把 `unsupported` 或 `error` 转换成空数组、`false`、相同或零。

## `manifest.json`

记录：

- schema 名称与版本；
- UTC 采集时间；
- APK 版本、构建类型、Git revision 和 dirty 状态；
- Android build fingerprint、API、设备型号、默认 locale 与 ABI；
- 每项采集能力的最低 API 和本机状态；
- 顶层请求状态计数，以及包括子证据层在内的全部状态节点计数；
- 除 manifest 自身外每个 ZIP 条目的名称、字节数与 SHA-256。

设备身份用于区分系统镜像和字体包，不能参与“同一请求是否换字体”的字段级比较。

## `observations.jsonl`

每行是一个完整 JSON 对象，使用稳定 `id`。当前有两类：

### `platform-shape`

`probe` 保存文本、语义标签和明确的 LTR/RTL 方向；`request` 保存 Typeface 构造方式、家族来源、
locale、精确字重、legacy style 与可变轴请求。`Typeface.create(name)` 的结果只表示该请求最终的
平台行为，不证明 name 一定被平台识别。

观测分为三个相互独立的层：

- `runMetrics`：`measureText`、`getRunAdvance` 与请求 Paint 的 font metrics；
- `glyphReadback`：API 31+ 的 `PositionedGlyphs`。每个 glyph 保留 glyph id、x/y、实际 `Font`、
  source identifier、文件路径与 SHA-256、TTC index、style、axis、locale、glyph bounds 和 nominal
  glyph advance；API 35+ 另有 fake bold/italic 与 style override；
- `raster`：固定白底软件 Bitmap 上由同一 Paint 调用 `drawTextRun` 得到的 ARGB SHA-256、墨迹像素数
  和像素 bounds，并以 `pngEntry` 指向 `renders/` 中的实际 PNG。hash 用于发现“文件、glyph id、
  advance 均相同但轮廓发生变化”的情况；PNG 则保留可供肉眼复核的 OEM 实际外观。

三个层可以分别为 `observed`、`unsupported` 或 `error`，不得因 glyph 读回不可用而丢掉确实测到的
run metrics 和 raster。

公开 `PositionedGlyphs` 不提供 glyph 到 UTF-16 cluster 的映射，因此 `sourceMapping` 明确为
`unsupported`；不能根据 glyph 顺序擅自补造字符映射。glyph 与其实际 `Font` 的关联仍由同一个
`PositionedGlyphs` 索引直接给出。

### `paint-has-glyph`

同时记录整段 `Paint.hasGlyph` 和逐 Unicode code point 结果。整段结果表示该序列能否合成单个 glyph，
不等价于“全文是否没有缺字”；逐 code point 结果也不能证明 emoji、变体选择符或复杂文字序列的
shaping 正确。

## 字体配置与清单

`font-config.json` 按源文件分别保留 family/alias 顺序，不把多份配置擅自合并成“有效 fallback 图”。
解析结果与 raw XML 的 SHA-256 相连；主题引擎和运行时替换仍须由 shaping 观测证明。

`system-fonts.json` 忠实记录公开 API 的无序字体集合。它不补造 named-family 归属或 fallback 顺序。
`font-directories.json` 只记录存在性、可见性、文件名和大小，不按路径或文件名推断“厂商字体”。

## 比较规则

比较工具应按 observation `id` 对齐，再分别比较 request、能力状态和证据字段。Android 版本、
采集器版本、字体文件哈希或 API 能力不一致时，报告应先标注混杂因素，不能把所有差异归因于 OEM。

AOSP 样本是参照而非规范；提椠当前实现也不进入这份证据 schema。实现验收应在另外一层消费已经
审阅的平台事实。
