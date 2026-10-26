# 后台 Game MCP（Windows 已验证，macOS 适配待实机验证）

本版提供可运行的 **stdio MCP 服务 + 独立 Java 会话 worker + 本地编译的原生桥接**。
后台游戏使用真实 OpenGL 渲染，不需要打开 Game 面板，不移动桌面鼠标，不激活窗口。
运行时工具无需重打编辑器安装包；编辑器图接口需要启动含本次 `/automation/v1` 路由的编辑器。

## 已实现

- 注册指定项目，查询实际能力；所有项目访问受启动参数约束。
- 在独立目录冻结磁盘资源并本地构建；相同资源的后续构建命中会话内缓存。
- 导入已有 `build/default` 和项目自己的 `dmengine.exe/.pdb`，支持保留 Spine 等原生扩展。
- 创建/停止独立隐藏游戏；最多两个并发会话；worker 异常退出由 Windows Job Object 回收游戏。
- 使用现有设备目录，按方向解析尺寸及安全区；调整后返回新帧。
- 点击、移动、拖拽、滚轮、按键和文字；租约、有限动作、输入回执、重复请求去重。
- 原始 GPU PNG、帧序号、输入水位、设备版本、SHA-256、聊天内缩略图及 MCP 原图资源。
- 从已有原图裁剪局部 UI，返回到原图的坐标偏移；不会重新截取另一个时刻的画面。
- 两张原图进行 RGB 像素比较，支持检查区域、忽略区域、通道差阈值及变化比例阈值；报告并列显示前图、后图和差异图。
- 查询运行时节点；读取日志、按动作前游标等待业务日志；导出离线 HTML/JSON 报告。
- 报告记录操作回执和匹配的业务日志，区分输入派发、像素变化和业务证据。
- JSON 后台用例：一次构建、逐台设备独立运行、业务日志断言、节点数量断言、画面对比、失败现场和取消清理。
- 按资源路径构建并运行 GUI、GO、Collection（包括嵌套 Collection），所有临时启动文件只写入独立副本。
- 从已冻结的完整构建派生单资源预览，保留原生扩展引擎，无需重新编译扩展。
- 本版包含 40 个 MCP 工具；实际列表以 `tools/list` 为准。

## 本地准备

在仓库根目录执行。依赖放到仓库临时目录，不修改系统 Python 包：

```powershell
python -m pip install --target tmp/automation-python -r scripts/automation/requirements-lock.txt
python -X utf8 scripts/windows/editor.py automation-build --app X:\Programs\Defold
```

需要 Python 3.12+、Java 25 编译器和 Windows C 编译器。构建入口复用既有 native 编译流程。
默认使用 PATH 中的 `javac`，找不到时查找本机 Eclipse Adoptium Java 25；运行使用安装包中的 Java。
构建清单位于 `tmp/automation-runtime/manifest.json`，包含 Java/native/引擎/PDB 哈希。
源码或安装 JAR 发生变化后重新执行 `automation-build`，命中缓存时不重复编译。

本机已准备好的示例配置在 `mcp.local.json`。通用 MCP 客户端配置形状：

```json
{
  "mcpServers": {
    "defold-background": {
      "command": "C:/Python313/python.exe",
      "args": [
        "-X", "utf8", "X:/AI/UI/defold/scripts/automation/server.py",
        "--project", "X:/AI/UI/defold/scripts/automation/fixture",
        "--project", "X:/AI/Copy/apk_analysis/defold_game"
      ]
    }
  }
}
```

`--project` 可以重复，只注册需要操作的项目。启动服务是 stdio 协议会话，不能当普通交互命令行输入。
运行时工具由客户端启动服务即可；编辑器图接口通过项目的本机认证 HTTP 适配器访问，不使用 eval 或 REPL。
配置示例不会自动修改任何客户端的全局配置。

## macOS 接入状态

已经加入 macOS `.app` 路径、Apple Silicon/Intel 平台选择、无 Windows 启动标志的 worker、
原生共享输入确认与帧标记，以及独立的本地 `kqueue` 退出监护/loopback 监听 guard。
Python 依赖中的 `pywin32` 只在 Windows 安装。
**当前仅通过 Windows 回归和平台逻辑单测；尚未在 Mac 编译或运行，不作为已验证交付。**
按本轮要求先继续 Windows，Mac 上的隐藏窗口、输入、安全区、guard 加载及崩溃回收均待实机验证。

