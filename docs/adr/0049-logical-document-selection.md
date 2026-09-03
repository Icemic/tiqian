# ADR 0049：虚拟正文选区使用逻辑文档坐标

- Status: Accepted
- Date: 2026-08-14

## Context

静态 `CjkSelectionContainer` 原先把锚点绑定到已组合的 `CjkText` 节点，并按节点几何顺序拼接
复制内容。这适合短正文，但虚拟化正文会回收屏外节点：若为了保住选区而让已选块常驻，跨屏选择
和全选会逐步退化为组合全文；若不常驻，锚点又会随节点销毁。

## Decision

虚拟正文由宿主向 `CjkSelectionContainer` 提供 `CjkSelectionDocument`。文档是有稳定 key、
阅读顺序、源忠实 `AnnotatedString`、复制投影和片段间分隔符的逻辑片段列表。选择锚点只保存
片段 key 与 UTF-16 offset；全文选择、选区文字和复制都从逻辑文档计算，不依赖屏外布局。

可见 `CjkText` 通过 `CjkSelectionScope` 把片段 key 登记到同一份 `LayoutResult` 几何。节点进入
或离开组合只增删可见几何，不能清除逻辑选区。手柄只在对应端点可见时呈现；滚动后由新登记的
节点恢复端点和高亮。

Foundation 手势检测仍安装在实际文本命中节点，避免与内层 `verticalScroll` 的手势竞争。拖选
进行期间只允许宿主暂时保留起手节点所属的一个虚拟化 owner；抬手或取消后立即释放。已完成的
选区、跨屏拖选和全选不得扩大组合范围。

没有提供逻辑文档时，容器保持原有静态正文行为，按可见节点几何排序。

## Consequences

- 全选的状态建立是常数次锚点更新，不触发全文 shaping、measure 或 composition。
- 复制成本与实际复制字符数相关；绘制成本只与当前可见片段相关。
- Markdown 等结构化宿主必须生成与真实 `CjkText` 表面一致的片段投影，不能另猜一份纯文本。
- 非 `CjkText` 的宿主 slot 仍需通过自己的片段适配能力接入可见选区几何；逻辑文档可以先保证
  全文复制不丢内容，但不能伪造未接入节点的选择框。

2026-09-03：Android View 前端的 `CjkTextSurface` 沿用同一模型，View 侧的绑定、滚动与保活契约记在
ADR 0058 第 9 条。
