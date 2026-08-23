# TS 移植断言对照清单

本清单逐条登记 `frontend/web/src/jsTest/kotlin/org/tiqian/web/` 下五个测试文件的
全部 `@Test` 函数，作为 ADR 0053（web prose 宿主收敛）B 组把 Kotlin/JS 运行时
重写为 TypeScript 时的行为规格。每个测试函数一条清单条目；函数内每条断言以
静态调用点计数登记在「断言数」字段，移植时同名 TS 用例必须覆盖这些断言。
对应关系：B1 产出本清单；custody 对应 B2 段落托管与回滚；progressive-job 对应
B3 渐进任务状态机；eligibility 与 responsive-measure 对应 B4 段落资格策略与
响应式度量稳定化；content-reconcile 对应 B5 内容 reconcile（现 jsTest 中没有
对应用例，见下文空节说明）；copy-fidelity 对应 B6 复制保真；renderer-output
对应 B7 lowerer 统一与 B8 浏览器后处理；markdown-lowering 对应 B9
MarkdownParagraphLowering 迁移。另有三个标签落在 B 组之外：exact-session 登记
B10（引擎策略移出 ABI）的现行判定断言组（精确会话桥接与 CJK dash 能力判定），
source-fidelity 登记源语义保真类断言（B5、B6 的交叉范围），event-channel 登记
现事件与命令式入口面的行为规格（对应 C1 通道废除前的对照基准）。

计数规则：断言数为该测试函数源码内 `kotlin.test` 断言调用与断言辅助
`assertEnginePunctuationFeatureLock` 调用的静态出现次数；循环体、局部函数与
lambda 内的调用点按一处计；用于非空判定的 `assertNotNull` 调用同样计入；
`assertEnginePunctuationFeatureLock` 单次调用内部实际执行四条断言，表中按
调用点计一。TS 用例名按 `<主题>_<行为>` 的 camelCase 格式命名。条目表的
依赖辅助列中，「exact 会话 fixture 组」指 installExactFontSessionFixture 与
clearExactFontSessionFixture 成对使用；「worker harness」指
ProgressiveRelayoutTest 与 SourceFidelityTest 各自文件私有的
attachWorker、grantWorkerSlice、runWorkerJobToCompletion 等辅助，见附录 B
附注。

## 汇总

`@Test` 计数由 `grep -c '@Test' <文件>` 核对，断言数由逐行通读清点：

| 源文件 | 函数数（grep） | 断言总数 |
|---|---|---|
| TiqianWebCopyTest.kt | 4 | 28 |
| TiqianWebEnhancerTest.kt | 30 | 198 |
| TiqianWebExactSessionTest.kt | 16 | 88 |
| TiqianWebProgressiveRelayoutTest.kt | 27 | 186 |
| TiqianWebSourceFidelityTest.kt | 27 | 217 |
| 合计 | 104 | 717 |

## custody（6 条，42 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebProgressiveRelayoutTest.kt `destroyRestoresOriginalChildrenAndHostAttributes` | 9 | destroy 恢复原始子节点并把宿主自有属性值原样交还，同时移除引擎写入的 style 属性。 | custody_destroyRestoresOriginalChildrenAndHostAttributes | mount、testOptions |
| TiqianWebProgressiveRelayoutTest.kt `destroyCancelsProgressiveWorkBeforeItTouchesNativeContent` | 4 | 渐进任务启动前调用 destroy 即取消任务，原生正文未被改动。 | custody_destroyCancelsPendingWorkBeforeTouchingContent | mount、testOptions、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `detachKeepsInvisibleRenderedDomButReconnectDestroyCanRestoreSource` | 6 | detach 保留已渲染 DOM 不重建，destroy 仍能把源节点恢复回来。 | custody_detachKeepsRenderedDomUntilDestroyRestoresSource | mount、testOptions |
| TiqianWebProgressiveRelayoutTest.kt `destroyCancelsScheduledProgressiveTailBeforeItTouchesNativeParagraphs` | 3 | destroy 取消未执行的尾部任务，全部段落保持原生内容。 | custody_destroyCancelsScheduledTailBeforeTouchingParagraphs | mount、testOptions、setElementRect、installTestAnimationFrames、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `relayoutCommitFailureRollsBackRenderedNodesAndStillCompletesTheJob` | 14 | 提交异常时渲染节点整体回滚为提交前状态，job 以 error 加 ready 收尾并允许下一次成功。 | custody_commitFailureRollsBackNodesAndCompletesJob | mount、exactTestOptions、exact 会话 fixture 组、failExactPreparedDomRender、pendingTestAnimationFrameCount、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |
| TiqianWebProgressiveRelayoutTest.kt `destroyCancelsPendingRelayoutBeforeItCanRestoreRenderedDom` | 6 | destroy 在重排在途时取消任务并把全部段落恢复为原生内容。 | custody_destroyCancelsInFlightRelayoutAndRollsBack | mount、testOptions、installTestAnimationFrames、dispatchRelayout、worker harness |