Mac 必须使用从本 checkout **本地编译**的 debug GPU `dmengine`，不能使用官方旧引擎、
`dmengine_headless` 或 `dmengine_release`。含 Spine 等扩展的工程也需要对应的本地定制引擎。
普通旧版 editor bridge 扩展没有本次协议，不会被当作支持自动化的引擎。
先按仓库的 `scripts/cmake/README.md` 准备本地依赖并构建 `dmengine`，再执行：

```sh
python3.12 -m pip install --target tmp/automation-python -r scripts/automation/requirements-lock.txt
python3.12 scripts/automation/build.py --app /Applications/Defold.app --engine /absolute/path/to/local/dmengine
python3.12 scripts/automation/test_core.py
```

构建使用 `JAVA_HOME`/PATH 中的 Java 25 编译器、Xcode clang 和本地 ad-hoc codesign。
MCP 配置沿用上面的形状，将 Python、server.py 和项目路径替换为 Mac 上的绝对路径。
manifest 必须在 Mac 本机生成，服务会拒绝加载其他平台的 manifest。
目前 `test_background.py`/`test_lifecycle.py` 的 OS 检查仍面向 Windows，Mac 实机验收需继续接入。
这些命令不上传源码，也不打编辑器安装包。

## 调用顺序

1. `defold_project_discover` 获得项目 ID 与能力。
2. 普通项目使用 `defold_build_run`；含原生扩展的现有项目先在本地编辑器完成构建，再使用 `defold_build_import`。
3. `defold_job_status` 等待构建完成，得到 `build_id`。
4. `defold_session_start` 指定 `build_id` 和设备，等待 job 完成，取得 `session_id` 和 `lease_id`。
5. `defold_capture` 取得图片和 `observation_id`；查询日志游标。
6. `defold_input_perform` 携带该截图、租约、唯一 `operation_id` 和动作。
7. `defold_wait` 从动作前日志游标等待业务标记；返回新的截图。
8. `defold_report_export` 输出报告，`defold_session_stop` 释放游戏。

输入示例：

```json
{
  "session_id": "启动返回的 session_id",
  "lease_id": "启动返回的 lease_id",
  "operation_id": "login-click-001",
  "observation_id": "截图返回的 observation_id",
  "action": {"type": "click", "x": 320, "y": 240}
}
```

坐标是原始截图像素，左上原点。响应的 `inline_preview.to_frame_scale` 用于将聊天内缩略图坐标换回原图。
设备变化后旧截图会被拒绝。同一 `operation_id` 同参数返回原回执，不能为超时请求盲目生成新 ID 重试。
租约 60 秒，`defold_input_renew` 续租；每个手势完成时都释放按键，不提供无限期按住接口。
启动时可传 `project_config`，只接受 `project.*` 字符串选项，用于项目已有的 QA 入口。例如当前 Dragon Burst 工程的 `{"project.smoke_test":"1"}` 使用测试存档并进入自动烟测；该选项的业务意义由游戏定义。
按键采用原有 Game 桥接索引：32..127 为字符键，128 为 Esc、129/130/131/132 为上/下/左/右、133/134/135 为 Shift/Ctrl/Alt、136 为 Tab、137 为 Enter、138 为 Backspace，后续为 Delete/Home/End/PageUp/PageDown/F1..F12。

## 局部查验和画面对比

`defold_capture_region` 使用原图的 `artifact_id` 和 `{x,y,width,height}` 区域。
返回 `observation_id` 仍指向原始帧，`to_frame_offset` 是裁剪区域左上角。
从聊天缩略图选点时：`原图坐标 = 缩略图坐标 × inline_preview.to_frame_scale + inline_preview.to_frame_offset`。
输入工具要求原始帧 observation，拒绝将裁剪图或差异图的 artifact ID 当成原始观测。

`defold_image_compare` 示例：

```json
{
  "before_id": "操作前的原图 artifact_id",
  "after_id": "操作后的原图 artifact_id",
  "channel_threshold": 8,
  "max_changed_ratio": 0.005,
  "ignore": [{"x": 10, "y": 10, "width": 100, "height": 30}]
}
```

比较只接受同设备尺寸和安全区的原图，不偷偷缩放。可选 `region` 限制比较范围；
`ignore` 最多 16 个区域，重叠区域不会重复扣除。RGB 任一通道差 **大于** `channel_threshold`
计为变化像素；变化比例不超过 `max_changed_ratio` 返回 `match`，否则返回 `different`。
alpha 不参与比较，差异图粉色显示变化像素。动画、粒子和时间文字可用忽略区域排除。
`match` 只表示满足像素阈值，不表示布局正确或业务成功；应继续查验实际画面和业务日志。
产物仍是本次 MCP 服务内的 ID；跨服务启动的基线导入尚未支持。

