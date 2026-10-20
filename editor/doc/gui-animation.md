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
可一步撤销，`.gui` 原始布局保持不变。右侧通用属性面板仍用于修改原始布局；预览调整与录制使用动效面板或场景操作杆。
若第一次直接在大于 0 的时间记录新属性，面板会补入 GUI 原始值作为 0 秒关键帧。
关键帧上的缓动控制“上一个关键帧到这个关键帧”的过渡；首个关键帧之前没有过渡。
位置使用 GUI 本地坐标，旋转使用欧拉角的度数，缩放 `1` 表示原大小，`color_a`
是透明度，通常使用 `0` 到 `1`。

## 工作区布局

场景左上角的 **动效**（**Animation**）按钮可收起或展开动画底栏。收起后场景占满编辑区域，并暂停播放、关闭录制、恢复原始 GUI 姿态。再次展开会恢复分隔高度、播放头和预览，但不会自动播放或重新开启录制。切换不修改 GUI 或动画文件。

标准 **GUI 动效 · Panthera** 标签页、播放工具栏、属性轨道与时间轴、底部设置页按 Panthera 参考布局排列。
顶部 **帧显示** 在帧数与秒数标尺之间切换；采样率可选 24、30、60、120 fps。
100% 缩放默认每屏显示 1 秒。延长片段会增加时间轴的可滚动范围，已有关键帧之间的距离保持不变。
按住鼠标中键，可在属性树或时间轴中横向平移时段、纵向浏览轨道，不移动播放头或关键帧。
点击 **适应** 可将整个片段缩放到当前视口。
**循环** 配合底部正常、倒放、往返模式预览播放。右侧全屏按钮可暂时铺满场景区域，再次点击恢复。
拖动场景和动效面板之间的原生分隔条可调整高度，展开后返回会恢复分隔位置。小窗口仍保留场景区域，动效内容不足以完整显示时可使用右侧滚动条浏览设置。
按钮、下拉框与标签页使用应用通用样式；顶部和底部 Tab 使用紧凑高度，窄窗口可横向滚动工具栏和设置区。
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
- 使用 **缩放** 滑杆放大时间轴，底部横向滚动条浏览其他时段；点击 **适应** 查看完整动画范围；**100%** 恢复每屏 1 秒。
  跳转或播放超过可见范围时，视图跟随播放头。左侧属性与关键帧行始终保持对齐。
- 在时间输入框输入秒数并按 **Enter**，可精确查看某一时刻；切换到底部动画设置，在时长输入框输入秒数
  并按 **Enter**，可修改动画总时长。时长不能短于已有关键帧的结束时间。
- 在名称输入框输入新名称并按 **Enter** 可重命名当前片段；右上角 **…** 菜单可新增、复制、删除动画，使用紧贴标题 Tab 的下拉框切换。
  名称必须非空且唯一；重命名会同步当前文件中的初始状态和片段引用；仍被引用的片段不能删除。
- 点击 **Play** 从当前播放头继续，到结尾自动停止（开启循环时从头继续）；再次点击可暂停。时间轴获得焦点时
  **Space** 也可切换播放状态。

勾选 **Preview** 时，场景显示临时动画姿态，`.gui` 的原始布局不会被改写。
关闭 **Preview** 即可恢复布局。调整原始布局时先关闭预览。
关键帧编辑修改的是 JSON 资源，可使用编辑器的撤销、重做和保存操作。

## 导入、保存与导出

底部 **动画设置** 页可管理片段和文件。**导入 JSON / Lua** 读取 Panthera JSON 或生成的 Lua 数据表，并把内容放入当前 GUI 对应的
`*_panthera.json`。若已经存在动画文件，这会替换其内容，可通过撤销恢复。
导入数据的节点 ID 必须与当前 GUI 匹配。

JSON 是主要编辑文件，会保留导入的元数据、额外字段和暂不支持的轨道。
**Export Lua** 另存一个返回动画数据表的 `.lua` 文件，供游戏直接 `require`。
导出后继续编辑 JSON，需要再次导出才能更新 Lua 文件。Lua 导入静态解析 `return { ... }` 数据表、字面量和模板 `require`，不会执行脚本；包含计算、循环或函数的 Lua 需要先转换成数据表。

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

