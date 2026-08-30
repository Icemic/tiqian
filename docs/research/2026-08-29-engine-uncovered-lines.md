# engine 未覆盖行逐行记录（2026-08-29）

本文件逐行记录 engine 的 commonMain 在 Kover 报告中仍未覆盖的行，并给出
「这一行为什么不可能被测试执行到」的证明。覆盖任务的要求是把 commonMain
补到 100% 行覆盖与 100% 分支覆盖；执行到后期确认剩下的每一行都属于任何
测试输入都执行不到的情况。验收方法：/tmp/acceptance-check.py 从 Kover XML
报告提取全部未覆盖行，减去本文件条目主张的行号集合，结果必须为空集。
2026-08-29 的终态核验通过：27 个文件共 233 行，其中 commonMain 231 行、
jvmMain 2 行，每一行都有条目主张。

2026-08-30 补记：本文件的行号、计数与覆盖状态对应本仓 main（含
PreparedParagraph 数字序列化重写与各覆盖测试群）。这些改动尚未同步到
上游主仓；条目 3、4、66 引用的重写函数与多个条目引用的测试文件目前
只在本仓存在，同步到上游后需要在上游复测。当轮的 Kover 聚合覆盖率
数字见条目 67。

2026-08-30 第二次补记：已合并 upstream/main 1d8c134d（PR #18，引号
语境规则）为 484c92ca。上游改写 isNonCjkInWordApostrophe 并新增两条
word-internal 解析路径，条目 60/61 的行号随之移位；新增主张、覆盖
测试与复测数字见条目 68。

2026-08-30 第三次补记：已合并 upstream/main 29c67d52（PR #19，语境
破折号与省略号角色）为 aaaa6568。上游新增语境破折号与省略号解析器与
两个管线外分类器扩展，条目 44/50/56/62 的行号随之移位；新增主张、
覆盖测试与复测数字见条目 69。

阅读约定：

- 未覆盖行指 Kover XML 中 mi>0（存在未执行指令）或 mb>0（存在未走到的
  分支方向）的行。
- 证明分五类。第一类，条件对所有能到达该行的输入永远为真或永远为假。
  第二类，前置检查先处理了同一输入，后面的检查收不到相反取值。第三类，
  执行到达该行之前就会抛出异常。第四类，防御性检查，它依赖的输入不可
  能出现，例如索引总在界内。第五类，编译器在编译产物中生成的检查或
  分支，源代码中不存在对应路径。
- javap 偏移指 `javap -c -l` 输出中的字节码偏移量，用于指认具体指令。
- 每条证明在写入前都对照编译产物或调用点核对过。
- 部分条目写作时的行号与当前源文件有整体偏移（后续源码编辑所致），
  条目 63 逐条核对了偏移并追认对应关系；条目内的行号以写作时的源文件
  为准，验收脚本使用的当前行号集合与条目 63 的追认一致。

## PreparedParagraph.kt（条目 1-7）

### 条目 1：PreparedParagraph.kt:115，非空集合上的防御性 takeIf

`openTypeFeatures[cluster.range]?.takeIf { it.isNotEmpty() }`。map 里的
条目只在 `if (run.openTypeFeatures.isNotEmpty())` 内部写入，写入语句在
同一条语句里执行 `addAll`（第 41-43 行），所以一个存在的集合永远不为
空，takeIf 返回 null 的方向走不到。这是防御性检查，保留。

### 条目 2：PreparedParagraph.kt:244，非空列表上的防御性 takeIf

`glyphIdsByRange[cluster.range]?.takeIf { it.isNotEmpty() }`。map 的值由
`getOrPut { mutableListOf() }.add(glyph.id)` 创建（第 48 行），add 在条目
可被观察到之前执行，所以一个存在的列表永远不为空。防御性检查，保留。

### 条目 3：（代码已删除）平台字符串解析臂

原主张 :436 与 :440-441（小写 e 指数臂、无小数点尾数臂）针对的
`Double.toString` 平台串解析代码已在数字序列化重写中删除。当前实现自行
完成最短位数搜索与布局分支，不解析平台字符串，本条不再有对应行。

### 条目 4：（代码已删除）同条目 3

原条目主张的两个臂与条目 3 属于同一段已删除代码，一并不再主张。

### 条目 5：PreparedParagraph.kt:551，canonicalFloatDigits 的零值守卫

`if (mantissa == 0 && biasedExponent == 0) return doubleDigits`。唯一调用点
（第 447 行）在第 443 行对零值返回 `"0"` 之后才到达；canonicalFloatDigits
是 private 函数且只有这一个调用点，到达时 magnitude 非零，f32 位模式不为
全零。该守卫保护 Kotlin/JS 上的未钉格 double（小于 2^-150 的值经
`toRawBits` 量化为全零位），JVM 输入不可达。防御性检查，保留。

### 条目 6：（已由测试覆盖）incrementDecimal 的内部进位

原主张 :560-561（`index -= 1` 内部进位回边）在重写后的最短位数搜索里由
L 循环对每个前缀构造进位候选到达，例如 0.45f 的精确展开在 L=3 的进位候选
`449 -> 450`。该行已覆盖，不再需要主张。

### 条目 7：行为说明，精确平局的输出有意与 V8/JDK 的 String() 不同

本条不是未覆盖行主张，记录一个行为差异。`ecmaJsonNumber(
5.960464477539063e-8)`（2^-24）在每个后端都返回
`"5.960464477539062e-8"`，V8 与 JDK 都打印 `...063`：精确展开的最后一位形成
等值平局，规范平局规则选择偶数候选。该函数保证跨后端的输出字节一致
（golden dump、ffi/js），不追求复现某一个 dtoa。舍入结果与 double 数位长度
不等时 canonicalFloatDigits 回退到 double 数位（:570，见条目 66），该类输入
在 JVM 上不存在；排版也不产生触发回退的值。

## 标点几何与行调整（条目 8-31）

以下条目的测试文件：PunctuationModelCoverageTest.kt、
PunctuationGeometryLedgerCoverageTest.kt、
PunctuationGeometryStageCoverageTest.kt、
PunctuationGeometryBranchArmsCoverageTest.kt，jvmTest 全部通过。

### 条目 8：PunctuationGeometryStage.kt:322，循环入口检查的空区间方向

`for (pointMarkIndex in runStart..runEnd)` 的入口检查（字节码偏移 741
的 if_icmpgt）。runEnd 初始化为 runStart 且只增不减，区间永不为空，
入口检查的跳过方向走不到。退出检查（偏移 800）是另一条指令，已覆盖。

### 条目 9：PunctuationGeometryStage.kt:379，getOrNull 的 null 臂

`shapedGlyphs.getOrNull(displayIndex)`。displayIndex 遍历
displayText.indices，该 null 臂只在 `shapedGlyphs.size ==
displayText.length` 的构造下运行，索引总在界内。防御性检查。

### 条目 10：PunctuationGeometryStage.kt:415，isEmpty 真臂

`unionAsSingleGlyph` 的 `isEmpty()` 真臂。唯一调用方
（punctuationInkInputFor，L378）在 shapedGlyphs 为空时先返回 null，
再调用合并。防御性检查。

### 条目 11：PunctuationGeometryStage.kt:436-439，空集合抛出

`minOf`/`maxOf` 的空集合抛出（字节码偏移 304-314 的
NoSuchElementException）。L433 的守卫在边界列表为空时返回 first，
执行到抛出指令前就已返回。不可达。

### 条目 12：PunctuationGeometryStage.kt:508，getOrNull 的 null 臂

`getOrNull(nextIndex)`。UnicodePunctuationBoundaryResolver.kt:88 用
`(end + 1).takeIf { it < inlineAttachments.size }` 设置 nextClusterIndex，
所以非空值总是有效的索引。防御性检查。

### 条目 13：PunctuationGeometryStage.kt:534/537，相邻簇的 getOrNull null 臂

`getOrNull(idx + 1)` 与 `getOrNull(idx - 1)`，位于 `nextSpacing ==
Narrow` 与 `previousSpacing == Wide` 守卫的臂内。这两个 spacing 值本身
就是从 `eastAsianSpacingEdges[idx ± 1]` 读出的，进入该臂时邻居簇必然
存在。防御性检查。

### 条目 14：PunctuationGeometryLedger.kt:239/396 与 LedgerKt:648，anchor 的 null 臂

`geometries[index]?.anchor`。`from()` 用 `geometries.mapValues` 构造
预算表，没有任何消费方在缺少 geometry 的情况下添加预算，有预算的索引
总有 geometry。防御性检查。

### 条目 15：PunctuationGeometryLedger.kt:333，第二个析取项不能单独触发

`rightLeading != adjustedVirtualGlue` 析取项单独触发（同时
`leftTrailing == 0.0`）。`leftAtom == null` 时调整值取自然臂，等于
`leftTrailing + rightLeading == rightLeading`。（2026-08-30 复核更正）
原推导「`leftAtom != null` 时预算使 `leftTrailing > 0.0`」不成立：
开引号类原子的 trailingGlue.natural 可以为 0
（buildPunctuationClusterGeometries 读 atomsForCluster.last() 的
trailingGlue.natural），此时该析取项可以单独触发，该行是活代码。
该行的构造其后由测试覆盖，不再出现在未覆盖行集合里，本条不再主张
不可达（见条目 63）。

### 条目 16：PunctuationGeometryLedger.kt:337/339，非空属性的中间判空

next.range（偏移 1101）与 next.text（偏移 1167）的安全调用判空。两个
属性都是非空类型，编译出的 ifnull 分支因 Kotlin 可空性而不可能走。
外层 `next == null` 与 `firstOrNull`/`lastOrNull` 的 null 臂已覆盖。

### 条目 17：LedgerKt:690，mergeValue 的重映射臂

（2026-08-30 复核改写）原条目主张「两个调用点都在 consumeAtEdge，
运行时键必然不存在」对当前树不成立。mergeValue 现有 23 个调用点
（grep mergeValue engine/src/commonMain，排除定义本身）：
LineAdjustmentStage 16 处（L163-441）、PunctuationGeometryLedger 4 处
（L259/361/412/415）、PunctuationGeometryStage 2 处（L690/693）、
WidthIndependentAnnotationCache 1 处（L794）。同一簇键先后合并两次的
路径确实存在：单簇拉丁行同时携带 leading 与 trailing 自动加空决策时，
L245 的 trimEdge 对同一 clusterIdx 先后以两侧各合并一次；L324-325 又
把 pushInRawTrims 合并进 `HashMap<Int, Float>(autoSpaceEdgeTrims)` 拷贝
出的 rawTrims，键与 autoSpaceEdgeTrims 并存。重映射臂是活代码，由
LineAdjustmentStageCoverageTest.loneLatinClusterMergesBothAutoSpaceEdgeTrimsIntoOneKey
（「中A中」，maxWidth 24）覆盖，adjustedWidth 断言固定两次修剪相加
的语义。该行的指令转为已覆盖，残余 mb=1 按第五类主张：javap 偏移 48
ifnonnull 的 null 出口要求 remap 返回 null，remap 的类型
`(V, V) -> V` 与 `V : Any` 排除 null 返回值，该方向不可达。

