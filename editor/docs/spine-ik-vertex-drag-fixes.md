# IK 和网格顶点拖动修复（2026-11-10）

仅修改编辑器视图和测试。已热更新当前 Defold 画布的事件处理器；没有重新打包或安装，磁盘应用仍为 patch.130，热更新重启后不保留。用户持续编辑期间未覆盖、启用或保存其动画数据。

## 原因与修复

1. IK 目标通常和约束链末端重合，旧命中逻辑按骨骼顺序选到了链骨。相同命中距离优先骨骼原点，再区分当前选择，保证目标可选、可拖。
2. 普通移动、旋转、缩放、倾斜工具只画出网格点，没有接入顶点拖动。现在直接拖动可见顶点会平移该点／选中点组；设置模式保持 UV，动画模式编辑 FFD，支持已有的自动／手动打关键帧和撤销。
3. 姿势工具错误地在拖骨骼原点时先求解整条选择链。现在原点拖动平移该骨骼，轴身拖动才求解选择链。

实际用户尾巴案例还存在动画配置原因：`attack` 第 0 帧 `tail_tip_ik` 的混合关键帧为 0，覆盖设置姿势中的 mix=1。拖目标不会启用关闭的约束。要跟随，应将混合设为 1 并移动 `tail_ik_target`。普通移动一个子骨骼不自动调整父骨骼；直接调整骨骼链需多选连续骨骼并使用 B 姿势工具。

规则依据：[Spine 姿势工具](https://esotericsoftware.com/spine-tools#Pose-tool)、[IK 约束](https://esotericsoftware.com/spine-ik-constraints)、[网格变换工具](https://esotericsoftware.com/spine-meshes#Transform-tools)。没有将零混合时无响应误改为自动启用 IK。

## 验证

- 源码回归：402 tests / 13,235 assertions，全部通过。
- 最终编译版回归：336 tests / 12,362 assertions，全部通过。
- 新增实际 JavaFX 鼠标事件回归：4 tests / 109 assertions；覆盖重叠目标、父骨骼跟随、姿势链和原点、四类工具、设置／动画、加权／非加权网格、UV、关键帧及撤销。
- 实际尾巴文档隔离副本：先将 attack 的 IK 混合设为 1，拖目标改变 bone7；拖第 13 个网格点形成 FFD，世界位置误差约 1.24e-14；绑定数据不变，撤销／重做均通过。
- 编辑后尾巴与官方 spine-core 4.2.120：8 段动画、617 帧通过；最大顶点误差 0.0000643198351，容差 0.0001。
- 完整编辑窗口后台流程：121 步通过，Metal Scene3D 可用，停用处理器和计时器均为 0。脚本显式重新打开输入 Enter 后可能关闭的设置浮窗，不依赖浮窗保持打开。
- cljfmt 完成；clj-kondo 0 errors，本次改动行和新增测试无提示，保留旧文件已有 warning。
- 验证 JAR 仅改变 skeletal_animation_view 条目，其他编辑器模块及运行时条目逐字节一致。实际安装应用没有更改。

[证据目录](skeletal-animation-evidence/2026-11-10/ik-vertex-drag/)。隔离编辑后的示例保留于 `/tmp/spine-drag-fix/edited-tail.skeleton`，没有写回游戏项目。