## 属性、缓动与原始片段

支持 Panthera GUI 的全部 32 个数值通道：位置、欧拉旋转、缩放、尺寸、颜色和透明度、文字描边与阴影、九宫格边距、饼图内半径与填充角度、文字间距与行距。节点下拉框选择对象，属性列表按节点类型过滤。

支持 41 种内置缓动：`linear`，以及 `quad`、`cubic`、`quart`、`quint`、`sine`、`expo`、`circ`、`back`、`bounce`、`elastic` 的 `in`、`out`、`inout`、`outin` 形式。内置曲线使用 Defold 引擎相同的 64 点采样数据。选择关键帧后拖拽底部的小曲线即可编辑自定义采样点；也可以从关键帧旁的 **… → 自定义曲线** 开始。自定义曲线保存为 Panthera 的 `easing_custom`，随关键帧移动、复制和导出保留。

在帧轨道内右键，选择 **添加 event 关键帧** 或 **添加 trigger 关键帧**。开始时间按鼠标横向位置吸附到当前采样率的帧，并自动选中新关键帧、打开底部 **事件 / 片段** 页。节点优先取右键所在轨道；空白区域使用当前节点。未选择节点时仍可添加全局 event，但 trigger 菜单不可用。

**事件 / 片段** 页只显示当前选中的片段，不再列出全部事件。点击时间轴条带或左侧对应片段可切换编辑对象；点击轨道空白处清空选择。多选时显示当前活动片段，只有一个片段的拖动、粘贴和键盘移动也会继续显示它。可编辑四类片段：

选中事件或片段会自动打开 **事件 / 片段** 页；选中数值关键帧会打开 **关键帧曲线** 页。单次 `event` 只显示类型、可选节点、时间、事件 ID、数据和时长；时长大于 0 时才展开起止数值与插值。`trigger` 只显示类型、节点、属性、时间、初始数据和目标数据：布尔属性使用勾选框，轴心、混合模式、尺寸模式、裁剪模式和饼图外边界使用下拉框，文字、图层、贴图等使用输入框。未识别的已有枚举值保留在选项中，不会因打开表单而改写。

| 类型 | 编辑内容 | 预览 |
| --- | --- | --- |
| `tween` | 节点、属性、开始时间、时长、起止数值和缓动 | 在场景中显示数值变化 |
| `trigger` | 节点、属性、开始时间、初始数据、目标数据 | 在开始时刻切换离散属性 |
| `event` | 可选节点、事件 ID、数据、时间、时长和数值 | 面板显示事件及采样值，游戏回调由运行时执行 |
| `animation` | 空节点表示当前文件片段；模板节点表示子文件片段 | 按片段时长缩放嵌套动画时间 |

触发属性包括文字、图层、贴图、启用、可见、继承透明度、轴心、混合模式、尺寸模式、裁剪模式、裁剪可见性/反转、自动换行、饼图外边界和顶点数。布尔数据填写 `true` / `false`；文字与贴图填写 Panthera 对应字符串。

右键添加的 event 默认为单次事件，事件 ID 为 `event`；trigger 默认属性为 `visible`，起始和目标数据均为 `true`。选中片段后填写字段，点击 **应用** 修改当前片段；**删除关键帧** 删除当前片段。修改支持撤销、重做，保存及导出流程保持不变。原始片段在时间轴中显示为条带，可以拖动开始时间；关键帧重叠、间隔和跳变保持原样，不会被自动展平。此类数值轨道的菱形编辑不会强行重写片段，使用原始片段编辑区修改。

勾选 **仅编辑器** 会保留 `is_editor_only`：内嵌编辑器显示该姿态，Panthera Runtime 按其导出/预处理规则处理。未知数据和扩展字段原样保留，无法解释的类型会显示明确的预览错误。

## 初始状态、嵌套与模板

动画名称旁的 **初始状态** 下拉框可以选择同文件另一段动画。预览以它的结尾姿态作为初始状态；当前动画中更晚开始的关键帧继续生效。

