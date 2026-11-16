# 高级创作与界面

以下能力存在于编辑器，但**尚未封装成此技能的结构化 edit 操作**。优先通过骨骼编辑器 UI（cljfx.plorer）执行；需要定制批处理时，检查当前 checkout 的相应公开函数签名，沿用文档校验与图事务。不要编造同名 MCP 工具。

## UI 入口

目标页根节点 `#skeletal-workspace`，上下文存放在 `:editor.skeletal-animation-view/context`。通过 `cljfx.plorer` 查询已打开窗口和节点；不要依赖某个会话内的 `rig-context` 等 REPL 变量。动态按钮先观察存在/visible/disabled，再操作。

| 功能 | 常用控件 ID |
|---|---|
| 模式 / 动画 | #skeletal-mode / #skeletal-animation |
| 层级搜索 | #skeletal-tree-search |
| 属性 / 权重 / 皮肤 | #skeletal-rigging-properties / -weights / -skins |
| 描绘 / 自动布网布骨 | #skeletal-smart-mesh / #skeletal-auto-rig |
| 网格创建 / 恢复 | #skeletal-make-mesh / #skeletal-restore-image |
| 附件管理的恢复 | #skeletal-attachment-restore-image |
| 权重骨骼列表 | #skeletal-weight-bone-list |
| 已选骨骼绑定 / 移除 | #skeletal-bind-selected-bones / #skeletal-unbind-bone |
| 动画编辑网格 | #skeletal-edit-deform |
| 画布 | #skeletal-canvas |

选择 ID 可含文件/骨骼名称，需观察实际节点，不自行拼接含特殊字符的选择器。权重页选择骨骼时保留网格目标，已绑定骨骼在列表高亮，未绑定骨骼显示绑定按钮。鼠标绘制/拖动经 plorer 的真实事件链；不要只改控件值后声称完成交互验证。

## 有用的代码入口（相对 Defold checkout）

| 文件 / 函数 | 用途 |
|---|---|
| editor/src/clj/editor/skeletal_animation_smart_mesh.clj — trace!, trace-proposer | 从 alpha 轮廓描绘、重布网及预览缓存 |
| skeletal_animation_auto_rig.clj — proposer | 自动布网、参数化控制点和骨骼；先生成提案再提交 |
| skeletal_animation_mesh.clj — change-binding, bind-weights, set-weight-group | 绑定、自动权重、手动权重，返回含迁移策略的 mesh-edit 结果 |
| skeletal_animation_data.clj — apply-mesh-edit | 按顶点映射迁移权重/FFD，不直接替换 vertices |
| skeletal_animation_attachments.clj | 皮肤附件增删、关联、恢复图片、序列 |
| skeletal_animation_slots.clj | 颜色、透明度、绘制顺序、附件切换、显式 FFD 时间线 |
| skeletal_animation_project.clj | 多骨架工程和独立工程存储 |
| skeletal_animation_io.clj | 图片库存、运行时兼容检查、导出项目 |
| skeletal_animation_spine.clj | 通过安装且已授权的 Spine CLI 读写原生 .spine |
| skeletal_animation_textures.clj | 进包贴图缩放，保留原始图片 |

上述表除首行外均位于 `editor/src/clj/editor/`。只读需要的文件，不整库灌入上下文。

自动布骨参数使用工具原有字符串键：`bone-count` 2–32、`control-count` 2–8、`control-points` 为图片内归一化 UV 点、`reverse`。描绘参数的范围/整数映射读取当前面板，不把 UI 的百分比直接当底层浮点参数。`auto-rig/proposer` 的返回闭包缓存昂贵分析，可重复调整参数；勿为每个滑块值启动全套进程。

重型描绘/布骨运算放在后台，不阻塞 JavaFX 线程。捕获输入文档基线，完成后比较当前文档，再在 FX 线程一次提交。用户期间有更改就重新提案；不能覆盖未保存修改。

自动权重是本项目的表面距离/拓扑实现，不宣称复制了 Spine 专有求解器。网格与约束保持兼容需要实际逐帧检查。
