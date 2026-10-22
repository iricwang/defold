# Panthera GO 动画示例

使用包含本仓库 GO 动画改动的 Defold 打开 `game.project`，再打开 `main.collection`。

## 看什么

- 左侧：普通父子 GO，以及一个独立 GO。
- 中间：`left` 实例中的 `nested → nchild`，以及嵌套 `deep.collection` 中的 `leaf → child`。
- 右侧：同一份 `sub.collection` 的另一实例 `right`。两组节点使用独立路径，动画不会串到另一实例。
- 11 个 GO；4 秒循环，0 → 2 秒移动、旋转、缩放，2 → 4 秒返回。
- 子 GO 只给 `position_x` 打帧，验证其他属性保持原值，同时继承父级变换。

蓝色和绿色是父级，黄色和粉色是对应子级。示例使用正交相机，并给三维旋转保留足够的裁剪深度。

## 编辑与运行

1. 在 `main.collection` 下方的「GO 动画 · Panthera」启用预览，拖动时间线；可选 `left/deep/leaf` 等节点查看轨道。
2. 点击 Game 工具栏的运行按钮。
3. 点击 Game 画面获得键盘焦点：空格暂停/继续，R 重新播放，左右方向键前后定位 0.25 秒。
4. 修改动画后导出 Lua；运行入口使用 `main_panthera.lua`，导出时覆盖这份文件。

`main_go_panthera.json` 是可编辑的 Panthera 动画数据；`main_panthera.lua` 是对应运行数据；`main.script` 展示实际播放接入，`callback_template.lua` 是生成的接入模板。

开发时可按 `scripts/windows/README.md` 使用热更新；热更新只对当前编辑器会话有效。示例游戏运行数据本身可独立编译。

## 兼容与验证

内置与当前工程一致的 Panthera runtime.7 和 Tweener 6；保留原始许可证，运行不依赖联网下载。

动画使用 Panthera 的对象路径和数据格式。嵌套 Collection 根 GO 的关键帧采用 Defold 编译后的运行局部姿态；子 GO 采用相对父 GO 的局部姿态。播放入口沿用编辑器生成的 Euler 通道适配与线性时间采样，保持三轴旋转与拖动定位一致。

实际 dmengine / OpenGL 运行验证：11 个 GO、8 组采样，涵盖 0、0.5、1、2、3、4 秒、倒退定位和播放结束。最大世界矩阵误差小于 0.00005；暂停、续播、重播和前后定位处理均通过。采样数值和截图比较结果见 `validation/`。

同帧截图：`validation/editor-2s.png` 为编辑预览，`validation/runtime-2s.png` 为真实引擎 2 秒画面。底部状态文字由脚本更新，因此编辑预览仍显示初始文本；它不属于动画轨道。

可用命令行 `--config=example.time=2` 固定运行时间以复查画面；正常运行不设置该参数。

2 秒同帧画面的动画展示区共 505,200 像素，其中 26 个像素的通道差超过 8/255，集中于渲染边缘；对象变换另以世界矩阵数值校验。

## 重复运行验证

在本地构建该项目后，用对应版本的 `dmengine` 在项目目录启动：

```text
dmengine build/default/game.projectc --config=test.enabled=1
python validation/verify.py
```

测试模式会播放一次并导出 `runtime.json`，打印 `GO_EXAMPLE_CONTROLS_OK` 和 `GO_RUNTIME_PARITY_SAMPLES_OK` 后退出。校验脚本与保存的编辑器采样逐项比较。正常试用可直接点击编辑器 Game 运行按钮。
