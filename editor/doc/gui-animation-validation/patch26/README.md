# patch.26 GUI 动效验证

2026-10-17，macOS Apple Silicon，JDK 25。界面操作全部通过运行中的 Defold 编辑器及 `cljfx.plorer` 完成。

- 完整 Clojure 回归：**88 项测试、4938 条断言，0 失败、0 错误**，见 [日志](clojure-tests.log)。
- 增量更新 Python 回归：12 项测试通过，其中 3 项 Windows 专用测试在 macOS 跳过，见 [日志](patch-tests.log)。
- 动效源码、单元测试和集成测试的 clj-kondo：0 错误、0 警告，见 [日志](clj-kondo.log)。全部修改的动效命名空间完成整文件重载，没有新增反射或装箱警告。
- **90 项实际运行时检查全部通过**，见 [机器可读结果](ui-results.json)。下面的 PNG 均来自实际界面。

## 功能与证据

| 功能 | 验证与截图 |
| --- | --- |
| 原生紧凑 Tab、紧贴标题的动画选择器、中性下拉框 | [默认状态](01-native-tab-neutral-controls.png)、[展开后的选中高亮](02-dropdown-selection.png) |
| 属性录制、自动原始值首帧、显式打帧 | [录制](03-record-property-baseline-and-key.png)、[打帧](04-explicit-key-and-preview.png) |
| 多选、剪切、复制粘贴、时长扩展、一步撤销重做 | [多选](05-multiple-selection.png)、[粘贴](06-batch-copy-paste.png)、[剪切](07-batch-cut-delete.png) |
| 41 种内置缓动、拖拽自定义采样曲线 | [内置](08-builtin-easing.png)、[自定义](09-custom-curve-drag.png)、[采样曲线](10-sampled-property-curve.png)；每种缓动另有 `easing-*.png`，数值测试逐一覆盖引擎采样值 |
| 动画新建、重命名、复制、删除、引用保护 | [新建与重命名](11-create-rename-clip.png)、[复制](12-duplicate-clip.png)、[删除](13-delete-clip.png)；引用保护另有数据测试 |
| 初始状态 | [初始状态](14-initial-state.png)，另测引用改名、删除保护、循环检测 |
| 事件创建、修改、删除 | [创建](15-timed-event-create.png)、[修改](16-edit-event.png)、[删除](20-event-delete.png)，检查事件数据及采样值 |
| 15 种离散触发属性 | [可见性编辑](17-trigger-visibility.png)、[场景触发效果](25-discrete-properties.png)，集成测试逐项检查图属性且不污染原始 GUI |
| 嵌套动画、模板动画、开始时间优先级 | [嵌套](18-nested-animation.png)、[模板](26-template-animation.png)，另测边界、初始姿态、可达依赖、循环/深度保护 |
| 原始片段、仅编辑器标记、重叠片段不展平 | [原始片段](19-raw-tween-editor-only.png)、[重叠](27-overlap-segments.png)、[连续微调和粘贴](41-raw-batch-nudge-paste.png) |
| 全部 32 个数值通道 | [变换](21-transform-preview.png)、[尺寸/颜色/九宫格](22-size-color-slice9.png)、[文本/描边/阴影](23-text-outline-shadow.png)、[饼图](24-pie-properties.png)；逐通道实时预览及图属性集成测试 |
| 时长延长、中键平移、适应缩放 | [延长](28-duration-extends-timeline.png)、[中键](29-middle-mouse-pan.png)、[适应](30-fit-timeline.png)；断言平移不修改播放头和 JSON |
| 正放、倒放、倍速、往返循环、帧/秒标尺 | [倒放倍速](31-reverse-playback-speed.png)、[循环](32-ping-pong-loop.png)、[秒标尺](33-seconds-ruler.png)，等待实际播放时间变化 |
| 展开工作区后仍可播放 | [展开](34-expanded-workspace.png)；已修复脱离场景树的工具栏导致空指针并停用刷新定时器的问题，新增回归测试 |
| 移动/缩放/旋转手柄录制 | [移动](35-move-gizmo-recording.png)、[缩放](36-scale-gizmo-recording.png)、[旋转](37-rotate-gizmo-recording.png)；实际按下、拖动、松开后检查动画关键帧和原始 GUI 不变 |
| JSON/Lua 文件往返、Unicode、数据安全 | [文件加载后](38-file-roundtrip.png)；实际磁盘读写，中文及表情字符测试，静态 Lua 解析不执行脚本，非法导出不会截断已有文件 |
| 未录制草稿、重置、记录全部 | [草稿操作](39-draft-commit-reset.png)，检查未录制时文档不变、提交后写入动画 |

## 验证范围

参考 [Panthera](https://github.com/Insality/panthera) 提交 `3cf7557e045c2e1198bbdedbaf413c445505045a` 的 GUI 动画格式、数值/触发通道、嵌套优先级及快捷编辑行为。内置缓动数据来自当前仓库 Defold 引擎 `easing_lookup.h`。测试场景在 [gui_animation_project](../../../test/resources/gui_animation_project/)。

本实现覆盖嵌入 Defold 的 GUI 动画编辑流程。独立 Panthera 的项目主页、atlas 编辑和 GO/collection 动画不属于本次范围。事件在编辑器中显示数据，不执行游戏回调；游戏仍使用 Panthera Runtime。系统原生文件选择器不纳入自动化，测试覆盖它调用的同一文件读写边界及导入后界面。Windows/Intel 的打包与回归由 GitHub CI 执行，本地运行时结论仅针对 macOS arm64。

## 补丁安装验证

完成增量包构建后，通过本机 HTTP 更新源，对精确 patch.22 安装基线的隔离副本执行下载、安装和重启，并核对完整文件哈希与权限。结果另附安装记录。