## progressive-job（11 条，100 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebProgressiveRelayoutTest.kt `mixedSnapshotProgressReportsObservableTotalThroughoutTheRuntimeTail` | 7 | 快照段与运行时段混合时进度计数全程可读，ready 事件分别报告两类数量，销毁后保留快照计数。 | progressiveJob_mixedSnapshotProgressReportsObservableTotal | mount、testOptions、eventDetailInt、installTestAnimationFrames、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `longProgressiveEnhancementCommitsParagraphsAtomicallyAcrossFrames` | 11 | 十八段长文跨帧分片提交，任一帧内每段要么保持源内容要么已是完整结果，完成后只发一次 ready。 | progressiveJob_longJobCommitsParagraphsAtomicallyAcrossFrames | mount、testOptions、installTestAnimationFrames、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `progressiveEnhancementPrioritizesViewportParagraphs` | 3 | 渐进增强先提交视口内段落，再处理远处段落直至全部完成。 | progressiveJob_viewportParagraphsCommitFirst | mount、testOptions、setElementRect、installTestAnimationFrames、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `progressiveEnhancementDoesNotWaitForHandledScrollQuietWindow` | 1 | 已处理的滚动事件不推迟可见段落的提交。 | progressiveJob_handledScrollDoesNotDelayCommits | mount、testOptions、setElementRect、installTestAnimationFrames、dispatchTestProgressiveScroll、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `progressiveEnhancementReportsStaleAcrossWidthChangeWithoutTearingCommittedParagraphs` | 8 | 宽度变化使旧 job 以 stale 状态收尾，已提交段落保留渲染结果，新 job 补齐其余段落后再发非 stale ready。 | progressiveJob_staleFinishPreservesCommittedParagraphs | mount、testOptions、installTestAnimationFrames、relayoutEventIsStale、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `relayoutDuringInitialProgressiveWorkRestartsWithoutStrandingCandidates` | 4 | 初次渐进期间发生重排时任务重启，重启后所有候选段落完成增强且 ready 只发一次。 | progressiveJob_relayoutDuringInitialWorkRestartsCleanly | mount、testOptions、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `newerRelayoutReplacesPendingWorkAndUsesTheLatestWidth` | 7 | 连续重排以后发的宽度替换在途任务，全部段落收敛到最新宽度的行签名，relayout-ready 只发一次。 | progressiveJob_newerRelayoutSupersedesPendingWork | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `relayoutSwapsParagraphDomAtomicallyWithoutAFrameDelay` | 10 | 重排在派发任务内同步换入新 DOM，任何帧都读不到旧行盒已移除而新行盒未挂载的中间状态。 | progressiveJob_relayoutSwapsDomAtomicallyWithoutFrameDelay | mount、testOptions、pendingTestAnimationFrameCount、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |
| TiqianWebProgressiveRelayoutTest.kt `longRelayoutYieldsAndCommitsEachParagraphAtomically` | 9 | 长文重排逐片让出主线程并逐段原子提交，全部完成后才发 relayout-ready。 | progressiveJob_longRelayoutYieldsBetweenAtomicCommits | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `sliceWalkingPastTierGatedParagraphKeepsJobOpenInsteadOfAbandoningIt` | 19 | 分片越过层级门槛段落时保持 job 开启，该段不被当作完成上报，更宽门槛的分片到达后才收尾并恢复宽行结果。 | progressiveJob_tierGatedParagraphKeepsJobOpen | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness（含 grantUnboundedSlice） |
| TiqianWebSourceFidelityTest.kt `widthDependentCapabilityRetryRestartsProgressivelyFromNativeSource` | 21 | clone 装饰断行能力随宽度失效后段落回到原生并报告问题，宽度恢复后从原生内容重新渐进接管。 | progressiveJob_widthDependentCapabilityRetryRestartsFromNative | mount、testOptions、installTestAnimationFrames、dispatchRelayout、worker harness |

## eligibility（9 条，51 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `enhancesLeafListItemsWithoutReplacingListContainers` | 10 | 只增强叶子列表项，列表容器不替换、不加布局属性、不改 display。 | eligibility_enhancesLeafListItemsWithoutReplacingListContainers | mount、testOptions、computedStyleValue、copySelection |
| TiqianWebEnhancerTest.kt `progressiveEnhancementDoesNotMeasureSkippedAutoSizedListContainers` | 8 | flex 与 grid 列表容器被跳过增强后其子段落照常接管，容器与子项的宿主宽度不变。 | eligibility_progressiveEnhancementSkipsAutoSizedListContainers | mount、testOptions、elementWidth、installTestAnimationFrames、flushAllTestAnimationFrames、relayoutEventIsStale |
| TiqianWebEnhancerTest.kt `directRuntimeKeepsSourceNativeWhenSharedStylesAreMissing` | 4 | 共享样式未就绪时直接增强拒绝接管，段落标注 MissingSharedRuntimeStyles 并保持原文。 | eligibility_missingSharedStylesKeepsSourceNativeWithIssue | mount、testOptions |
| TiqianWebEnhancerTest.kt `nestedRootsOwnOnlyTheirDirectParagraphScope` | 6 | 嵌套根各自只接管直属段落，enhanced-count 分别为一且互不计入。 | eligibility_nestedRootsOwnOnlyDirectParagraphScope | mount、testOptions |
| TiqianWebEnhancerTest.kt `reportsStatefulInlineObjectAndKeepsOriginalParagraph` | 3 | 段内含 button 一类状态型行内对象时整段拒绝接管，标注 UnsupportedStatefulInlineObject 且 HTML 原样。 | eligibility_statefulInlineObjectKeepsParagraphOriginal | mount、testOptions |
| TiqianWebEnhancerTest.kt `ignoresParagraphWhoseOnlyContentIsABlockImage` | 6 | 只含块级图片的段落被忽略，不报能力问题也不写 issue 计数。 | eligibility_blockImageOnlyParagraphIgnoredQuietly | mount、testOptions |
| TiqianWebEnhancerTest.kt `textMixedWithABlockImageStillFallsBackAtomically` | 5 | 文本混排块级图片时报 UnsupportedInlineFormattingContext 与 img:block 明细，段落原样保留。 | eligibility_textWithBlockImageFallsBackAtomically | mount、testOptions |
| TiqianWebProgressiveRelayoutTest.kt `keepsNativeParagraphWhenVisibleGlyphsHaveNoMeasuredAdvance` | 4 | 可见字形量出零 advance 时段落保持原生，标注 InvalidWebShapingAdvance 及 advance=0 明细。 | eligibility_zeroAdvanceGlyphsKeepParagraphNative | mount |
| TiqianWebSourceFidelityTest.kt `inlineShapingFeatureThatLayoutResultCannotModelStaysNative` | 5 | 行内 font-feature-settings 无法进入布局模型时段落保持原生，明细定位到 span:font-feature-settings。 | eligibility_unmodelableInlineFeatureStaysParagraphNative | mount、testOptions |