### 条目 18：LineAdjustmentStage.kt:204-206 与 212-215，LeadingAndTrailingGlue 通道

`ShrinkChannel.LeadingAndTrailingGlue` 臂（配对的 used 读取与减半的
`take / 2.0` 合并）。该通道要求 `PunctuationAnchor.Center` 原子两侧都有
正 glue（glueCapacities，PunctuationGeometryLedger.kt:239）。stub shaper
下没有任何原子产出配对预算：在 `中文X文internationalization`（宽 112，
剩余空间 2）与 `中中X中中internationalization`（宽 98）两个构造里扫过 19 个
Center 候选（· • ～ 〜 ≈ ＊ ＃ ＠ ＆ ＋ － × ÷ ＝ ＜ ＞ … — ⸺），
每个原子的 advance 都是 16.0。该通道只有非 stub 字体的 ink 度量（jvm/apple
shaping）才能到达；记录现状，不在 stub 下强行构造。

### 条目 19：LineAdjustmentStage.kt:265-266，inline-object 分隔符守卫的真臂

InlineObjectAttachedKinsoku 配对规则在放得下时把 object、separator、
mark 保持在同一行；放不下时 mark 挂接在该组的行。在 object advance
100-500 乘宽度 48-96 的全矩阵里，分隔符空间始终是内部簇，从未到过行
边缘（见 attachedObjectMarkHangsInsteadOfLeavingTheSeparatorAtAnEdge）。
该守卫与 inlineObjectSeparatorSpaceTrims 的处理相同，其 advance 已在
withRawEdgeTrims 中重置为 0。防御性检查。

### 条目 20：LineAdjustmentStage.kt:342/405，空簇区间析取项

空簇区间只作为尾部强制换行之后追加的 ParagraphEnd 行出现
（`中文中文\n` 的 line1 = 1..0；连续 `\n` 各自成非空行，
`中文\n\n中文` 为 0..2 / 3..3 / 4..5）。尾部行在 L342 被 isLast、在
L405 被 `endReason != AutoWrap` 先行短路，空区间析取项不可能成为决定
条件。防御性检查。

### 条目 21：LineAdjustmentStage.kt:413，rejectedForSpan 的真臂

`selectedTechnicalBreak.tier in rejectedForSpan`。回放路径在两个注册点
（ParagraphShapingStage.kt:615 与 :653）都过滤掉被拒绝的 tier，从
progressiveBreakOpportunities 查到的机会不会携带被拒绝的 tier。
防御性检查。

### 条目 22：LineAdjustmentStage.kt:414，getOrNull 的 null 臂

`justificationPlans.getOrNull(lineIndex)`。plans 与 lines 来自同一个
列表上的 mapIndexed，索引总在界内；null plan 只存在于 isLast、空行、
非 AutoWrap 行（L342），而到达 L414 要求 AutoWrap 非空行，只剩 isLast，
但最后一行的结束原因总是 ParagraphEnd（规划阶段追加尾部行），不可能是
AutoWrap。不可达。

### 条目 23：LineAdjustmentStage.kt:468，centerDashInk 的移位臂

`inset > 0.5` 的移位臂。stub shaper 不报告 glyph ink 边界（输入 `中` 加两个 U+2014 组成的文本
产出 3 个 `bounds == null` 的 fallback glyph），L465 在计算 inset 之前
返回。只有非 stub 的 shaping 能到达；记录现状，不在 stub 下强行构造。

### 条目 24：LineAdjustmentStage.kt:568，baseLineMetrics.height 回退

`?: baseLineMetrics.height`。visibleLines 是 `laidOutLines.take(maxLines)`
且 `maxLines >= 1`；`lineSolution.lines` 只在簇列表为空时为空，而簇列表
为空只在文本为空时发生。所以 `lines.lastOrNull()` 为 null 当且仅当
`text.isEmpty()` 为真，else 臂走不到。
emptyTextYieldsZeroHeightWithoutLines 固定了 null 一侧的行为。

### 条目 25：LineAdjustmentStage.kt 的部分覆盖行

以下行在 Kover HTML 里显示为部分覆盖，两个语义方向实际都执行过，
残缺来自 tableswitch 与合并安全调用 ifnull 的计数方式：

- L200-203 的 used when：四臂 tableswitch 包含条目 18 的配对通道；
  其余三臂两个 elvis 方向都执行过（map 存在：
  `中文，internationalization` 宽 74-80，PushIn 在连字符挤压读取剩余
  之前消耗了逗号 glue 的 2-6px；map 缺失：宽 88-90，行本来就放得下）。
- L304-307：boundary 为 null 走
  formulaObjectWithoutBoundaryDiscardsNothingAtLineEnd，非 null 走
  formulaLineEndDiscardsTheTrailingBoundaryAdvance。
- L345-350：takeIf 真走
  emergencySelectedBreakOpensThePreferredTrackingSpan；假走
  technicalLineBodyStretchRejectsTheCleanTierAndReplays 的回放前一遍
  （第 0 行持有 Whitespace-tier 断点）。
- L411-412：第一遍 map 必然 miss；同一测试的第二遍 map hit：
  `Whitespace+WholeToken` 累计拒绝证明第 2 轮在
  `rejectedTechnicalTiersBySpan = {Whitespace}` 已加载时评估了
  WholeToken 断点。
- L450：inline-object 簇的度量决策 miss
  （formulaLineEndDiscardsTheTrailingBoundaryAdvance），文本簇 hit
  （baselineShiftSpanRaisesTheFinalClusterShift）。
- L477-481：文本簇的 shaped 路径与 inline-object 簇的 fallback 路径
  在同一批测试中都执行过。

### 条目 26：Justifier.kt:712/728，getOrNull 的 null 臂

`getOrNull(idx) ?: return false`。两个助手都从同一列表的 indices 派生
的循环值调用，索引总在界内。防御性检查。

### 条目 27：Justifier.kt:716/719，相邻全空格簇的假方向

`!this[idx ± 1].text.all { it == ' ' }` 的假方向（邻居是全空格簇）。
shaping 阶段把连续空格合并成一个空格 run 簇，两个相邻的全空格簇不可
能出现。从 shaped 输入不可达；对手工构造的列表是防御。

### 条目 28：LineRepair.kt:417，"$text.0" 臂只在 JS 目标执行

toPortableDebugString 的 then 臂（`"$text.0"`）。JVM 的 `Double.toString`
总是输出 `.` 或 `E`，该条件在 JVM 上永远为假；该臂为 JS 存在，JS 的
toString 对整数值省略小数点（与条目 3-4 同类）。else 臂由每个小数
shrink dump 执行。

### 条目 29：LineRepair.kt:689，totalShrink 非正且机会列表非空

`totalShrink <= 0.0` 且机会列表非空的组合。distributePushInShrink 是
private 函数，唯一调用点 L368 由 `shrink > 0f` 守卫（2026-08-30 复核
更新计数，原条目写作时为两个调用点）：shrink 由 overflow 取
`coerceAtLeast(0f)` 得到，非正溢出走 else 分支不调用；进入调用的行
totalShrink 传正的 shrink，且 buildShrinkOpportunities 只添加正容量
条目（条目 30），机会列表非空时容量和为正。防御性检查。

### 条目 30：LineRepair.kt:698，tierCapacity 非正

`tierCapacity <= 0.0`。buildShrinkOpportunities
（WidthIndependentAnnotationCache.kt:836-880）只添加 `capacity > 0.0`
的条目（paired、leading、trailing 各有守卫），tier 组的容量和总是正数。
防御性检查。

### 条目 31：LineRepair.kt:508，冗余合取项

`currentBreak != null` 合取项冗余：promotesProgressiveTier（L503-506）
已要求 `currentBreak != null`，该链不可能在第二个合取项处为假。
L505/L540 链的每个原子方向都在 technical fill 测试里执行过；L540 的
全真拒绝路径由 technicalLineBodyStretchRejectsTheCleanTierAndReplays
的回放场景覆盖。

【已删除】2026-08-30 外部复核裁定删除该合取项。块内 lambda 的
`currentBreak.spanRange`/`.tier` 依赖 if 条件里的判空做智能转换，
删除后在块首以 `val activeBreak = currentBreak!!` 捕获非空值（同
LineOptimization.kt:97 守卫后断言的写法；Kotlin 把 `!!` 编译为
checkNotNull 调用，没有分支，不产生新的未覆盖方向）。

## 查询与入口校验（条目 32-42）

### 条目 32：LayoutQueries.kt:134，getTextForCopy 的 continue 条件

`if (annotationEnd < cursor || annotationEnd > end) continue` 的两个
方向。addAnnotation（L125-128）丢弃 `start < baseRange.start` 或
`baseRange.end > end` 的基范围，存储的键都在 `[start, end]` 内，
`> end` 从不触发；键经 `.sorted()` 迭代，cursor 起始等于 start，每次
迭代设 `cursor = annotationEnd`，后续排序键不可能小于 cursor。
防御性检查。

### 条目 33：LayoutQueries.kt:148，throwIndexOverflow 臂

内联 flatMapIndexed 的 throwIndexOverflow 臂。javap
（LayoutQueriesKt.positionedClusters）显示守卫在字节码 60-65
（`ifge 68; invokestatic throwIndexOverflow`）：lambda 索引从 0 起每行
加一，要到负数需要 2^31 行。不可达。覆盖方面：空行（空簇区间给 addAll
喂空列表）由 noArgPositionedClustersWalksEveryLine 执行。

### 条目 34：LayoutQueries.kt:362，maxOfOrNull 的空集合臂

内联 maxOfOrNull 的空集合臂。javap 字节码 1004-1015：
`if (!iterator.hasNext()) return null` 编译为 `ifne 1018; aconst_null`；
接收者是 `glyphRuns...groupBy { it.clusterRange }`（L763-765）的值，
groupBy 不会把键映射到空列表。不可达。Math.max 更新臂（字节码
1096-1103）的两个方向由 backgroundTrailingEdgePicksTheLargestGlyphAdvance
（[5, 6]）与 backgroundTrailingEdgeKeepsTheFirstGlyphWhenItIsLargest
（[6, 5]）覆盖。

### 条目 35：LayoutQueries.kt:407，takeIf 谓词假臂

sharedClearance 中 `other?.takeIf(::sameVisibleStyle)` 的谓词假臂。
javap 字节码 18-25（`ifeq 25; aconst_null`）：唯一调用方
（withAdjacentSameStyleClearance，字节码 654 与 689）传入的邻居来自
`.firstOrNull(segment::sameVisibleStyle)`，非空实参已在同一接收者上
满足同一谓词；谓词为假只能来自 null 实参，而 null 走 `?.` 臂。
不可达。

### 条目 36：LayoutQueries.kt:841，sliceRect 的非正长度臂

`range.length <= 0` 臂。两个调用点（getBoundingBoxes L224 与
positionedRichTextSegments L267）都要求 `sliceStart < sliceEnd` 才切分，
到达 sliceRect 的定位簇长度 >= 1。同一守卫的 `width <= 0.0` 臂是活
路径，由零 advance 簇测试
（emptyMidClusterHoldsTheCaretAndSlicesKeepDegenerateRects）覆盖。

