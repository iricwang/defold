# Defold LAN Remote

游戏在电脑运行，Android 手机通过同一局域网查看实时画面、回传单指触控，并控制启动／停止。停止游戏后电脑服务继续运行，因此手机可以再次启动游戏。

## 使用

1. 覆盖安装 `android/build/DefoldRemote-debug.apk`，当前版本 0.6.2（Android 8.0 / API 26 以上）。保持原开发签名，可直接升级。
2. 使用包含 Remote 的更新后编辑器打开项目。在 **Game 面板**中点击 `Remote · 八位配对码`，打开二维码；弹窗中的“启用局域网 Remote”需要勾选。
3. 手机和电脑连接同一 Wi-Fi，在 Remote 首页点击“扫码”，允许相机权限，扫描编辑器二维码。手机会连接指定游戏；未运行时由编辑器执行构建和启动。
4. 成功连接后，手机完整显示游戏画面，隐藏应用和系统顶底栏。再次打开 App 会恢复上次成功连接的游戏；外部 App 指定的目标优先。
5. **四指向下滑动**或 Android 返回操作打开顶部控制栏，提供运行、停止、返回首页、手机实际显示 FPS 和清晰度选择。运行成功收到首帧后自动隐藏；停止后保留最后画面与控制栏。只有点击“返回”才回首页，且不改变电脑游戏的运行状态。
6. 清晰度默认 720p，可选 540p、1080p、原始尺寸，运行中立即切换并记住选择。保持原始比例、不放大；原始尺寸受 4096 像素最长边和 8 百万像素保护上限限制。较高档位增加带宽与编解码开销，达不到目标帧率时可降低档位。

首页通过局域网发现已启用 Remote 的编辑器游戏，显示游戏名、电脑名和状态。已配对游戏可显示预览；没有授权的游戏显示占位图，点选后输入其配对码。多个编辑器使用独立游戏标识和凭据。扫描和手动输入都可以作为发现失败时的备用入口。

电脑端直接复用 Game 面板的共享画面和输入通道，手机与编辑器控制同一个游戏进程。无需单独运行 Python 服务。配对凭据保存在本机用户配置中，编辑器重启后保留；弹窗可以刷新配对码或撤销已信任的手机。

默认 HTTP 端口 8390，端口被占用时依次尝试到 8419；UDP 发现使用 8391，支持 `239.255.83.91` 组播及广播。二维码包含实际端口和游戏路径。原有独立 `host.py` 仍可用于旧版单游戏调试协议，不能与编辑器争用同一个端口。

## 从其他 App 进入 Remote

推荐显式指定包名，避免系统弹出多个处理程序的选择器。连接地址可从 Game 面板复制；它包含具体游戏标识。

```java
Intent intent = new Intent("com.defold.remote.CONNECT");
intent.setPackage("com.defold.remote");
intent.putExtra("address", "http://192.168.1.10:8390/games/GAME_ID");
intent.putExtra("pairing_code", "12345678");
startActivity(intent);
```

也支持与二维码相同的 URI：

```text
defoldremote://connect?v=1&address=http%3A%2F%2F192.168.1.10%3A8390%2Fgames%2FGAME_ID&code=12345678
```

以上均为示例参数。冷启动、前台和后台已存在的 Remote 实例均由同一连接流程处理。无效参数不会回退连接另一台电脑。仅传 IP 和端口时，电脑必须只提供一个游戏；有多个游戏时应传完整游戏地址。

## 当前能力和边界

- 发现同一局域网内多个编辑器中的游戏；每个游戏有独立配对凭据，同时保留一个活动串流控制连接。新配对撤销该游戏的旧令牌。
- 可选最长边 960 / 1280 / 1920 / 原始尺寸，目标 60 FPS。编辑器通过共享帧缓冲采集并编码 JPEG，持续推送手机；旧 Python 服务仍使用 PNG。手机只保留一个待显示画面，全屏默认隐藏控制栏；FPS 按最近一秒实际绘制的新画面统计，停帧时衰减为 0。实际帧率取决于场景、编码和 Wi-Fi。
- 单指映射为游戏鼠标左键，支持按下、拖动、松开；游戏需有鼠标输入绑定。画面保持比例，黑边不触发按下。不是原生多点触摸或虚拟手柄。
- 手机退到后台会断开并释放按键；断线超过 1 秒， 引擎也会释放远程输入。
- 不传输音频，不包含 H.264/WebRTC 硬件编码。电脑游戏窗口最小化时引擎不产生新画面。
- 旧的独立调试协议仅在 `DM_REMOTE_TOKEN` 为 16–128 字节时启用；编辑器内置协议使用 Game 会话自己的共享画面和输入，不依赖此环境变量。
- 独立 Python 服务的 `--editor` 启动已编译项目；新的编辑器内置 Remote 启动操作会复用 Game 面板的构建流程。

