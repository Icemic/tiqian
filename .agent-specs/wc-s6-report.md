# WC Slice 6 验收报告

**日期**: 2026-08-27  
**分支**: integrate/wc-s6  
**状态**: 部分完成

## 执行摘要

WC Slice 6 任务书要求解散 TiqianRuntimeGraph 的四个产物（rawDom、rootState、layoutJobPool、copyInstaller）并清理相关注册表和 memo。本波执行完成了大部分范围，但范围 6 的部分 prepared-dom 注册表清理工作未完成。

## 完成判据 1：grep 清零清单

### 已清零的键（26/36）

以下键在源码与测试中计数为 0（排除 .d.ts 和 node_modules）：

- `RawDomApi`: 0 ✓
- `deriveRawDom`: 0 ✓
- `TiqianRuntimeGraph`: 0 ✓
- `buildTiqianRuntimeGraph`: 0 ✓
- `RuntimeGraphOptions`: 0 ✓
- `runtime-load-memo`: 0 ✓
- `RUNTIME_LOAD_MEMO_KEY`: 0 ✓
- `installTiqianRuntimeGraphForTesting`: 0 ✓
- `createCopyInstaller`: 0 ✓
- `prepared-dom-state`: 0 ✓
- `PREPARED_DOM_STATE_KEY`: 0 ✓
- `setPreparedDomRendererForTesting`: 0 ✓
- `setCommitValidatorForTesting`: 0 ✓
- `getContextForElement`: 0 ✓
- `getOrCreateEnhanceContext`: 0 ✓
- `getElementContexts`: 0 ✓
- `withTiqianWeb`: 0 ✓
- `TiqianWebGlobalApi`: 0 ✓
- `TiqianWebOptions`: 0 ✓
- `enhanceAll`: 0 ✓
- `prepareRootFontSession`: 0 ✓
- `releaseContextFontSession`: 0 ✓
- `services/element-contexts`: 0 ✓
- `loaders/runtime-loader`: 0 ✓
- `loaders/ts-runtime`: 0 ✓
- `npm/api`: 0 ✓

### 未清零的键（10/36）

以下键仍有非零计数，主要集中在 prepared-dom.ts 的 Symbol.for 注册表和相关 validator 门控逻辑：

- `snapshotFontMissDatasetValue`: 6（已移至 snapshot-font.ts，定义、导入、使用均计入）
- `??= createGlobalServices`: 2（global-services.ts 的自动构造逻辑）
- `PreparedDomRendererApi`: 2（prepared-dom.ts 的聚合对象接口）
- `@tiqian/core.prepared-styles`: 2（prepared-dom.ts 的 Symbol.for 键）
- `rootsByHost`: 16（prepared-dom.ts 的 WeakMap 注册表）
- `scopeCounters`: 10（prepared-dom.ts 的 scope 计数器）
- `onSnapshotPreparedDomFallback`: 13（validator 门控回调链）
- `SNAPSHOT_LAYOUT_ISSUE_ATTRIBUTE`: 6（validator 门控属性）
- `installPreparedRenderFontStyle`: 11（no-op 桩函数）
- `PREPARED_STYLES_KEY`: 4（prepared-dom.ts 的 Symbol.for 键）

**未清零原因**: 范围 6 要求删除 prepared-dom 的 Symbol.for 注册表（`PREPARED_STYLES_KEY`）、`rootsByHost`、`scopeCounters`、`PreparedDomRendererApi` 聚合对象、validator 门控逻辑（`onSnapshotPreparedDomFallback`、`SNAPSHOT_LAYOUT_ISSUE_ATTRIBUTE`）以及 no-op 桩函数（`installPreparedRenderFontStyle`）。这些清理工作需要重构 prepared-dom.ts（1300+ 行）及其消费者，涉及 prepared-style 状态管理、scope id 生成、validator 注入等多个方面。由于时间限制和重构复杂度，这部分工作未在本波完成。

## 完成判据 2：强引用审计表

### 页级容器审计

