# Defold Remote Manager

独立运行的桌面管理器。添加包含 `game.project` 的目录后，在 Android Remote 扫码配对电脑、选择项目连接。连接过程自动同步依赖、构建资源和原生扩展、启动后台游戏、取得控制权并开始串流，不需要手动启动构建服务器或导入产物。

## 自动构建

- 原工程只读；编译和运行使用管理器数据目录中的独立副本。
- 未变化的项目复用已构建资源。原生引擎另按平台、SDK、构建器和扩展源码内容缓存；只改 Lua/GUI 时可复用原生引擎。
- 项目依赖下载使用 Bob 的 resolve。扩展编译请求只发送到管理器临时启动的 `127.0.0.1` 服务，没有云构建回退。
- 本地 CMake/编译器由管理器负责启动和清理，多个构建器通过文件锁串行访问同一 CMake 目录。
- 关闭窗口仅收起到托盘；显式退出停止游戏和正在进行的构建。Windows 构建使用拥有整个子进程树的 Job Object。
- 构建错误通过项目状态同步到电脑和已配对手机，包含 `failed_stage`、`error_kind`、`error`。项目报错、依赖失败、工具链环境问题分别标记；修复后重新连接即可自动重试。
- 更新运行资源时，正在运行的游戏保留旧版本；新产物下次启动生效。

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

Android 0.7.4 起，首页分为“扫码电脑”和“自动发现”两个分页。已配对电脑和 HTTPS
共享项目从“扫码电脑”进入，独立局域网游戏在“自动发现”中显示。桌面端连接地址
为单选并保存；多 IP 时先选择地址，再生成二维码。二维码只包含选中的地址，
切换地址使旧二维码失效。同一电脑的旧版
HTTP 游戏发现卡片不再进入单独游戏配对流程；电脑配对凭据用于共享项目的启动和
控制权申请，切换项目或重连不需要再次输入游戏配对码。未配对的独立编辑器直连
仍使用原有游戏配对。电脑凭据不会迁移到 UDP 发现的 HTTP 地址。

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

```powershell
python -X utf8 -m unittest patch.test_local_extensions
python -X utf8 remote/manager/test_manager.py <runtime/manifest.json> --extension <event-extension-directory>
python -X utf8 remote/manager/test_lifecycle.py <runtime/manifest.json> --project <project-directory>
```

`test_manager.py` 使用真实 GPU 游戏验证手机协议触发构建、两个独立会话、H.264、Lua 点击回调、原生模块加载、缓存、故意制造的 C++ 编译错误回传、修复后重连和设备接管。`test_lifecycle.py` 验证取消构建后子进程退出，并可只读复制实际工程进行启动和画面验证。报告和截图写入 `tmp/remote-manager-*`。

Windows 回环协议测试不等于 Android 真机/Wi-Fi 显示性能验收；APK 本地编译与签名验证也不等于手机触控界面验收。

Android 客户端端到端回归（先在指定模拟器安装当前 APK）：

```powershell
python -X utf8 remote/manager/test_android.py <runtime/manifest.json> --adb <adb.exe> --serial emulator-5554
```

测试启动独立管理器，通过真实 APK 的二维码链接入口完成配对、显示项目列表；使用透明 TLS 代理模拟网络中断，检查列表自动恢复、连接重试、自动构建、视频首帧和触控 Lua 回调。测试只操作指定 Android 设备和自建后台会话，不操作已有桌面管理器。截图和报告保存在 `tmp/remote-android-tests/`。二维码图像解码另由 `RemoteLogicTest` 验证；模拟器链接测试不能代替实体手机摄像头扫码和实际 Wi-Fi 网络验收。