## 指定资源后台预览

1. `defold_resource_search` 传入 `project_id`，可选 `query`、`types`（gui/go/collection）、`offset` 和 `limit`，查询已保存资源。
2. `defold_preview_build` 传入 `project_id`、`resource_path`（例如 `/gui/login.gui`）、唯一 `operation_id`。
3. 用 `defold_job_status` 等待构建，得到 `build_id`，然后沿用 `defold_session_start` 或 `defold_scenario_run`。
4. 截图的 `preview` 字段记录资源路径、类型、启动入口和限制；仍支持输入、日志、设备切换及画面对比。

Collection 直接成为启动入口，保留引擎原有层级处理。GUI/GO 在副本中生成临时 Collection，
GUI 挂在临时 GO 上，GO 通过原始 prototype 在原点实例化。使用 Bob 原有 `--settings` 覆盖副本构建的启动入口，
不改写原项目的 `game.project` 或资源文件，也不打开编辑器页签。

这是**指定资源的真实运行预览**。资源脚本正常执行，沿用项目的 render、display、input 配置；
原游戏入口及其管理服务不会自动启动，也不会额外添加相机或移动 GO 来填满画幅。
依赖外部管理器、摄像机、其他场景消息或存档的资源需要自行提供预览环境/QA 入口。
此磁盘预览入口只读已保存内容；未保存 GUI 使用下文 `editor_snapshot` / `editor_preview_build`。

预览构建缓存以磁盘资源指纹与预览路径分别匹配；预览某个资源不会污染完整游戏的构建缓存。
当前仅支持项目目录内已保存的普通资源；库内资源、符号链接和目录联接不支持。
含原生扩展的项目不支持单资源源码编译，会在调用编译器前明确拒绝；可按以下流程使用**已有本地编译结果**。
不会上传源码编译。

### 原生扩展 / 已编译资源预览

1. 在本地编辑器完成游戏构建，用 `defold_build_import` 冻结 `build/default` 和匹配的引擎/PDB。
2. 用 `defold_preview_import` 传入这个完整构建的 `build_id`、`resource_path`、唯一 `operation_id`。
3. 等待 job 完成，将返回的新 `build_id` 交给 `defold_session_start` 或 `defold_scenario_run`。

也可以从 `defold_build_run` 的完整构建派生。预览构建不能再次作为派生基础。
同一完整构建可以预览不同资源；相同基础构建和资源路径再次请求时复用缓存。
派生入口只在服务自有的冻结资源目录中新增唯一包装资源，通过启动参数覆盖 bootstrap；不复制整份资源，也不改现有资源或配置。
重复导入会校验内容哈希：完全相同则复用；有修改时仅复制变化资源，未变资源优先链接服务自有的不可变副本，不链接用户的构建目录。不支持硬链接的文件系统会退回复制。缓存当前限同一服务进程；创建新的游戏仍有启动开销。

Collection 直接使用已有 `.collectionc`，保留已编译层级。
GUI/GO 使用安装 JAR 的官方 protobuf 类生成最小 `.goc/.collectionc` 包装，组件容量沿用项目配置的动态上限，
不猜测扩展类型或重新生成业务资源。副本只覆盖 `game.projectc` 的启动入口，保留 render/display/input 配置。
资源必须在完整构建的散装产物中存在；尚未被构建的资源会明确报错，归档包内资源暂不支持。

元数据及截图标记 `source_policy: existing_compiled` 和 `base_build_id`（构建信息），
不会把未重新编译的源码或未保存修改当成最新结果。依赖完整入口服务的 GUI/GO 仍需提供自己的预览环境；
例如单独启动 Dragon Burst 的 GUI 不会自动创建原 Collection 中的 `/audio` 对象。

已有 JSON 用例也可用于资源预览：

```powershell
python -X utf8 scripts/automation/run_scenario.py --project scripts/automation/fixture --resource /main.gui --scenario scripts/automation/fixture/scenario.json
```

已有编译结果也可以直接运行：

```powershell
python -X utf8 scripts/automation/run_scenario.py --project path/to/project --source existing_compiled --resource /main/main.collection --scenario path/to/scenario.json
```

根据实际资源调整用例中的坐标和业务日志标记。