### 条目 37：LayoutQueries.kt:283，span 同一性检查的假结果

positionedRichTextSegments 合并检查中 `current.span === next.span` 的
假结果。span 在每次 span 迭代里规范化一次（L249-250 的注释：一个
规范化实例供该 span 的全部切分段使用，合并检查按同一性比较）；pending
只持有由该单一实例构造的段，同一性检查到达时不可能失败。不合并路径
本身是活路径，由 range-end 与 start 的比较条件覆盖。javap 证据同条目
35（字节码 18-25）。

### 条目 38：LayoutQueries.kt:408，elvis 的 null 结果

sharedClearance 的 `?.let` 链上 elvis 的 null 结果。javap 字节码 27-28
（`aload_2; ifnull 66`）：该检查只对非空 other 执行；非空 other 的
takeIf（字节码 18）总是产出非空结果（条目 35），该检查的 null 分支也不
可达。`other == null` 的入口臂经字节码 1 直接跳过检查。

### 条目 39：LayoutQueries.kt:471，style 的第二个判空

resolvedTextStyleAt 的 `?.style ?: input.textStyle` 的 style-null 结果。
javap 字节码 94-116：编译器生成两个判空，字节码 98 检查 lastOrNull
结果（活路径：没有任何 span 包含该偏移时触发），字节码 105 检查载入的
`TextSpan.style`。TextSpan.style 是非空构造属性
（TextModel.kt:53-56），第二个检查的 null 方向是不可达的防御字节码。

### 条目 40：LayoutQueries.kt:529，richTextDecorationLineY 的同型判空

`lastOrNull { ... }?.style ?: input.textStyle` 编译出字节码 244（span
为 null，空 span 场景覆盖）与字节码 256（style 为 null）；后者因
TextSpan.style 非空属性而不可能触发。lastOrNull 谓词分支由
match-first、miss-first、match-then-miss 三种 span 顺序覆盖
（decorationLineYPicksTheLastMatchingSpan 与
decorationLineYKeepsTheEarlierSpanWhenALaterOneMisses）。

### 条目 41：SourceInteractionBoundaries.kt:151，isHangulLv 的第一个合取项

`isHangulLvOrLvt() && (this - 0xAC00) % 28 == 0` 的第一个合取项。
javap 字节码 4 的 ifeq 守卫整个函数体（内联的 isHangulLvOrLvt 区间
检查），先于 `% 28` 测试。唯一调用方是 L79 的
`if (first.isHangulLv())`，只在 L78 的
`else if (first.isHangulLvOrLvt())` 守卫内到达，函数运行时区间检查
永远为真，假方向是不可达的防御字节码。`% 28` 的两个结果都覆盖
（가 = U+AC00，LV 为真，precomposedHangulSyllablesAbsorbJamo /
가ᅡᆨ；각 = U+AC01，LVT 为假，각ᆨ）。

### 条目 42：ParagraphLayoutEngine.kt:77/89/95/109，start 非负的冗余复查

四个输入校验 require 的第一个合取项 `range.start >= 0`（InlineBoxSpan、
LineBreakSpan、auto-space suppressed ranges、InlineObjectSpan）。
TextRange 的 init 块（core/Geometry.kt）在任何实例逃离构造之前执行
`require(start >= 0)`，引擎层的复查见不到负 start，假方向不可达。其余
合取项（start < end、end <= text.length）由 golden 与校验输入执行
（四行 cb > 0；只有冗余合取项的结果缺失，mi = 0）。

## shaping 与注解（条目 43-45）

### 条目 43：ParagraphShapingStage.kt 八处

- L395：`urlLike || (opaque && ...)`。两个调用方（L677 的
  `w.contains('-')`、L679 的 `!allLetters`）都蕴含 token 含非字母字符，
  第二合取项求值时 opaque 永远为真（L392 把 opaque 定义为同一性质）。
- L480/494/684：LineBreakPolicy 只有 ProgressiveTechnical 一个变体
  （TextModel.kt L44），`!=` 方向不可达（L494/L684 还处于 allLetters
  下，`w.contains('-')` 永远为假）。
- L489：`!isAllCaps && !isAbbreviation`。isAbbreviation = isAllCaps &&
  ...（L487-488），`!isAllCaps` 为真时第二合取项永远为真。
- L503：`maxOfOrNull { ... } ?: w.length`。elvis 右值需要
  `bounds.zipWithNext()` 为空，即少于两个不同边界；isLatin 要求
  `segmentRange.length > 0`（L477），w 长度 >= 1，bounds ⊇ {0, w.length}
  总有至少 2 项。
- L790：`if (index !in 0 until lastIndex) return false`。唯一调用方循环
  `for (i in 0 until token.lastIndex)`（L409），循环边界保证区间前提
  成立。
- L819：`volume.isEmpty() || issue.isEmpty() || pages.isEmpty()`。
  L810 保证 open >= 1，L812 保证 close >= open + 2，两个子串都非空；
  pages 臂是活路径。

### 条目 44：WidthIndependentAnnotationCache.kt:228/474/681/833 与 847

L228/474/681/833 是编译器对非空声明属性插入的第二个判空
（TextSpan.style、单变体枚举比较、FontDecision.role、
PunctuationAtom.punctuationClass），javap 已逐条验证。L847 可达，不要
按不可达处理：paired 来自 `anchor == PunctuationAnchor.Center`
（PunctuationGeometryLedger.kt L239），与 glue 值无关；附着内联边界
（ledger L319-328）整段消耗一侧 glue，中心锚定且 glue 对称的簇可到达
`pairedCapacity == 0.0`。由
centeredPunctBeforeAttachedReferenceKeepsLeadingGlueOnly 覆盖（窄中心
ink 加 InlineAttachment.Previous 脚注）。

### 条目 45：AnnotationGeometryStage.kt 分组

- L162/176：非空属性 InlineObjectPreferredStretch.kind 的第二个判空。
- L313/537：`metricDecisions.firstOrNull { cluster.range.start >=
  it.range.start && ... }` 区间谓词的第一个合取项。metricDecisions 由
  源顺序的字体决策区间构造（LineBreakPlanningStage.kt L248-272），按
  start 排序且覆盖全部区间，簇不跨决策边界，最终匹配的决策上 start
  比较永远为真。end 比较的假方向（推进扫描的 miss）已覆盖，含 Latin-ruby
  变体。
- L315/316/538：非空属性 ClusterMetricDecision.request 与 .layoutMetrics
  的第二个判空。
- L597：`for (idx in clusterRange)` 的零迭代方向。bopomofo 循环拿到的
  每个区间都来自与 ruby 基底重叠的行盒，每个行盒都持有至少 1 个簇。
- L632/644-645/776-780/782/786-787：BopomofoTone.Ru 臂全族。v1 解析器
  从不产出 Ru（BopomofoReading.kt 声明了该变体但没有构造点），L642
  的源内注释记录了这一点。【2026-08-30 裁定：保留】Ru 与已删除的
  Quote 不同：源内注释写明 v1 解析器不产出 Ru，该注释是写入源码的
  计划说明，入声支持实现时这一族转为活代码，不按 Quote 先例删除。
- L695-698：内联 minOf/maxOf 的空集合抛出，由 L693 的显式
  `if (bounds.isEmpty()) return null` 守卫。

## 断行（条目 46-49）

### 条目 46：LineBreaker.kt

- L9：LineBreaker$DefaultImpls.getStrategyName 桥方法，Kotlin 调用方
  不会分派到它（javap 证据在 cov-we2 报告）。
- L269：else if 假方向要求循环以 `lineStart == size` 退出且最后的簇
  不是硬断行；lineStart 只在硬断行时经 `i + 1 == size` 到达 size
  （breakAt 总是小于 size），空簇情形被 L155 的
  `adjustedClusters.isEmpty()` 早退截断。
- L451/452/464：候选过滤的区间检查。每个候选 <= greedyEnd，
  findGreedyEnd 返回值 <= 其 endExclusive（L867-885），
  `greedyEnd >= segmentEndExclusive` 时 L418 拦截在候选循环之前退出，
  前两个上界比较（`it <= adjustedClusters.size`、
  `it <= segmentEndExclusive`）永远为真。L464 的
  `e == segmentEndExclusive` 在同一不变式下永远为假（每个候选
  `e <= greedyEnd < segmentEndExclusive`；2026-08-30 复核更正方向，
  该行的未覆盖方向不变）。
- L509：第二合取项 `lineStart < committedEnd`。
  adjustBreakForLineEnd（ProgressiveBreakDecisions.kt L291）只在
  `b - 1 > lineStart` 时回退，返回值总是 >= lineStart + 1。
- L587-594/672-674/676：两个 private 函数的默认值表达式。唯一调用点
  显式传全部参数（rawGreedyLinesFrom 的 $default 桥只设置 maxLines 的
  掩码位 4096，所以 L675 的 Int.MAX_VALUE 已被覆盖，其余默认值从不
  求值）。
- L678：`start >= endExclusive`。唯一调用传 `start = e`（候选端点，由
  L418 的不变式总是小于 segmentEndExclusive）与
  `endExclusive = segmentEndExclusive`。
- L679：`require(maxLines > 0)`。唯一调用传 futureLineHorizon + 1，
  公共入口在 L362 要求 `futureLineHorizon >= 0`。
- L775：`endReason == AutoWrap` 下的
  `!clusterRange.isEmptyClusterRange()`。空簇区间的唯一生产者
  emptyLineCandidate 只以 LineEndReason.ParagraphEnd 调用（L270、L430、
  L525 与 ParagraphDpLineBreaker.kt L649）。
- L833：rebuildLine 内 `for (idx in clusterRange)` 的零迭代方向，被
  L830 的 `require(!clusterRange.isEmptyClusterRange())` 排除。

### 条目 47：ParagraphDpLineBreaker.kt

- L246/249/252：三个 DpContext 间距计数器的 isEmptyClusterRange 守卫。
  全部调用方（L432、L441、L442）传入按
  `start..(e - 1).coerceAtMost(segmentEndExclusive - 1)` 构造的行内
  度量区间，`e >= start + 1`（L325 过滤加 L374 回退），区间永不为空。
- L325：上界比较 `it <= segmentEndExclusive`。池元素来自
  `((rawGreedy - candidateWindow)..rawGreedy)`（findGreedyEnd 保证
  rawGreedy <= segmentEndExclusive）加 compressed（其循环条件限制
  `e <= segmentEndExclusive`，L309）。
- L358：内联 minOf 的空集合抛出。L354 的 else 分支只在
  `promotions.isEmpty()` 为假时进入（L352）。
- L479：`incoming.isEmpty()`。segmentStart 分支交出 `listOf(null)`，
  其余起点读取的桶由 L514 的 getOrPut 创建并在 L518 无条件追加（L508
  的劣成本 continue 发生在桶存在之前），读取时桶永不为空。
