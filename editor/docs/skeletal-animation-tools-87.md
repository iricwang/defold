# 同图曲线与选择交互 — patch.87

## 本轮实现

- X/Y 等标量通道在同一 Canvas 共享时间与数值坐标，用不同颜色区分。各通道可独立显示/隐藏。
- 点击通道关键帧后直接拖动其 Bezier 手柄；写回包含通道身份，同一时刻的其他通道不受影响。
- 工具栏提供线性、阶梯和 Bezier 插值切换，操作可逐次撤销。
- 视口中键拖动改为始终框选；Alt+中键保留平移入口。Escape 清空，Page Up/Down 回溯/前进选择，Cmd/Ctrl+数字保存选择组，数字恢复。
- 图片补偿保留 region 类型、贴图引用和尺寸，只修改位置、旋转、缩放。若目标仿射矩阵包含 region 无法表达的剪切，则拒绝该次变换并显示原因，不会生成网格顶点、UV 或三角形。

交互参考：[Spine Graph](https://en.esotericsoftware.com/spine-graph)、[Spine Tools](https://en.esotericsoftware.com/spine-tools)。本轮不宣称所有 Spine 曲线工具均已等价：多关键帧联合平移/缩放、完整切线模式、曲线剪贴板等仍须继续完善。

## 实际操作验证

截图位于 [2026-10-24](skeletal-animation-evidence/2026-10-24)，本轮文件以前缀 `87-` 标记。

| 操作 | 验证结果 | 截图 |
| --- | --- | --- |
| 图片补偿 | 四角世界坐标保持、类型仍为 region、无 vertices；撤销恢复 | 87-region-preserved.png |
| 中键框选 | 三骨骼选中集合正确 | 87-middle-box-drag.png |
| 数字选择组 | Cmd+1 保存，Escape 清空，1 完整恢复 | 87-selection-group.png |
| 选择历史 | Page Up 返回清空状态，Page Down 恢复三骨骼 | 87-selection-history.png |
| 同图多通道 | 单 Canvas 中两条曲线共用坐标 | 87-shared-graph.png |
| 独立编辑 X/Y | X 手柄修改不影响 Y；再编辑 Y 正确写回 | 87-x-edited.png、87-y-edited.png |
| 插值切换 | Y 的阶梯、线性、Bezier 切换正确 | 87-stepped.png、87-bezier-restored.png |
| 撤销 | 五次曲线修改逐次撤销后文档等于编辑前 | 实际断言通过 |

双击清空的原生 click-count 分支已接入，但本轮 plorer 连续两次点击未产生双击计数，因此不把它列为已实际验证的手势。

## 自动化

267 项测试、4274 条断言，0 失败、0 错误；clj-kondo 0 错误、0 警告。新增共享坐标边界和 region 类型/仿射表示能力回归。

## 安装

待更新最终本机增量安装与编译版验证结果。