## 可重复后台用例

示例文件：`scripts/automation/fixture/scenario.json`。从仓库根目录执行：

```powershell
python -X utf8 scripts/automation/run_scenario.py --project scripts/automation/fixture --scenario scripts/automation/fixture/scenario.json
```

命令通过真正的 stdio MCP 服务执行，后台构建一次，再运行桌面、手机竖屏、手机横屏三个独立游戏进程。
进度写到 stderr，最终 JSON 包含汇总报告路径。断言全部满足时退出码为 0，失败为 1，Ctrl+C 取消为 130。
`--source existing_compiled` 导入项目已有构建，适用于已经本地编译好原生扩展的项目。
`--output` 指定结果目录；默认使用 `tmp/automation-runs/<id>`，不重打编辑器安装包。

智能体通过 `defold_scenario_run` 传入已完成的 `build_id`、`scenario` 对象和唯一 `operation_id`，
再用 `defold_job_status` 看进度。重复请求返回同一个 job，避免重复点击。
job 的 `complete` 只表示用例执行结束，必须检查 `result.state` 是否为 `passed`。
`defold_scenario_get` 查询汇总；指定 `case_index`、`offset`、`limit` 分页读取步骤（每页最多 8 步）。
完整步骤、截图、日志和差异结果保存在磁盘 JSON/HTML；MCP 返回紧凑摘要。

用例版本 1 支持最多 8 台设备、每台最多 32 步：

| kind | 用途 |
| --- | --- |
| `capture` | 以唯一名称保存原图，供后续步骤引用 |
| `input` | 执行有限点击、拖动、移动、按键、文字或滚轮；可用 `expect_log` 等待业务证据 |
| `wait_log` | 从 `startup`、`last_input` 或 `last_wait` 游标之后等待日志 |
| `scene_assert` | 以 id/type/text 子串查询独立实时节点样本，断言匹配数量范围 |
| `compare` | 比较此前命名截图，断言 `match` 或 `different`，支持忽略区域和阈值 |

`input.coordinate_space` 必填：`frame_pixels` 为原图像素；`frame_normalized` 为整个画幅的 0..1 坐标，
端点映射到第 0 和第 width/height−1 个像素。它不自动寻找 GUI 节点，也不保证按钮随布局变更仍在同一相对位置。
每次输入前自动捕获新帧并记录日志游标，`expect_log` 可以捕获立即发生的回调；当前关联级别为 `cursor_only`，
不是业务层 operation ID 精确关联。`passed` 仅说明声明的步骤和断言通过。

单台失败后保存可取得的现场图和日志、关闭游戏，继续下一台。取消时停止等待，在当前有限操作结束后清理游戏，
余下设备标记为 skipped；已经发生的业务行为不会撤销。尚未启动的 queued job 取消时没有运行现场。
每台设备有独立进程，但**不会自动清理游戏存档**，实际项目仍应使用 `project_config` 接入自己的 QA 模式。
用例 DSL v1 仍采用上述步骤；新增节点操作、布局断言和关联回执可通过 MCP 工具组合。macOS 实机验收未进行。

## 数据与验证边界

- `render_sequence` 是原生窗口提交序号，不冒充引擎 simulation frame。
- `scene_query` 保留独立实时样本语义。Windows `capture.scene_snapshot.supported=true` 时，新节点快照在 render 后、PostUpdate 前生成，与指定 GPU 帧一起发布；需要精确匹配的 PDB 和 ABI 检查通过。检查不通过会保留截图并拒绝精确几何操作。
- Windows 输入回执为 `window_dispatch`；macOS 适配使用 `hid_snapshot`，均不表示业务已完成。测试样例使用 `BUTTON_CLICKED`、`DRAG_COMPLETE` 等标记。
- 未保存 GUI 快照、现有节点基础布局属性事务、受保护撤销/重做已支持。GO/Collection 未保存图快照、原生扩展项目的未保存源码重建、运行中热更新和 attached 会话尚未实现。自定义 render、文字字形、非矩形/反向裁剪和像素遮挡判定不返回猜测几何。
- 设备安全区实际传入运行时；异形屏遮挡图形未叠加到原始游戏截图。游戏仍按自己的 render script 和 GUI 适配规则渲染。
- 后台进程不是业务存档沙箱；真实项目的存档、网络服务需要自己的 QA 配置。本次自动测试使用无外部业务的独立样例。
- 资源构建在副本执行，不保存用户 dirty 文件。原生编译服务固定为不可用的本机地址，不能回退上传。
- `build_import` 读取已编译内容，不保证与尚未构建的源码一致；导入过程检测文件变化，报告记录资源及引擎指纹。
- 第一版没有集成官方 Automation Bridge 的二进制。已研究版本为 `941fc93f8b9eb6fa81ca9d70df892ed0ad689d0c`；当前实现复用本 fork 的原生窗口桥接和引擎场景接口，后续单独验证官方模块集成。