- L524 与 L537-571：greedyFallbackEnds 不可达，terminalBest 总是被提交。
  每个评估过的起点产出至少 1 个 `>= start + 1` 的候选端点（L325 过滤、
  L374 ifEmpty 回退）；边向前追加状态（L514-518），终端边设置
  terminalBest（L511-512）。取最大可达起点，它的边要么设置
  terminalBest 要么创建更右的可达起点，归纳终止于
  `terminalBest != null`。javap 单调用点证据在 cov-we2 报告。
- L672：与 LineBreaker.kt L509 相同的 adjustBreakForLineEnd 下限
  不变式。

### 条目 48：LineBreakPlanningStage.kt

- L481：单变体 LineBreakPolicy 过滤（同条目 43）。
- L534：`left.range.end != right.range.start`。自然簇铺满源文本（簇
  列表的构造保证每个簇的 end 是下一个簇的 start），continue 方向不可
  达；其后合取项（L539-540）处理的空文本情形关于簇内容，与区间铺叠
  无关。

### 条目 49：断行簇第三轮（任务标签 cov-we3，主会话逐条复核通过）

- LineBreaker.kt L760：badness 孤行判定中
  `inMeasureRange.isEmptyClusterRange()` 的方向不可达。到达 badness 的
  行都出自 rebuildLine（require 非空）加 applyKinsokuRepairs；Hang
  接收行保留自身簇前缀（挂接索引全部来自 curr，大于 prev.last），
  全挂接的 curr 在 LineRepair.kt L124-125 被 `mutable.removeAt(i)`
  移除；PushIn 吸收保留 prev.clusterRange.first；Carry 要求前行至少
  2 个簇。防御性检查。
- ParagraphDpLineBreaker.kt L368/374：baseline 总在
  `[start+1, rawGreedy]` 内。findGreedyEnd 首簇必累加（>= start+1）；
  `rawGreedy >= segmentEndExclusive` 时 L285 提前返回；
  decideHyphenBreak 与 decideProgressiveBreak 都在 `(start, rawGreedy]`
  内选取；adjustBreakForUnbreakables 只回退且下限 lineStart+1
  （ProgressiveBreakDecisions L285-293）。所以 L368 的区间判断永远为真，
  越界方向不可达；L374 的 `.ifEmpty` 从不触发（promotions 非空时它
  包含于非空的 pool；promotions 为空时 L369 拼入非空的 baseline）。
- ParagraphDpLineBreaker.kt L640：commitSegment 在 L633 总是传
  `mergeThroughClusterIndex = lastIndex` 且 L627 保证
  `curr.clusterRange.last == lastIndex`；mandatoryBreakTailEnd（L425）
  对 `mergeThroughClusterIndex >= curr.clusterRange.last` 直接返回
  入参，所以 tryPushIn（L401）的条件永远为真，`result.current` 总是为 null，
  L640 的 `current != null` 跳转方向不可达。
- LineBreakPlanningStage.kt L443：asciiPointMarkKinsoku 总是被 L442
  短路。入集条件要求前一簇末字符非空白且
  `previous.range.end == cluster.range.start`（紧邻）；
  UnicodePunctuationBoundaryResolver 的 followsAuthoredBoundary 在
  紧邻前一字符非空白时返回 false（L447），isDecimalMarkAfterSpace
  要求前置空白也不成立；6 个 ASCII 点号（`, . : ;` 属 IS、`! ?` 属 EX）
  都在 UAX14_FORBIDDEN_LINE_START_CLASSES（L337-342）里，该簇必然已
  进入 unicode 集合，L443 的真方向不可达。
- 同轮的 L539/549/562/563 已由测试覆盖
  （LineBreakPlanningStageCoverage2Test）；L364/L620 的早期报告表述
  与后续实测矛盾，以合并后的全量 Kover 报告为准（见条目 59）。

## font 与 linebreak 小项（条目 50）

### 条目 50：六个文件的收尾行

- FontPolicy.kt L133/134/135：isLatinCodePoint 的 A-Z、a-z、0-9 三个
  ASCII 区间臂。仅两个调用方：L77（classify 的 when 链，L76 的
  isTypedAsciiLatin（0x20..0x7E）先接住全部 ASCII）；L130
  （isLatinRunCodePoint 的第一个析取项就是 isTypedAsciiLatin，ASCII
  短路）。两处的 ASCII 到达时总是已被前置条件命中，三个 ASCII 区间的
  真方向不可达（mb=2 cb=4 的实测与之一致：区间外的真方向从未取到）。
- FontPolicy.kt L170：toCharOrNull 的 `this < 0` 方向不可达。唯一调用
  方 isSymbolCodePoint（L152），输入来自 codePointAtCompat（char 码或
  代理对计算值，总是 >= 0）。mi=1 即负值比较臂。
- EastAsianSpacingData.kt L24 与 L28：`when (RANGES[base+2])` 的
  else/error 臂不可达。生成器头注声明 Values: 0=Wide, 1=Narrow,
  3=Conditional；机械校验：intArrayOf 三元组的第三个元素全部属于
  {0,1,3}（python 扫描 RANGES 数组字面量验证）。L24 的 mb=1 是 else
  方向，L28 的 mi=4 是 error 体，两者同源。
- LineBreak.kt L50：`for (index in 1..text.length)` 入口比较的空区间
  方向不可达。L47 对空文本提前返回，到达 L50 时 length >= 1，
  `1 > length` 永远为假。
- Hyphenation.kt L56：`for (j in (i + 1)..work.length)` 的空区间方向
  不可达。外层 i 属于 work.indices（<= length-1），`i+1 <= length`
  总是成立，区间总是非空。
- ClreqProfile.kt L485 when 的 PunctuationClass.Quote 臂不可达：
  classify（L415-427）从不返回 Quote（引号分类为 Opening/Closing）；
  全仓只有两处引用该枚举值，都是 when 臂（另一处
  WidthIndependentAnnotationCache L892 同理）。【已删除】用户
  2026-08-29 授权，枚举值、两处 when 名单条目、
  PunctuationGluePlacementTest.kt:33 断言随 main 提交 76537615 一并
  删除（独立提交可审计）。该测试断言是清点时新发现的第四处引用：
  glueSideFor 的 Mainland 分支里 Quote 只落入 `else -> BothSides` 分支，
  断言固定的是不可达输入下 else 分支的行为，随枚举值同删。
- BundledHyphenationResource.jvm.kt L6-7 的 `?: error` 空方向不可达：
  资源 commonMain/resources/hyphenation/hyph-en-us.tex 随模块进入
  classpath，jvmTest 运行时 getResourceAsStream 总是非空；空臂是防御。
  jvmMain 源集的未覆盖行只能由 jvmTest 运行时执行覆盖（commonTest
  无法直接引用），按构建不变式记录。

## 行调整收尾（条目 51-59）

### 条目 51：ProgressiveBreakDecisions.kt

- L56：`boundary > bestBoundary` 的假方向。循环按
  `(lineStart + 1)..overflowAt` 升序迭代，bestBoundary 只在循环内被
  赋值为当前 boundary；一旦非空，后续 boundary 总是严格大于它，假方向
  不可达。
- L84：javap（ProgressiveBreakDecisionsKt.class，
  progressiveCandidateAllowed 偏移 63-95）：安全调用链
  `adjustedClusters?.getOrNull(candidateEnd)?.range?.start ?: return true`
  编译出三个 ifnull（偏移 66/77/84，同跳 93）。偏移 84 检查
  Cluster.getRange() 的结果是否为 null；Cluster.range 是非空的
  `val TextRange`，任何构造路径都不会让它为 null。前两个检查
  的双向与第三个检查的假方向都有测试覆盖；偏移 84 的 null 方向是
  编译器对安全调用链生成的防御判空，不可达。
- L146：`lastOrNull { ... } ?: continue` 的 continue 方向。priorities
  （L130-135）由 `(lineStart + 1..overflowAt)` 上 `opportunities[it]`
  过滤 `spanRange == active.spanRange` 后 distinct 得到；L146 的
  lastOrNull 在同一区间上用同一谓词再加 `priority == priority` 枚举。
  priorities 里的每个值都源自至少一个满足该谓词的候选，lastOrNull
  总是非空。
- L212/213/214：progressiveCandidateStretchDensity 是 private 函数，
  唯一调用点 L151 传入的 boundary 来自 L146 的 lastOrNull，其谓词
  `opportunities[candidate]?.let { ... } == true` 保证
  `opportunities[boundary]` 非空。`opportunities[boundary]?.spanRange`
  的 null 方向（L213 为真、L214 的 0 臂）不可达。
- L137 的两个中间方向（clusters 非空加 lineLimit 无穷；lineLimit 有限
  加 maxCjkStretchPerGap 无穷）由 ProgressiveBreakDecisionsTailTest
  覆盖。

### 条目 52：Justifier.kt（任务标签 cov-jf2，主会话独立复核）

- L284/333：`!in IntRange` 展开的下界检查方向不可达。javap
  （Justifier.class）L284 偏移 1222 的 if_icmpgt、L333 偏移 1696：
  `range.first > nextIndex` 方向。前一合取项已保证
  `targetIndex in range`，`nextIndex = targetIndex + 1 > range.first`
  总是成立。
- L488：`for (idx in lineClusterRange)` 的空范围方向。L394-396 对空
  范围的三个 any 谓词全假并提前 return finalize，到达 L488 时
  first <= last。
- L551/552：`remaining > 0.0 &&
  lineHasEmergencyTrackingBoundary` 永远为假。L361（preferred 路径）与
  L524（Tier 4 路径）对命中 emergency 边界的索引都给
  `capacity = remaining`；allocate 在 `totalCapacity >= deficit` 时
  返回 0.0，L370/L539 调用后 remaining 变为 0（L376 提前返回）。合取
  为真不可达。
- L584：compress 的 `totalCapacity <= 0.0` continue。byTier 来自
  `filter { it.capacity > 0.0 }` 的非空分组，严格正数之和总是正数。
- L610/612/613：`private inline fun buildBoundaryOpportunities` 的独立
  方法体。javap 全文只有 2 处出现该方法名（定义本身与 $default 桥），
  零 invoke 指令；调用点全部内联进 justify()。合成方法的入口检查与
  循环边界分支永不执行。
- L632：allocate 的 `deficit <= 0.0` 方向。allocate 是 private 函数，
  7 个调用点（L204/238/320/345/370/507/539）全部形如
  `remaining = allocate(...)`，调用前 `remaining > 0.0` 已由守卫保证。
- 全量报告核对（2026-08-29）：Justifier 的全行未覆盖 = [552]，部分
  覆盖 = [284, 333, 610, 632, 711, 715, 718, 727]，分支 = [488, 551,
  584, 612, 613]，全部行有本条或第 26-27 条的证明。

### 条目 53：LineRepair.kt:81，all 的空集合短路

extendsContextualHang 内 `all {}` 的空集合短路。javap
（LineRepairKt.class，偏移 543-548）显示未覆盖方向是空集合直接为真的
短路。L78 的 `existingHanging.isNotEmpty()` 守卫保证进入 all 时集合
非空。四个语义方向由既有 4 个子用例覆盖
（contextualHangExtendsOnlyInsideItsProtectedGroup）。

