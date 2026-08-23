# Compose Android 字体选择审计

日期：2026-08-05

> 实施状态：本文审计的是修正前实现。当天后续改动已经完成“修正顺序”第 1、2 步：有序 family
> fallback、有效轴与 API 35+ style override 实例、单调 catalog revision、Compose cache invalidation、
> 旧结果 face 保留，以及 API 31+ 的逐请求平台字体读回。API 23–30 默认路径另增加了有序
> `fonts.xml` 声明目录，并明确报告运行时选择不可观察。

## 结论

Compose Android 当前的 HarfBuzz / FreeType 测量与字形重放可以保证“选中的字体”测量、布局和
绘制同源；问题发生在它之前：默认字体目录把 `SystemFonts.getAvailableFonts()` 这个无序字体集合
当成了 Android 默认 family / fallback 关系，再用文件名、locale 和固定 400 / 700 字重猜实际字体。

这条路径不能继续叫作“系统默认字体”。在 8 份支持逐 glyph 字体读回的 OEM 样本中：

- 当前西文选择规则都会优先选 Roboto；8 份样本的默认西文实际字体都不是 Roboto。
- 当前中文规则只有 OnePlus 系统默认样本能够得到与平台默认等价的正文实例；其余样本至少在
  字体文件、TTC 子字体、可变轴实例或 style override 中有一项不同。
- 8 份样本的中文标点都与中文正文使用同一组字体路径；当前目录却同时允许中文脸和西文脸承担
  `CjkPunctuation`，最终按字重与 `FontFaceId` 排序，不保留“中文优先”的 fallback 次序。
- 8 份样本的默认 emoji 都由 `/system/fonts/NotoColorEmoji.ttf` 绘制；当前默认目录没有收录这个
  字体，只把 `Emoji` 角色赋给猜出的中文脸。

因此，当前默认目录会破坏用户在系统里选择的字体模块、主题字体和 OEM 默认可变字重。它可以
保留为具名的诊断性 heuristic，不能作为 Compose 生产默认路径。

## 审计依据与边界

证据来自本地私有机器目录；公开仓库只保留
[脱敏汇总](oem-samples-v1.md)。11 份证据包全部通过 manifest、成员 SHA-256、结构、observation ID、
状态与 PNG 引用校验；其中 API 31+ 的 8 份支持 `PositionedGlyphs.getFont()` 逐 glyph 读回。
API 26 / 29 的 3 份样本只能证明栅格、宽度和字体配置，不能用于判断实际字体文件。

这批采集器均来自 revision `8e977b9d53a5` 的 dirty build。包内文件哈希和观测仍可校验，但它们
不能代替同 revision 的干净可复现构建；扩大设备样本前应先发布有固定版本身份的采集器。

本审计比较的是：

1. 平台对默认 `Typeface` 做 shaping 后实际返回的字体文件、TTC index、轴和 style override；
2. `PublicSystemFontsCatalog` 对同一份 `system-fonts.json` 按当前 Kotlin 规则会选出的字体；
3. Compose measurer、字体目录和缓存的当前源码行为。

它不根据字体文件名推断主题来源，也不把不同设备之间的差异当成受控因果。只有那组明确来自同一
OnePlus 设备的样本可用于比较“系统默认”与“启用字体模块”。

## 已证实的偏差

### 1. 字体枚举不等于默认 family

`PublicSystemFontsCatalog` 从公开字体集合挑出一个中文字体和一个西文字体。中文依赖 locale 与
文件名评分；西文明确优先文件名含 Roboto 的字体。但 `SystemFonts.getAvailableFonts()` 不提供
named family、alias、fallback 顺序和当前主题选择，集合里“存在 Roboto”不表示默认 sans 是
Roboto。

逐 glyph 样本中的直接反例：

| 样本 | 平台默认中文 | 平台默认西文 | 当前规则会猜的中文 | 当前规则会猜的西文 |
| --- | --- | --- | --- | --- |
| Huawei API 31 样本 A | HarmonyOS Sans SC，`wght=399` | HarmonyOS Sans，`wght=399` | 同文件，随后改写为 `wght=400` | Roboto |
| Huawei API 31 样本 B | HarmonyOS Sans SC，`wght=640` | HarmonyOS Sans，`wght=640` | 同文件，随后改写为 `wght=400` | Roboto |
| OnePlus / 字体模块 | `400.ttf` | `400.ttf` | Noto Sans CJK | Roboto |
| OnePlus / 系统默认 | SysSans Hans Regular | SysSans En Regular | 等价的 SysSans Hans 实例 | Roboto |
| Xiaomi API 34 | MiSans VF，`wght=310` | MiSans Latin VF，`wght=310` | MiSans L3 | Roboto |
| Xiaomi API 36 样本 A | MiSans VF，weight override 310 | MiSans VF，weight override 310 | 同字体字节的 `wght=400` 实例 | Roboto |
| Xiaomi API 36 样本 B | MiSans VF Overlay，weight override 412 | MiSans VF Overlay，weight override 412 | 非 Overlay 的 MiSans VF | Roboto |
| vivo API 36 | 数据分区运行时字体 | 同左 | vivo Sans SC VF | Roboto |

这里“同字体字节”也不代表同一实例：轴坐标、TTC index 和 Android 的 style override 都是 shaping
输入的一部分，必须随字体身份一起保留。

### 2. 固定 400 / 700 会覆盖平台默认实例

目录选中候选后无条件调用 `instantiateWeight(400)` 或 `instantiateWeight(700)`。只要字体允许，
它就用 `Font.Builder` 写入新的 `wght`，而不是保留平台默认 shaping 实际使用的实例。