## 结果保存与测试

默认产物目录：`tmp/automation-artifacts/`。每次捕获生成原图和 JSON 元数据。
每个会话最多 256 个图片产物（含裁剪和差异图），达到上限时显式报错；报告引用的文件不随游戏退出删除。
操作 journal 阻止服务重启后误重放旧操作；旧进程的会话和工具内存索引不跨重启恢复，磁盘报告仍可查看。

```powershell
python -X utf8 scripts/automation/test_core.py
python -X utf8 scripts/automation/test_background.py
python -X utf8 scripts/automation/test_lifecycle.py
python -X utf8 scripts/automation/test_scenarios.py
python -X utf8 scripts/automation/test_scenario_runtime.py
python -X utf8 scripts/automation/test_resources.py
python -X utf8 scripts/automation/test_preview_runtime.py
python -X utf8 scripts/automation/test_preview_runtime.py --compiled
```

第二条使用真正的 MCP SDK 客户端，经 stdio 启动服务、本地构建测试工程并运行隐藏 GPU 游戏。
验证原图哈希、按钮业务日志、拖拽、键盘/文字/滚轮、两种手机方向、安全区、旧坐标拒绝、幂等、缓存、仅本机监听和退出清理。
原图与报告位于 `tmp/automation-verification/<run-id>/`，终端输出本次准确路径。
第三条额外验证已有编译资源的导入，以及强制终止 worker 后游戏进程被操作系统回收。
用例相关的两条测试验证预检、坐标边界、失败继续、取消清理，以及真正的 MCP 三设备用例、错误断言、业务超时和失败图片资源。
资源测试验证路径隔离、临时入口、原生扩展拒绝，以及 GUI/GO/Collection/嵌套 Collection 与完整项目启动的受控像素对照、按钮回调、缓存隔离和原文件不变。
`--compiled` 额外验证官方 protobuf 包装、已编译资源的缓存隔离、基础配置不变、缺失产物和重复派生拒绝。
实际 Dragon Burst/Spine 工程可显式运行 `test_native_preview_runtime.py --project <本地已编译工程>`，
它使用工程已有 QA 存档开关，交付完整游戏、Collection 和独立 GUI 的原图/对照指标/日志，显式加入 `/main/audio.go`，校验独立 GUI 的音频依赖、原图一致性、零复制派生和缓存性能。

后台捕获不依赖桌面截图。样例项目按默认 FIT 渲染，因此窄屏/宽屏存在留白，这是样例原始运行画面，不是工具重新排版。

本机另已验证 Dragon Burst 的已有编译资源和 Spine 引擎：使用 `project.smoke_test=1` 测试存档入口，以 F3 进入战斗参考场景，后台执行拖拽并交付前后图。战斗入口由 `REFERENCE_READY` 确认；拖拽仅记录原生派发/释放和画面变化，不宣称已有游戏业务 ACK。本机本轮约一万个文件、492 MiB 首次冻结为 59.5 秒，同内容重新校验导入为 4.6 秒；独立入口无需再次复制资源。这是本次测试值，不是性能保证。

## 同帧节点、点击和布局检查

- `defold_element_query(artifact_id, selector)`：精确匹配 `id` / `element_id` / `type` / `text`，分页读取节点。
- `defold_element_click(session_id, lease_id, observation_id, selector, operation_id)`：重新捕获并定位同一节点，只点击已验证的可见矩形交集；歧义、已销毁、隐藏和未知几何会拒绝。
- `defold_layout_check(artifact_id, rules)`：支持 `inside_viewport`、`inside_safe_area` 和显式指定 `other` 的 `no_overlap`。返回 passed / failed / unknown，不把未知当通过。
- 矩形来自实际 GUI world matrix，处理 pivot、父子变换、矩形裁剪、透明继承。仅默认 GUI render 投影；纹理透明像素、自定义材质效果和其他节点遮挡不包含在矩形判定中。新帧定位与输入之间仍有运行时间，快速移动目标应使用业务 ACK 确认结果。
- `defold_device_overlay(artifact_id, name, rotation)` 创建异形屏矩形遮罩与安全区标线的派生图；必须与原图设备参数一致。遮罩按目录提供的矩形绘制，不伪造曲线轮廓；原始 GPU PNG 不变。

