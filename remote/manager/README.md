# Defold Remote Manager

独立运行的桌面管理器。添加包含 `game.project` 的目录后，在 Android Remote 扫码配对电脑、选择项目连接。连接过程自动同步依赖、构建资源和原生扩展、启动后台游戏、取得控制权并开始串流，不需要手动启动构建服务器或导入产物。

0.2.6 在“设置 → Defold 引擎目录”中指定 Windows 安装文件夹或 macOS 的 `Defold.app`，
所有项目统一使用所选版本的资源编译器和引擎。选择前校验目录、平台、配套 PDB / macOS 后台桥接
及本地 SDK；无效选择不会修改原配置或中断游戏。选择相同内容的引擎保留会话和缓存；
版本改变时自动停止、重新准备并恢复原有启动意图。设置保存在数据目录，Manager 重启后重新校验。
源码扩展仍只使用对应的本地 SDK，不回退云构建；所选版本没有匹配 SDK 时明确拒绝。

0.2.6 同时将游戏构建兼容性与整个 Manager 版本分开：只更新连接、界面、取帧或输入组件，
不再让未变化的项目重新复制、执行 Bob 构建。兼容性包含编译器资源包、默认引擎/PDB、
GUI 迁移类、本地构建脚本和工具链配置；这些输入改变时仍重建。旧 receipt 可以通过保留的
旧运行包 manifest 核对后迁移；旧包缺失或不能证明兼容时重新准备。EXE/PDB 哈希校验保留。
首次启动、项目已保存资源改变、构建器或引擎改变仍需要构建；快速启动不是直接执行未编译资源。
Remote 触控边沿只等待一帧完整游戏更新，MCP 确定性操作继续等待两帧；输入代理采用定长响应，
两级 HTTP 服务关闭 Nagle 小包等待。兼容现有 Android 0.8.1。

0.2.2 修复 Windows 原生扩展引擎搬迁后的 PDB 查找：直接读取 EXE 旁的同名 PDB，
校验 GUID、age 和 ABI，不依赖 EXE 中残留的原始构建目录。导入成对复制 EXE/PDB，
receipt 记录两者哈希；自动构建缓存损坏时在本次连接中重建，导入产物损坏则明确报错。
兼容 Android 0.8.1，无需为本次修复重新安装 APK。

0.2.1 修复持续编辑时反复准备却不启动的问题：快照逐文件稳定复制并校验内容，其他文件
后来变化不再作废整份副本；新变化仍标记为后续更新。正在保存的文件可重试并保留启动意图，
Stop/cancel 与真实编译错误仍终止对应运行请求。Android 0.8.1 另外处理 ready/dirty/new
但未运行的状态，有限退避确保启动。两端应一起更新。

## 手机尺寸与音频（Manager 0.2.9 / Android 0.8.2）

手机连接取得控制权后先同步当前全屏窗口的像素尺寸、刘海和底部手势安全区，再开始串流。旋转时去重同步，释放旧输入并更新设备版本后恢复音视频。540p / 720p 等选项只控制传输清晰度，不改变游戏渲染尺寸。

游戏内取消左上角常驻按钮，四指下滑呼出控制栏；系统返回键仍可打开。控制栏分别显示设备尺寸和串流尺寸。尺寸同步失败会提示重试，不以旧尺寸继续预览。旧编辑器直连模式保留原有行为，自动尺寸同步用于独立 Manager 连接。

macOS Manager 复用现有引擎音频捕获接口，补齐安全环形缓冲区、PCM 端点和带控制权校验的 TLS 转发。单游戏只保留当前音频订阅，重新连接、旋转、接管、释放和停止会清理旧订阅；视频与音频独立传输。运行时引擎源码未改。Windows 音频能力仍未启用。

回归入口：`test_media.py <runtime/manifest.json>`，验证实际 GPU 游戏、两种手机尺寸、安全区、立体声频率、音视频并行、旋转和重连。更新 UI / 尺寸功能需要同时安装 Android 0.8.2。

