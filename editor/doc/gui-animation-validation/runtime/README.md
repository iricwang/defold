# GUI 动画的真实游戏运行时回归（patch.28）

已验证编辑器制作的内容能够在真实 Defold 游戏内运行。使用可交互的 [Runtime Arena 项目](../../../test/resources/gui_animation_runtime_project/README.md)，执行 Bob 编译、独立 Metal 游戏进程，以及编辑器内嵌 Game 的完整链路。没有用模拟 GUI adapter 代替引擎，也没有把编辑器预览当作游戏运行结果。

## 结果

| 验证 | 通过 | 失败 |
| --- | ---: | ---: |
| Metal GPU，独立游戏，第 1 轮 | 3,154 | 0 |
| Metal GPU，独立游戏，第 2 轮 | 3,151 | 0 |
| 无窗口引擎，CI 同款路径 | 3,154 | 0 |
| 编辑器 Game，120 FPS 设置 | 3,244 | 0 |
| 安装后 patch.28 Game，再次运行 | 3,267 | 0 |
| 编辑器自动化测试 | 49 项 / 5,249 条断言 | 0 |
| clj-kondo | 0 错误、0 警告 | — |

逐帧断言数量随实际帧时序略有变化。Game 的 120 FPS 是目标设置，不代表持续帧率基准成绩。所有运行均等待动画完成与事件完成的明确信号；90 秒看门狗独立于帧率。详见 [汇总](summary.json)、[GPU 第 1 轮](gpu-run-1.json)、[第 2 轮](gpu-run-2.json)、[无窗口](headless.json)、[Game](embedded-120fps.json)、[编辑器测试日志](editor-tests.log) 和 [资源审计](project-audit.json)。

## 覆盖与实测操作

- JSON / Lua 两种真实导出；392 个采样点，包含开始、结束、边界与向后定位。覆盖全部 32 个数值属性和 15 个触发属性；Z 轴、旋转、布尔开关也使用非默认变化值。
- 全部 41 种内置缓动及自定义超调曲线，在实际播放中与独立 `gui.animate` 参考节点逐帧比对，并核验结束姿态及完成回调。
- 初始状态、重叠轨道、本地嵌套片段、GUI 模板、非线性嵌套的采样播放。
- 事件顺序、字符串/数值参数、一次性伤害和积分、连续事件的多个进度回调、有限弹性曲线数值。
- 倍速、倒放、三次循环、停止保留姿态、再次播放不重复结算。
- 通过 `cljfx.plorer` 在真实 GUI 编辑器中开启录制，把 `box.position_x` 的 1 秒关键帧改为 480，保存后使用生产导出器生成游戏资源；记录保存在 `authoring/` 和 [文件哈希清单](fixture-inventory.json)。
- Game 内鼠标攻击后 HP=75 / Score=10，空格攻击后 HP=50 / Score=20，R 重置后 HP=100 / Score=0。依据实际游戏回调写出的状态文件验收，见 [鼠标](mouse-input.json)、[键盘](keyboard-input.json)、[重置](reset-input.json)。

## 修复的运行时差异

首次实际引擎回归有 350 / 2,226 条断言失败：Panthera 11 + tweener 6 的公式缓动与 Defold 的 64 点内置曲线不同，`inelastic` 时间采样产生 NaN。现在 **导出 JSON / Lua** 会把命名曲线烘焙成同一组 `easing_custom` 采样，保留既有自定义曲线，并保持编辑源数据不变。修复前的 [差异样本](initial-differences.json) 与修复后结果均有留档。第三方运行库保持原样，版本与每个模块的哈希固定在项目的 `dependencies.lock.json`，MIT 授权原文保留。

原生 `panthera.play` 的嵌套调度按线性时间推进；需要非线性嵌套时间、倒放区间时使用 `panthera.play_tweener`。两条路径分别验收，不能把采样路径通过等同于原生调度器支持时间扭曲。引用的模板动画文件也需要导出。

## 截图

[实际录制关键帧](01-editor-authored-keyframe.png) · [场景与游戏并排运行](02-game-live.png) · [完成的游戏](03-runtime-complete.png) · [完整编辑器](03-runtime-scene-and-game.png) · [鼠标攻击](04-mouse-hp75-score10.png) · [键盘攻击](05-keyboard-hp50-score20.png) · [重置](06-reset-hp100-score0.png)。

实际本地运行平台为 macOS arm64，Defold 1.14.1，Metal 与无窗口引擎。CI 已加入 macOS 两种架构的游戏回归和结果产物上传；本地报告不宣称 Windows、移动端或浏览器运行验证通过。

## 增量补丁验证

已构建 `1.14.1-patch.28`（代码提交 `b6a9b325c872b350b2b8455bacb08050eb73866b`），仅生成增量 ZIP，没有重新打包 App/DMG。使用与 `/Applications/Defold.app` 完全一致的 patch.22 基线副本，通过本地源 `http://127.0.0.1:8773/manifest.json` 实测下载、校验、应用补丁及自动重启；全部 168 个文件的哈希与权限一致。见 [安装记录](patch-installation.json)、[发现更新](07-update-available.png)、[校验完成](08-update-verified.png)。此项是本地更新源验证，不代表 GitHub 正式源的安装验证。

在更新后应用中直接调用发行版 AOT 代码（没有热加载或替换函数），三个文档的 JSON 与 Lua 导出均与已通过游戏回归的资源一致，见 [安装后导出验证](installed-export-proof.json)。原始编辑源没有被运行时烘焙改写。

安装后 patch.28 的干净项目启动没有 ERROR；再次实际编译运行游戏，3,267 / 3,267 条断言通过，见 [发行版游戏结果](installed-game.json)、[截图](09-installed28-runtime.png)。运行后 [26 个项目资源哈希未变](resource-immutability.json)。重导出辅助脚本放在 `editor/scripts/`，避免被游戏项目当作编辑器插件自动执行。
