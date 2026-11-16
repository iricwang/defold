# Spine 运行时差异补齐（2026-11-10）

本次修改仅在编辑器。已通过本机回环更新源将 patch.129 增量安装为 **1.14.2-patch.130**，并重新启动本地 Defold、恢复原项目；未发布到 GitHub。运行时引擎、Bob、原生库及 JDK 未修改。

## 修复

- 原始网格权重按官方求和，预览不再自动归一化；编辑工具仍可显式归一化。覆盖零权重、权重和小于／大于 1、带变形偏移。
- 双骨 IK 在非 normal 继承时跳过求解，mix 为 0 也不会异常。设置数据校验与动画采样规则统一；覆盖 normal → noScale → normal。
- 独立原生 codec 补齐 39 号继承轨道，五种模式的旋转／缩放／反射标志均由官方 4.2.43 工程验证。官方生成的工程可读，我们生成的工程经官方重开并导出后关键帧完整。不是仅绕过保存检查，也不烘焙或丢弃继承轨道。
- 混合预览增加倒放、起止范围、保持上一动画、最短旋转和空动画淡出控件。修正中断过渡的通道归属、过渡结束姿势保留与片段循环事件；未知 TrackEntry 参数立即报错。
- 实际时间步模式 `:step-mode :runtime` 记录原始 dt，seek 按相同时间步与轨道命令回放且不重复触发事件。默认固定步长模式继续用于确定性预览。覆盖 Physics 变帧率、重置、蒙皮启用／停用和 Transform＋Physics。
- 导出菜单提前显示默认 Defold Spine 材质的暗色通道限制；JSON 与编辑器预览保留双色数据。该材质的运行时表现仍受现成扩展提供的顶点数据限制，本次不通过修改运行时引擎解决。
- 后台实测发现并修复任意暂停时间打继承关键帧后下拉框恢复旧值的问题：写入可立即采样的 Float32 时间，重复修改替换同一关键帧。已有导入轨道仍按官方 Float32 时刻采样。

## 验证

| 范围 | 结果 |
| --- | --- |
| 源码回归（含原生 codec、导入导出） | 398 tests / 13,126 assertions，0 failure / error |
| 最终隔离编译 JAR | 332 tests / 12,253 assertions，0 failure / error |
| 官方 Runtime 4.2.120 新案例 | 13 组、1,313 帧，事件一致，最大矩阵误差小于 0.000001 |
| 原 05_tail 案例 | 8 段动画、617 帧，最大世界顶点误差约 0.00006432，容差 0.0001 |
| JavaFX／Metal 后台交互 | 最终 118 步通过，停用处理器和计时器均为 0 |
| 原生工程互操作 | 官方 Spine 4.2.43 生成 → 独立解码／编码 → 官方重开导出，五种模式保留 |
| 格式与 lint | 所有改动 Clojure 文件执行 cljfmt；clj-kondo 无 error，本轮生产代码改动行无新增提示；已有文件的历史 warning 保留 |

源码回归包含 60 项原生／IO 测试和 6 项智能布网源码测试；编译回归选择实际编译路径可执行的测试集，并包含本轮全部新增案例。将旧拖拽测试的函数调用计数替换成真实关键帧数量和世界顶点断言，避免 direct-linking 绕过测试 spy。没有将编译失败当作通过。

后台首次最终复跑在继承模式立即显示的断言上失败，已修复生产逻辑并新增 `inheritance-playhead-rounding-test` 后重新通过。后台验证包括原有平移、缩放、播放暂停、FFD、撤销重做及新增混合参数；原生系统文件对话框和与 Spine 桌面逐像素一致性不在本次断言范围。

## 复现入口

- `test/editor/skeletal_animation_runtime_gap_test.clj`
- `test/headless/skeletal_animation_runtime_gaps.edn`
- `scripts/spine_runtime_gaps_oracle.mjs RUNTIME/dist/index.js OUTPUT.json`：必须使用固定的官方 4.2.120；生成数据与仓库 golden 内容一致。
- `scripts/spine_tail_oracle.mjs`：原 05_tail 世界矩阵、顶点、附件状态与绘制顺序对照。
- [详细证据](skeletal-animation-evidence/2026-11-10/runtime-gaps/)

兼容范围仍为已验证的 Spine 4.2.43/schema 40 原生工程和 4.2.120 Runtime。这里验证的是本轮列出的行为与组合，不代表所有 AnimationState API、所有骨架或平台已完全等价。

## 本地交付

- 增量包：`tmp/spine-runtime-update/Defold-arm64-macos-patch-129-to-130.zip`（217,383,799 字节）。
- 安装前、暂存应用与安装后均校验 169 个文件，哈希及权限无差异。
- 最终交付 JAR 与已测试 JAR 只有版本元数据不同；47,917 个非编辑器依赖条目与安装基线逐字节一致。
- 实际安装版本启动原 Lastlight District 项目，恢复两个 GUI 和 `tail_ik_example.skeleton` 标签；骨骼文档加载成功，新混合控件可见，停用处理器及计时器均为 0。原尾巴工程文件哈希未改变。
- 安装器回滚备份保留。验证采用本机 HTTP 回环更新源，未发布远端更新。
- 安装证据：`skeletal-animation-evidence/2026-11-10/runtime-gaps/installed-verification.json` 和 `jar-verification.json`。