### 条目 54：LineGeometryStage.kt

- L434：lineMetrics 的 $default 桥。spacingFloor 有默认值 0.0；唯一
  传全参的调用点（LineBreakPlanningStage L313）直接调主方法，桥零
  调用（与条目 46 的 LineBreaker L587-594 同一模式）。
  LineNumberTable 偏移 7 映射 L434，mi=2。
- L455/456：`maxOf { it.layoutMetrics.ascent/descent }` 的空列表抛出
  臂。javap（LineGeometryStageKt.lineMetrics，L455 偏移 205-215、
  L456 偏移 314-321）：`new NoSuchElementException; dup; <init>;
  athrow` 与对应 ifne 的 hasNext 假方向。L437 的 `if (isEmpty())`
  早退（EmptyParagraphBaselineFallback）处理了唯一能使 heightSource
  为空的输入；`heightSource = filter{}.ifEmpty{this}`，为空当且仅当
  接收者为空。两条抛出臂均不可达。行为由
  emptyMetricListTakesEmptyParagraphBaselineFallback 固定。
- 全量核对：LineGeometryStage 只剩 [434, 455, 456]，全部有本条证明；
  此前的 L268/273/302/352 已由 LineGeometryDirectTailTest 用测试覆盖
  （两单位簇触发 clusterIndexRangeFor 的 null 臂、无几何 span 触发
  map elvis 的 null 臂、带 span 的空行触发 maxOrNull elvis、双行
  ruby 触发重叠合取双向、objectTopIntrusion 占优与持平触发边界合取
  双向）。

### 条目 55：WidthIndependentAnnotationCache.kt:179-182，被重载解析绕开的私有扩展

同包存在两个同形扩展：本文件 L178 的 `private fun <K, V>
MutableMap<K, V>.mergeValue(...)` 与 PunctuationGeometryLedger.kt
L689 的 `internal fun <K, V : Any> MutableMap<K, V>.mergeValue(...)`。
L801 调用点的接收者是 `HashMap<Int, Float>`（2026-08-30 复核更正，
原文误写为 `HashMap<Int, Double>`；rubySpread 由 computeRubySpread
构造，值为 `Map<Int, Float>`），`V : Any` 约束更具体，
重载解析选中 ledger 版本。javap
（WidthIndependentAnnotationCacheKt.class）：唯一调用点偏移 6283 是
`invokestatic PunctuationGeometryLedgerKt.mergeValue`；本文件的私有
mergeValue 零 invoke 指令。方法体 L179-182 的全部行不可达。
【已删除】用户 2026-08-29 授权，main 提交 074eb5ce 删除私有版的
六行（独立提交可审计），jvmTest 全量含 golden 零 diff 验证。

### 条目 56：WidthIndependentAnnotationCache.kt:883

该行的未覆盖由两部分构成：

1. cls 的空路径。javap（buildParagraphLayoutPrep，L883 偏移
   7043-7049）：`ifnonnull 7052` 的假方向及其 3 条指令（pop;
   iconst_m1; goto）。`caps != null` 要求 budgets[idx] 存在，budgets
   的键集等于 geometries 的键集，geometries 只收录含 atom 的簇
   （PunctuationGeometryLedger.kt L170-172 的 isInside 谓词）；
   atomClassByRange 用同一包含谓词（firstContainedItem，
   `start >= cluster.start && end <= cluster.end`，与 isInside 逐字
   相同）。簇有 atom 蕴含 `atomClassByRange[cluster.range]` 非空，
   cls 总是非空。
2. PunctuationClass.Quote 的 case 边。ClreqProfile.classify
   （L415-429）没有任何分支产出 Quote；Quote 只在
   forbiddenAtLineStart 列表中被引用。tableswitch（偏移 7060）的
   case 5 槽永不命中。

tier-3、tier-4、PauseOrStop、else 四个体与 Interpunct、MiddleDot、
Opening、Closing、Ellipsis 各 case 边由 InterpunctShrinkOpportunityTest
与既有引擎 fixture 覆盖（halt 半宽证据加 PreserveInput 保码点两条路）。
全量核对：WIAC 的未覆盖行 = [179-182, 228, 474, 681, 833, 883]，全部
有条目 44、55 与本条的证明。

【部分删除】第 2 项 Quote 的 case 边随枚举值删除（main 提交 76537615，
见条目 50），tableswitch 重排后 Quote 槽位不复存在；第 1 项 cls 的
空路径仍在，属构建不变式（`caps != null` 蕴含 budgets[idx] 存在），
其后 Kover XML 的行号整体前移 7。

### 条目 57：LineOptimization.kt:98/118

- L98：`hangingClusterIndices.maxOrNull()` 的编译器空检查。javap 偏移
  192 `ifnonnull 200` 的假方向与偏移 196-197（pop; goto 212）。L96
  的 `hangingClusterIndices.isNotEmpty()` 守卫保证进入时集合非空，
  Int 集合的 maxOrNull 总是非空。三个拒绝方向（min 出界、max 不等于
  last、不连续）由 LineCandidateValidationTest 的四个用例覆盖（含
  firstHanging > last 时上界比较直接失败的方向）。
- L118：`minOrNull()?.let { ... }` 的 let 结果空检查。javap 偏移 39
  `ifnonnull 47` 的假方向。lambda 体是 `RangesKt.until(...)`，返回的
  IntRange 总是非空。elvis 的输入空臂（minOrNull 为 null）由无悬挂
  用例覆盖。

### 条目 58：PreparedParagraph.kt:68，styleAt 的第二个判空

toPreparedParagraphJson$styleAt 的字节码（javap -c -l，行 68 的区间
56-93 与 94-108）：偏移 98 的 lastOrNull 判空（`ifnull 108`）之后，
链在偏移 105 重新检查 getStyle()（`ifnonnull 116`）；假路径（105 到
108，elvis 回退到 input.textStyle）要求 TextSpan.style 为 null。
TextSpan 声明 `val style: TextStyle`（TextModel.kt:53-56，非空），
非空 span 总是携带非空 style：第二个判空是编译器对安全调用 elvis 的
降级，假方向不可达。与条目 44、57 同族。行 68 的其余方向（谓词合取、
lastOrNull 的 null elvis）由 PreparedParagraphJfTest 覆盖。

行 309（`inlineStartByOffset.isNotEmpty() ||
inlineEndByOffset.isNotEmpty()`）是最后一个可达的未覆盖方向：(false, true)
方向（只有 inlineEnd 的 box）与 (false, false) 的确认由
PreparedParagraphInlineEdgesTest 在 `renderEvidence = true` 下覆盖：
整个 inlineEdges 块在 appendParagraphRenderEvidence 里，无 evidence
的序列化从不执行。

### 条目 59：ParagraphDpLineBreaker.kt:364/620，用测试覆盖的两行

条目 49 遗留的两行由测试覆盖，两个方向都是活路径：

- L364（promotion pool 同 span 清洗的 `spanRange != promotedSpan`
  保留臂）：
  ParagraphDpTierPromotionPoolTest.foreignSpanCandidateSurvivesThePromotionPoolPurge
  在 tier-promotion 基础场景上加入 end 1 的异 span Emergency 机会，
  该候选经 progressiveCandidateAllowed L87（异 span 无条件放行）入池，
  再经 L362 过滤器的 span 不等比较保留。
- L620（commit 段 promotion 合取的第二个判空 `resultingBreak != null`
  的假方向）：
  committedCompressedEndWithoutOpportunityKeepsPlainPushInReason 只在
  重算的 greedy 端放机会，chosen 压缩端无机会。空映射场景在第一个
  判空即短路，到不了第二个判空，这是唯一构造。
  committedCompressedLineWithForeignSpan... 同时覆盖 L621 的 span
  不等方向。

全量 Kover（2026-08-29 合并树）：364/620/621/622 全部 mb=0。
ParagraphDpLineBreaker 的未覆盖行至此全部由条目 47、49 与本条或测试
覆盖。

## 解析器（条目 60-62）

### 条目 60：QuotePairAnalyzer.kt:155，负索引比较方向

`codePointAtOrNull` 的负索引比较方向。`if (index !in indices)` 的
`index < 0` 半边（javap 偏移 2 `if_icmpgt 26` 与偏移 26 `iconst_0`，
合并后重编未变）不可达：函数是 private，上游合并后共三个调用点
（L100/L115/L123），分别传 isNonCjkInWordApostrophe 的右邻
`index + 1`（该函数的两个调用方 QuotePairAnalyzer.kt:46 与
ContextualQuoteRoleResolver.kt:127 的循环 index >= 0，实参 >= 1）、
isNonCjkWordInternalQuotePair 的闭引号后邻 `closeIndex + 1`
（>= 2）、内层循环下标（自 `openIndex + 1` 起且 < closeIndex）。
`index >= length` 半边是活路径，由 `a’`
（撇号为串尾）覆盖。与条目 44/57/58 同族：调用点不变式排除了越界
方向。其余 QPA 未覆盖行已由测试覆盖：analyze 的 when 边由
QuotePairAnalyzerSurrogateAdjacencyTest 的 lowQuoteCodePoints 用例
（8218/8219 case 边）加另一批已合并测试（8216/8217/8220/8221）的
并集覆盖；codePointBefore/codePointAtOrNull 的 `!in a..b` 上界比较
方向由 plainAndBoundaryNeighboursWalkTheNonSurrogateArms 与
apostropheAfterASurrogatePair 的 ``、双低代理、`\uD83D`
用例覆盖；isNonCjkWordCharacter 的合取由全量套件的其余测试覆盖。
新增两处主张见条目 68（L123 的 elvis 右值与 L143 的全宽区间真方向）。

### 条目 61：ContextualQuoteRoleResolver.kt 五处

其余未覆盖方向已由 ContextualQuoteRoleResolverNestedAndSurrogateTest
（13 个用例）覆盖。行号随上游合并移位：96/102 -> 104/110，
156/227/263 -> 176/247/283；javap 偏移按合并后编译产物更新：

- L104/110：`resolvedPairs[enclosingPair]?.let` 的 null 方向（javap
  偏移 320 `ifnull 344` 的跳转方向与跳转目标 344 处的后续指令；合并前为
  偏移 292 `ifnull 316` 与 317 nop）。resolve 循环按 openIndex
  升序、closeIndex 降序处理，findParent 保证
  `parent.openIndex < pair.openIndex`，父对总是先于子对写入
  resolvedPairs，查表不为 null。
- L176：leftRole 非空时 `rightRole != null` 的假方向（偏移 183
  `ifnull 205`，合并前目标为 192）。L161 的规则一（`left != null &&
  (right == null || right == left)`）先于 L176 捕获该组合。
- L247：`nestedPair.closeIndex < end` 的假方向（$ScriptEvidence
  addRange 偏移 39 `if_icmpge 53`，合并未改变该类体）。QuotePairAnalyzer
  的栈纪律保证产出的对不跨接：任何在 addRange 段内打开的对必在段内
  闭合（内容段、包围层左右段、全文段四种调用点逐一验证）。
