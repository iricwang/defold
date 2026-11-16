# 05_tail：路径约束与控制骨骼

## 交付

游戏项目新增 `docs/source/spine_smart_mesh_validation/tail_path_example.skeleton` 和同名 Markdown 使用说明。仓库测试资源为 `editor/test/resources/skeletal-animation/performance-project/tail_path.skeleton`。原 `tail_ik_example.skeleton` 不变，原八段动画保留在原文件；路径版包含独立两秒循环 `tail_path_demo`。

- `tail_path_tip` 控制尾尖；7 根尾骨一起跟随路径，实际末端落在控制点。
- `tail_path_mid` 调整中部弯曲，保持根和尖端落点。
- `tail_path_root` 控制尾根；只拖中段/尾尖时根部固定。
- 加权路径 `tail_path` 使用三控制骨骼的连续混合影响，路径约束为 `tail_follow_path`。
- 使用 Spine 原生 Chain Scale / proportional spacing。尾骨统一以 `tail` 为父，满足 Chain Scale 的层级要求，控制骨骼不依赖受约束骨骼。骨骼随路径长度适度伸缩。
- 转换保留网格 UV、三角拓扑、骨骼影响权重比例，重新计算局部绑定坐标，使转换前后静止世界顶点一致。
- 路径版移除旧的 `tail_ik_target`，避免误操作无效控制器。

动画编辑使用既有骨骼关键帧机制。开启 Auto Key 后拖动记录控制骨骼 X/Y；关闭时使用打帧操作提交。Cmd+Z/Cmd+Shift+Z 撤销/重做，Cmd+S 保存，空格播放/暂停。

## 验证

- 219 tests / 3251 assertions，0 failures / 0 errors。
- 新增真实 JavaFX 拖拽验证 setup/animate × mid/tip：整链每根骨骼变换变化，尾根固定，末端准确落点，网格变化，权重不变；动画模式绑定骨骼数据不变，撤销与重做恢复完整文档。
- 另验证转换前后静止网格顶点、UV、三角索引和影响权重比例一致。
- 最新编译编辑器后台回放 54 步通过：设置模式拖动与撤销，中段/尾尖动画打帧、撤销重做、保存、播放暂停。disabled-handlers=0，stopped-timers=0，Metal/Scene3D 正常。
- 保存后的实际文件：animations 已改变，bones/slots 与输入相同。不是仅检查按钮或屏幕数值。
- 官方 `@esotericsoftware/spine-core@4.2.120`：基准示例与后台修改保存后的示例各 121 帧（60 FPS），总计 242 帧。逐帧比较所有骨骼世界矩阵、尾巴网格世界顶点、附件及绘制顺序。
- 基准：骨骼最大误差 0.000058359，网格最大误差 0.000060894。
- 编辑后：骨骼最大误差 0.000060878，网格最大误差 0.000063457。
- 两组均通过 0.0001 阈值，附件/绘制顺序差异为 0。官方运行时未修改。
- cljfmt、clj-kondo（0 errors / 0 warnings）、git diff --check 通过；游戏工程结构检查通过。

[日志、回放、逐帧数据与截图哈希](skeletal-animation-evidence/2026-11-14/tail-path-controls/)。截图保留原图，没有裁剪或重绘。

## 重现与范围

生成脚本：`editor/scripts/spine_tail_path_example.clj`，参数为原 IK skeleton 与独立输出 skeleton，需在 editor 目录使用项目 Clojure classpath 运行。脚本拒绝相同输入/输出路径。它针对当前 05_tail 示例，不是任意角色的通用重绑定工具。

官方对照使用既有 `spine_tail_sample.clj` 与 `spine_tail_oracle.mjs`。本机 Homebrew Node 动态库缺失，本次使用临时目录中的官方 Node 22.16.0 运行原版 oracle，没有修改系统 Node。

本轮只新增示例资源、生成脚本、测试和证据，没有改运行时引擎，也没有替换 Defold 应用包。后台在隔离项目中运行，不修改用户当前打开的文档。原始 IK 示例保留。截图中的隔离游戏 Play 预构建失败提示不属于本轮骨骼编辑器/Spine 求值验证，本轮不声称完成游戏运行打包或像素级渲染对照。

## 本机旧安装包兼容性检查

直接使用 `/Applications/Defold.app/Contents/Resources/packages/defold-871d419fe7ad8f81d5d988f58afa153df5ca818f.jar` 在隔离后台打开同一资源。资源加载、设置模式控制点拖动和撤销、动画关键帧拖动均可执行；旧版撤销会清除选择。尝试连续撤销/重做后再选中控制点，X 值仍为原始值，动画重做断言失败（38 步时停止）。没有放宽该断言或将本机旧版标记为通过。最新编译版相同动画撤销/重做断言通过。

旧版回放最初还遇到旧 UI 没有新版 tool-dock 节点、需要使用旧层级树选择器的问题；随后按旧布局定位，再检查真实数值。最终旧版问题是重做恢复，不是资源格式或窗口定位失败。未修改本机应用包；因此不能声称旧安装版已包含源码里的既有重做/选择修复。详见 installed-compatibility-report.json 与原始失败截图。