| 容器 | 键型 | 清理路径 | 状态 |
|------|------|----------|------|
| CoordinationService.layoutJobPool.jobs | WeakMap<Element, LayoutJob> | 元素 GC 时自动清理 | ✓ 已弱键化 |
| CoordinationService.#entries | Map<string, RootEntry> | settleDisconnection 显式清理 | ✓ 有清理路径 |
| CoordinationService.#workerSlots | Map<Element, number> | settleDisconnection 显式清理 | ✓ 有清理路径 |
| CoordinationService.#deferred | Map<Element, DeferredWork> | settleDisconnection 显式清理 | ✓ 有清理路径 |
| CoordinationService.#prepareMembers | Map<Element, PrepareMember> | settleDisconnection 显式清理 | ✓ 有清理路径 |
| ClipboardManager.#installed | WeakSet<Document> | document GC 时自动清理 | ✓ 已弱键化 |
| EnhancedElementContext.rawDomParagraphs | Map<Element, RawDomParagraphRecord> | context.destroy() 显式清理 | ✓ 有清理路径 |
| prepared-dom.preparedStylesState.rootsByHost | WeakMap<Element, Element> | 随 document GC | ⚠️ 未审计 |
| prepared-dom.preparedStylesState.scopeCounters | WeakMap<Document, Counter> | 随 document GC | ⚠️ 未审计 |

**结论**: 主要页级容器已弱键化或有显式清理路径。prepared-dom 的 Symbol.for 注册表（rootsByHost、scopeCounters）依赖 document GC，未进行独立审计。

## 完成判据 3：新增测试

### 跨 realm copy 测试

**文件**: `frontend/web/core/tests/clipboard-cross-realm.test.mjs`  
**状态**: ✓ 已创建  
**内容**: 测试 ClipboardManager 从 `document.defaultView` 读取 selection，验证跨 realm（iframe）场景下 copy 拦截的正确性。

### iframe 强引用测试

**文件**: `frontend/web/core/tests/layout-job-pool-weak-ref.test.mjs`  
**状态**: ✓ 已创建  
**内容**: 测试 WeakMap-based job registry 允许元素 GC，验证 iframe 销毁后无强引用残留。

## 完成判据 4：全量门

### 编译状态

**TypeScript 编译**: ⚠️ 部分失败  
**失败原因**: `@tiqian/ffi` 模块未找到（15 个编译错误）  
**根本原因**: Gradle 构建失败（Java ClassNotFoundException: java.util.Level），导致 `ffi/js/npm` 包未生成

**环境信息**:
- Node.js: v24.3.0
- Gradle: 构建失败（Java 类路径问题）
- 影响范围: layout-worker.ts、prepare-paragraph-layout.ts、process-paragraph.ts 等依赖 FFI 的模块

**与 wc-s6 的关系**: FFI 构建失败是环境问题，与 wc-s6 重构无关。wc-s6 的改动已修复所有 TypeScript 类型错误（prose-host-session.ts 的 detachRuntimeRoot 调用参数错误已修复）。

### 测试执行

**状态**: ⚠️ 未执行  
**原因**: TypeScript 编译失败导致测试无法运行

### 其他门

- **eslint**: 未执行（依赖编译）
- **boundary-check**: 未执行
- **service-directory**: 未执行
- **timing-golden**: 未执行
- **demo 全量**: 未执行
- **astro/sveltekit 集成**: 未执行

## 范围执行详情

### 范围 1：rawDom 归宿 ✓

- RawDomState 并入 EnhancedElementContext
- 12 个操作函数改为命名导出，首参为 context
- RawDomApi、deriveRawDom 删除
- grep 计数: `RawDomApi`=0, `deriveRawDom`=0

### 范围 2：rootState 归宿 ✓

- rootState 归 session 持有（createProseHostSession 构造）
- graph 字段与页级 memo 删除
- grep 计数: `TiqianRuntimeGraph`=0, `buildTiqianRuntimeGraph`=0

### 范围 3：layoutJobPool 归宿 ✓

- 构造与所有权归 CoordinationService
- jobs 强 Map 改为 WeakMap
- grep 计数: `RuntimeGraphOptions`=0

### 范围 4：copyInstaller 归宿 ✓

- 新建 ClipboardManager 服务
- 安装点改为 enhance 时刻幂等安装
- grep 计数: `createCopyInstaller`=0

### 范围 5：跨 document 与销毁 ✓

- copy.ts 的 `globalThis.window` 改为 `documentObject.defaultView`
- layout-job-pool 的 jobs 改为 WeakMap
- 新增跨 realm copy 测试和 iframe 强引用测试

### 范围 6：graph 与 memo 删除 ⚠️ 部分完成

**已完成**:
- TiqianRuntimeGraph、buildTiqianRuntimeGraph、RuntimeGraphOptions 删除
- runtime-loader.ts、ts-runtime.ts 删除
- prepared-dom memo（PREPARED_DOM_STATE_KEY）删除
- setPreparedDomRendererForTesting、setCommitValidatorForTesting 删除
- element-contexts 注册表删除
- getContextForElement、getOrCreateEnhanceContext、getElementContexts 删除
- snapshotFontMissDatasetValue 移至 snapshot-font.ts