## 自动构建（0.2.8）

- 直接以添加的游戏工程为 Bob `--root`，不再复制工程到 Remote 数据目录。
- 游戏资源输出为工程内 `build/default`，原生引擎输出为 `build/<平台>`，依赖仍放在 `.internal/lib`。后续 Bob 构建可以复用这些产物和增量缓存；编辑器仍会按自己的构建缓存规则校验资源，不保证跳过全部编译。
- Remote 的 receipt、进度、日志、原生 ZIP 缓存放在工程 `.internal/remote-manager`。每次编译结束清理原生任务临时目录，ZIP 缓存限 4 GiB / 30 天，编译日志只保留末尾约 12 KiB。
- Manager 数据目录仅管理项目登记、设备配对、运行会话和运行组件。移除项目、停止和清理不会删除工程源码或 `build`。
- 项目锁防止多个 Remote 实例同时编译/预览同一工程。预览运行时只标记源文件变更，停止后再更新产物。此锁不控制独立 Bob 命令或编辑器，二者不要与 Remote 同时写同一 `build/default`。
- 重新启动前校验源码内容、构建输出状态、引擎哈希和编译器兼容性。失败/取消前作废 receipt，禁止把未完成输出当作成功构建；下次继续利用 Bob 的增量缓存重试。
- GUI 源文件不再由 Remote 改写；旧格式由 Bob 的 GUI 插件在内存中迁移和验证。
- 本地原生扩展仍只请求临时回环服务，无云构建回退。打包时也会校验所选 Bob、真实 SDK 版本、核心库与引擎，避免交付不配套版本。
- 旧版本的 Remote 工程副本不再作为源码模式构建复用，确认没有活动进程引用后由已有缓存清理机制回收。

以下旧版本记录描述各版本当时的实现；0.2.8 的工程构建与清理规则以上述内容为准。

## 本地构建管理器

Windows 使用仓库的统一入口：

```powershell
python -X utf8 scripts/windows/editor.py remote-manager-build --app X:/Programs/Defold --output X:/AI/UI/deliverables/DefoldRemoteManager-0.1.2 --shell
```

macOS：

```sh
python3 remote/manager/build.py --app /Applications/Defold.app --engine /path/to/local/dmengine --output /path/to/RemoteManager --shell
```

macOS 的 `--shell` 同时生成 `Defold Remote Manager.app`，可直接双击启动。
应用与托盘使用 `icons/icon.png`；打包时通过 `sips` / `iconutil` 生成多尺寸
`DefoldRemote.icns` 并写入应用声明，无需构建 Android APK。
运行组件和配置放在 app 的 `Contents/Resources`，保留已有项目和配对配置。
构建前请在所选 Python 环境独立安装 `PyYAML`，例如
`env -u PYTHONPATH /path/to/venv/bin/python -m pip install PyYAML`。
构建器会用隔离模式检查依赖，避免只在终端的 `PYTHONPATH` 中可用而从 Finder
启动时报 `No module named 'yaml'`。构建时找到的 CMake/Ninja 路径也会写入配置。
原生扩展仅请求运行组件对应的本机架构；Apple Silicon 不会隐式请求 Intel SDK。

构建端需要 Java 25、Python/PyYAML、Rust 缓存和本机的引擎 CMake SDK。`build.py` 从现有 CMake 配置自动读取 SDK 路径，并把本机构建配置写入运行组件。当前开发交付包引用这台电脑的 Python、源码仓库和 SDK；不是可随意复制到无工具链电脑的完整 SDK 发行包。引擎 SDK 必须与 Bob 及后台桥接版本一致，缺少环境时返回明确错误，不上传源码、不伪造 SDK 版本。