- L283：codePointAtCompat 的 `low !in 0xDC00..0xDFFF` 真方向
  （return high，偏移 59 `if_icmpgt 78` 与 67 `if_icmpge 74`）。唯一
  调用方 strongScriptRole 传 `end = index + codePointLengthAt(同一段)`；
  后继不是低代理时 lengthAt 答复 1，compat 在 L281 的 `index+1 >= end`
  提前返回，L283 只可能看到区间内的后继。

与条目 44/57/58/60 同族：排序顺序、规则前置、调用点不变式排除了
这些方向。

### 条目 62：ClusterRoleResolution.kt 九处

javap 偏移引自 ClusterRoleResolutionKt.class（2026-08-29 全量构建）。
其余可覆盖方向已由 ClusterRoleResolutionSurrogateAndExtenderEdgeTest
（11 个用例）覆盖：星面变体选择符的扩展、点号断开、修饰符游走三路，
E01F0 上界，低代理区间两边，空白邻接臂，inline-object 盖住 CR 后的
LF 强制断行守卫，修饰符基簇尾 `next < end` 的假方向。
modifierBaseWithABmpSelectorWalksTheSelectorTrueArm（✊ 加 U+FE0F 加
🏻）是 CM 遮蔽论证的行为见证：BMP 选择符被 CM 检查先行处理，序列
保持不变。

- L139（mi=5 mb=3）：
  `sourceGraphemeBoundaries.getOrElse(graphemeBoundaryIndex) {
  text.length }` 的默认臂（偏移 142 `if_icmpgt 165` 的越界方向与
  `$i$a$-getOrElse` lambda 体）。interactionBoundaries 对
  `range=[0, text.length]` 的产出是非空递增序列且末元素等于
  text.length：非空文本时 size >= 2（out 以 start 起始、每簇尾追加
  一个元素），索引 1 总在界内；空文本时索引 0 命中 [0]。默认 lambda
  永不求值。
- L141（mb=1）：内层 while 的第二个合取
  `graphemeBoundaryIndex < sourceGraphemeBoundaries.lastIndex` 的假
  臂。最后边界总是等于 text.length（同一不变式），外层 while 保证
  `index < text.length`，`index >= graphemeEnd` 只能在非最后边界处
  成立，第二个合取为假的方向不可达。
- L205（mi=2 mb=1）：`text.getOrNull(previousRange.range.end - 1)`
  的 null 臂。已产出的 range 连续覆盖整个文本：每个 range 满足
  `1 <= end <= text.length`，end-1 总是有效的索引。防御性检查。
- L206（mb=1）：`previousRange.range.end == start` 的假臂。range 按
  游走顺序追加，新 range 的 start 总是等于前一 range 的 end（连续性
  不变式；该检查防范未来可能出现的非连续产出）。
- L282（mi=2 mb=1）：`emojiRolePromotionReason ?:
  "EmojiPresentationCodePoint"` 的 elvis 右值。进入该块要求
  `role == Emoji` 且 `classifiedRole != Emoji`，而 L191-198 的 role
  赋值只有两个来源（`classifiedRole == Emoji` 或 `reason != null`），
  二者合取下 reason 总是非空。
- L311（mi=4 mb=2）：变体选择符 BMP 区间的真臂族（偏移 11
  `if_icmpge` 不跳加 14/15 `iconst_1`，及偏移 23 `ifne 52` 短路真加
  52/53）。两个调用点（通用扩展循环 L266、修饰符游走 L348-351）都
  先查 isCombiningMarkCodePoint 再查 VS；U+FE00..U+FE0F 全部属于 Mn
  且 Mn 在 COMBINING_MARK_CATEGORIES 里，BMP 选择符总是被 CM 检查
  拦截，VS 的 BMP 区间永远收不到真值输入。
- L314/317（各 mi=1 mb=1）：`this in 0..0xFFFF` 的负值臂（两个函数
  偏移 1/2 `iconst_0; if_icmpgt` 的跳转方向）。输入全部来自
  codePointAtCompat（char 码或代理对合成值，总是 >= 0）。
- L354（mi=2 mb=1）：修饰符区间的上界臂（偏移 211 `if_icmpgt 218`
  加 218/219 `iconst_0`）。检查读取的是打断游走的字符本身（游走
  处理完 CM/VS 成员后的首个非 CM/VS 簇成员）：可达的打断者只有 ZWJ
  （0x200D，在区间之下）或修饰符本身（在区间之内）；0x1F3FF 之上的
  星面字符要进入簇只能跟在 ZWJ 之后，而游走在 ZWJ 处即断，永远读
  不到它。

## 行号核对与验收（条目 63-65）

### 条目 63：行号核对补注（2026-08-29 全量实测）

条目 18/21/22/24/26/27/28/29/30/31 写作时的行号比当前源文件整体大 1
（后续编辑上移）。当前未覆盖行与条目主张的构造一一核对无误：
LineAdjustmentStage 的 L203-205 是条目 18 的 LeadingAndTrailingGlue
臂族；L567 是条目 24 的 baseLineMetrics.height 回退；Justifier 的
L711/L715/L718/L727 是条目 26/27 的 getOrNull null 臂与全空格邻簇假
方向；LineRepair 的 L416 是条目 28 的 `"$text.0"` then 臂，L507 是
条目 31 的 `currentBreak != null` 冗余合取。追认以上条目覆盖当前
行号。

另外两组偏移同轮核对追认：条目 29/30 的 LineRepair 构造当前在
L688（`totalShrink <= 0.0` 合取）与 L697（`tierCapacity <= 0.0`）；
条目 21/22/25 涉及的 LineAdjustmentStage 区间当前为 L411
（rejectedTechnicalTiersBySpan 的映射 miss 与 hit）、L412（tier in
rejectedForSpan）、L413（justificationPlans 的 getOrNull null 臂）。
条目 15 主张的 PunctuationGeometryLedger L333 构造已由后续测试覆盖，
不再出现在未覆盖行集合里；该行的主张已被测试取代。
（2026-08-30 条目 31 合取项删除后，LineRepair 的 L688/L697 整体
下移 2，现为 L690/L699。）

### 条目 64：UnicodePunctuationBoundaryResolver.kt（任务标签 cov-upb）

初始 24 行。外部模型产出 105 个测试，其中两个 decimal 测试用错码点
（U+FF0C 属于 CL 类且空格簇插在标记与数字之间使 following 探测读到
空格），主会话改为 ASCII 句点并补一个输入全角逗号、断言仍然禁止的
反向用例。主会话分两批新增 16 个测试（decimal 族 5 个、撇号邻码点
族 4 个、attachments 析取族 6 个、空 previous 簇 1 个），24 行清到
17 行。余下 17 行全部不可达，逐条裁定如下（字节码证据取自
`javap -c -l`）：

- L258（mi=2 mb=1）：`lastSignificantCodePoint() ?:
  return@forEachIndexed` 的 null 臂。该函数只在整段源全为空白时返回
  null，而 L257 的 firstSignificantCodePoint 已在同一 source 上返回
  非空，「全为空白」与「存在有效码点」互相排斥，null 臂走不到。
- L350（mi=5）：ruleForLineStart 的 `else -> error` 臂。when 的接收
  者来自 UAX14_FORBIDDEN_LINE_START_CLASSES 的成员过滤，该集合的四
  个成员与 when 的三个显式臂加 IS 臂完全覆盖（python 扫描证明），
  error 臂收不到任何值。
- L388（mi=2 mb=1）：`offset + if (codePoint > 0xFFFF) 2 else 1` 的
  2 臂。该表达式位于 `if (codePoint == 0x2019)` 分支内，0x2019 总是
  小于 0x10000，条件永远为假。
- L414/433/445/478（各 mi=2 mb=1）：codePointAtOrNull 与
  codePointBefore 的 null 回退臂，位于四个游走循环内。循环头分别
  保证 offset < length（414/478）、offset 为 end-1 或 end-2 且 end
  有下界（433）、cursor > 0 且 cursor 不超文本长度（445）；两个探测
  函数只在 index 越界时返回 null，界内总是非空。防御性判空。
- L416/448（各 mi=2 mb=1）：游走步进 `if (codePoint > 0xFFFF) 2
  else 1` 的 2 臂。步进只发生在 codePoint 为空白时；增补平面不存在
  空白码点（isWhitespaceCodePoint 以 `this <= 0xFFFF` 为第一个合取
  项），星面字符在步进前已被当作有效码点返回。
- L486（mi=21 mb=10）：firstCodePointLength 的代理对臂。字节码行号
  表显示整个方法只映射到 486 一行；mi=21 为偏移 40 至 77 的第二个
  代理检查与 `iconst_2` 返回段。唯一调用方 isDecimalMarkAfterSpace
  读 currentSource，其首个有效码点必须是 IS 类（python 扫描 RANGES
  表确认全部在 BMP），前导空白也全在 BMP，`this[0]` 永远不是高位
  代理。
- L426/427/491：把孤立代理送进有效码点扫描的三个方向。L426 的假臂
  要求 end=1 且 `this[0]` 为孤立低位代理（首个有效码点就是孤立低位，
  classOf 的 require 先抛异常）；L427 的余方向是低位代理的前邻不是
  高位代理的布局（如 `"A\uDC00"`），该簇自身的扫描先读到孤立低位
  代理，同样在 require 处抛异常；L491 的真方向是串尾孤立高位代理
  经 codePointAtOrNull 返回后成为 lastSignificant，同样在 require
  处抛异常。三条路径都在 classOf 的 require 处终止，不存在能安全
  执行到该行的输入序列。
- L493/500：区间检查降级产生的空交集方向。字节码把
  `in 0xDC00..0xDFFF` 降级为「先测 > 0xDC00 就跳出，再测 >= 0xE000」
  两段；第一段已分流一切大于 0xDC00 的值，第二段为假意味着值既小于
  0xE000 又不大于 0xDC00，这个区间是空集。L493 的 mi=2 即偏移
  105-106（iconst_0 与 goto），L500 的 mi=3 为偏移 45-46 与 86-87
  两处同型方向。L500 另有一个 mb 方向（low 代理在位置 0）属于 L426
  同型的必抛路径。
- L489（mi=1 mb=1）：`index !in indices` 的负索引方向。全部调用点
  的 index 来自循环 offset（从 0 起）或字面量 0，总是非负。
- L498（mi=0 mb=1）：`index > length` 的越界方向。唯一调用点
  quoteDirectionAt 传 cluster 的首偏移，总是不超过文本长度。

验证：全量 `:engine:jvmTest` 加 `:engine:koverXmlReportJvm` 构建成功；
该文件的 17 行未覆盖与上述裁定一一对应，没有未主张的行。

### 条目 64 补记：维护裁定执行（2026-08-30）

条目列举的 17 行按五类维护裁定处置。第一类（旧 L388）、第二类
（旧 L416/448/486）、第三类（旧 L426/427/491）删除；第四类
（旧 L258/350/414/433/445/478/489/498）与第五类（旧 L493/500）保留。

- 第一类：U+2019 分支内 `codePoint > 0xFFFF` 的条件永远为假，右邻
  偏移改为 `offset + 1`。
