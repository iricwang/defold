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

构建端需要 Java 25、Python/PyYAML、Rust 缓存和本机的引擎 CMake SDK。`build.py` 从现有 CMake 配置自动读取 SDK 路径，并把本机构建配置写入运行组件。当前开发交付包引用这台电脑的 Python、源码仓库和 SDK；不是可随意复制到无工具链电脑的完整 SDK 发行包。引擎 SDK 必须与 Bob 及后台桥接版本一致，缺少环境时返回明确错误，不上传源码、不伪造 SDK 版本。

原生构建目前覆盖默认 debug app manifest、C/C++、DDF proto、symbols/defines/includes、Windows `.lib`，以及 macOS `.a`、Objective-C/Objective-C++、frameworks 和本机签名。额外 manifest 配置不会静默忽略；未支持的配置作为具体构建错误返回。macOS 路径已接入，仍需 Mac 实机验证。

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