原生构建目前覆盖默认 debug app manifest、C/C++、DDF proto、symbols/defines/includes、Windows `.lib`，以及 macOS `.a`、Objective-C/Objective-C++、frameworks 和本机签名。额外 manifest 配置不会静默忽略；未支持的配置作为具体构建错误返回。2026-10-29 已在 Apple Silicon 实测标准 app 启动、双游戏自动构建、配对/接管、触控回调、H.264 视频及独立环境的 printer 原生扩展编译。该验证不等于 Android 真机或全部第三方扩展验证。

串流坐标按平台处理：macOS 的引擎读回已经是左上角原点，不再纵向翻转；
Windows 保留原有翻转。`test_manager.py` 用非对称位置的橙色控件检查实际截图方向。

同次 macOS 验证还通过管理器窗口启动了 Path of Kings：在隔离副本中编译 Event
原生扩展和游戏资源，进入“运行中”，实际游戏缩略图方向正常。原工程内容不变，
既有项目列表保留。构建和回归日志位于 `tmp/remote-manager-macos-validation/`；
双游戏协议回归报告位于 `tmp/remote-manager-tests/38f1baa664244a6d81ae8180541d7a8c/report.json`。

Android 0.9.9 起，底部保留“游戏”和“设置”。点顶部电脑名称选择主机，点线路图标切换 Wi-Fi 或服务器。从右上角 ＋
进入扫码、相册识别或粘贴完整链接。配对只读取项目列表，选择项目后才自动准备与运行。
游戏页保留电脑筛选与搜索条件；返回列表只释放手机控制权，明确停止才停止所选游戏。
旧编辑器局域网游戏从“＋ → 发现编辑器游戏”进入。桌面端连接地址
为单选并保存；多 IP 时先选择地址，再生成二维码。二维码只包含选中的地址，
切换地址使旧二维码失效。同一电脑的旧版
HTTP 游戏发现卡片不再进入单独游戏配对流程；电脑配对凭据用于共享项目的启动和
控制权申请，切换项目或重连不需要再次输入游戏配对码。未配对的独立编辑器直连
仍使用原有游戏配对。电脑凭据不会迁移到 UDP 发现的 HTTP 地址。

Manager 0.2.0 的“连接手机”窗口默认使用紧凑 v3 二维码，完整保留 32 字节一次性凭据和
32 字节证书指纹，提供旧 APK 兼容二维码与完整 v2 链接。二维码按物理像素整倍数缩放，
保留白色静区；显示有效期、等待、配对中和已配对状态，接受配对后禁止刷新和切换 IP。
手机相机支持对焦、补光、缩放和相册识别；实际扫码识别率仍需 Android 真机对屏验证。

独立构建会在隔离副本中将旧 GUI `spine_scenes` 资源表转换为 `resources`，
与编辑器加载时的资源迁移一致，保留名称、路径、现有资源、节点和布局覆盖。
不修改原工程；旧节点类型和其他插件字段仍由对应插件迁移并校验，不跳过 Bob 校验。
依赖同步同时识别 `dependencies` 和 `dependencies#0` 等索引形式。
本地扩展构建固定使用已安装 SDK 的版本号，仍严格校验核心库；编辑器代码提交
不会单独改变扩展引擎的版本标识。真实核心库变更仍要求重新同步 SDK。

本次旧资源回归：Dragon Burst 的原工程保留 24 个 `spine_scenes`，管理器新建的
构建副本迁移为 24 个 `resources`；通过本地 Spine 扩展编译、SDK 核心库校验，
在管理器中实际点击运行成功并显示游戏主界面。迁移定向测试覆盖既有资源、节点、
稀疏布局覆盖及重复迁移；本地扩展单元测试 12 项通过。

## 验证

macOS 目录选择与快速启动回归：

```sh
python3 remote/manager/test_macos_fast_start.py <runtime/manifest.json>
```