## 构建 APK

需要 Android SDK Platform 36、Build Tools 36.1.0 和 JDK 17+。使用 Android 官方工具直接编译、打包和校验，无 Gradle 或额外运行依赖：

```sh
python3 remote/android/build.py --sdk /路径/Android/sdk --java-home /路径/JDK
```

产物：`remote/android/build/DefoldRemote-debug.apk`。开发密钥保留在同目录，重复构建可以覆盖安装；删除密钥后重建需要卸载旧 APK。正式分发应另行使用发布签名。

## 维护与验证

- `engine_remote.h`：私有协议、帧缓冲、尺寸限制、触摸队列和超时。
- `engine_service.*`：调试 HTTP 服务注册与 release 空实现。
- `engine.cpp`：硬件输入采集后注入远程输入；渲染完成、交换缓冲前采集画面。
- `remote/host.py`：配对、发现、固定游戏进程生命周期、PNG 编码和手机 API。
- `remote/android/`：独立原生 Android 客户端，画面与控制使用独立线程。

```sh
python3 -m unittest discover -s remote/tests -v
cmake --build engine/build/arm64-macos --target dmengine dmengine_release test_engine
engine/engine/build/arm64-macos/src/test/test_engine '--test-filter=*Remote*'
java -jar tmp/dynamo_home/share/java/bob.jar --root remote/tests/fixture --platform arm64-macos build
python3 remote/tests/smoke.py \
  --engine engine/engine/build/arm64-macos/dmengine \
  --project remote/tests/fixture/build/default/game.projectc \
  --output tmp/remote-smoke
```

测试游戏连续改变背景颜色，并打印触摸边沿。真实运行测试检查不同画面、持续连接中无重复帧、实测传输帧率、串流期间触摸的两个边沿、重复启动不产生第二个进程，以及停止后重新启动。可加 `--stream-frames 600 --min-fps 55` 在本机做帧率验收。

补丁遵循 [`patch/README.md`](../patch/README.md)。本地增量包以已安装应用的精确文件清单为基线；不能应用到文件哈希不同的同版本安装。APK 与电脑脚本是独立产物，不通过编辑器更新器安装。