## responsive-measure（11 条，74 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `progressiveEnhancementPreservesWidthDerivedThroughShrinkToFitAncestor` | 6 | 由收缩包裹祖先推出的段宽登记为 host-inline-size，增强前后一致，销毁后移除。 | responsiveMeasure_preservesWidthDerivedThroughShrinkToFitAncestor | mount、testOptions、elementWidth、installTestAnimationFrames、flushAllTestAnimationFrames、relayoutEventIsStale |
| TiqianWebEnhancerTest.kt `cssMultiColumnFragmentsUseOneFragmentainerAsTheLineMeasure` | 6 | 跨 CSS 多栏片段的段落以单个栏宽作为行长度完成断行，正文完整可复制。 | responsiveMeasure_multiColumnFragmentsUseOneFragmentainerAsLineMeasure | mount、testOptions、elementWidth、elementFragmentWidths、copySelection |
| TiqianWebEnhancerTest.kt `listItemPaddingIsExcludedFromTheAvailableLineMeasure` | 4 | 列表项自身的水平 padding 不重复计入可用行长，行宽等于内容盒宽。 | responsiveMeasure_listItemPaddingExcludedFromLineMeasure | mount、testOptions、computedStyleValue、copySelection |
| TiqianWebEnhancerTest.kt `typographyRefreshRelowersCurrentHostMetrics` | 6 | refresh 按宿主新的字号、行高与字重重新降级，行高变量更新为新值。 | responsiveMeasure_typographyRefreshRelowersCurrentHostMetrics | mount、cssPx、computedStyleValue |
| TiqianWebExactSessionTest.kt `workerRequestsUseTheResponsiveLineLengthGrid` | 3 | worker 请求宽度按行长度网格量化：同一格复用请求，跨格产生新值。 | responsiveMeasure_workerRequestsUseResponsiveLineLengthGrid | mount、exactWorkerRequestMaxWidth |
| TiqianWebExactSessionTest.kt `configuredFontSizeMeasuresAndPaintsTheSameHostTypography` | 7 | 显式配置字号与行高时按配置度量与绘制，销毁后恢复宿主排版且链接元素保留。 | responsiveMeasure_configuredFontSizeMeasuresAndPaintsConsistently | mount、testOptions、cssPx、computedStyleValue |
| TiqianWebProgressiveRelayoutTest.kt `relayoutNeverCommitsPreparedMeasureOneGridCellBehindCurrentWidth` | 13 | 相差一格的宽度漂移在下一分片头部被拦截，落后一格的已备度量不提交，补排后收敛到最终宽度。 | responsiveMeasure_staleMeasureGuardSkipsOneCellDrift | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `relayoutDiscardsPreparedMeasureMoreThanOneGridCellBehindCurrentWidth` | 8 | 宽度跨越多个字格时剩余分片停止，历史度量不逐格提交，ready 记一次 stale。 | responsiveMeasure_multiCellDriftDiscardsPreparedMeasure | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `relayoutDiscardsPreparedMeasureAfterOvershootOrDirectionReversal` | 6 | 目标宽度越过或反向后，旧方向准备的度量停止提交，两种场景各记一次 stale。 | responsiveMeasure_overshootOrReversalDiscardsPreparedMeasure | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、worker harness |
| TiqianWebProgressiveRelayoutTest.kt `fractionalWidthCrossingAFontSizeGridBoundaryRelayouts` | 2 | 原始宽度差不足半像素时跨字格边界仍触发重排，行签名随之改变。 | responsiveMeasure_fractionalWidthCrossingGridBoundaryRelayouts | mount、testOptions、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |
| TiqianWebSourceFidelityTest.kt `stableCapabilityIssueStaysNativeWhileEnhancedParagraphsRelayoutNormally` | 13 | 带稳定能力问题的段落在重排中保持原生节点不变，同一次重排中的其余段落同步换入新排版且无帧延迟。 | responsiveMeasure_stableIssueParagraphUntouchedDuringRelayout | mount、pendingTestAnimationFrameCount、renderedLineSignature、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |

## content-reconcile（0 条）

现 jsTest 七个文件中没有覆盖内容 reconcile 的用例：五个测试文件均未出现
MutationObserver、characterData 或 taint 相关断言。B5 内容 reconcile 移植前需
另行补充行为规格，不能从本清单取得对照条目。

## copy-fidelity（7 条，42 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebCopyTest.kt `singleParagraphClipboardRestoresSourceAndSemanticHtml` | 12 | 整段复制时 text/plain 还原源文本，text/html 保留语义元素与宿主样式，剔除引擎属性、显示簇改写字与 paint-only 节点。 | copyFidelity_singleParagraphClipboardRestoresSourceAndSemanticHtml | 本文件私有 copiedData |
| TiqianWebCopyTest.kt `partialRangeKeepsEitherHalfOfAMandatoryBreak` | 4 | 选中硬断行标记或其 br 任一侧时，text/plain 得到换行符，text/html 得到 br 元素。 | copyFidelity_partialRangeOfMandatoryBreakCopiesNewlineOrBr | 本文件私有 copiedNodeData |
| TiqianWebCopyTest.kt `crossParagraphClipboardKeepsOnlySourceParagraphBoundaries` | 9 | 跨段复制只在源段落边界产生换行与 p 元素，软折行的 br 结构与引擎属性不进入剪贴板。 | copyFidelity_crossParagraphClipboardKeepsOnlySourceBoundaries | 本文件私有 mount、copiedData |
| TiqianWebCopyTest.kt `copyOutsideRenderedParagraphRemainsNative` | 3 | 未渲染段落上的 copy 事件不被拦截，剪贴板保持浏览器原生结果。 | copyFidelity_copyOutsideRenderedParagraphRemainsNative | 本文件私有 copiedData、copyWasIntercepted |
| TiqianWebProgressiveRelayoutTest.kt `copyHandlerDoesNotInterceptTextOutsideRenderedParagraphs` | 2 | 复制处理器不拦截渲染段落之外的普通站点文本。 | copyFidelity_copyHandlerIgnoresNonRenderedParagraphs | copySelection、copySelectionWasIntercepted |
| TiqianWebSourceFidelityTest.kt `copyKeepsHardBreakAndSourceTextButOmitsSoftWraps` | 8 | 复制文本保留硬断行为换行符，软折行的 br 标注 aria-hidden 与 copy-ignore，源硬断行不带这两个标注。 | copyFidelity_hardBreakCopiedSoftWrapsOmitted | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `copyOmitsEngineOwnedHyphenGlyphs` | 4 | 引擎自绘的连字符节点带 copy-ignore 与 aria-hidden，复制文本不含连字符。 | copyFidelity_engineHyphenGlyphsOmittedFromCopy | mount、testOptions、copySelection |