创建 `animation` 类型片段时，**节点** 留空，**当前属性** 填另一个片段名称，即为同文件嵌套。指定模板节点并在 **模板动画** 中填写项目内的 `.json` 或 `.lua` 路径，则引用模板动画。模板路径记录在 `metadata.template_animation_paths`，子节点按 `模板节点/子节点` 绑定。路径输入为空时保留已有绑定。

嵌套预览遵循 Panthera 的根时间优先级：更晚开始的属性覆盖更早属性；正时长片段在非零开始边界仍保持之前的姿态。循环引用和超过 16 层的嵌套会报告错误。仅加载当前动画可达的模板，不影响未引用该模板的其他动画；资源修改后会重新读取依赖。

## 批量编辑与播放

- **Shift+单击** 增减菱形/片段选择；**Shift+拖动** 框选菱形。
- 时间轴获得焦点时，**Cmd/Ctrl+A** 全选，**C / X / V** 配合 Cmd/Ctrl 复制、剪切、粘贴；粘贴以播放头为起点，保留相对时间并按需延长时长。
- **Delete / Backspace** 删除选择。批量操作只产生一次文档修改，可一步撤销、重做。左右方向键按帧移动，Shift 放大为十帧。
- 关键帧旁的 **…** 同时提供复制、粘贴、记录全部预览修改和重置预览修改。**Shift+Enter** 记录全部临时修改，**U** 重置临时修改。
- 时长旁的倍速菜单提供 `0.1×` 至 `4×` 预览速度。正常、倒放、往返和循环仅影响预览，不改变导出的动画。
- 时间轴中 **A / D** 倒放/正放，**Shift+A / Shift+D** 逐帧，**R / Shift+R** 定位首尾，**Q / E** 定位所选菱形的首尾，**M** 切换预览。

## 验证记录

底部 **事件记录** 页签显示当前片段播放经过的事件起点、事件 ID 和参数，最新在前，最多保留 200 条。拖动播放头不记日志；点击记录暂停并定位、选中对应事件关键帧。持续事件仅记录进入点，不模拟游戏中的逐帧业务回调。修改动画或切换片段会清空旧记录，避免定位到已变更的关键帧。

在该页签点击 **复制 Lua 回调模板**，将模板合并到 GUI 脚本，核对导出 Lua 模块的 `require` 路径，并在对应事件函数中填写业务逻辑。模板使用 Panthera 的 `callback_event(event_id, node, data, value)`；编辑器本身不执行这些函数。成功导出后保存内容指纹；再次修改会提示 **动画已修改，需要重新导出**，重新导出或撤销回已导出内容后提示消失。

patch.34 优化播放头拖动：高频鼠标输入在界面刷新时合并处理，复用轨道数据和当前姿态；临时场景只清除受动画影响的缓存，避免 Trigger 预览反复编译着色器。预览不写入 GUI 或动画文件。

[patch.26 功能清单、测试与截图](gui-animation-validation/patch26/README.md)。截图来自实际编辑器界面，测试场景位于 `editor/test/resources/gui_animation_project/`。