样本已经观测到以下默认值：

- 同一份 HarmonyOS Sans SC 字体字节分别以 `wght=399` 与 `wght=640` 运行；
- Xiaomi API 34 的 MiSans 中文和西文默认实例都是 `wght=310`；
- 两台 Xiaomi API 36 分别报告 weight override 310 与 412。

所以这不是一个视觉微调问题。当前实现会改变 advance、字面大小、字重和断行输入，足以解释真机
上出现的“默认字重突然变粗”和宽度轴 / 字重轴异常。

### 3. fallback 被压平后失去了顺序

`LoadedAndroidFontCatalog.resolveNative()` 先按 role 和 coverage 过滤，再按斜体匹配、字重距离、
`FontFaceId` 排序。`AndroidFontFaceSpec.roles` 是集合，不表示 primary / fallback 次序。

默认目录同时给中文字体和西文字体分配 `CjkPunctuation`、`Symbol`、`Unknown`，因此即使两张脸都
覆盖某个标点，最终选择也不是“中文正文脸优先”，而是哈希身份排序。8 / 8 份逐 glyph 样本中，
中文标点 probe 与中文正文使用同一组字体路径，当前模型表达不了这个平台事实。

### 4. Greek / Cyrillic 被误归为 Unknown

`CjkFontRoleClassifier` 的西文判断只覆盖 ASCII、数字和 U+00C0–U+024F；Greek 与 Cyrillic 落入
`Unknown`，而 `Unknown` 默认走中文脸。

在字体确实中西分家的样本里，平台对 Greek / Cyrillic 使用 HarmonyOS Sans、SysSans En 或
MiSans Latin VF，而不是对应的中文正文脸。分类应依据版本固定的 Unicode Script / General
Category 数据生成，不能继续追加手写区间。

### 5. 字体目录变化不会使 Compose 缓存失效

`TiqianAndroidFontBackend.install()` 会替换进程级 `activeCatalog`，但没有 revision；Compose 的
measurer 只以 `profile` 和 `applicationContext` 为 `remember` key，shaping、metrics 与段落结果
缓存也不含字体目录版本。

保留旧 face 让已经产出的 `LayoutResult` 能继续重放是正确的；问题是安装新目录后，相同输入仍
可能命中旧 shaping / metrics / layout。字体模块、主题字体或宿主目录切换必须形成新的不可变字体
环境身份，并参与所有缓存边界。

### 6. emoji 的实际字体与当前目录不一致

8 份逐 glyph 样本的默认 emoji probe 都读回 `NotoColorEmoji.ttf`。当前默认目录没有建立独立的
emoji face，只把 `Emoji` role 交给猜出的中文字体，因此无法复现平台默认选择。

此外，当前 Android 重放以 FreeType outline / Android `Path` 为主；彩色 emoji 的 COLR、CBDT、
SVG 等能力是否完整并未由本批证据验证。默认目录缺失是已证实事实；彩色绘制支持程度应另做能力
测试，不能在这里假定支持或不支持。

## 正确的边界

### API 31+

平台 `TextShaper` + `PositionedGlyphs.getFont()` 是默认字体的运行时事实来源。当前 API 31+ 路径
对每个实际 shaping 请求读回 `Font` 的字节 / 文件、TTC index 与轴；API 35+ 进一步把 weight /
italic override 合入有效轴实例，再交给 HarfBuzz / FreeType。平台仅以 fake bold / italic 表达样式时，
当前后端明确拒绝把它冒充成可重放实例。

不能从完整 `SystemFonts` 集合反推出默认 family。该集合只适合补充字体文件元数据或诊断。

### API 23–30

这些版本没有逐 glyph 字体读回，不能证明平台默认 `Typeface` 最终选了哪张 face。当前默认路径读取
单一可读 `fonts.xml` 根文件的有序声明，并以具名 capability issue 标明它不是 Minikin 最终运行时
真值；宿主可以安装显式、可重放的 `AndroidFontCatalog` 覆盖。`SystemFonts` / well-known path 仅是
更末级的近似来源，不得宣传为 OEM 系统默认保真。

### 所有版本

字体环境至少应包含：

- 不可变 revision，目录变更时创建新环境；
- 每个 role 的有序 primary / fallback，而非 role 集合；
- 物理字体字节、TTC index、完整轴实例和 style override；
- 基于 Unicode 数据生成的 script / category 分类；
- 独立的 emoji / color-glyph 能力声明；
- 同一个环境身份贯穿 shaping、metrics、layout 与 replay，旧环境只为旧 `LayoutResult` 保留。

## 修正顺序

1. 先让 `AndroidFontCatalog` 表达有序 fallback、实例身份和 revision，并把 revision 接入 Compose
   measurer 与三层缓存；这一步不改变字体来源，也能消除切换后的陈旧结果。
2. 在 API 31+ 增加平台默认字体快照，删除生产默认路径中的文件名评分与固定 400 / 700 实例化。
3. 将 punctuation、Greek / Cyrillic 与 emoji 改到新的 role / fallback 定义，并用本目录样本形成
   Android instrumentation fixture。
4. API 23–30 只保留宿主显式目录作为保真路径；近似路径必须在 capability report 和 demo 中可见。
5. 用干净版本采集器重采 OnePlus 受控对照以及至少一台 Huawei、Xiaomi、vivo，再进行真机
   `LayoutDumpGoldenTest`、layout report 与 Compose 截图验收。

这五步完成前，Compose alpha 可以验证显式随包字体或明确标注的实验性系统字体模式；不能把当前
自动目录作为“尊重 OEM / 用户系统字体”的生产承诺。
