# IK 子骨骼拖拽修复（2026-11-14）

## 行为

已启用 IK 的末端子骨骼，在移动或姿态工具下拖拽时，现在按鼠标位移移动该约束的目标，由现有求解器更新父子骨骼。保留抓取偏移和坐标轴限制，不直接改写子骨骼绑定位置。动画模式给目标打位置关键帧；关闭自动关键帧时保留待提交姿态。锁定目标不产生修改，mix=0 保持 FK，部分 mix 保留原值。一次拖拽可一次撤销。

未配置 IK 时，姿态工具单选骨干/末端可自动取连续父链，遇到分叉、零长度控制骨骼或锁定骨骼停止。多选时仍仅求解选中的连续链，不向其他选中肢体写关键帧。未约束骨骼的原点拖动仍按普通平移处理。

参考 [Spine Pose tool](https://esotericsoftware.com/spine-tools#Pose-tool) 和 [IK constraints](https://esotericsoftware.com/spine-ik-constraints)。单选自动取父链、拖子骨骼代理目标是针对本项目操作需求添加的便利交互，不宣称 Spine 默认就是这个规则。

## 验证

- 源码：217 tests / 3183 assertions，0 failures / 0 errors。
- AOT 编译验证包：相同 217 tests / 3183 assertions 全部通过。
- 真实 JavaFX 输入覆盖 setup/animate、move/pose、原点/骨干、父链旋转、mesh 世界顶点、锁定、mix、关闭自动关键帧和撤销。
- `05_tail` / `tail_ik_demo`：直接拖 `bone8` 原点，验证 `bone7` 变换、末端目标、加权网格顶点发生变化；骨骼绑定数据和 slot/mesh 权重保持不变，撤销恢复完整文档。
- 完整后台编辑器：60 步通用回放 + 38 步尾巴专项回放通过。专项通过层级树选中 bone8 后拖拽，确认 tail_ik_target 的 X 值发生变化。两轮 disabled-handlers=0、stopped-timers=0，使用 macOS Metal 渲染和 cljfx.plorer 输入。
- cljfmt、git diff --check 通过；clj-kondo 0 errors，27 个文件既有 warnings，本次变更行无新增警告。

[日志、场景及截图](skeletal-animation-evidence/2026-11-14/ik-child-drag/)。完整后台回放的末端拖动从空白处发起（保持选中骨骼）；直接点击骨骼原点/骨干的命中测试由 JavaFX 单元交互覆盖。首次专项回放因层级树搜索 tail 隐藏 bone8 失败，调整脚本搜索条件后通过，没有放宽功能断言。

## 范围与交付

只修改 editor 的 skeletal_animation_view.clj 和相应拖拽测试，不改运行时引擎，不改用户工程。验证包位于 `/tmp/spine-ik-child/build/editor.jar`，仅供测试，未作为增量发布包安装。当前应用 REPL 只读探测超时，未热加载。后台使用隔离工程；游戏 Play 构建提示失败不属于本轮 IK 编辑器验证范围，本次不声称完成游戏运行打包测试。