- 第二类：空白码点全部在 BMP，firstSignificantCodePoint 与
  followsAuthoredBoundary 的游走步进改为常量 1。firstCodePointLength
  函数整体删除，唯一调用方 isDecimalMarkAfterSpace 的后继码点探测
  从 char 1 开始（IS 标记与前导空白各占一个 char）。
- 第三类的删除前提是入口拒绝孤立代理。validateLayoutInput 新增
  UTF-16 扫描：高代理必须后随低代理，低代理必须前有高代理，违者抛
  IllegalArgumentException。拒绝路径由
  ParagraphLayoutEngineValidationCoverageTest 的
  sourceTextMustNotContainUnpairedSurrogates 覆盖，四个输入分别走
  串尾高代理、前导低位代理、高代理后随高代理（区间检查低侧出口）、
  高代理后随 U+F900（区间检查高侧出口）。此后
  lastSignificantCodePoint 不再检查 end ≥ 2 与前邻高位代理，
  codePointAtOrNull 删除 `index + 1 >= length` 臂。
- 第四类保留：删除会使返回值可空，调用方需要补判空处理。当前行号
  258/350/416/430/442/477/485/496。
- 第五类保留：区间检查降级产生的空交集方向是编译器产物。当前行号
  491/498。旧 L493 的 `low !in 0xDC00..0xDFFF` 检查保留，串中孤立
  高位代理返回该单元的行为有直达测试。

验证：全量 `:engine:jvmTest`（含 LayoutDumpGoldenTest）与
js/linuxX64/linuxArm64/mingwX64 测试编译、compileAndroidHostTest 通过。
135 份 trace txt 中仅 ParagraphLayoutEngineValidationCoverageTest.txt
增加新小节，其余逐字节不变。kover 复测（reportJvm.xml）该文件未
覆盖降为 10 行，即第四类 8 行与第五类 2 行，无新增未主张的行。

### 条目 65：commonMain 覆盖验收（2026-08-29）

全量 `:engine:jvmTest` 加 `:engine:koverXmlReportJvm` 构建成功
（1300+ 测试）。核对脚本 /tmp/acceptance-check.py 从 reportJvm.xml
提取全部未覆盖行（mi>0 或 mb>0），与第 1-64 条的主张逐行相减：
27 个文件共 228 行（commonMain 226 行；jvmMain 2 行即
BundledHyphenationResource.jvm.kt 的 L6-7，见第 50 条）全部有条目
主张，差集为空。行号偏移的核对依据：第 63 条及其补注（整体偏移 1
的一批）、第 56 条（WidthIndependentAnnotationCache 删除后整体偏移
7）、第 49/59 条的实测行。测试集核对：合并树持有全部测试文件，与
并行工作副本的差异核对确认合并树是超集（CRR 测试多
modifierBaseWithABmpSelectorWalksTheSelectorTrueArm 用例、PGP 测试
已随 76537615 删除 Quote 断言、多出 org/tiqian/test 共享语料目录），
并行副本独有的 UnicodePunctuationBoundaryResolverCoverageTest 已
并入合并树。

### 条目 65 补记（2026-08-30）

条目 64 补记的裁定执行后复测（reportJvm.xml）：27 个文件共 226 行
（commonMain 224 行；jvmMain 2 行不变），acceptance-check 以裁定后
的主张行重跑，差集为空。条目 42 的 ParagraphLayoutEngine.kt 四行因
validateLayoutInput 新增代理扫描行号整体加 17，当前行号
94/106/112/126，构造不变（四组 `range.start >= 0` 冗余合取）。

### 条目 66：PreparedParagraph.kt 数字序列化重写后的未覆盖行（2026-08-29 复测）

ecmaJsonNumber 重写为平台无关实现（位数取未钉格 double 的最短往返位数，
数位取 f32 网格值的精确展开在该位数下的 half-even 舍入）后重测。原先由
测试覆盖的行变化如下：:441（NaN 出口）由 zeroValuesSerializeWithoutSign
的 NaN/Infinity 断言覆盖；:516-517（区间界精确相等臂）由
boundaryMidpointsAcceptOnlyAtEvenMantissa 的两个偶 mantissa 平局值覆盖
（121,830 个样本扫描里仅有的两个命中）；:638 与 :642 的指令部分（分块
乘法器跳过为零的低 8 位块）由 decimalAlignedMantissaSkipsZeroChunk（ widen
后 mantissa 为 6710886400000000 的 12500000f）覆盖；Justifier.kt:588
（compress 的 `shrink > 0f` 假方向）由 JustifierCompressionTest 的
nanSurplusEmitsNoAllocations 覆盖（NaN surplus 使每个 tier factor 为 NaN）。
本条主张以下各行。

- L457（mi=4 mb=1）：指数臂的 k==1 分支（`digits[0].toString()`）。else
  臂要求 k==1 且 n ≤ −6 或 n > 21。k==1 的值形如 d×10^(n−1)，d ∈ 1..9。
  n ≤ −6 时分母含 5^(1−n) ≥ 5^7，d 吸收不了，任何 dyadic 都取不到这些
  n。n > 21 时 d×2^(n−1)×5^(n−1) 落在 f32 网格上要求奇部 5^(n−1)×odd(d)
  不超过 24 位，n−1 ≤ 10，n ≤ 11，落在第一布局臂里。两个方向对 JVM 输入
  不可达；JS 上未钉格 double（如 1e-302）可达，与原条目 3/4 的平台差异
  同性质。
- L525/L536：L 循环走满 17 次的出口方向与 `return Pair(exact, n)` 回退。
  有限 double 的 17 位有效十进制正确舍入值解析回原值：exact 位数 D ≤ 17
  时，L=D 的候选就是全串，区间判定必真，循环更早返回；D > 17 时 17 位
  舍入候选是 keep 与 up 之一且在区间内，析取在 L=17 成立。循环必然在
  17 次内返回，两行不可达（L536 的 ci=0，从未执行）。
- L551：canonicalFloatDigits 的零值守卫，见条目 5。
- L570：`rounded.length == doubleDigits.length` 的 else 回退。JVM 上
  canonicalFloatDigits 的 stripped 与 shortestRoundTripDigits 的 exact 是
  同一值的同一精确展开；在 doubleDigits 的位数处重新 half-even 舍入得到
  同一字符串（舍入后 trim 掉的数位在更短位数下重新舍入，结果不变：尾部
  零接 round-down 保前缀，尾部 9 接 round-up 保进位前缀），rounded 与
  doubleDigits 总相等；exact 位数不超过 doubleDigits 位数时 :567 守卫提前
  返回。长度不等的回退只服务 JS 未钉格 double。两次扫描（43,550 与
  121,830 样本）零命中。
- L600/L616：fiveToThe/twoToThe 锚点扫描 `j in 0 until k` 的 `0 > j` 方向
  （javap：fiveToThe L600 偏移 112 的 if_icmpgt，twoToThe 同构）。两个
  缓存的键来自 dyadicDecimal 与 canonicalFloatDigits 的调用点，fiveToThe
  的 k 为 −f ≥ 1，twoToThe 的 k 为 f ≥ 0，缓存中不存在负键，`0 > j` 永假。
- L642-643/L647：else-if 为真要求低 8 位块为零且没有高位块，即
  factor == 0；:647 的 elvis 右臂要求所有块为零或循环未进入，同样只剩
  factor == 0。调用方传入的 factor 是 53 位 mantissa、2m±1、4m−1 或 24 位
  mantissa，全部 ≥ 1。:642 的假方向（块为零但有高位块）已由
  decimalAlignedMantissaSkipsZeroChunk 覆盖，剩余方向不可达。
- L695：compareDecimal 的 `eA >= eB` else 补零方向。compareDecimal 只被
  insideInterval 调用（:514-515），候选位数 L 不超过 exact 位数 D（L=D
  时全串就是值本身，判定必真，循环更早返回）。e < 0 时 eA < eB 等价于
  L > D+1（binade 底界为 D+2）；e ≥ 0 时边界展开是整数（eB = 0）或 x.5
  （eB = −1），而 eA = D − L ≥ 0。两个方向都不可能。

验证：全量 `:engine:jvmTest` 加 `:engine:koverXmlReportJvm` 构建成功；
acceptance-check 差集为空（条目 65 复跑）。

## 外部复核处置（条目 67）

### 条目 67：2026-08-30 外部复核处置

外部复核报告对本文件逐条重验，基准为上游主仓 main @ 13d8ca38；本仓
的重写与测试群尚未同步到上游（见文头补记），据此判定：52 条成立，
3 条推翻（3、4、17，其中 3、4 的前提是上游未含重写），5 条部分成立
（15、44、46、60、62），1 条论据过时（29），5 条无法在复核环境验证
（6、7、25、59、66，依赖 Kover 数据或本仓测试）。处置：

- 条目 17 按复核改写（见上）：重映射臂是活代码，补测试
  loneLatinClusterMergesBothAutoSpaceEdgeTrimsIntoOneKey 覆盖；
  PunctuationGeometryLedger.kt:690 的指令转为已覆盖，残余 mb=1 按
  第五类主张。验收脚本对该文件的主张行集改为
  {239, 337, 339, 396, 648, 690}（333 已由测试覆盖，条目 63）。
- 条目 15 更正开引号反例；条目 46 更正 L464 的方向；条目 29 更新
  调用点计数；条目 55 更正接收者类型；条目 60 补全调用方清单。
- 条目 44/62 的源码论证复核成立；两者引用的测试在本仓存在。
- 调用点计数型证明自本轮起在条目内附上生成证据的 grep 命令与当时
  计数（条目 17 已按此补写），复核时先重跑计数再核对结论。
- 裁定版复核的删除清单另执行两项。条目 31 的冗余合取项删除（补记见
  该条目；LineRepair.kt 该文件 L509 之后行号整体下移 2，条目 29/30
  的构造现为 L690 与 L699，条目 28 的 L416 与条目 53 的 L81 在删除点
  之前不变）。条目 45 的 BopomofoTone.Ru 臂裁定保留（源内注释记录
  v1 不产出，属计划内扩展点，与 Quote 的无记录占位不同）。裁定版对
  落单代理项三行的「留」建议被更早的用户裁定取代：入口拒绝孤立代理
  已实现，三行已随条目 64 补记第三类删除。
- 验收脚本增加调用点计数核对（先核条目 17 的 mergeValue 计数 23），
  行号主张集同步更新（LineRepair 去掉 507，条目 29/30 行号加 2）。

复测：全量 `:engine:jvmTest` 与 `:engine:koverXmlReportJvm` 通过
（1317 个用例，含 LayoutDumpGoldenTest 零 diff；
LineAdjustmentStageCoverageTest 20 个用例），acceptance-check 差集
为空（残余 225 行，commonMain 223 行）。

同轮 Kover 聚合覆盖率（engine JVM 全部源集，reportJvm.xml 根计数）：

- 指令 89430/89853（99.53%，未执行 423）
- 分支 5881/6087（96.62%，未走到 206 个方向）
- 行 12490/12549（99.53%，未执行 59）
- 方法 736/741（99.33%）
- 类 298/299（99.67%）

