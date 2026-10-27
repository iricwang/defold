# Spine 制作工具交互回归：patch.84–85

本轮验证的是可操作行为，不以布局相似代替功能验收。运行平台：Apple Silicon macOS。所有编辑器点击、输入、快捷键、拖拽、截图均通过运行中编辑器的 cljfx.plorer 完成；没有用生成图替代截图。

## 本轮实现

- 本地、父级、世界坐标切换：位置和旋转数值转换；世界缩放显示合成缩放，进入输入时显示并修改本地缩放。世界旋转保留本地多圈转数。覆盖五种继承模式、父级非均匀缩放和反射；退化轴拒绝逆变换。
- 画布 X/Y 操作柄：移动限轴、缩放限轴、两个倾斜通道；旋转时 Shift 吸附 15°。操作柄保持固定屏幕尺寸，缩放和倾斜使用骨骼自身轴。
- 自动关键帧：关闭时保留临时姿势，不改保存数据。切换时间/动画/模式丢弃临时姿势；同一动画切帧后可用撤销在新时间恢复。临时修改支持撤销/重做。K 或“关键帧已编辑”写入修改。
- 分组打帧：旋转、平移 X/Y、缩放 X/Y、倾斜 X/Y；绿色表示当前时间无该组关键帧，橙色表示未打帧修改，红色表示已有帧。分组及可见轨道打帧均可单次撤销。
- 工具入口：C 旋转、V 移动、X 缩放、Z 倾斜、G 权重、B 姿势、N 创建，右键切回上一工具。Space 清除选择。Cmd/Ctrl+Shift+L 为可见轨道打帧。
- 创建工具支持画布点击/拖动建立骨骼、Shift 保留父级继续创建兄弟骨骼；动画模式禁用。姿势工具支持单骨骼原点平移及朝向目标。
- 骨骼、图片、名称独立显示开关。移除已支持 slots/drawOrder 预览的旧“不预览”警告，保留事件/未支持类型的说明。

