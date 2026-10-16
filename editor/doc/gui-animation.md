# GUI 动画时间轴

GUI 动画面板直接使用 Defold 编辑器的场景视图预览动画，不需要启动游戏或
Panthera 独立编辑器。动画数据采用 Panthera 格式，游戏中的播放仍由
[Panthera Runtime](https://github.com/Insality/panthera) 完成。

## 创建第一段动画

1. 打开一个可编辑的 `.gui` 文件，场景视图底部默认显示 **GUI 动效 · Panthera**
   （**GUI Animation · Panthera**）面板。下文同时使用英文按钮名，方便对应界面语言。
2. 空面板中点击 **创建 / 打开**（**Create / Open**）。例如，`/main/main.gui` 会在旁边创建
   `/main/main_panthera.json`，包含一段名为 `default`、时长 1 秒的动画。
3. 在场景或大纲中选择 GUI 节点，也可以使用面板中的节点下拉框选择。
4. 选择属性，例如 `position_x`，点击 **添加属性**（**Add property**）。左侧出现按节点
   分组的属性轨道，并以 GUI 原始值建立第 0 帧。点击节点名称可折叠或展开该组。
5. 开启 **● 录制**（**Record**），在顶部“帧”输入框填 `30` 并按 **Enter**，再直接修改
   左侧轨道数值。按 **Enter** 或离开输入框时，会在当前播放头自动记录关键帧。
   也可以在底部选择属性、输入数值并点击 **设置关键帧**（**Set key**）。
6. 关闭录制后仍处于预览状态。点击 **Play** 从当前播放头播放；在结尾再次播放会从头开始。
   开启 **循环**（**Loop**）可反复检查效果；拖动时间标尺可预览任意帧。
7. 使用 **Ctrl+S** 保存，或在底部 **动画设置** 页点击 **全部保存**
   （**Save all**）。保存操作也会保存其他已修改资源。

关闭录制时，在左侧轨道修改数值只改变临时预览；底部数值按 **Enter** 也可临时预览。
点击 **Set key** 才会保留当前选中属性。移动播放头、切换动画或关闭预览会丢弃未记录的调整。
预览和录制按钮显示当前状态。录制支持动画面板内的数值编辑，以及场景中的移动、缩放和旋转操作杆。
预览时拖动操作杆只改变临时姿态；开启录制后，松开鼠标将发生变化的通道一次写入当前帧，
可一步撤销，`.gui` 原始布局保持不变。右侧通用属性面板仍不自动录制。
若第一次直接在大于 0 的时间记录新属性，面板会补入 GUI 原始值作为 0 秒关键帧。
关键帧上的缓动控制“上一个关键帧到这个关键帧”的过渡；首个关键帧之前没有过渡。
位置使用 GUI 本地坐标，旋转使用欧拉角的度数，缩放 `1` 表示原大小，`color_a`
是透明度，通常使用 `0` 到 `1`。

## 工作区布局

标题栏、播放工具栏、属性轨道与时间轴、底部设置页按 Panthera 参考布局排列。
顶部 **帧显示** 在帧数与秒数标尺之间切换；采样率可选 24、30、60、120 fps。
**循环** 配合底部正常、倒放、往返模式预览播放。右侧全屏按钮可暂时铺满场景区域，再次点击恢复。
标题栏和底部 Tab 使用紧凑高度，窄窗口可横向滚动工具栏和设置区。
左侧默认列出位置、缩放、旋转和透明度六个常用通道；菱形按钮可以直接为通道打帧，
已有通道的复选框控制它是否参与预览。`opacity` 对应文件中的 `color_a`。

## 编辑时间轴

- 点击菱形关键帧，会选中其节点、属性、时间和缓动。修改数值后点击 **Set key**
  可替换同一时间的关键帧。
- 水平拖动菱形可移动关键帧，松开鼠标时提交。拖动按文件的采样率吸附（默认 `60 fps`），范围限定在
  当前动画时长内。移动到已有关键帧的时间会替换该时间的值。
- 点击 **Delete key** 删除选中的关键帧；时间轴获得焦点时也可以按 **Delete**。
- 标尺显示帧数；顶部可用帧数或秒数定位。文件中已有的 `metadata.fps` 用于显示和吸附，
  不会改变 Panthera 以秒保存的时间。未提供有效采样率时使用 `60 fps`。
- 使用顶部首帧、播放、下一关键帧、末帧按钮快速定位。时间轴获得焦点时，
  **逗号 / 句号** 逐帧移动，**Alt+逗号 / Alt+句号** 跳转前后关键帧，**Home / End** 跳到首尾。
- 使用 **缩放** 滑杆放大时间轴，底部横向滚动条浏览其他时段；选择 **100%** 恢复完整动画范围。
  跳转或播放超过可见范围时，视图跟随播放头。左侧属性与关键帧行始终保持对齐。
- 在时间输入框输入秒数并按 **Enter**，可精确查看某一时刻；切换到底部动画设置，在时长输入框输入秒数
  并按 **Enter**，可修改动画总时长。时长不能短于已有关键帧的结束时间。
- 在名称输入框输入新名称并按 **Enter** 可重命名当前片段；右上角 **…** 菜单可创建另一段动画，
  使用标题下拉菜单切换。名称必须非空且唯一。
- 点击 **Play** 从当前播放头继续，到结尾自动停止（开启循环时从头继续）；再次点击可暂停。时间轴获得焦点时
  **Space** 也可切换播放状态。

勾选 **Preview** 时，场景显示临时动画姿态，`.gui` 的原始布局不会被改写。
关闭 **Preview** 或收起面板即可恢复布局。调整原始布局时先关闭预览。
关键帧编辑修改的是 JSON 资源，可使用编辑器的撤销、重做和保存操作。

## 导入、保存与导出

底部 **动画设置** 页可管理片段和文件。**Import JSON** 读取 Panthera JSON，并把内容放入当前 GUI 对应的
`*_panthera.json`。若已经存在动画文件，这会替换其内容，可通过撤销恢复。
导入数据的节点 ID 必须与当前 GUI 匹配。

JSON 是主要编辑文件，会保留导入的元数据、额外字段和暂不支持的轨道。
**Export Lua** 另存一个返回动画数据表的 `.lua` 文件，供游戏直接 `require`。
导出后继续编辑 JSON，需要再次导出才能更新 Lua 文件。本面板不执行或导入 Lua 代码。

## 在游戏中播放

按 Panthera 的说明为项目添加 Runtime 及其依赖，并获取库。编辑器时间轴不会自动
把 Runtime 加入游戏项目。假设从 `/main/main.gui` 导出了
`/main/main_panthera.lua`，可在 GUI 脚本中使用：

```lua
local panthera = require("panthera.panthera")
local animation = require("main.main_panthera")

function init(self)
    self.motion = panthera.create_gui(animation)
    panthera.play(self.motion, "default")
end

function final(self)
    panthera.stop(self.motion)
end
```

也可以直接加载 JSON：先在 `game.project` 的 **Custom Resources** 中包含
`/main/main_panthera.json`，然后把 `panthera.create_gui(animation)` 替换为：

```lua
self.motion = panthera.create_gui("/main/main_panthera.json")
```

播放名称必须与面板中的动画名称一致。模板或克隆节点的运行时绑定方式请参考
Panthera 的 `create_gui` 接口。

## 当前支持范围

可以创建、编辑和预览以下数值补间属性：

| 类型 | Panthera 属性 |
| --- | --- |
| 位置 | `position_x`、`position_y`、`position_z` |
| 欧拉旋转 | `rotation_x`、`rotation_y`、`rotation_z` |
| 缩放 | `scale_x`、`scale_y`、`scale_z` |
| 透明度 | `color_a` |

缓动包括 `linear`，以及 `quad`、`cubic`、`quart`、`quint`、`sine` 的
`in`、`out`、`inout` 形式，共 16 种。接受 Panthera 的字符串 `"tween"` 和
数值 `1` 类型。省略的 `start_time`、`duration`、`start_value`、`end_value`
按 Panthera 约定视为 0，省略缓动视为 `linear`。

以下导入数据会保留，但当前动画的预览会给出限制提示：触发器、事件、嵌套动画、
`initial_state` 动画引用、`is_editor_only` 关键帧、自定义曲线、其他缓动和属性，
以及起止值不是有限数字的补间。未知类型的附加字段不会被强制转换为数字。

同一数值轨道含有间隔、重叠或数值跳变时，可以按 Panthera 规则采样，但不能用
当前关键帧操作编辑，以免改写导入动画的行为。该情况会显示提示，JSON 内容保留。
**关键帧曲线** 页显示当前属性的采样曲线，插值下拉菜单可修改所选关键帧的缓动；
**事件** 页只查看导入事件，不编辑或执行事件。本版不支持自由曲线控制点、事件编辑、
批量多属性打帧或动画片段删除。

交互参考 Unity 的 [Animation 窗口](https://docs.unity3d.com/cn/6000.0/Manual/animeditor-UsingAnimationEditor.html)
和 [预览 / 录制流程](https://docs.unity3d.com/cn/6000.0/Manual/animeditor-AnimatingAGameObject.html)。
目前实现的是属性轨道、播放头与面板数值录制的编辑流程，尚未覆盖 Unity 的自由曲线编辑、
多关键帧框选、复制粘贴和通用属性面板录制。