```json
{"artifact_id":"原图 ID","rules":[
  {"kind":"inside_safe_area","selector":{"id":"login_button"}},
  {"kind":"no_overlap","selector":{"id":"login_button"},"other":{"id":"cancel_button"}}
]}
```

## 声明预览依赖

`defold_preview_import` 可传 `support_objects`（最多 32 个）和 `instance_id`：

```json
{"build_id":"冻结构建 ID","resource_path":"/main/game.gui","instance_id":"preview",
 "support_objects":[{"id":"audio","resource_path":"/main/audio.go"}],"operation_id":"gui-with-audio"}
```

Collection 保留原有层级并添加依赖 GO；重复 ID 会拒绝。依赖应由工程明确列出，工具不会猜测全局管理器或自动执行原 bootstrap。

## 业务 operation ID 回执

显式把 `sdk/automation.lua` 集成到 QA/debug 工程，在一个长期存活的脚本调用 `qa.update(dt)`，初始化调用 `qa.ready()`。创建会话时传 `app_channel:true`，否则 SDK 不启用。

```lua
local qa = require "automation"
-- init
qa.register("screen_state", function() return {screen = self.screen} end)
qa.ready()
-- update
qa.update(dt)
-- 真正业务成功时，例如提交按钮完成校验后
qa.ack("login_submitted", {screen = self.screen})
```

`defold_application_command` 只调用显式注册的函数，不支持 eval。
`defold_element_click_verified` 先把操作 ID 交给 QA SDK，再按节点点击，等待相同 ID 和指定 event；成功返回 `ack_level:application_correlated`。
错误事件/超时会失败，但已经发生的点击不会撤销，也不会自动重试。会话内 SDK 去重最多 4096 个命令，达到上限后停止接受新命令。
不要在通用 on_input 派发处直接发成功 ACK；要从真实业务完成处调用。现有 Dragon Burst 的普通拖拽日志仍是派发证据，没有被冒充为业务 ACK。

## 未保存 GUI 与编辑事务

需要含本次编辑器代码的运行实例；连接读取已注册项目 `.internal/photocraft/editor-adapter.json` 和 `.internal/editor.token`，仅访问 loopback、禁止重定向，不输出 token。

1. `defold_editor_snapshot(project_id,path)`：返回未保存 GUI 源码、节点属性、`editorSessionId`、`editorGraphRevision`、`historyRevision`。不打开/选择页签、不保存。
2. `defold_editor_gui_edit`：携带上述 session、`expectedRevision`、`expectedHistory` 和 `changes:[{id,property,value}]`，应用一个原生撤销事务。
3. 支持现有节点基础布局的 position / rotation / scale / manual_size / text / enabled / visible / pivot / x_anchor / y_anchor / adjust_mode / size_mode / alpha；按原生接口检验可写性，size 在自动尺寸模式下可能只读。控制属性需先独立提交，例如先切 manual size_mode 再修改 manual_size。第一版不创建节点、不重设父子关系、不修改命名布局。
4. `defold_editor_history` 传 `transactionId`、action=undo/redo 及最新快照 guard，只操作尚未被其他编辑穿插的最上方智能体事务；否则报冲突。
5. `defold_editor_preview_build(project_id,resource_path,include_gui_paths?)` 把最多 32 个明确指定的 GUI 图快照覆盖到隔离源码副本，然后本地构建。其他依赖来自磁盘，不会暗中保存用户文件；不同快照期间编辑器历史变化会拒绝。
6. 用返回的 build_id 新建后台游戏并截图。已运行游戏不会被自动热更新；原生扩展源码重建仍明确拒绝。

真实编辑器图测试位于 `editor/test/integration/automation_editor_test.clj`。设 JVM 属性 `defold.automation.python` 为已准备 MCP 依赖的 Python 可执行文件后，会额外运行 `test_editor_runtime.py` 完整链路，不创建可见 Stage。
本机验证：未保存编辑产生 2862 个像素差异，撤销恢复后差异为 0，源文件哈希不变。

新增回归：`test_advanced.py`（几何、冲突、内容缓存、关联回执），`test_layout_runtime.py`（同帧动态像素对照、裁剪、错误回执/过期节点、横竖屏标注）。