Android 构建和联网行为参考：[AAPT2 官方文档](https://developer.android.com/tools/aapt2)、[Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)。

## 本次本地交付（2026-10-24）

- APK：`remote/android/build/DefoldRemote-debug.apk`。
- Apple Silicon 增量包：`tmp/remote-patch/Defold-arm64-macos-patch-86-to-88.zip`。
- 该包基于本机 patch.86 的精确文件清单，只更新引擎和补丁版本，保留原编辑器内容。不是包含工作区所有未提交编辑器修改的完整构建。
- 已通过本地 HTTP 下载、完整文件校验、应用测试副本安装及编辑器重启；随后从该副本提取引擎，完成两轮画面／触摸／启停测试。用户原安装未修改，未发布 GitHub。
- 验证详情：[evidence/2026-10-24.json](evidence/2026-10-24.json)。旧版 APK 已由用户确认在 Android 真机连接并显示画面；Windows、Linux、Intel macOS 尚未构建验证。

如需将该本地补丁应用到对应的 patch.86 安装，先保存并退出 Defold。仓库根目录开启本地更新源：

```sh
python3 -m http.server 18390 --bind 127.0.0.1 --directory tmp/remote-patch
```

另开终端，从仓库根目录启动编辑器并一次性指定更新源：

```sh
JDK_JAVA_OPTIONS="-Ddefold.patch.directory=$PWD/tmp/remote-patch" \
  /Applications/Defold.app/Contents/MacOS/Defold
```

通过“帮助 → 检查更新”或欢迎页的“Patch 更新”下载并应用。升级后完全退出，再正常启动，以恢复正式更新源。不匹配的文件基线会被更新器拒绝。

### 60 FPS 更新（patch.89 / APK 0.2.0）

- 手机覆盖安装同一签名的新版 APK；旧 APK 的定时拉取方式无法达到 60 FPS。
- 电脑端新增 `/stream` 持续连接；采集/编码与网络发送分离，只保留最新一帧，避免慢连接积压旧帧。状态和触控不阻塞手机解码线程。
- 引擎按 60 Hz 的时钟相位采集，允许少量垂直同步抖动，避免固定间隔比较误跳至 30 FPS；不缩放时直接复制帧缓冲。
- 本机已安装补丁内引擎在 640×360 测试场景中，两轮各 600 张不同画面均测得约 59.9 FPS。该结果为电脑回环端到端传输测试，手机实际显示帧率以 APK 的读数为准，复杂画面与 Wi-Fi 带宽仍会影响结果。
- 增量包：`tmp/remote-patch-89/Defold-arm64-macos-patch-88-to-89.zip`。精确匹配本机 patch.88 基线，已验证本地源下载、168 个文件校验、安装及编辑器重启，未发布 GitHub。
- 当前启动的 Remote 编辑器：`tmp/remote-patch-89/Defold.app`。验证记录：`evidence/2026-10-24-60fps.json`。

### 游戏首页与扫码更新（patch.91 / APK 0.3.0）

验证记录：[evidence/2026-10-24-ui.json](evidence/2026-10-24-ui.json)。

- 已实现游戏首页、持久配对与自动重连、无工具栏全屏、四指下滑退出、相机扫码和外部 APK 直达。
- 编辑器 `RemoteGameServer` 直接注册 Game 会话并提供局域网游戏目录、二维码、画面和输入。停止手机串流不停止游戏；明确“停止电脑上的游戏”才调用编辑器停止。
- 二维码编码、解码使用固定版本 ZXing 3.5.3，完全离线；上游 JAR、来源、哈希及许可证在 `remote/vendor/`。
- 本地补丁链为 patch.89 → patch.90 → patch.91，分别按精确的 168 文件基线验证。未发布 GitHub。
- 当前交付编辑器位于 `tmp/remote-patch-91/Defold.app`；用户原 `/Applications/Defold.app` 未替换。
- Java 逻辑测试覆盖地址校验、外部链接、四指手势及二维码往返；真实编辑器联调覆盖约 60 FPS、触控边沿、启停、多游戏鉴权隔离、配对保持和发现。Android 真机的相机、手势和页面布局仍需手机验收。

运行纯 Java 客户端逻辑测试：

```sh
javac -cp remote/vendor/zxing-core-3.5.3.jar -d tmp/remote-tests \
  remote/android/src/com/defold/remote/RemoteAddress.java \
  remote/android/src/com/defold/remote/FourFingerGesture.java \
  remote/android/src/com/defold/remote/PointerBuffer.java \
  remote/android/src/com/defold/remote/FrameRate.java \
  remote/tests/java/RemoteLogicTest.java
java -cp tmp/remote-tests:remote/vendor/zxing-core-3.5.3.jar RemoteLogicTest
```

已启动新版编辑器时，可验证其真实会话：

```sh
python3 remote/tests/editor_smoke.py \
  --address http://电脑IP:端口/games/游戏标识 \
  --code 八位配对码 --output tmp/remote-editor-smoke --min-fps 55
```

该测试会配对并替换此前的手机令牌，默认结束时停止当前游戏；不要在手机正在测试时运行。`--leave-running` 可保留游戏运行。

### 合并到唯一正式安装（patch.92，2026-10-25）

- `/Applications/Defold.app` 已从 patch.86 更新至 patch.92，保留正式安装现有的引擎配置并恢复 Dragon Burst 项目；Remote 功能与 patch.91 相同，APK 仍为 0.3.0。
- 增量包：`tmp/remote-install/Defold-arm64-macos-patch-86-to-92.zip`，精确匹配更新前的正式安装。已通过本地 HTTP 下载、168 文件校验、安装及正式路径重启验证；未发布 GitHub。
- 已清理 10 个测试副本和旧更新备份，应用与启动记录只保留 `/Applications/Defold.app`。上文测试副本路径为历史记录，其应用已删除，补丁 ZIP 与验证记录保留。
- 验证记录：[evidence/2026-10-25-installed.json](evidence/2026-10-25-installed.json)。

### 石墨灰主题、控制栏与高清串流（patch.94 / APK 0.4.0，2026-10-25）

- 参考用户提供的 Companion 交接稿：背景 #181818、卡片 #222222、输入 #333333、分隔 #303030、暖橙 #e2763e。首页按在线游戏与离线历史分组；连接和游戏操作使用统一底部面板，扫码页适应横竖屏。
- 四指下滑现在呼出控制栏；Android 返回也呼出控制栏，避免系统边缘返回直接离开游戏。运行成功后自动隐藏；停止保留画面；“返回”进入首页；清晰度入口显示档位与已收到的实际尺寸。
- 单指拖动合并连续坐标，保持按下/抬起顺序，发送上限约 31 Hz；服务繁忙时重试边沿，触控短暂失败保留游戏页。旧代码在队列满或触控异常时直接返回首页的路径已移除。
- patch.93 增加串流分辨率参数和分档 JPEG 质量，缩略图独立限制最长边 400。APK 0.4.0 在旧电脑端仍可连接，但需要 patch.93 才能切换高清档位。
- 验证范围：APK 编译、签名与对齐；单指/四指、输入积压、按键边沿与 FPS 逻辑；实际编辑器的四档 JPEG 尺寸及切换；本地增量安装。尚未进行 Android 真机布局、手势和相机验收。

- patch.94 在 patch.93 的清晰度协议上优化批量像素读取与 RGB 复制，减少高清画面的逐像素开销。

- 最终正式安装为 `/Applications/Defold.app` patch.94，新增更新备份已清理。详细记录：[evidence/2026-10-25-controls-quality.json](evidence/2026-10-25-controls-quality.json)。

### Hardware video (0.5.0 / patch.95)

macOS uses VideoToolbox H.264 hardware encoding and GPU scaling; Android uses
MediaCodec decoding directly to a GPU surface. Select 1080p in the four-finger
control bar. The quality button displays **H.264** when active or **JPEG** for
compatibility mode. Touch polling is reduced to 8 ms, with bounded/coalesced moves.
See [native build and design](native/README.md) and [wire protocol](PROTOCOL.md).

Hardware checks require the built dylib on the Java classpath alongside editor
resources. `remote/tests/java/RemoteVideoTest.java` verifies first-frame SPS/PPS/IDR,
GPU scaling, invalid sizes and lifecycle. `remote/tests/video_smoke.py --connection
<private-json>` exercises an independent editor test game (JSON keys `base`,
`token`), including authentication, profile switching, JPEG compatibility and stop.
Android hardware decoding and end-to-end Wi-Fi latency still require a real phone.


## 游戏音频与首页（patch.96 / APK 0.6.0）

macOS 内嵌游戏现在把自身混音后的声音同步到手机，不采集系统其他 App 或麦克风。
采用独立的 16 位立体声 PCM 通道，48 kHz 时约增加 1.54 Mbit/s 带宽。
手机使用媒体音量，返回首页、停止游戏或退到后台时停止播放；音频异常不打断画面。
网络积压会丢弃过时音频，避免延迟持续增长。电脑仍正常播放本地声音。
旧编辑器不提供音频能力时，新 APK 继续正常显示画面。当前音频宿主仅支持 macOS。

首页“你的游戏”右侧收纳扫码、手动连接图标，保留游戏名称、电脑名称和必要状态，
删除 Wi-Fi 提示、数量说明及操作说明。启动器与首页使用新的自适应图标，见
[设计源文件与提示词](design/README.md)。

音频协议见 [PROTOCOL.md](PROTOCOL.md)。回归测试包括 `RemoteAudioTest.java`、
引擎 `SoundRemote` 测试，以及 `audio_smoke.py`（需独立 440/660 Hz 立体声循环音源测试项目，
会启动和停止指定游戏；不要用于用户正在游玩的项目）。


### patch.97 / APK 0.6.1 无声修复

原生扩展项目（例如 Spine）使用云端生成的自定义引擎，不能只更新内置引擎。
编辑器现在把 `src/audio.h` 随 Game 桥接一同加入构建，内容参与扩展引擎缓存键，
自动重建旧引擎。macOS 桥接在游戏自己的 AVAudioPlayerNode 排队时复制输出 PCM，
不采集系统其他声音，保持电脑本地播放不变；遇到已含原生混音采集的 SDK 时避免重复采集。

APK 修复首次音频焦点申请失败后不重试，以及部分设备缓冲未填满就被反复清空的问题。
四指控制栏显示简短声音状态，媒体音量为零、等待电脑音频或正在播放可直接区分。