## exact-session（18 条，94 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `canonicalPreparedParagraphCanFallBackIntoRuntimeWithoutTreatingGeometryAsHostObjects` | 3 | 规范 prepared 段落回退运行时重排时不把几何节点当宿主对象处理，无能力问题且源文本可复制。 | exactSession_canonicalPreparedParagraphFallsBackIntoRuntimeCleanly | mount、testOptions、copySelection |
| TiqianWebEnhancerTest.kt `canonicalPreparedFallbackSamplesHostLineHeightBeforeRuntimeLowering` | 3 | prepared 段落回退先采样宿主行高再降级，行高变量取宿主的三十像素值。 | exactSession_canonicalFallbackSamplesHostLineHeightBeforeLowering | mount、cssPx、copySelection |
| TiqianWebExactSessionTest.kt `exactFontSessionUsesSharedBackendAndCanonicalPreparedDomBridge` | 9 | conforming 会话经共享字体后端 shaping 并由 prepared DOM 桥回放，段落带规范属性、zh-Hans 语言标记、标点特征锁，plan 含 layoutRevision 与 height 字段。 | exactSession_conformingSessionShapesViaSharedBackendAndPreparedDomBridge | exact 会话 fixture 组、mount、enginePunctuationFeatureStyle、exactTestOptions、assertEnginePunctuationFeatureLock、exactPreparedPlan |
| TiqianWebExactSessionTest.kt `exactFontSessionAlsoShapesSemanticParagraphsBeforeRuntimeDomReplay` | 7 | 富语义段落同样先经会话 shaping 再做运行时 DOM 回放，链接元素保留且文本可复制。 | exactSession_semanticParagraphShapedBeforeRuntimeDomReplay | exact 会话 fixture 组、mount、exactTestOptions、exactFontShapeCount、copySelection |
| TiqianWebExactSessionTest.kt `exactFaceEvidenceDoesNotFragmentOrdinaryDomText` | 3 | 字体回放证据不在普通 DOM 文本中制造可见几何分段，正文照常渲染。 | exactSession_faceEvidenceDoesNotFragmentOrdinaryDomText | exact 会话 fixture 组（varyFaceByText）、mount、exactTestOptions、copySelection |
| TiqianWebExactSessionTest.kt `semanticParagraphFallsBackPerUnsupportedFontRunWithoutAbandoningExactLayout` | 7 | 不支持的字体 run 单独回退浏览器度量，段落整体保持精确排版路径且无能力问题。 | exactSession_unsupportedFontRunFallsBackPerRunNotPerParagraph | exact 会话 fixture 组（failFamily）、mount、exactTestOptions、exactFontShapeCount、exactFontFallbackCount、copySelection |
| TiqianWebExactSessionTest.kt `exactWorkerFontReplayMissFallsBackOnlyForRichBrowserRun` | 6 | worker 回放缺键时只有富文本 run 回退浏览器管线，段落照常提交且无能力问题。 | exactSession_workerReplayMissFallsBackOnlyForRichRun | exact 会话 fixture 组（failFamily）、installPreparedWorkerIssue、mount、exactTestOptions、exactFontFallbackCount、copySelection |
| TiqianWebExactSessionTest.kt `exactWorkerUnsupportedLiveSemanticReplaysWorkerPlanFromSourceElement` | 7 | worker 不支持的活语义结构由 worker plan 从源元素回放克隆，主线程零重新 shaping，prepared 渲染次数为一。 | exactSession_workerPlanReplaysLiveSemanticsFromSourceElements | exact 会话 fixture 组、installPreparedWorkerLivePlan、mount、exactTestOptions、exactFontShapeCount、exactPreparedRenderCount、copySelection |
| TiqianWebExactSessionTest.kt `unkeyedRuntimeCompletionKeepsExactDashWhenAnotherRunNeedsBrowserFallback` | 7 | 无键运行时补齐的段落与其余 run 分别走 shaping 与浏览器回退，破折号保持源文本且无能力问题。 | exactSession_unkeyedCompletionKeepsExactDashForOtherRuns | exact 会话 fixture 组（failText）、mount、exactTestOptions、exactFontShapeCount、exactFontFallbackCount、copySelection |
| TiqianWebExactSessionTest.kt `unsupportedGlyphFallbackKeepsExactParagraphLineMetrics` | 4 | 含不支持字形的回退段与精确段共用同一行高与基线偏移变量。 | exactSession_fallbackParagraphKeepsExactLineMetrics | exact 会话 fixture 组（failText）、mount、exactTestOptions、exactFontFallbackCount |
| TiqianWebExactSessionTest.kt `exactBrowserFallbackCarriesLatinQuoteFeaturesIntoPreparedDomPlan` | 2 | 浏览器回退把拉丁弯引号 run 的 pwid 与 palt 特征写入 prepared plan。 | exactSession_browserFallbackCarriesLatinQuoteFeaturesIntoPlan | exact 会话 fixture 组、mount、exactTestOptions、exactPreparedPlan |
| TiqianWebExactSessionTest.kt `browserFontFallbackMeasuresAndReplaysLatinCurlyQuoteFeatures` | 5 | 浏览器管线量出三个拉丁弯引号特征 run 并以 palt 特征锁回放，引号码点共五个且正文可复制。 | exactSession_browserFallbackMeasuresAndReplaysLatinCurlyQuoteFeatures | mount、testOptions、assertEnginePunctuationFeatureLock、copySelection |
| TiqianWebExactSessionTest.kt `browserQuoteContextMatrixReplaysOnlyLatinQuoteFeatures` | 3 | 八种引号语境矩阵下只有拉丁弯引号获得比例特征，中文语境引号不受影响，窄宽度重排后结论不变。 | exactSession_browserQuoteContextMatrixReplaysOnlyLatinQuoteFeatures | mount、testOptions、isCurlyQuoteForWebTest、copySelection、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |
| TiqianWebExactSessionTest.kt `unavailableExactFaceFallsBackToTheBrowserPipeline` | 5 | 字体 shaping 失败时整段回退浏览器管线，保留规范源标记且无精确回放标记。 | exactSession_unavailableFaceFallsBackToBrowserPipeline | exact 会话 fixture 组（failShaping）、mount、exactTestOptions |
| TiqianWebExactSessionTest.kt `exactPreparedDomGeometryMismatchDisablesRepeatedExactAttemptsForTheRoot` | 9 | prepared DOM 几何校验失败后该 root 停用精确尝试，两个段落都走运行时管线并在 root 上记录原因。 | exactSession_preparedDomGeometryMismatchDisablesExactForRoot | exact 会话 fixture 组、failExactPreparedDomValidation、mount、exactTestOptions、exactPreparedRenderCount |
| TiqianWebExactSessionTest.kt `layoutOptionOverrideCannotReuseTheSnapshotExactSession` | 4 | 覆盖版式选项后不复用快照精确会话，段落转入运行时管线。 | exactSession_layoutOptionOverrideCannotReuseSnapshotSession | exact 会话 fixture 组、mount、exactTestOptions |
| TiqianWebSourceFidelityTest.kt `keepsDashParagraphNativeWithoutAVerifiableFontSource` | 6 | 无合格破折号字形证据时段落保持原生，标注 NoConformingCjkDashGlyph 且正文原样可复制。 | exactSession_dashParagraphNativeWithoutVerifiableFontSource | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `conformingDashEvidenceWithoutAnExactSessionReportsTheActualMissingCapability` | 4 | 有破折号证据而无精确会话时报 ConformingCjkDashRequiresExactFontSession，明细含 status=conforming。 | exactSession_conformingDashEvidenceWithoutExactSessionReportsMissingCapability | mount、testOptions |