使用隔离的管理器和真实 GPU 游戏，验证相同引擎内容保留会话、错误目录保持原配置、
目录设置持久化、重复启动、重启及仅 Manager 版本变化后复用构建。
2026-10-29 在 Apple Silicon 实测重复启动和重启后启动到画面均约 0.83 秒，
四次启动只生成一份构建；该时间针对测试工程，不代表所有游戏。
原生文件选择器也已实测选择 `/Applications/Defold.app` 并成功应用。
报告见 `remote/evidence/2026-10-29-macos-engine-directory-fast-start.json`。

Manager 0.2.5 将连续视频与 MCP 场景快照分开：共享头字 266 为 1 时只取像素，
避免在每个视频帧遍历节点并写入场景 JSON；显式 MCP `capture` 设为 0，继续
等待同帧的完整场景数据。仅在上一请求发布后切换视频模式，不改变请求确认规则。
macOS 现有桥接忽略该保留字，其画面／输入确认协议保持兼容；本轮未做 macOS 实机验证。
`RemoteCaptureModesTest` 用复杂原生 GUI 检查连续视频不改写场景文件，并验证
中途的 MCP 截图拥有匹配渲染序号的完整节点数据。

Manager 0.2.4 修复持续串流和快速点击：取帧只保留一个未完成的请求，等待引擎
发布对应场景与画面后再请求下一帧；按钮按下和抬起使用已有输入确认及渲染帧
确认，避免快速点击在一次 HID 更新内被抵消。持续拖动仍异步更新，不逐次等待。

```powershell
python -X utf8 remote/manager/test_streaming.py <runtime/manifest.json> --seconds 60
python -X utf8 remote/manager/test_streaming.py <runtime/manifest.json> --project-build <built-project> --seconds 60
```

第一项在 800 个附加 GUI 节点的真实游戏里持续收取 H.264，同时发送紧邻的按下／
抬起，逐次核对 Lua 业务回调。第二项只读导入真实工程构建，验证连续画面帧号和
最大帧间隔，不点击其业务内容。两项顺序执行，报告保存在 `tmp/remote-streaming-tests/`。
它们不代表实体手机无线网络验收。

Manager 0.2.3 修复真机连接在 TLS 握手前被反向 DNS 阻塞的问题。JDK HTTPS
服务器会查询连接方主机名；局域网手机没有可用 PTR 记录时，Windows 实测等待
超过 APK 的响应时限。独立 Manager 进程通过 Java 标准解析器 SPI 对其允许的
局域网地址直接返回数字 IP，正常域名解析仍交给系统。后台 worker 未启用此策略，
证书固定、公钥身份和配对授权均保留。打包器将 SPI 注册资源纳入运行组件哈希。
该实现跨平台；本次实机故障取证和打包验证为 Windows，未代替 macOS 或手机出画面验收。

```powershell
python -X utf8 remote/manager/test_resolver.py <runtime/manifest.json> --peer-ip <phone-ip>
```

此测试校验包内实际 SPI 注册、局域网不访问反向 DNS、正常域名委托，以及真实
HTTPS 在受控 DNS 阻塞尚未解除时仍完成握手。`--peer-ip` 可省略；提供时另以
独立 JVM 对比同一 IP 的原始和修复后查询耗时，不修改电脑 DNS 设置。

```powershell
python -X utf8 -m unittest patch.test_local_extensions
python -X utf8 remote/manager/test_manager.py <runtime/manifest.json> --extension <event-extension-directory>
python -X utf8 remote/manager/test_lifecycle.py <runtime/manifest.json> --project <project-directory>
python -X utf8 remote/manager/test_pairing.py <runtime/manifest.json>
```

`test_manager.py` 使用真实 GPU 游戏验证手机协议触发构建、两个独立会话、H.264、Lua 点击回调、原生模块加载、缓存、故意制造的 C++ 编译错误回传、修复后重连和设备接管。`test_lifecycle.py` 验证取消构建后子进程退出，并可只读复制实际工程进行启动和画面验证。报告和截图写入 `tmp/remote-manager-*`。