实现参考 [Panthera 官方编辑器说明](https://github.com/Insality/panthera/blob/main/docs_editor/getting_started.md)、[快捷键](https://github.com/Insality/panthera/blob/main/docs_editor/hotkeys.md) 和官方 Runtime 数据语义。这里集成的是 GUI 动效工作区；GUI 原始布局、资源、工程与窗口管理继续使用 Defold 编辑器本身。


## Floating windows and panel composition

Resource editor pages, the GUI scene, and the GUI animation workspace have a **… → Float** menu action. The GUI scene and animation workspace can float separately; the resource page menu floats the whole editor. Assets, Inspector, Resource Preview, Tools, and Game can also float.

Drag a floating panel's title into another floating panel:

- The middle creates a tab group.
- The left/right edges create a horizontal split.
- The top/bottom edges create a vertical split.
- Release outside a target to create another window.

Splits can be nested and resized. Drag individual tab titles to rearrange them. Double-click a floating title, choose **… → Return** in its panel menu, or close the floating window to return panels to their original positions. Closing a resource tab still closes that resource and releases its floating panels. Reset Panel Layout returns floating panels as well.

For a second monitor, float the GUI scene and animation workspace, drag the animation title onto the bottom edge of the scene window, then move the combined window to that monitor. Preview, key editing, event history, and editor shortcuts operate on the same document. Floating windows are remembered per project. Reopening the project restores their positions, sizes, maximized state, tab groups, selected tabs, and nested split proportions. If a monitor is disconnected, windows are fitted onto a connected screen. Missing or renamed resource panels are skipped; the remaining groups still restore. Returning a panel or resetting the panel layout updates the saved arrangement. Layout data is stored in local project preferences, not in GUI or animation resources.

Floating removes the original panel from its split instead of leaving a return-button placeholder. Remaining panels expand into the released space; returning restores the panel order and divider proportions. If both GUI subpanels float, their empty editor area also collapses. Selecting the resource tab again returns its panels.


### Named workspace layouts

Every panel's **… → Layouts** menu offers **Save Layout As…**, **Switch Layout**,
**Recall All Floating Windows**, and **Restore Default Layout**. Names are trimmed
and limited to 80 characters; saving an existing name replaces that preset.
Presets belong to the current project and survive reopening it. They retain floating
window bounds, tab groups, selected tabs, split ratios, and the main panel arrangement.
Switching a preset restores panels whose resources are currently open; missing or
closed resource panels are skipped. Recall returns floating panels to their homes.
Restore Default also resets main panel placement. Neither operation deletes presets.

### Batch editing and recording feedback

Drag empty track space to box-select scalar keys and event/trigger segments together.
Shift or Ctrl/Cmd adds or toggles selection. Dragging a selected key moves the whole
selection; all selected keys preview their destinations, and releasing creates one
undo step. The group stops at time zero without changing relative spacing. Escape
cancels a drag. The ruler continues to scrub the playhead.

Copy, cut and paste use Ctrl/Cmd+C/X/V and share a key clipboard across animation
views. Paste aligns the earliest copied key with the playhead. Left/Right nudges one
frame; Shift+Left/Right nudges ten. **Snap to Frames** controls mouse snapping; Alt
while dragging temporarily disables it. Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z (or Ctrl+Y)
use the editor's global undo/redo history.

The animation workspace title shows preview and recording status, with a red badge
while recording. The GUI canvas stays clear of this status text. In preview mode, unrecorded
scene or property edits show an amber value and a **◆+** marker; **◆** means a key
exists at the current time, and **◇** means no key exists there. Click the property's
diamond to key that value, or the scene's **Set all keys** button to commit the pending
pose in one edit. Moving the playhead still discards unrecorded preview changes.

Outline and Properties are separate panels. Each has its own **…** menu and can
float, dock, or join another window independently. Selecting a node in Outline
continues to update Properties across windows. Saved layouts retain each panel's
placement; older layouts place Outline immediately before Properties.

Docked panels support nested splits in either direction: drop near a panel's
left/right edge to place panels side by side, or its top/bottom edge to stack them.
Floating windows use the same four edges, with their center reserved for tab grouping.
Mixed horizontal and vertical splits and their divider positions are saved with layouts.

Drag the Assets, Outline, Properties, or tool-tab headers to another docked panel to rearrange
them. The real panel detaches into a native floating window as soon as the pointer leaves
the click threshold and follows the pointer. The destination half is outlined before release.
Releasing outside dock targets leaves an independently movable and resizable window.
Drag a floating tab over a main-panel dock target to redock it. Windows samples the physical
primary button and logical screen coordinates throughout the gesture, so reparenting and
native-window activation cannot interrupt the drag when JavaFX loses mouse capture.

The resource editor tabs and GUI animation title use the same controller. Resource tabs
reorder within their existing tab strip; dragging out of the strip floats the page, and dropping
back on the strip returns it. Normal clicks, close buttons, and controls do not float panels.

Main panels reserve their original dock slot only while dragging, labelled with the panel
name and a dashed frame. Floating groups retain their original split and a placeholder when
a tab is torn out. Drag back into the reserved area to return. Releasing elsewhere removes
the placeholder and gives its space to the remaining panels. Escape restores the original
window, composition, selection, bounds, and main slot without saving an intermediate layout.

Moving the last panel out of a dock region removes the empty outer region as well
as its placeholder. Adjacent panels automatically expand into the released width
or height after the drop; the temporary return target remains during dragging.