## source-fidelity（9 条，57 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `variationSelectorStaysWithItsVisibleBaseDuringWebShaping` | 3 | 变体选择符与其可见基底一同参与 shaping，无能力问题且复制文本完整。 | sourceFidelity_variationSelectorStaysWithItsVisibleBase | mount、testOptions、copySelection |
| TiqianWebProgressiveRelayoutTest.kt `westernShapingBoundariesRemainInNativeInlineSelectionFlow` | 4 | 西文 shaping 边界 span 保持 display:inline，选区跨边界连续且文本完整。 | sourceFidelity_shapingBoundariesStayInNativeSelectionFlow | mount、testOptions、computedStyleValue、copySelection |
| TiqianWebProgressiveRelayoutTest.kt `combiningMarksAreShapedWithTheirBasesInsteadOfRejectingTheParagraph` | 4 | 组合附加符与基底一同 shaping，段落正常增强且复制文本完整。 | sourceFidelity_combiningMarksShapedWithTheirBases | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `unverifiedCanvasEllipsisKeepsSourceDisplayAndCopyText` | 4 | 画布无法核验的省略号保持源码点显示与复制，不改写为数学省略号码点。 | sourceFidelity_unverifiedEllipsisKeepsSourceCodepoint | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `collapsesHostFormattingWhitespaceAndKeepsReflowDeterministic` | 12 | 宿主格式空白折叠投影与浏览器 innerText 一致，硬断行恰一个、无空行盒，双向重排回到原签名且复制稳定。 | sourceFidelity_whitespaceCollapseProjectionMatchesInnerText | mount、testOptions、nativeInnerText、emptyRenderedLineCount、renderedLineSignature、copySelection、installTestAnimationFrames、dispatchRelayout、pendingTestAnimationFrameCount、flushAllTestAnimationFrames |
| TiqianWebSourceFidelityTest.kt `normalizesPreservedCrLfToOneSegmentBreak` | 4 | pre-wrap 下的 CRLF 序列按单一段内断行处理，不产生空行盒。 | sourceFidelity_preservedCrLfNormalizesToOneBreak | mount、testOptions、emptyRenderedLineCount、copySelection |
| TiqianWebSourceFidelityTest.kt `zeroWidthSpaceSoftBreakEnhancesAndCopiesSourceFaithfully` | 5 | 零宽空格参与断行且复制文本逐码点保留。 | sourceFidelity_zeroWidthSpaceCopiesFaithfully | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `keepsHostFontFamiliesAsTheMeasureAndPaintSource` | 13 | 段落、链接与 code 各自的宿主字体族同时决定度量与绘制，字号字重逐层保留，行高取宿主值。 | sourceFidelity_hostFontFamiliesDriveMeasureAndPaint | mount、computedStyleValue、cssPx |
| TiqianWebSourceFidelityTest.kt `preservesHostInlineRenderStylesOnSemanticTags` | 8 | strong 上的颜色、文字装饰线型、装饰色、装饰厚度与下划线偏移逐项保留。 | sourceFidelity_hostInlineRenderStylesPreservedOnStrong | mount、testOptions |

