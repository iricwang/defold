# Remote 公网预览

Remote Manager 的“设置 → 公网连接”使用现有 CodexCamp 账号登录，点击“授权手机”生成一次性邀请。在 Android 顶部加号左侧选择云端图标，用同一账号登录并粘贴邀请，随后选择电脑和游戏。选择 Wi-Fi 图标则继续使用局域网配对；模式会被记住，切换前会释放当前连接。

游戏中使用四指下滑显示控制面板。公网模式会同步手机真实像素和安全区域，旋转后重新同步并更换视频轨道。公网预览可选 540p、720p、1080p，分别限制传输长边为 960、1280、1920，保留原始宽高比且不放大原图；游戏渲染分辨率不会随之缩小。默认 720p。

## 组成

- `remote/manager/java/.../RemoteCloud.java`：账号接入、设备租约、请求去重、控制权与断线释放。
- `remote/transport`：独立 Rust LiveKit 0.11.0 进程；从私有 loopback sidecar 读取 H.264/PCM，发布 H.264/Opus。父子进程管道只传命令，不传 Base64 视频。
- `remote/cloud`：随 APK 离线打包的 LiveKit 2.24.1 WebRTC 接收视图。Android 保留原生导航、真实尺寸和四指手势，限制 WebView 导航与设备权限。
- `RemoteRuntime` 与 `remote/native/remote_video.m`：仅在公网 profile 下使用 1.2–1.8 Mbps、10–30 fps，并响应关键帧及拥塞反馈。局域网保持原有编码设置。

本次不修改运行时引擎源码。当前公网发送端完成 macOS 实现；Windows 原生编码器尚未接入低码率配置，不提供未验证的高码率回退。

## 构建

先运行 `remote/native/build-macos.sh`，再构建 `remote/transport/Cargo.toml --release`。SDK 的 WSS 使用 `rustls-tls-webpki-roots`，macOS 链接必须保留 `-ObjC`，否则 Apple 编码器的 NSString category 会被丢弃。

向 `remote/manager/build.py` 提供 `DEFOLD_REMOTE_TRANSPORT`（优化版传输程序绝对路径），以及已有的匹配 `--app`、`--engine`。构建器将传输程序和匹配的 JNI 库放入内容寻址的运行包，不修改安装的编辑器。Android 继续使用 `remote/android/build.py`；SDK、许可证及校验记录位于 `remote/cloud`。

## 回归

`remote/manager/test_cloud.py` 启动实际测试游戏、账号服务与独立接收端，验证画面、声音、控制、设备旋转和撤销。需要先启动本地 LiveKit dev 服务，并提供运行包 manifest、relay server.mjs、Node 24、`cargo build --example probe` 产物路径。

`--credentials` 只用于已获授权的公网临时测试账号，文件应为权限 600 的 JSON，含 server、username、password、token。不要使用或提交用户账号文件。测试凭据应在回归结束后清理。

局域网回归：`test_manager.py`、`test_media.py`；界面回归：`node remote/manager/test_ui.js`。Android APK 编译与纯 Java 测试不能代替手机上的 WebView、音频路由和手势实机检查。

## 游戏目录与导航

两种模式共用 Remote Manager 中已添加并启用共享的项目，不需要再次添加游戏。Android 的顶部图标切换、电脑选择、搜索、游戏/运行中/电脑/设置导航保持一致；加号打开登录或添加电脑弹层。公网模式记住上次选择的电脑，浏览列表时每 8 秒更新，预览中只续租，不拉取目录。目录按最多 20 项且低于 12 KB 分页，客户端读完所有页，不再截断到前 50 项。

目录回归：`python3 remote/manager/test_catalog.py --manifest /运行包/manifest.json --javac /JDK/bin/javac`；客户端状态回归：`node remote/manager/test_cloud_ui.cjs`。

### Android 实机公网验证（2026-11-18）

APK 0.9.3 修复 JS SDK `performRpc.responseTimeout` 的单位错误：原来的 `12` 实际是 12 毫秒，改为 `12000`（12 秒）。此前桌面原生接收器的公网回归不覆盖此 JS 调用，不能代表 APK 验证通过。新增客户端回归覆盖真正 RPC 适配器的毫秒参数。

已通过 USB 安装至 Android 17 / WebView 157 手机；手机使用移动网络，日志到达 `webrtc-connected` 和 `catalog-ready`，用户确认三个共享游戏均可见，服务器同时观测到电脑和手机 ACTIVE。本次实机结论覆盖授权、连接和目录；未将它记为手机音视频播放及触摸验证。

### 公网触控修复（Manager 0.3.2 / APK 0.9.4）

公网消息保留物理像素坐标及设备版本，由 `RemoteCloud.pointerPacket` 转为现有 worker 接口的 0–65535 归一化坐标。此前直接写入像素值会将点击压缩到画面左上角。客户端串行发送按下/抬起，等待回执期间不发送可能抢先到达的移动包；尺寸或连接变化时丢弃旧队列。

验证：`test_cloud_input.py` 穷举横竖屏坐标往返与边界；`test_cloud_ui.cjs` 验证慢网顺序及尺寸切换隔离。已覆盖安装到真实手机，用户在云端 Eatventure 确认点击能正确响应。未修改运行时引擎。

### 公网清晰度设置（Manager 0.3.3 / APK 0.9.5）

入口：手机“设置 → 预览清晰度”，或游戏内四指下滑后的“预览清晰度”。选择保存在手机本地，后续连接、旋转和重连继续使用所选档位。运行中切换仅重新发布传输轨道，游戏进程、渲染尺寸和触控设备版本保持不变；控制面板显示视频实际解码尺寸。3 Mbps 服务器的带宽整形和 1.8 Mbps 视频码率上限保持不变。

`test_cloud_quality.py` 使用真实游戏和 H264 编码检查 540p → 720p → 1080p → 540p 输出，验证游戏会话和设备版本保持不变，并验证局域网设置互不影响。