`test_pairing.py` 检查真实本机 TLS、紧凑/兼容内容一致、证书指纹、刷新失效与一次性凭据。
`RemoteCompactQrTest` 覆盖 v1/v2/v3 互通、严格参数校验及多尺寸静态二维码；
`RemotePairingStateTest` 检查已接受的配对尚未结束时禁止轮换凭据和切换地址。
`RemoteScanTest` 检查稠密、反色、旋转二维码、相册像素入口及相机预览/对焦坐标。

`RemoteSnapshotTest` 用真实文件 I/O 与受控编译/启动替身检查复制时变更、原子保存、
取消、启动意图和编译错误；它不代替实际游戏运行。`StartupWaitTest` 使用显式时钟检查
客户端的就绪补启动、错误保留、退避和总体截止时间。

真实工程及 APK 单击启动回归（原工程只读，管理器自行建立隔离构建副本）：

```powershell
python -X utf8 remote/manager/test_android_project.py <runtime/manifest.json> --project <project-directory> --adb <adb.exe> --serial emulator-5554 --expected-version 0.8.1
```

报告包含阶段时间线、真正新建的游戏 session、APK 画面与管理器画面。测试不从管理脚本
补发启动、不注入预编译游戏、不点击游戏业务按钮。测试专用的
`RemotePreparedOnlyTestHost` 可配合 `--main-class` / `--extra-classpath` /
`--require-prepared-only-restart` 验证第一次仅准备到 ready 后由 APK 自己再次发起启动；
该测试类不进入生产运行包。

Windows PDB 搬迁回归（只修改新建副本，原始构建路径在副本中改为未挂载盘符）：

```powershell
python -X utf8 scripts/windows/test_engine_symbols.py --hook <game_window_hook.dll> --engine <dmengine.exe> --legacy-hook <previous-game_window_hook.dll>
python -X utf8 remote/manager/test_engine_relocation.py <runtime/manifest.json> --project-build <already-built-native-project>
python -X utf8 -m unittest remote.manager.test_prepare
```

第一项覆盖配套 PDB、缺失、GUID/age 不符、损坏、并发和旧 DLL 对照。
第二项用实际项目的 EXE/PDB 及编译资源验证 Manager 导入、真实 GPU 画面和重启复用。
追加 `--previous-manifest <old-runtime/manifest.json>` 可验证跨版本缓存迁移，报告包含
两次启动耗时，并断言没有新增构建目录。`test_streaming.py <manifest> --seconds 30 --tap-hz 10`
在复杂 GUI 下验证快速按下/抬起的 Lua 回调数量，并记录 HTTP 输入往返耗时；不是手机到画面的端到端延迟。
`RemoteEngineArtifactsTest` 另用受控编译/启动检查 receipt、自动修复和导入错误路径。

Windows 回环协议测试不等于 Android 真机/Wi-Fi 显示性能验收；APK 本地编译与签名验证也不等于手机触控界面验收。

Android 客户端端到端回归（先在指定模拟器安装当前 APK）：

```powershell
python -X utf8 remote/manager/test_android.py <runtime/manifest.json> --adb <adb.exe> --serial emulator-5554
```

测试启动独立管理器，通过真实 APK 的紧凑 v3 和兼容 v2 链接入口完成配对、显示项目列表；
检查四 Tab、搜索保留、透明 TLS 代理断网/恢复、自动构建、视频首帧、触控 Lua 回调、
双游戏切换、单独停止，以及真实脚本编译错误后的显式修复重试。模拟器额外检查横竖屏，
结束时恢复其显示覆盖配置。测试只操作指定 Android 设备和自建后台会话，不操作已有桌面管理器。
截图和报告保存在 `tmp/remote-android-tests/`。模拟器链接测试不能代替实体手机摄像头扫码和实际 Wi-Fi 网络验收。


### Desktop Manager UI regression (2026-11-07)

桌面入口收敛为项目、运行中、设备、设置；项目详情、连接地址和版本按需展开。
预览与状态请求分离，同一时刻只请求一张预览；相同画面不重设图片，旧会话结果
不写入当前项目。活动任务每 1.5 秒检查状态，空闲时每 5 秒检查，窗口隐藏时暂停。