## renderer-output（20 条，173 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `plainRuntimeFlowUsesTextNodesUntilGeometryActuallyNeedsASpan` | 12 | 纯文本段落渲染后正文保持文本节点，只追加选区末尾哨兵 span，生成节点不携带 all 与 text-spacing-trim 类内联样式。 | rendererOutput_plainFlowUsesTextNodesUntilGeometryNeedsSpan | mount、testOptions、directTextContent、copySelection |
| TiqianWebEnhancerTest.kt `longInlineCodeTokenUsesRuntimeEmergencyBreaksWithoutOverflow` | 12 | 超长行内 code 用紧急断行拆行，行盒以 slice 方式延续，无连字符节点、无横向溢出且正文可复制。 | rendererOutput_longInlineCodeTokenUsesEmergencyBreaksWithoutOverflow | mount、testOptions、computedStyleValue、cssPx、copySelection |
| TiqianWebEnhancerTest.kt `longLinkTokenUsesTheSameCleanEmergencyBreakPolicy` | 7 | 长链接 token 采用与 code 相同的无连字符紧急断行策略，不溢出且正文可复制。 | rendererOutput_longLinkTokenSharesCleanEmergencyBreakPolicy | mount、testOptions、copySelection |
| TiqianWebEnhancerTest.kt `orderedListKeepsNativeMarkersOnATwoIcBodyIndent` | 19 | 有序列表保留原生编号标记列，列表项续行与正文共用同一两字宽版心网格，窄宽度重排后结论不变，销毁后属性清空。 | rendererOutput_orderedListKeepsNativeMarkersOnTwoIcBodyIndent | mount、testOptions、computedStyleValue、renderedLineSignature、copySelection、installTestAnimationFrames、dispatchRelayout、flushAllTestAnimationFrames |
| TiqianWebEnhancerTest.kt `unorderedListUsesTwoIcNativeMarkerColumnAndNoParagraphIndent` | 7 | 无序列表沿用原生标记列，列表项行不加首缩进，显式两字缩进只作用于普通段落。 | rendererOutput_unorderedListUsesNativeMarkerColumnWithoutParagraphIndent | mount、testOptions、computedStyleValue |
| TiqianWebEnhancerTest.kt `leavesStrongAsBoldByDefault` | 6 | 默认配置下 strong 保持加粗，不加着重号标记也不绘制圆点。 | rendererOutput_strongStaysBoldByDefault | mount、testOptions、computedStyleValue、copySelection |
| TiqianWebEnhancerTest.kt `explicitlyRendersOnlyCjkContentInStrongAsEmphasisMarks` | 15 | 开启着重点后只有 CJK run 加标记并绘制同色圆点，拉丁 run 保持 700 字重，覆盖层以变量定位。 | rendererOutput_onlyCjkContentInStrongGetsEmphasisMarks | mount、testOptions、computedStyleValue、copySelection |
| TiqianWebEnhancerTest.kt `exposesExplicitEmphasisDotGap` | 2 | emphasisDotGapEm 配置按 em 值平移着重点圆心纵坐标，零点一 em 与零点二五 em 差值符合配置。 | rendererOutput_emphasisDotGapShiftsDotCenterByConfiguredEm | mount、testOptions、cssPx |
| TiqianWebProgressiveRelayoutTest.kt `negativeGapAfterMultiCharacterRunUsesOverlapInsteadOfBeingDropped` | 1 | 多字符 run 之后的负间隙解析为 Overlap 间距载体。 | rendererOutput_negativeGapResolvesToOverlapCarrier | mount、testOptions、cssPx |
| TiqianWebProgressiveRelayoutTest.kt `positiveGapAfterMultiCharacterRunUsesSelectableCarrierWithoutBreakingShaping` | 17 | 正间隙由零高不换行空格载体承载，载体带 copy-ignore 与 aria-hidden，保持在原生 Range 选区内且复制文本不受影响。 | rendererOutput_positiveGapUsesSelectableZeroHeightCarrier | mount、testOptions、elementWidth、computedStyleValue、cssPx、selectionCoversElement、copySelection |
| TiqianWebProgressiveRelayoutTest.kt `plainBodyTextUsesSparseRunsRatherThanOneNodePerCluster` | 4 | 普通长正文以稀疏节点渲染，元素数远小于字符数，多行输出且保留规范源标记。 | rendererOutput_plainBodyTextRendersSparseRuns | mount、testOptions |
| TiqianWebSourceFidelityTest.kt `expandsCjkContextCurlyQuotesButKeepsLatinPairsProportional` | 7 | CJK 语境弯引号展开为全宽盒子，同一源码点在拉丁语对中保持比例宽度，两侧复制文本不变。 | rendererSourceFidelity_expandsCjkContextCurlyQuotesButKeepsLatinPairsProportional | mount、elementWidth、copySelection |
| TiqianWebSourceFidelityTest.kt `preservesOneNativeLinkAcrossEngineOwnedLines` | 14 | 一个源链接跨多条引擎行仍是单个 DOM 链接，target、rel、title、颜色、装饰线型与过渡样式逐项保留。 | rendererSourceFidelity_preservesOneNativeLinkAcrossEngineOwnedLines | mount、testOptions |
| TiqianWebSourceFidelityTest.kt `keepsOneLinkAcrossConsecutiveEmptyHardBreakLines` | 5 | 连续空硬断行行内的链接保持单个元素，产出两个断行标记，复制得到两个换行符。 | rendererSourceFidelity_keepsOneLinkAcrossConsecutiveEmptyHardBreakLines | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `keepsSemanticLinkContinuousAcrossGeometryFragments` | 4 | 链接跨几何分段保持单一语义包装，分段位于链接元素内部。 | rendererSourceFidelity_keepsSemanticLinkContinuousAcrossGeometryFragments | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `keepsInlineBoxAsOneNativeElementAcrossEngineLines` | 7 | 跨行 inline box 保持单一生效元素，左右 padding 保留且无开合拆分标记。 | rendererSourceFidelity_keepsInlineBoxAsOneNativeElementAcrossEngineLines | mount、testOptions、computedStyleValue |
| TiqianWebSourceFidelityTest.kt `engineGeometrySpansAreNeutralToHostSpanRules` | 6 | 宿主针对 span 的盒模型规则不作用于引擎几何 span，后者保持 inline、零 padding 与十八像素字号。 | rendererSourceFidelity_engineGeometrySpansAreNeutralToHostSpanRules | mount、testOptions、computedStyleValue、cssPx |
| TiqianWebSourceFidelityTest.kt `engineAnnotationsAreNeutralToHostSpanAndSvgRules` | 5 | 宿主 svg 规则不影响引擎注记层，注记 svg 保持 block 显示且圆点填充继承宿主文字颜色。 | rendererSourceFidelity_engineAnnotationsAreNeutralToHostSpanAndSvgRules | mount、testOptions、computedStyleValueElement |
| TiqianWebSourceFidelityTest.kt `emitsFinalAndLatinAdjacentPunctuationSpacingWithoutClippingInk` | 14 | 行尾挤压与西文邻接标点间距生效，行盒只携带变量与数据属性，不写裁切墨迹的内联几何样式。 | rendererSourceFidelity_emitsFinalAndLatinAdjacentPunctuationSpacingWithoutClippingInk | mount、testOptions、computedStyleValue、cssPx、lastTextLeaf |
| TiqianWebSourceFidelityTest.kt `browserPunctuationTrimDoesNotDoubleCompressClosingCommaOpeningSequence` | 9 | 闭引号加顿号序列整体只压缩半个 em，浏览器 text-spacing-trim 开启时不二次压缩，特征锁与复制文本均正确。 | rendererSourceFidelity_browserPunctuationTrimDoesNotDoubleCompressClosingCommaOpeningSequence | mount、enginePunctuationFeatureStyle、testOptions、geometryLeafWithText、assertEnginePunctuationFeatureLock、textNodeCharacterWidths、computedStyleValue、elementWidth、copySelection |

