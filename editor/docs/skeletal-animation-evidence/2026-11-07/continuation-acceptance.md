# 2026-11-07 续作验收证据索引

能力和限制以 [主验收文档](../../skeletal-animation-parity.md) 为准。本索引区分独立
对照、真实运行和源码测试；不把源码入口或历史截图当成全部 UI 交互已通过。
运行时引擎及用户游戏资产未修改，本轮交付仍为源码和本机会话热加载，没有持久增量包。

最终源码全套 376 tests / 7586 assertions 通过；最后标签调整后的定向 12 tests / 134 assertions 通过。两组重叠，不相加。

| 验收内容 | 入口 | 能证明的范围 |
| --- | --- | --- |
| 最终原生界面与回归 | [交互记录与截图](continuation-ui/README.md)、[全套日志](continuation-ui/full-regression.log) | 实际多轨设置/播放/暂停、0 帧事件、名称点击切换与边缘标签；源码回归和最后布局回归分开记录 |
| 独立原生互操作 | [范围和限制](native-validation.md)、[官方重开断言](native-official-results.json)、[官方输出](native-official-exports.json) | 4.2.43/schema 40；80 项字段核对，含附件、颜色轨道、蒙皮依赖、同刻事件和多骨架编辑 |
| GPU 预览与几何 | [渲染记录](render-validation.md)、[裁剪 oracle](clipping-oracle.json)、[性能原始数据](render-performance.json)、[像素截图](gpu-pixel.png) | 双色/PMA、四种混合、凹裁剪、权重/变形和结束作用域；真实 GPU 读回，不是 CPU 示意图 |
| AVI 独立解码 | [解码结果](avi-results.json)、[帧校验](avi-standard.framemd5) | FFmpeg 7.1 的 15 项检查：帧数、尺寸、时间基、方向/顺序和透明合成；MJPEG 无音轨/无 alpha |
| Sequence 与附件资源管线 | [Sequence README](sequence/README.md)、[索引对照](sequence/report.json)、[图集解析](sequence/atlas-report.json) | 七种序列模式，84 案例/1092 采样；独立官方实现对照；Spine 多页 atlas 的五个区域读回一致 |
| 实际 Defold 运行 | [运行 README](workflow-runtime/README.md)、[结果](workflow-runtime/runtime-results.json)、[构建工具摘要](workflow-runtime/build-results.json) | 当前四资源导出、标准 Bob clean build、现成引擎运行 525 帧、12 次事件，Sequence/Linked Mesh/Clipping/特殊混合的画面像素核对 |
| 暗色运行时边界 | [导出结果](workflow-runtime/export-results.edn)、[实际画面](workflow-runtime/frame-0120.png) | JSON 保留双色，但默认 Defold 材质只有 light tint；导出明确警告，未将该项计为视觉等价通过 |
| 原生保存失败安全 | [真实写入失败](workflow-runtime/write-failure.log)、[复跑脚本](workflow-runtime/run_write_failure.py) | OS 文件大小限制导致真实写入失败时，目标不存在、临时文件清理；不是只模拟保存成功 |
| 多轨动画状态 | [官方 golden](../../../test/resources/skeletal-animation/stateful-mixing-oracle.json)、[状态测试](../../../test/editor/skeletal_animation_mixing_test.clj)、[播放测试](../../../test/editor/skeletal_animation_playback_test.clj) | 425 帧独立参考；多轨、add/replace、队列、旋转历史、混合 Physics 及播放时钟边界 |
| 曲线批量制作 | [曲线 UI 测试](../../../test/editor/skeletal_animation_curve_view_test.clj)、[摄影表测试](../../../test/editor/skeletal_animation_timeline_test.clj) | 多通道、选择/拖动/提交及关键帧批量语义；不等同于 Spine 全部曲线 UI 已复刻 |
| 音频、媒体、多骨架 | [制作流程测试](../../../test/editor/skeletal_animation_workflow_test.clj)、[视图流程测试](../../../test/editor/skeletal_animation_view_workflow_test.clj)、[IO 回归](../../../test/editor/skeletal_animation_io_review_test.clj) | 媒体可读性/时长、资源边界、音频生命周期、原生元数据与音频目录、工作区切换状态和缓存 |

上述日志中的测试数是各自专项运行的数量，不相加作为最终全套结果。图片是实际
运行帧；并未将设计稿当作验收截图。可复跑脚本依赖本机工具和隔离临时工程，
仓库只收录小型数据、日志和画面，不收录引擎、插件二进制或大构建缓存。

同日第一阶段的历史证据仍在本目录根部：
[editor-validation.json](editor-validation.json)、[event-log.png](event-log.png)、
[slot-timeline.png](slot-timeline.png)、[mixing-validation.json](mixing-validation.json)、
[runtime-validation.json](runtime-validation.json) 和 [regression.log](regression.log)。
这些文件对应当时的事件、插槽和双轨阶段，不代表本轮全部新增能力或最终测试总数。