语义依据：[Spine Tools](https://en.esotericsoftware.com/spine-tools)、[Spine Keys](https://en.esotericsoftware.com/spine-keys)。原 Spine 桌面的逐项交互对照尚未完成：用户暂时无法开启桌面，因此本轮证据为 Defold 实际运行与官方文档语义对照。

## 自动化与实际交互

完整骨骼测试集：**262 tests / 4256 assertions，0 failures / 0 errors**。新增独立坐标/临时姿势测试，以及真实图事务的自动关键帧、分组打帧、单次撤销和重做测试。骨骼源码与测试 clj-kondo：0 错误、0 警告。

测试中发现并修复：未聚焦窗口的世界缩放输入未切回本地值、分组打帧事务参数错误、普通编辑命令返回值导致重命名后选择未更新。修复后重复相应实际操作。画布测试先平移骨骼到浮动工具栏外，避免误把工具栏命中当作画布拖动。

截图位于 [evidence/2026-10-23](skeletal-animation-evidence/2026-10-23)。下表均为实际运行截图：

| 验证行为 | 截图（`qa-tools84-` 前缀） |
| --- | --- |
| 三种坐标及世界位置修改 | `parent.png`、`local.png`、`world-edit.png` |
| 世界缩放输入显示本地值、撤销 | `world-scale-local-entry.png`、`setup-undo.png` |
| 关闭自动关键帧后预览、撤销、重做 | `auto-off-pose.png`、`unkeyed-undo.png`、`unkeyed-redo.png` |
| 切帧丢弃与撤销恢复 | `seek-discards.png`、`seek-undo-recovers.png` |
| K 打帧与开启自动关键帧 | `key-edited.png`、`auto-on.png` |
| 工具快捷键及右键返回 | `shortcut-rotate.png`、`shortcut-scale.png`、`shortcut-shear.png`、`shortcut-weights.png`、`shortcut-pose.png`、`right-click-previous.png` |
| 世界 X 轴拖动不改变 Y，关闭录制不改保存数据 | `world-x-drag-auto-off.png`、`world-x-drag-keyed.png` |
| 创建骨骼、撤销、重做及姿势朝向 | `create-drag.png`、`create-undo.png`、`create-redo.png`、`pose-aim.png` |
| 真实 fire 工程橙色/红色组关键帧及整组撤销 | `fire-unkeyed-orange.png`、`fire-group-key-red.png`、`fire-group-undo.png` |
| 所有可见轨道打帧及快捷键 | `fire-key-shown.png`、`fire-key-shown-shortcut.png` |
| 图片隐藏、骨骼隐藏、显示名称 | `fire-images-hidden.png`、`fire-bones-hidden.png`、`fire-bone-names.png` |
| 实际角色及工具入口总览 | `fire-tools.png` |

数值单测覆盖 X/Y 两轴和继承模式；UI 重点验证世界 X 轴拖动、单骨骼创建与朝向。安装版另已验证 Shift 15° 旋转吸附、X 轴缩放、X 轴倾斜、Shift 连续创建兄弟骨骼、零长度骨骼和动画模式创建保护，截图为 `installed-shift-rotate.png`、`installed-scale-x.png`、`installed-shear-x.png`、`installed-shift-create-siblings.png`、`installed-zero-length.png`、`installed-create-setup-only.png`。实际 IK 混合值的临时修改与手动打帧见 `ik-auto-off.png`、`ik-key-edited.png`。其余约束临时姿势及所有组合手势不能仅依据这些截图宣称均已逐项 UI 验证。

## 尚未完成的等价能力

仍不称为 Spine 全功能替代：多骨骼 Pose IK 手势、补偿开关、完整选择历史/分组、多通道曲线编辑、全部层级过滤图标、完整上下文入口和窄画布浮栏适配尚未完成。当前中键仍用于画布平移，未改成 Spine 的中键框选。键盘微调、完整数值拖拽及所有工具的组合修饰键尚未齐备。图标和菜单细节仍有差异。

本轮没有修改独立 `.spine` codec 的兼容范围：仍限已验证的 Spine 4.2.43/schema 40。完整原生格式、附件/皮肤/导出等未完成项见 [功能矩阵](skeletal-animation-parity.md)。Windows 与 Intel Mac 未实际测试。本轮也未重跑游戏引擎采样对照，不能把编辑器交互回归写成新增游戏运行时验证。

## 最终显示修正与增量交付

逐张复看截图发现，patch.84 的临时姿势虽未写入保存数据，仍在时间轴绘制蓝色关键帧。patch.85 将时间轴和曲线读取改为已保存动画，预览图仍读取临时姿势，橙色组按钮提示尚未打帧。增加未打帧曲线回归测试。数值滚轮同时按场景焦点判断，非法输入不会抛出未处理的回调异常。

本轮本地增量链为 `83 → 84 → 85`；使用本机回环 HTTP 更新源完成 check/download/prepare/restart，不是 GitHub 发布源。没有新打包完整 App、DMG 或 JDK。最终 `/Applications/Defold.app` 已是 patch.85，168 个清单文件的哈希和权限全部匹配；确认正常重启并加载项目后，用安装 JAR（没有源码覆盖）复测交互。已移除经基线核验的旧备份，`/Applications` 只保留一个 Defold App。未提交、推送或发布 GitHub 补丁。

最终安装版额外证据：

- `qa-tools84-installed85-pending-no-keys.png`：临时修改可见，但时间轴 X/Y 无蓝色关键帧。历史 patch.84 截图只作为回归过程记录，以此最终截图为准。
- `qa-tools84-installed85-pending-no-curve.png` / `qa-tools84-installed85-keyed-curve.png`：同一 X 轨道在手动打帧前为空，打帧后才有关键帧。
- `qa-tools84-installed85-relative-input.png` / `qa-tools84-installed85-fine-wheel.png` / `qa-tools84-installed85-invalid-number.png`：实际键入 `+5`、`*2`、`/4`，滚轮与 Shift 精调，非法输入滚轮不会写入或禁用控件。
- `qa-tools84-installed85-ffd-unkeyed.png` / `qa-tools84-installed85-ffd-keyed.png` / `qa-tools84-installed85-ffd-undo.png`：Bezier 网格顶点的临时拖动、手动打帧、撤销恢复；保存数据在未打帧时保持原样。
- `qa-tools84-installed85-final-playing.png`：安装版真实 fire 角色 idle 动画播放，来源确认为 `/Applications/Defold.app/.../editor/skeletal_animation_view__init.class`。

最终回归未发现编辑器错误提示。测试项目与用户原始工程隔离，所有修改都在仓库 `tmp/` 中的回归项目内。