## markdown-lowering（10 条，76 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `enhancesSupportedMarkdownInlineParagraphInPlace` | 11 | 含 strong、em、code、链接与 br 的段落原地增强，无包裹容器，语义元素保留且复制文本还原。 | markdownLowering_enhancesSupportedMarkdownInlineParagraphInPlace | mount、testOptions、copySelection |
| TiqianWebEnhancerTest.kt `enhancesInlineCodeParagraphWithBrowserResolvedMonospaceFont` | 5 | 行内 code 以浏览器解析出的等宽字体族参与度量并正常增强。 | markdownLowering_enhancesInlineCodeParagraphWithBrowserResolvedMonospaceFont | mount、copySelection |
| TiqianWebEnhancerTest.kt `lowersMeasurableUnknownInlineElementsAsOpaqueObjects` | 10 | 可测量的未知行内元素与图片、内联 svg 降级为 inline object，从复制文本消失且无占位码点残留。 | markdownLowering_measurableUnknownInlineElementsBecomeOpaqueObjects | mount、testOptions、copySelection、cssPx |
| TiqianWebEnhancerTest.kt `enhancesParagraphWhoseOnlyContentIsAnInlineObject` | 4 | 只含一个行内对象的段落正常增强并保留该对象。 | markdownLowering_paragraphOfOnlyInlineObjectEnhances | mount、testOptions |
| TiqianWebEnhancerTest.kt `lowersTextualInlineElementsByFormattingContextInsteadOfTagWhitelist` | 7 | 文本类行内元素按格式化上下文判定降级资格，未知标签同样登记为语义元素。 | markdownLowering_loweringDecidesByTextualFormattingContext | mount、testOptions |
| TiqianWebSourceFidelityTest.kt `generatedInlineContentOnSemanticElementsUsesMeasuredBoxGeometry` | 12 | 语义元素上的伪元素生成内容按测量盒宽计入行长，生成内容的段落行长大于普通段落且复制文本不含括号。 | markdownLowering_generatedContentOnSemanticsUsesMeasuredBox | mount、testOptions、computedPseudoContent、copySelection |
| TiqianWebSourceFidelityTest.kt `generatedContentDirectlyOnParagraphStaysNativeWithoutASourceRange` | 5 | 段落自身伪元素携带流内生成内容时整段保持原生，明细定位到 p::before 及内容文本。 | markdownLowering_rootGeneratedContentKeepsParagraphNative | mount、testOptions |
| TiqianWebSourceFidelityTest.kt `measuresHostInlineBoxEdgesIntoLayout` | 5 | 行内 code 的左右 padding 保留为四像素并计入排版度量，段落无能力问题地完成增强。 | markdownLowering_hostInlineBoxEdgesMeasuredIntoLayout | mount、testOptions、computedStyleValue |
| TiqianWebSourceFidelityTest.kt `semanticSuperscriptAndSubscriptAreEnhancedInsteadOfStayingNative` | 6 | sup 与 sub 参与中文段落增强，无能力问题且复制文本还原。 | markdownLowering_superscriptAndSubscriptParticipateInEnhancement | mount、testOptions、copySelection |
| TiqianWebSourceFidelityTest.kt `enhancesSuperscriptGeneratedContentAndPreservesUniqueId` | 11 | 上标脚注的相对定位、唯一 id 与链接在增强后保留，单行输出的声明行长与实测行宽一致。 | markdownLowering_superscriptGeneratedContentKeepsUniqueId | mount、testOptions、computedStyleValue、copySelection、renderedSingleLineFlowWidth |

## event-channel（3 条，8 断言）

| 源文件与测试函数名 | 断言数 | 行为摘要 | TS 用例名 | 依赖辅助 |
|---|---|---|---|---|
| TiqianWebEnhancerTest.kt `jsOptionsCanExplicitlyMapStrongToEmphasisMarks` | 2 | 经 tiqian:enhance 事件 detail 传入 strongAsEmphasisMarks 后，strong 加着重号标记并绘制两个圆点。 | eventChannel_jsOptionsMapStrongToEmphasisMarks | mount、dispatchEnhanceWithStrongAsEmphasisMarks |
| TiqianWebEnhancerTest.kt `enhanceEventWithoutOptionsUsesComputedParagraphMetrics` | 3 | 无 options 的 tiqian:enhance 事件继承宿主计算字号与行高，行高变量为三十二像素。 | eventChannel_enhanceEventWithoutOptionsUsesComputedMetrics | mount、dispatchEnhanceWithoutOptions、cssPx |
| TiqianWebEnhancerTest.kt `enhanceAllFindsCustomElementRoots` | 3 | enhanceAll 能发现 tiqian-prose 自定义元素根，增强计数与行盒输出正确。 | eventChannel_enhanceAllFindsCustomElementRoots | mount、testOptions |

## 附录 A：support 辅助（TiqianWebEnhancerTestSupport.kt）

「使用方」列出直接调用该辅助的测试文件；「无」表示当前五个测试文件没有调用。

| 辅助名 | 形态 | 作用摘记 | 使用方 |
|---|---|---|---|
| installTestAnimationFrames | JsFun | 把 rAF、idle callback 与 setTimeout 替换为手动队列。 | 四个测试文件 |
| flushOneTestAnimationFrame | JsFun | 执行一帧队列回调。 | 无 |
| flushAllTestAnimationFrames | JsFun | 循环执行帧队列直至队列清空。 | 四个测试文件 |
| pendingTestAnimationFrameCount | JsFun | 读当前排队帧数。 | ProgressiveRelayout、SourceFidelity |
| cancelledTestAnimationFrameCount | JsFun | 读累计取消帧数。 | 无 |
| scheduledTestIdleCallbackCount | JsFun | 读累计 idle 调度数。 | 无 |
| setTestIdleCallbackBudget | JsFun | 设置 idle 回调的 timeRemaining 返回值。 | 无 |
| dispatchTestProgressiveScroll | JsFun | 向 window 派发 scroll 事件。 | ProgressiveRelayout |
| installTestConsoleWarnCapture | JsFun（private） | 接管 console.warn 收集消息。 | 无 |
| capturedTestConsoleWarnings | JsFun（private） | 取收集到的 warn 消息。 | 无 |
| restoreTestConsoleWarnCapture | JsFun（private） | 还原 console.warn。 | 无 |
| setElementRect | JsFun | 改写 getBoundingClientRect 提供固定 top 与 width。 | ProgressiveRelayout |
| relayoutEventIsStale | JsFun | 读事件 detail.stale。 | Enhancer、ProgressiveRelayout |
| eventDetailInt | JsFun | 从事件 detail 读数值字段。 | ProgressiveRelayout |
| restoreTestAnimationFrames | JsFun | 还原被替换的计时函数。 | 四个测试文件的 cleanup |
| installExactFontSessionFixture | Kotlin 包装 | 安装共享字体后端、prepared DOM 桥与校验器替身。 | ExactSession、ProgressiveRelayout |
| installExactFontSessionFixtureBridge | JsFun（private） | 上项的 JS 实现。 | support 内部 |
| exactFontShapeCount | JsFun | 读会话 shaping 计数。 | ExactSession |
| exactFontFallbackCount | JsFun | 读字体回退计数。 | ExactSession |
| failExactPreparedDomValidation | JsFun | 令 prepared DOM 校验器返回指定 issue。 | ExactSession |
| failExactPreparedDomRender | JsFun | 令 prepared DOM 渲染抛指定错误。 | ProgressiveRelayout |
| exactPreparedPlan | JsFun | 读最近一次 prepared plan JSON。 | ExactSession |
| exactPreparedRenderCount | JsFun | 读 prepared DOM 渲染次数。 | ExactSession |
| exactWorkerRequestMaxWidth | JsFun | 派发 tiqian:worker-layout-request 并解析响应 maxWidthPx。 | ExactSession |
| installPreparedWorkerIssue | JsFun | 安装返回指定 issue 的 worker 替身。 | ExactSession |
| installPreparedWorkerLivePlan | JsFun | 安装回放活语义 plan 的 worker 替身。 | ExactSession |
| clearExactFontSessionFixture | JsFun | 删除全部会话相关 globalThis 替身。 | ExactSession、ProgressiveRelayout |
| dispatchEnhanceWithoutOptions | JsFun | 派发无 options 的 tiqian:enhance。 | Enhancer |
| dispatchEnhanceWithStrongAsEmphasisMarks | JsFun | 派发带 strongAsEmphasisMarks 的 tiqian:enhance。 | Enhancer |
| dispatchRelayout | JsFun | 派发 tiqian:relayout。 | 四个测试文件 |
| dispatchDomEvent | JsFun（private） | 向元素派发 Event。 | 无 |
| copySelection | JsFun | 全选元素内容并模拟 copy，读 text/plain。 | 四个测试文件 |
| copySelectionWasIntercepted | JsFun | 模拟 copy 并读 defaultPrevented。 | ProgressiveRelayout |
| nativeInnerText | JsFun | 读 element.innerText。 | SourceFidelity |
| emptyRenderedLineCount | JsFun | 统计空行盒数量。 | SourceFidelity |
| renderedLineSignature | JsFun | 拼接各行的 range、width、end 数据属性作为行签名。 | Enhancer、ProgressiveRelayout、SourceFidelity |
| directTextContent | JsFun | 拼接段落的直接文本子节点。 | Enhancer |
| lastTextLeaf | JsFun | 取最后一个有文本的几何叶子。 | SourceFidelity |
| geometryLeafWithText | JsFun | 按文本查找几何叶子。 | SourceFidelity |
| renderedSingleLineFlowWidth | JsFun | 按文本矩形实测单行流宽。 | SourceFidelity |
| textNodeCharacterWidths | JsFun | 逐码点实测首文本节点宽度并拼接。 | SourceFidelity |
| computedStyleValue | JsFun | 读 getComputedStyle 属性值。 | 四个测试文件 |
| computedPseudoContent | JsFun | 读伪元素 content 并去引号。 | SourceFidelity |
| computedStyleValueElement | JsFun | 同 computedStyleValue，接受 Element。 | SourceFidelity |
| selectionCoversElement | JsFun | 判定选区矩形是否覆盖目标元素。 | ProgressiveRelayout |
| elementWidth | JsFun | 读 getBoundingClientRect().width。 | Enhancer、ProgressiveRelayout、SourceFidelity |
| elementFragmentWidths | JsFun | 读多栏片段宽度数组。 | Enhancer |
| Char.isCurlyQuoteForWebTest | Kotlin 扩展 | 判定四个弯引号码点。 | ExactSession |
| assertEnginePunctuationFeatureLock | Kotlin 函数 | 断言 halt 为 0、chws 为 0、palt 按比例引号开关取值。 | ExactSession、SourceFidelity |
| cssPx | Kotlin 函数 | 去除 px 后缀转 Float，失败得 0。 | 四个测试文件 |
| testGrantController | JsFun | 构造 GrantController 测试替身。 | ProgressiveRelayout、SourceFidelity（经各自 worker harness） |

