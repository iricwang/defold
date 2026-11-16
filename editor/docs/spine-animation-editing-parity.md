# Spine 动画编辑四项差异补齐（2026-11-10）

本次仅修改编辑器；运行时引擎、Bob、原生库及 JDK 保持安装基线。

| 项目 | 实现与验证 |
|---|---|
| 设置／动画边界 | 动画模式不能修改骨骼父级、长度、附件拓扑等设置数据；回调执行时复核模式、所选对象和附件身份，阻止过期属性面板误写。 |
| 附件级变形 | 按皮肤／插槽／附件定位变形轨道；支持不同顶点数的切换附件、链接网格共享或独立变形、非当前附件的时间轴编辑。复制粘贴核对几何签名；预览、保存和导出保持相同身份。 |
| 统一打帧 | 附件切换／隐藏、颜色、绘制顺序及序列与骨骼／网格共用自动打帧和临时姿势。显式打帧保留已暂存的操作；撤销／重做使用真实图事务。预览刷新期间不销毁展开的下拉菜单。 |
| 继承模式动画 | 五种继承模式可预览、打帧、删除、移动和复制；按离散轨道采样。混合播放在 alpha 为 0 时仍遵循官方继承语义，淡出立即回到设置继承模式。 |

## 验证

- 源码回归：332 个测试、9,247 条断言通过。
- 最终编译 JAR 回归：326 个测试、9,141 条断言通过。智能布网模块 6 项依赖函数替换的测试在源码回归运行；该模块未修改，也未纳入编译包回归。
- 官方对照固定为 @esotericsoftware/spine-core 4.2.120。原 05_tail 的 8 段动画共 617 帧，新附件／继承案例 121 帧；骨骼世界矩阵、尾巴世界顶点、附件状态、绘制顺序全部在 0.0001 容差内。
- 后台交互场景：test/headless/skeletal_animation_parity.edn。通过真实 JavaFX/Metal 窗口与 cljfx.plorer 输入验证；原生系统文件弹窗和桌面像素一致性不属于本次断言。
- 新增回归位于 test/editor/skeletal_animation_parity_edit_test.clj。参考数据为 test/resources/skeletal-animation/attachment-inherit-authoring.skeleton。
- 测试报告保存在 skeletal-animation-evidence/2026-11-10/editing-parity/。

## 重跑

在 editor 目录使用项目 JDK/Lein 环境执行 editor.skeletal-animation-parity-edit-test、editor.skeletal-animation-view-test、editor.skeletal-animation-slots-test、editor.skeletal-animation-timeline-test、editor.skeletal-animation-mixing-test。

逐帧脚本为 scripts/spine_tail_sample.clj 和 scripts/spine_tail_oracle.mjs，固定官方 runtime 模块路径，依次传入 .skeleton 与输出目录。后台交互使用 scripts/headless_editor.py 的 background 后端及上述 EDN 场景，在隔离项目副本执行。

## 本机安装

已通过本机回环 HTTP 源下载、SHA-256 校验、官方 PatchInstaller 和 PatchRestart 完成 patch.128 → patch.129 安装；未发布远程更新。

最终编译包 88 步后台交互全部通过；实际安装与暂存 App 的 169 个文件哈希、权限完全匹配。47,917 个非编辑器 JAR 条目字节不变。重启后的 Lastlight District 项目与原三个页签恢复，骨骼文档与安装前备份完全一致，停用事件处理器及计时器均为 0。

本地增量包：tmp/spine-parity-update/Defold-arm64-macos-patch-128-to-129.zip。保留官方安装备份用于回滚。