残余 225 行按缺失类型拆分：56 行有未执行指令（mi>0 且 mb=0），
64 行指令已全部执行、只缺分支方向（mb>0 且 mi=0），105 行两者都有；
commonMain 223 行 = 56 + 64 + 103，jvmMain 2 行属于两者都有
（BundledHyphenationResource.jvm.kt:6-7，条目 50）。聚合数里的
「未执行指令 423」统计的是指令条数，「56+105 行」统计的是行数，
两个数字统计的对象不同。

## 上游合并复测（条目 68）

### 条目 68：2026-08-30 上游合并后的残余核对

合并 upstream/main 1d8c134d（PR #18，引号语境规则）为 484c92ca。
上游把 isNonCjkInWordApostrophe 改写为块形态，新增
isDigitBoundClosingQuote、isNonCjkWordInternalQuotePair、
isFullwidthEastAsianWidth 与 UnicodeNumber；ContextualQuoteRoleResolver
插入 NonCjkWordInternalQuotePair 与 NumericPrimeUnmatchedQuote 两条
解析路径。合并后首次复测残余 229 行，其中 10 行未认领；处置：

- QuotePairAnalyzer.kt:123（mi=2 mb=1，第四类）：
  isNonCjkWordInternalQuotePair 内层循环
  `codePointAtOrNull(index) ?: return false` 的 elvis 右值。循环下标
  自 openIndex + 1 起且小于 closeIndex，两个索引都来自 analyze 对
  text.indices 的遍历，closeIndex <= lastIndex，查询下标总在界内，
  查询结果不为 null，elvis 右值不可达。
- QuotePairAnalyzer.kt:143（mi=2 mb=2，第一类）：
  isFullwidthEastAsianWidth 的偏移 4 `if_icmpeq 55` 相等真方向
  （码点等于 0x3000）与偏移 40 `if_icmpge 47` 的不跳方向
  （0xFFE0..0xFFE6 全段）。该函数只经 isNonCjkNonNumericWordCharacter
  到达，入口先经 isNonCjkWordCharacter 的 Letter/Mark 检查加非
  EastAsian 的 script evidence，再排除 UnicodeNumber 成员；0x3000 的
  类别是 Zs，0xFFE0..0xFFE6 全部是 Sc/Sk，无一能通过第一道检查。
  其余方向由三类输入覆盖：拉丁字母走第一区间的两侧、全宽拉丁字母
  （0xFF21..0xFF5A，Letter 且 evidence 为 Other）走第一区间真侧、
  星面数学字母（0x1D400，Scripts.txt 归 Common，evidence 为 Neutral）
  越出第一区间上界并进入第三区间下界比较。
- QuotePairAnalyzer.kt:155 与 ContextualQuoteRoleResolver.kt 的
  104/110/176/247/283：条目 60/61 的既有主张随上游插入移位，主张与
  不变式论证不变，两条内引用的行号与 javap 偏移已按合并后的编译
  产物更新。
- UnicodeWordCharacter.kt:21 的域检查失败方向由新增
  org.tiqian.core.UnicodeNumberTest 覆盖（-1、0x110000、孤立代理三项
  assertFailsWith 加正反成员断言），残余清除，不主张。
- QuotePairAnalyzer.kt:124 的对内非词字符 return false 由新增
  keepsSpaceInsidePairOutOfWordInternalFastPathLatin 覆盖（对内空格，
  解析结果为 ParagraphLanguageQuoteContext），残余清除，不主张。

新增测试三处：QuoteClassificationEngineTest 的
keepsSpaceInsidePairOutOfWordInternalFastPathLatin 与
keepsAstralLetterBoundedWordInternalQuotesLatin（星面字母作外邻，
代理对合成后仍是词字符，越出全宽区间），以及
org.tiqian.core.UnicodeNumberTest。

上游语义变化的一处测试修正：isNonCjkInWordApostrophe 的重写先取
两侧邻码点再作词字符查询，串尾撇号在右邻为 null 处返回 false，
不再到达抛异常的查询；QuotePairAnalyzerSurrogateAdjacencyTest 的
双低代理用例为串尾撇号补一个右邻字符，游走形态与抛异常断言保持。

复测：全量 `:engine:jvmTest` 与 `:engine:koverXmlReportJvm` 通过
（1330 个用例，LayoutDumpGoldenTest 零 diff），acceptance-check
差集为空（残余 227 行，commonMain 225 行）。同轮 Kover 聚合覆盖率
（reportJvm.xml 根计数）：指令 90857/91284（99.53%，未执行 427）、
分支 5944/6153（96.60%，未走到 209 个方向）、行 12676/12735（99.54%，
未执行 59）、方法 743/748（99.33%）、类 300/301（99.67%）。残余
按缺失类型拆分：56 行有未执行指令（mi>0 且 mb=0）、64 行只缺分支
方向（mb>0 且 mi=0）、107 行两者都有；commonMain 225 行 = 56 + 64 +
105，jvmMain 2 行属于两者都有。

### 条目 69：2026-08-30 第二轮上游合并后的残余核对

合并 upstream/main 29c67d52（PR #19，语境破折号与省略号角色）为
aaaa6568。上游新增 ContextualDashEllipsisRoleResolver（U+2014 与
U+2026 按两侧强文本解析，ParentheticalDashPairContext 括号对）、
ClusterRoleResolution 的 ContextualDashEllipsisRunSegmentation 块、
QuotePairAnalyzer 与新文件上的 withContextualQuoteRoles 与
withContextualDashEllipsisRoles 扩展（供 ffi classifyFontRoles 等管线
外调用方组合）、FontPolicy 注释行移位。合并后首次复测残余 256 行，
其中 29 行未认领；处置：

移位追认（主张与论证不变，验收脚本的行号集合按当前源文件更新）：

- 条目 44/56 的 WidthIndependentAnnotationCache.kt 主张 467/674/826/
  876 移位为 482/689/841/891（上游在 L290 附近插入 dash-ellipsis
  解析链，净增 15 行）；L221 在插入点之前不动。
- 条目 50 的 FontPolicy.kt 主张 133/134/135/170 移位为 134/135/136/
  172（上游只改注释，isLatinCodePoint 三臂净增 1 行，L170 净增 2 行）。
- 条目 62 的 ClusterRoleResolution.kt 主张 282/311/314/317/354 移位
  为 295/324/327/330/370（上游插入 L222-233 的合并块与 L251 的析取
  项）；L139/141/205/206 在插入点之前不动。

新文件 ContextualDashEllipsisRoleResolver.kt 主张五行：

- L101（mi=0 mb=1，第一类）：resolveSingleRun else 臂内
  `leftRole != null && rightRole != null` 第二合取的假方向。进入
  else 臂的输入已排除 (X, X)、(X, null)、(null, X) 三种组合，
  leftRole 非 null 时 rightRole 必非 null，第二合取取不到假值。
- L145（mi=0 mb=1，第一类）：resolveParentheticalPairs else 臂的同构
  合取，同一排除论证。第一合取的两个方向由覆盖测试给出（括号对
  冲突与两侧都没有强文本），见下。
- L265（mi=0 mb=1，第一类）：前向扫描 `for (boundary in
  scalarStart + 1..scalarEnd)` 入口比较的跳过方向。charCount 只能是
  1 或 2，scalarStart + 1 总是小于等于 scalarEnd，区间总是非空；javap
  （StrongScriptContextIndex.<init>）偏移 84 `if_icmpgt` 的真方向
  永不取到。
- L303（mi=0 mb=1）与 L304（mi=3 mb=3，第三类）：scalarStartBefore
  的 `lastIndex > 0` 假方向（低代理位于 0）与高代理区间检查的假
  方向（低代理的前邻不是高代理）。构造这两个方向都要求文本含孤立
  代理；索引构造的前向扫描对每个标量先调
  UnicodeScriptEvidenceClassifier.classify，孤立代理在该处抛出
  IllegalArgumentException，后向扫描（scalarStartBefore 的唯一调用
  方）只能看到 BMP 标量或完好代理对。
- 上游自带的 ContextualDashEllipsisRoleResolverTest 覆盖单 run 四臂
  与括号对的 matching、冲突两方向；其余方向由本仓补充的覆盖测试执行
  到，不主张。

其余新增残余由本仓新增测试覆盖，不主张：

- ContextualDashEllipsisRoleResolverCoverageTest
  （ContextualDashEllipsisRoleResolverCoverageTest.kt）三个括号对用例：
  中文、word 两侧各接两个 U+2014 且右端没有后继文本（左臂，L133-136）、
  两个 U+2014 加 word 加两个 U+2014 加中文（右臂，L138-141）、两个
  U+2014 加 word 加两个 U+2014（else 臂，两侧都没有强文本，L148 的
  reason 串；L145 第一合取的假方向随之覆盖，L128 两个分支点未走到的
  方向补上）。
- 同测试类的 forwardPassWalkerArmsRunBeforeTheClassifierRejectsLone
  Surrogates：0x2014 加 0xD83D（串尾高代理走 L314 第二析取真侧）、
  0xD83D 加 0x2014（后继在低代理区间之下走 L316）、0xD83D 加 0xFFFD
  加 0x2014（后继在低代理区间之上走 L316 的另一比较方向），三个输入
  都在前向游走后抛出 IllegalArgumentException。
- ContextualRoleExtensionCoverageTest：直接调用两个扩展。无语境标记
  时传与不传 context 各断言一次返回对象与接收者是同一个（默认参数
  表达式 CDER L201 与 QPA L183 随之执行）；有标记时断言包装器对 run
  角色直接解析、对其余区间委托基分类器。两个扩展的函数体此前在
  engine 模块没有调用点，仅 ffi 消费；CDER L203-207 与 QPA L185-190
  由该测试覆盖。
- ContextualDashEllipsisClusterCoverageTest：latinDashRunAtParagraph
  EndStaysOneCluster（输入 End 加两个 U+2014，合并循环经
  `index < text.length` 的假方向退出）与
  styleSpanInsideLatinDashRunSplitsTheCluster（输入 A 加两个 U+2014
  加 B，并给第二个 U+2014 加 TextSpan(2,3)，spanBoundaries 落在两个
  破折号之间，合并循环经 `index !in spanBoundaries` 的假方向退出）
  覆盖 ClusterRoleResolution L228 的两个退出方向（javap
  ClusterRoleResolutionKt 偏移 777 `if_icmpge` 与 792 `ifne`）。

复测：全量 `:engine:jvmTest` 与 `:engine:koverXmlReportJvm` 通过
（1354 个用例），acceptance-check 差集为空（残余 232 行，commonMain
230 行）。同轮 Kover 聚合覆盖率（reportJvm.xml 根计数）：指令
91772/92202（99.53%，未执行 430）、分支 6080/6296（96.57%，未走到
216 个方向）、行 12881/12940（99.54%，未执行 59）、方法 770/775
（99.35%）、类 307/308（99.68%）。残余按缺失类型拆分：commonMain
230 行 = 56 行有未执行指令、68 行只缺分支方向、106 行两者都有；
jvmMain 2 行（BundledHyphenationResource）属于两者都有。