## 附录 B：fixtures（TiqianWebEnhancerTestFixtures.kt）

| 辅助名 | 形态 | 作用摘记 | 使用方 |
|---|---|---|---|
| mounted | internal val | 已挂载根元素列表，供 cleanup 销毁。 | 五个测试文件 |
| mount(html, sharedStylesReady) | internal fun | 解析 HTML 片段、置共享样式就绪变量并挂载到 body。 | 四个测试文件（CopyTest 使用自带私有 mount） |
| testOptions() | internal fun | 默认十八像素字号、三十像素行高的 EnhanceOptions。 | 四个测试文件 |
| exactTestOptions() | internal fun | 快照键选择器加 conforming 精确会话的 EnhanceOptions。 | ExactSession、ProgressiveRelayout |
| enginePunctuationFeatureStyle | internal val | 关闭 halt/chws、开启 palt 的注入样式表文本。 | ExactSession、SourceFidelity |

附注：各测试文件还带有文件私有辅助，不属于上述两个文件。TiqianWebCopyTest.kt
定义私有 mount 与 JsFun 辅助 copiedData、copiedNodeData、copyWasIntercepted、
clearSelection。TiqianWebProgressiveRelayoutTest.kt 与 TiqianWebSourceFidelityTest.kt
各自定义私有 worker harness（attachWorker、grantWorkerSlice、
runWorkerJobToCompletion，ProgressiveRelayout 另有 grantUnboundedSlice），底层
调用 support 的 testGrantController 与 TiqianWeb 的 workerAttach、workerRunSlice、
workerHasJob、workerSetParagraphTier、workerPendingInTier、workerDetach 接口；
ProgressiveRelayoutTest 另有局部断言辅助 assertStaleAt。

## 附录 C：node 宿主校准

断言移植依赖 frontend/web/npm/runtime-host.mjs 内置 canvas 与级联替身的三项
校准，记录于此供后续维护对照：

- 假 canvas 的标点墨迹窗口按 Noto Sans CJK SC 实测值建模：全宽开引号类
  （〔【《〈「『｛ 的全宽形态）墨迹位于右半 [0.66, 0.95]em，闭引号与点号类
  墨迹位于左半 [0.05, 0.35]em，半角形 ｢｣｡､ 步进 0.5em，弯引号步进 0.45em
  （引擎经 UnderwidthPunctuationFullWidthBoxPlacement 展开为全宽盒）。窗口
  方向决定 compressionGeometry 选取的 body frame 侧与 glue 所属侧；
  rendererSourceFidelity_browserPunctuationTrimDoesNotDoubleCompressClosingCommaOpeningSequence
  的合并 span 断言依赖此校准。
- getComputedStyle 替身处理 all: unset：规则内显式声明优先于同规则的 all，
  可继承属性沿父链取值，其余回到空串；var(--custom[, fallback]) 按元素的
  自定义属性链代入。rendererSourceFidelity_engineGeometrySpansAreNeutralToHostSpanRules
  与 rendererSourceFidelity_engineAnnotationsAreNeutralToHostSpanAndSvgRules
  依赖此级联语义。
- 多字符 run 的负尾间隙在 DOM 侧序列化为 margin-right（Overlap 编码）；
  rendererOutput_negativeGapResolvesToOverlapCarrier 以 C++ 叶片的
  -9px margin-right 为观测点。

## 总数核对

条目 104 = 4 + 30 + 16 + 27 + 27；断言 717 = 28 + 198 + 88 + 186 + 217；
主题分布：custody 6、progressive-job 11、eligibility 9、responsive-measure 11、
content-reconcile 0、copy-fidelity 7、exact-session 18、source-fidelity 9、
renderer-output 20、markdown-lowering 10、event-channel 3。
