# 骨骼工具交互回归 — patch.86

本轮新增：选中连续骨骼链的 Pose 拖拽；画布 Shift/Cmd 追加与取消选择、框选、全选、Space 清空；Bones 子骨骼补偿和 Images (Setup) 图片补偿；可分别折叠的多通道曲线、关键帧选择及 Bezier 手柄编辑。所有编辑沿用图事务与撤销重做。

## 实际操作证据

截图目录：[2026-10-24](skeletal-animation-evidence/2026-10-24)。均由运行中的 Defold 通过 cljfx.plorer 操作后截图，未使用图像生成。

| 操作 | 验证 | 截图 |
| --- | --- | --- |
| 全选三骨骼 | 选中集合等于 root/arm/hand | select-all.png |
| Pose 拖动 | 骨骼旋转变化，单次撤销恢复、重做还原 | pose-chain.png、pose-redo.png |
| 框选 | 拖框后选中三骨骼 | box-selection-drag.png、box-selection-result.png |
| Shift 追加与取消 | 集合分别为 root/arm 和 root | shift-add-selection.png、shift-remove-selection.png |
| Space 清空 | 选中集合为空 | clear-selection.png |
| 子骨骼补偿 | 父旋转后子骨骼完整世界矩阵不变 | bone-compensation.png |
| 图片补偿 | 父旋转后图片四角世界坐标不变 | image-compensation.png |
| 双通道曲线 | 拖动 X 不改变 Y；再独立编辑 Y；逐次撤销恢复 | multichannel-curves.png、curve-x-edit.png、curve-y-edit.png、curve-undo.png |

## 自动化验证

265 项测试、4265 条断言，0 失败、0 错误。骨骼源码和测试 clj-kondo：0 错误、0 警告。

回归发现并修复：曲线刷新遍历 reducible 的异常；非等比、镜像父骨骼的补偿矩阵分解；各曲线同时间关键帧写回时需要携带通道身份。

本轮用当前源码测试时，安装版 AOT 对函数的直接绑定会绕过测试 mock；加载相应源码后，在 FX 线程串行执行回归，避免活动预览计时器污染全局 mock 的计数。

## 行为说明

- Pose 作用于当前选中骨骼向上的连续链，保持各骨骼位置和长度，不创建持久 IK 约束。
- Images 仅用于 Setup。区域图片在需要仿射补偿时转成四顶点网格，以保留父骨骼非等比变换下的形状；此转换与变换在同一撤销事务中。
- 曲线以各自数值范围并列为多个纵向轨道，不将不同量纲强行共用纵轴。它不是 Spine 全部曲线工具、菜单与手势的等价声明。
- 本轮实际验证平台为 Apple Silicon macOS，未声明 Windows 已实机验证。

## 安装验证

已通过本机回环 HTTP 更新源完成 85 → 86 的检查、下载、基线校验、安装与自动重启。最终 168 个文件哈希及权限完全匹配。

安装后以 /Applications/Defold.app 的编译 JAR（无源码覆盖）复测 Pose 拖动/撤销、曲线编辑/撤销、骨骼和图片同时补偿，均通过，见 installed86-*.png。最终运行无错误提示；早期截图中的提示来自已修复的刷新异常，以安装版截图为准。

本机仅保留一个 Defold.app。没有提交、推送或发布 GitHub 补丁。