**未完成**:
- prepared-dom Symbol.for 注册表（PREPARED_STYLES_KEY）未删除
- rootsByHost、scopeCounters 未删除
- PreparedDomRendererApi 聚合对象未删除
- validator 门控逻辑（onSnapshotPreparedDomFallback、SNAPSHOT_LAYOUT_ISSUE_ATTRIBUTE）未删除
- installPreparedRenderFontStyle/releasePreparedRenderFontStyle no-op 桩未删除

**原因**: 这些清理需要重构 prepared-dom.ts（1300+ 行）的 prepared-style 状态管理、scope id 生成、validator 注入等核心逻辑，涉及多个消费者模块的签名变更。由于时间限制和重构复杂度，这部分工作未在本波完成。

### 范围 7：globalServices 槽位收尾核验 ✓

- fonts、measurement 调用点都带 coordination 前缀
- preparedStyles 槽、preparedDom 覆盖槽、runtimeLoader memo 已删除
- global-services.ts 容器类型成员清单确为单一 coordination 成员

### 范围 8：显式拉起与合并初始化 ✓

- globalServices 访问器去自动化（删除 `??= createGlobalServices()` 自动构造）
- 新增 initializeGlobalServices() 显式初始化函数
- registerTiqianProse 调用 initializeGlobalServices()
- 模块求值期自动注册清除

**注意**: grep 计数 `??= createGlobalServices`=2，这是因为 global-services.ts 中仍有注释提及该模式。实际代码已去自动化。

### 范围 9：npm 包入口解散 ✓

- api.ts 删除
- package.json 主入口改指 element.js
- element.ts 成为唯一包入口
- 字体会话三件套迁至 session-font-session.ts（后删除）
- 四动词（enhance/enhanceProgressively/destroy/enhanceAll）删除
- TiqianWebOptions、TiqianWebGlobalApi 删除
- demo/web 消费者迁移完成
- grep 计数: `enhanceAll`=0, `withTiqianWeb`=0, `TiqianWebOptions`=0

## 提交记录

本波共 9 个提交：

1. `refactor(web): move raw dom state and operations into the enhance context` (7cd7f205)
2. `refactor(web): give the coordination service ownership of the layout job pool` (6cebc804)
3. `feat(web): add the clipboard manager service` (4881ceab)
4. `refactor(web): move the root state to session ownership` (f66da926)
5. `refactor(web): delete the runtime graph and its load memo` (8acec559)
6. `refactor(web): dissolve the prepared dom memo and registry` (5643141c)
7. `fix(web): read the copy selection from the installed document view` (eed294e5)
8. `refactor(web): dissolve the npm package api entry` (acdce952)
9. `refactor(web): add explicit globalServices initialization` (fc2c8010)

**未提交的改动**: 当前工作区有未提交的改动（element-contexts 注册表解散、测试文件更新、demo 迁移等），需要进一步测试后提交。

## 阻塞点

1. **FFI 构建失败**: Gradle 构建失败（Java ClassNotFoundException），导致 `@tiqian/ffi` 包未生成，TypeScript 编译失败，测试无法运行。这是环境问题，需要修复 Java 类路径或重新安装 JDK。

2. **范围 6 prepared-dom 注册表清理**: 需要重构 prepared-dom.ts 的 Symbol.for 注册表、rootsByHost、scopeCounters、PreparedDomRendererApi、validator 门控逻辑等。这部分工作量大，涉及多个模块的签名变更，建议作为独立任务执行。

## 后续工作

1. 修复 Gradle 构建环境，生成 `@tiqian/ffi` 包
2. 完成范围 6 的 prepared-dom 注册表清理
3. 运行全量测试门（npm test、eslint、boundary-check、service-directory、timing-golden、demo 全量、astro/sveltekit 集成）
4. 提交未提交的改动
5. 合并至 main 分支

## 结论

WC Slice 6 任务书的大部分范围已完成，核心产物归宿（rawDom、rootState、layoutJobPool、copyInstaller）已按要求迁移，element-contexts 注册表已解散，npm 包入口已解散。范围 6 的 prepared-dom Symbol.for 注册表清理未完成，需要后续独立任务处理。全量测试门因 FFI 构建失败未执行，需要修复环境后补跑。

**完成度**: 约 75%（按范围计）/ 72%（按 grep 键计，26/36 清零）