```sh
node --test remote/manager/test_ui.js
```

测试覆盖状态展示失效范围、请求合并、过期预览丢弃、隐藏恢复、错误恢复以及
切页时的操作按钮状态。原生检查使用隔离数据目录，验证目录选择、游戏预览和
窗口生命周期；浏览器检查覆盖深浅主题及 780×540 窗口，不代替真实手机扫码。

完整结果与范围见 [`2026-11-07-manager-simplification.json`](../evidence/2026-11-07-manager-simplification.json)。
本机 Bob 与原生 SDK 版本不一致，原生扩展成功构建路径尚未通过，不能据此声称
所有功能回归通过。此次未打包或安装用户版本。


### 构建缓存生命周期（2026-11-14）

Manager 的构建目录不是历史版本库。每个项目只保留登记的当前构建；正在运行的
旧版本、仍在退出的编译器和未完成的日志输出会继续保护其目录。新构建发布并
保存 registry 后，旧副本才可回收。失败、取消和移除项目不再留下完整工程快照。

内容 fingerprint 相同时复用已校验的构建，即使原子保存改变了文件标识或时间。
复用前的临时快照随后回收。SDK/磁盘等环境错误不会随每次资源编辑重复自动尝试；
修复环境后显式重试，或重新选择引擎。

会话结束后回收其共享内存文件和其他临时数据，每项目仅保留最近的构建与运行
日志尾部（各约 12 KiB）。原生 ZIP 缓存上限为 4 GiB、保留期限为 30 天；不再
被选中或被当前构建/运行会话引用的引擎副本也会回收。正在使用的资源不参与淘汰，
因此 4 GiB 不是整个 Manager 数据目录的总容量上限。

清理在串行构建队列执行，并在启动、构建结束、停止、移除、切换引擎和退出时
触发。数据目录独占锁阻止两实例互相回收。子进程身份记录用于崩溃恢复；无法
识别的运行记录会暂停回收并输出错误。清理仅处理受管理目录内已知格式的子项，
不跟随符号链接，也不修改源工程。创建副本前检查空间，并保留至少 2 GiB 余量；
此检查不保证其他进程随后不会占用磁盘。

回归：`RemoteStorageTest`（真实文件系统边界/容量/期限），
`RemoteCacheLifecycleTest`（连续编辑、内容复用、失败/取消/移除、重启、活动资源
保护、损坏记录恢复、目录互斥，以及真实受控构建父子进程退出），并保留原
`RemoteSnapshotTest`。这些测试不需要 SDK 编译，不代表原生扩展或 GPU 整体回归。
结果见 [`2026-11-14-manager-cache-lifecycle.json`](../evidence/2026-11-14-manager-cache-lifecycle.json)。

### 原工程构建验证（0.2.8，2026-11-14）

已在 `/Applications/Defold Remote Manager.app` 实测 Path of Kings 原目录编译与游戏预览，
产物为游戏自己的 `build/default` 和 `build/arm64-osx/dmengine`。项目、设备配对保持。
原生库解压缓存也按所选编译器 JAR 内容分开，避免固定 `remote-manager-v1` 复用旧库；
无活动会话时回收其他已识别版本。未知目录不参与回收。

当前回归覆盖共享工程失败/取消/重启/外部输出变更/并发保护 37 项、存储边界 40 项、
旧缓存回收与真实编译子进程取消 20 项、旧快照工具 11 项、SDK 与准备流程 18 项。
原生/GPU 集成通过 41 项；其后新增原生库版本隔离通过定向回归，并在最终安装版实测真实工程画面。
本轮没有重新运行 UI Node 套件：本机 Node 缺少 simdjson 动态库，UI 源码本轮未改。
详细范围见 [`2026-11-14-manager-inplace-build.json`](../evidence/2026-11-14-manager-inplace-build.json)。
