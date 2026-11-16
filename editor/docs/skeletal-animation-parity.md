# 骨骼动画制作器：Spine 4.2 兼容能力与验收

> 2026-11-07 后续安装：已按用户要求编译并通过本地回环 HTTP 更新源将本机升级到
> **1.14.2-patch.126**。安装前后 169 个文件校验无差异，冷启动 Lastlight District
> 和新 Spine 多骨架预览通过。下文“尚未打包”描述的是前一轮源码验收时点，现已完成持久安装。
> [安装记录](skeletal-animation-evidence/2026-11-07/patch126/installation.json) ·
> [冷启动截图](skeletal-animation-evidence/2026-11-07/patch126/cold-start.png)


当前源码已补齐附件与序列帧、双色和裁剪预览、多轨混合、事件音频、曲线批量编辑、
多骨架工作区及媒体和图集导出。目标是让制作结果进入 Defold 游戏；尚不能称为
全面替代 Spine Professional。运行时引擎源码未修改。

本页前半部分描述 **2026-11-07 续作阶段的当前能力**。后半部分保留各次验收和
安装记录，并标明历史阶段；其中的测试数量、未完成项和安装版本不代表本轮状态。
本轮只在本机编辑器会话中热加载源码，**尚未制作、安装持久增量包**。重启后不会
自动获得这些新增功能。

## 验收口径

将数据保留、编辑入口、交互验收和游戏运行时分别记录。保留原始字段不等于具有
制作工具；按钮已接入不等于所有交互都通过验收；编辑器渲染正确也不等于 Defold
默认材质能够表现同样的效果。文档改动经编辑器图事务进入撤销、重做和保存；
播放队列、试听和其他预览状态不写回动画关键帧。

原生读写使用独立 codec，不依赖已安装 Spine。官方应用只用于生成对照数据和
验证输出能否重开。兼容范围精确限定为 **Spine 4.2.43 / schema 40**；JSON
采样对照使用官方 Spine 4.2.120 runtime。这两个版本号分别对应工程格式和运行库，
不能据此宣称支持全部 4.2 工程格式或 4.3。原生工程只另存新文件，保留源文件。

## 当前功能矩阵

“已接入”表示有可操作的原生编辑器入口；证据强度以验收记录为准，不把所有
组合和平台都视为已验证。早期工具的实际操作截图见
[patch.84–85 工具回归](skeletal-animation-tools-84.md)，本轮证据见
[续作验收索引](skeletal-animation-evidence/2026-11-07/continuation-acceptance.md)。

| 领域 | 当前能力 | 验证与边界 |
| --- | --- | --- |
| 工程与撤销 | `.skeleton` 保存；编辑操作进入图事务；原生新文件另存 | 写入完成后发布，目标已存在时拒绝覆盖；实际文件大小限制失败测试未留下损坏目标 |
| 原生格式 | 独立读写骨骼、附件、蒙皮、约束、动画、事件及多骨架 | 只接受已验证的 4.2.43/schema 40 图结构；未知类型、标志或布局明确拒绝 |
| 多骨架工作区 | 导入全部骨架，创建、切换、改名、删除；统一另存；其他骨架以 setup 姿态灰显，可点名称切换 | 当前骨架可编辑；其他骨架是同屏参照，不是多骨架同时动画编辑。各骨架保存自己的文档和视图设置 |
| 骨骼 | 层级、移动、旋转、缩放、X/Y 错切；本地/父级/世界坐标；五种继承及反射 | 已有数值、官方运行库与实际操作对照；蒙皮骨骼遵守激活规则 |
| 插槽 | 附件选择/隐藏，RGBA/RGB/Alpha/双色关键帧，绘制顺序，四种混合模式 | 制作入口已接入；逐通道曲线保留；Defold 默认材质的暗色通道限制见下文 |
| 蒙皮 | 切换、复制、独立编辑、占位符管理、骨骼与约束归属、组合蒙皮 | 组合按输入顺序覆盖同名附件，合并所需骨骼/约束；保留获选附件的时间轴来源 |
| Region / Mesh / Linked Mesh | 编辑区域和网格、父网格链接、蒙皮与时间轴继承 | 检查缺失父项和循环链接；链接网格可使用自己的图片/Sequence，继承父项变形和序列轨道 |
| Point / Bounding Box | 可编辑位置/角度或顶点/权重，保存及导出；编辑器显示几何 | 它们不是图像，不作为可见贴图渲染；未增加游戏碰撞或玩法脚本 |
| Path / Clipping | Path 控制点、FFD 与约束；裁剪顶点/权重和结束插槽 | 凹多边形、变形及 UV 插值；结束插槽也参与裁剪；空/隐藏结束项仍终止。嵌套裁剪按 Spine 行为忽略 |
| Sequence | count/start/digits/setup；索引、模式、延时关键帧；全帧导入和导出 | 七种模式；84 个案例、1092 个索引采样与官方实现一致；Defold 实际运行三帧切换通过 |
| 网格与权重 | 顶点/边编辑、三角剖分、生成/描摹/重置；数值赋权、自动绑定、笔刷、平滑、剪枝、镜像、锁定 | 既有工具回归保留；复杂多区域描摹仍拒绝，不推断无法可靠迁移的拓扑 |
| 约束 | IK、Transform、Path、Physics 参数、轨道、求值和导出 | 单动画与官方运行库/实际游戏已有对照；混合 Physics 使用固定步缓存，不承诺任意 dt 下逐帧完全相同 |
| 摄影表 | 多选、复制粘贴、移动、删除、反向、缩放、吸附、筛选匹配、重复关键帧 | 包括插槽和 Sequence 离散轨；保留不属于本次编辑的原始数据，批量编辑作为一次文档变更 |
| 曲线 | 多通道显示/筛选、Shift 多选、框选、全选、批量拖动 Bezier、插值与切线编辑 | 图形曲线入口覆盖骨骼/约束标量通道；插槽多通道曲线可经时间轴 API 批量编辑。事件/附件/绘制顺序/Sequence 是离散轨 |
| 动画混合 | 任意多轨 replace/add、独立权重和时间倍率、循环、队列延迟/过渡、连续累计旋转、Physics | 主预览使用有状态播放器；425 帧官方 golden 对照。不是完整 AnimationState API，未开放子片段范围、holdPrevious、empty animations |
| 事件 | 定义和参数、同刻不同事件、打帧及批量编辑、播放日志 | 播放触发，拖动/seek 静音；日志最多 100 条。原生同刻事件要求名称顺序；乱序或同名同刻事件拒绝 |
| 音频 | 事件默认/关键帧 audio、volume、balance；项目内导入、手动试听、播放触发、静音/停止 | 异步加载、缓存上限和释放；暂停、seek、切换/关闭失效待播请求。解码能力仍取决于 JavaFX 与本机媒体支持 |
| 编辑器渲染 | 区域/网格与绘制顺序；双色、normal/additive/multiply/screen、裁剪 | 普通预览保留 JavaFX 批量网格；双色/混合走共享 JOGL 离屏 GPU；图像导出走同一 GPU 路径，无无头 CPU 后备 |
| 辅助视图 | 暂停编辑时前后三帧位置的两张洋葱皮、参考图/透明度、相机缩放/平移、显示设置保存 | 辅助设置留在编辑文档，不写入游戏 Spine 数据；不提供完整摄影机动画或可配置洋葱皮范围 |
| Defold 导出 | `.spinejson`、`.atlas`、`.spinescene`、`.spinemodel` 与 PNG/音频依赖 | Sequence 全帧入 atlas；特殊插槽混合写入 BLEND_MODE_INHERIT；当前 Bob clean build + 现成引擎验收通过 |
| 媒体导出 | PNG 序列、GIF、MJPEG AVI、PNG+JSON 帧表 | 导出选中的单骨架/单动画，保持当前视图取景；不输出混合队列、其他骨架、参考图或音轨 |
| Spine 图集 | 真实多页 PNG + Spine `.atlas`，保留图片路径名，padding 和边缘扩展 | 全蒙皮/全 Sequence 帧；确定性 shelf 排版，官方解析器通过。与 Defold 自己的 atlas 编译管线分别交付 |

## 制作到交付的实际路径

1. 导入 JSON 或受支持的原生工程。图片和事件音频按项目归档，缺失依赖给出警告；
   拒绝穿越或符号链接逃出指定资源根目录。没有实际归档的原生音频保留原目录，
   不因另存而擅自指向游戏根目录。
2. 在骨架/蒙皮/插槽属性中管理附件、组合蒙皮、Sequence、颜色和混合；在时间轴
   和曲线页编辑动画。链接附件的父项、轨道继承及未知 JSON 字段在编辑后保留。
3. 通过单动画或多轨队列预览姿态、附件、裁剪、事件和声音。混合结果仅用于预览，
   不将合成姿态意外写回基础动画。切换骨架时停止播放/声音并清理旧选择与缓存。
4. 保存 `.skeleton` 或将整个工作区另存新的 `.spine`。对已支持的原生图保留原工程
   元数据，再更新、增删或重排骨架；不以 JSON 重建代替原工程保真。
5. 游戏交付选择 Defold 四资源导出，外部 Spine 交付选择 Spine 图集；评审选择帧图、
   GIF、AVI 或帧表。输出发布到新目录/文件，不覆盖已有交付物。

## 本轮验证证据

- [最终编辑器交互与源码回归](skeletal-animation-evidence/2026-11-07/continuation-ui/README.md)：
  全套 376 tests / 7586 assertions 通过，最终标签布局再跑 12 tests / 134 assertions 定向回归。
  实际点击验证多轨混合、初始事件、WAV 静音触发、暂停和多骨架名称切换；窗口与测试节点已清理。

- [附件/Sequence/图集](skeletal-animation-evidence/2026-11-07/sequence/README.md)：
  七种 Sequence 模式、链接轨道继承、组合蒙皮、双色关键帧、音频归档和路径边界；
  官方图集解析器读回两页、五个区域，名称、尺寸及位置全部一致。
- [实际 Defold 游戏验收](skeletal-animation-evidence/2026-11-07/workflow-runtime/README.md)：
  当前源码导出四资源，当前 Bob 标准 clean build，现成本机 Spine 引擎运行 525 帧、
  退出码 0；四轮循环收到 12 个正确事件回调。截图像素核对 Sequence、Linked Mesh、
  裁剪和三种特殊混合；暗色通道不生效作为已知边界记录，并非通过项。
- 有状态混合的独立 golden 数据保存在
  [stateful-mixing-oracle.json](../test/resources/skeletal-animation/stateful-mixing-oracle.json)，
  覆盖 425 帧；不是只将本项目的结果与自身比较。
- [原生互操作与 GPU 验证](skeletal-animation-evidence/2026-11-07/README.md)：官方
  4.2.43 重开独立编码文件后，80 项字段核对通过；真实 GPU 覆盖双色、混合、裁剪
  和预览缓存。FFmpeg 7.1 对 AVI 的标准、奇数尺寸/小数帧率、单像素透明输入
  完成 15 项解码检查。
- 原生互操作、GPU 渲染和本机交互的证据入口统一列在
  [续作验收索引](skeletal-animation-evidence/2026-11-07/continuation-acceptance.md)。
  该索引区分本轮证据和同日第一阶段记录，不用历史测试数量替代当前全套回归。

## 明确限制与后续验收

- **Defold 默认 Spine 材质不支持暗色通道。** 编辑器双色预览和 JSON 数据保留已实现，
  实际游戏默认材质仍只表现 light tint。导出含 dark/rgba2/rgb2 时明确警告。
  需要扩展和材质实际提供 dark 顶点数据；单改 shader 不能证明运行时已经支持。
- **原生不是通用无损编辑器。** 独立 codec 只承诺已验证的 4.2.43/schema 40。
  未知完整图结构、flags/footer 拒绝；opaque 容器字节保留不等于可编辑。
  没有对应 named skin 的 skin-required 元素，或 membership 与 skin 标志冲突，
  无法无损表达时拒绝。暗色插槽的 RGB/RGBA 轨道在原生格式中转换为带 setup 暗色
  的 RGB2/RGBA2，因此不保留其他动画独立混入暗色通道的语义。不要将修改版本
  字符串视为升级兼容。
- **预览依赖桌面 OpenGL。** GPU 离屏渲染用于双色/混合和图片输出，没有无头 CPU
  渲染器。性能数据只代表本机与指定夹具，不是所有显卡或大型角色的帧率保证。
- **媒体导出有格式边界。** GIF 至多 100 FPS，按百分之一秒累计取整，受调色板及
  透明度限制；AVI 为 MJPEG、无音轨，透明部分合成黑底，不提供 MP4/H.264；超过 4 GiB RIFF 容量时拒绝发布。
  通用导出 1–240 FPS、最多 4096 帧，尺寸 1–4096，整张帧表最多 64 Mi 像素。
- **工作区、曲线和混合仍有产品范围。** 同屏其他骨架仅 setup 参照；曲线 UI 未覆盖
  Spine 全部通道工具；未实现完整 AnimationState 配置接口。旧双轨 stateless API
  仍保留兼容边界，主界面已使用有状态播放器。Physics 对比须考虑时间步长和初态。
- **验证平台为 macOS Apple Silicon。** Windows、Intel Mac、无显示环境和其他
  引擎/插件组合未完成实际交互回归；本轮不宣称跨平台验收通过。
- **本轮仅会话热加载。** 新功能尚未打成持久增量包；历史 patch.82–85 的下载、安装
  和重启记录不能证明本轮功能重启后可用。未在本轮提交、推送或发布更新包。

后续收口应先完成本轮持久包安装与重启验收，再扩大原生版本和跨平台覆盖；按实际
生产工程补充混合、声音和复杂附件交互回归。保留现有运行时引擎边界，不将未验证
能力标成完整替代 Spine Professional。

## 对照资料

- [Spine 用户指南](https://esotericsoftware.com/spine-user-guide)
- [JSON 数据格式](https://esotericsoftware.com/spine-json-format)
- [导出和 Nonessential data](https://esotericsoftware.com/spine-export)
- [官方命令行接口](https://esotericsoftware.com/spine-command-line-interface)

真实工程对照包括用户的 fire 4.2.43：36 根骨骼、12 个插槽、5 段动画。独立编码
路径不调用本机 Spine；官方程序仅用于兼容性对照。本轮游戏验收使用隔离项目，
未更改用户游戏资产，也未向远程原生扩展构建服务上传用户工程。

## 历史记录阅读说明

以下保留当时的实现、问题、测试和安装事实。段落中的“本轮”“最新”“仍未完成”
都指该小节所标记的历史阶段；当前能力与限制以上方矩阵和续作验收索引为准。

## 历史：2026-10-23 本地验收（patch 82）

- clj-kondo：新增和修改的 Clojure 文件 0 errors、0 warnings。
- 单元回归：82 项测试、529 个断言，全部通过。
- 资源/图事务集成回归：4 项测试、40 个断言，全部通过。损坏 JSON 的日志来自预期的错误输入用例。
- 实际 fire.spine：36 骨骼、12 插槽、5 动画；导入、修改、另存和回读通过，原文件字节未改变。
- 多骨架：另存含 hero / companion 的原生文件并回读，两个骨架都保留，修改只影响目标骨架。
- 皮肤：base / skin1 原生另存和回读；真实 UI 复制、独立位置修改、切换、撤销重做及 .skeleton 保存重开通过。
- 原生覆盖保护：实际按钮对已有目标报错，目标文件字节保持一致。
- 游戏运行：离线 Bob 1.13.1 + 本机缓存的匹配 Spine 引擎，166 次骨骼姿态采样；错切使子骨骼移动，base / winter 两次切换执行成功，录制画面确认颜色与形变变化。未向构建服务上传源码。
- 平台范围：本轮验证 macOS Apple Silicon。Windows、Intel Mac、本地 1.14.1 新引擎及所有未完成矩阵项目不计为通过。

本地证据位于仓库 tmp/：`spine-ui/all-unit-final.log`、`spine-integration-tests.log`、
`spine-ui/native-multi-result.json`、`spine-native-roundtrip/result.json`、
`spine-parity-runtime.log`、`spine-parity-runtime.mp4`；界面截图为
`spine-ui/qa-native-skins-reimported.png`、`spine-ui/qa-native-overwrite-protected.png`、
`spine-ui/qa-skin-edited.png`、`spine-ui/qa-skin-default-retained.png`。
这些本地证据不作为已发布 GitHub 或跨平台验证的替代。

本地增量交付：patch 81 → 82，使用回环 HTTP feed 下载并验证 SHA-256；
安装前和安装后均核验清单 168 个文件，零差异。已使用正式重启助手完成替换。
本轮未提交、推送或发布 GitHub 更新源。


## 历史：2026-10-23 独立制作器扩展验收（源码开发版）

本轮产品原生导入和另存不调用已安装 Spine。支持范围按 4.2.43 的已验证对象定义，
遇到其他版本或未知数据明确拒绝。原生工程只另存新文件；多骨架及图片/音频目录迁移
有专门回归。官方程序只用于独立验证生成文件能否重开。

### 实际交互证据

截图保存在 `skeletal-animation-evidence/2026-10-23/`，不是示意图。

| 已测试的操作 | 截图（该目录下） |
| --- | --- |
| fire 原生独立导入、播放、修改后另存与重开 | `qa-fire-independent-playing.png`、`qa-fire-independent-edited-reopen.png` |
| 从空白工程制作后另存原生工程 | `qa-independent-native-save.png` |
| Physics 编辑、关键帧、全局轨道、原生重开播放 | `qa-physics-setup.png`、`qa-physics-keys.png`、`qa-physics-global-key.png`、`qa-physics-independent-reopened-playing.png` |
| 网格多选和整组移动、拓扑分割及变形 | `qa-mesh-multiselect.png`、`qa-mesh-group-drag.png`、`qa-mesh-split-ffd.png` |
| 连边与断边 | `qa-mesh-connect.png`、`qa-mesh-disconnect.png` |
| 自动网格、透明度描摹、重置与撤销 | `qa-mesh-generated.png`、`qa-mesh-traced.png`、`qa-mesh-reset.png` |
| 自动绑定、锁定与直接权重 | `qa-auto-weights.png`、`qa-weights-lock-numeric.png` |
| 加/减/替换/平滑笔刷 | `qa-weights-brush-add.png`、`qa-weights-brush-subtract.png`、`qa-weights-brush-replace.png`、`qa-weights-brush-smooth.png` |
| 权重平滑、剪枝、归一化、镜像 | `qa-smooth-weights.png`、`qa-prune-weights.png`、`qa-normalize-weights.png`、`qa-mirror-weights.png` |
| 权重模式双击不删除顶点 | `qa-weights-double-click-retains-vertices.png` |
| IK 参数和 softness Bezier | `qa-ik-full-setup.png`、`qa-ik-softness-bezier.png` |
| Transform 局部/相对模式与曲线 | `qa-transform-local-relative.png`、`qa-transform-bezier.png` |
| Path 创建、控制点、变形轨道及实际游戏 | `qa-path-created.png`、`qa-path-ffd-recorded.png`、`qa-path-game-runtime.png` |
| 关键帧多选、粘贴、组移动、反向 | `qa-timeline-multiselect.png`、`qa-timeline-paste.png`、`qa-timeline-group-move.png`、`qa-timeline-reverse.png` |
| 关键帧缩放后保持选择并吸附至帧 | `qa-timeline-scale-physics.png`、`qa-timeline-snap-physics.png` |
| 底部工具栏溢出菜单与停靠按钮不再遮挡 | `qa-toolbar-overflow-fixed.png` |
| 附件切换、RGBA、绘制顺序、隐藏与返回 | `qa-slots-setup-frame.png`、`qa-slots-alternate-readonly.png`、`qa-slots-hidden.png`、`qa-slots-returned.png` |
| 动画路径 A/B/隐藏/恢复，约束与显示同步 | `qa-path-switch-original.png`、`qa-path-switch-alternate.png`、`qa-path-switch-hidden.png`、`qa-path-switch-returned.png` |
| Bezier 区间中间原位插入 FFD 帧、当前画面不跳动 | `qa-bezier-intermediate-key.png` |

### 运行时和误差范围

- 使用本地缓存的匹配 Spine 引擎和离线 Bob，未上传原生扩展或项目源码。
- Path/IK/Transform/加权网格组合：171 个动画帧，最大位置误差约 0.000047 像素。
- 实际 Physics 游戏：164 次更新、163 个动画样本；编辑器固定步长与游戏可变帧间隔
  对比的最大位置误差约 1.742 像素。将采集的游戏帧间隔逐帧输入官方运行时后，
  最大差降到 0.05554 像素、平均 0.00605；1 秒 reset 后的 80 个样本最大差约
  0.00001732 像素。主因是时间步长与初态差异。编辑器尚未提供游戏 dt 序列
  重放模式，不称为不同步长下逐帧完全等价。报告：`tmp/spine-ui/physics-dt-review.json`。
- 独立 Physics 原生文件：43 个样本、118 个激活变体、28,438 帧、654,074 个运行时
  数值比较，最大差约 0.0000112；包括局部/全局 reset 的调用时间。
- fire 插槽采样：5 段动画、105 帧、1260 个插槽样本，最大差约 0.00000205；热采样
  平均约 0.083 ms/帧，仅计算采样模块，不包含图像加载与 GPU 渲染。
- 颜色动画直接修改 GPU 材质。实际持续播放回归中，同一张贴图缓存保持 1 项，
  没有为每个颜色帧创建新图片。

本机 Spine 原应用的操作对照未完成：桌面锁定且用户暂时不能开启界面。
现有截图只能证明 Defold 内操作，不证明已逐项复刻 Spine 原应用交互。
Windows 与 Intel Mac 本轮未测试。完整制作功能仍以矩阵中的未完成项为准。

### 参考图布局修正（2026-10-23，第二轮）

用户指出旧工作区的界面元素和入口位置不匹配参考图，本轮已重新安排：

- 上部按约 70:30 分为画布和层级区；下部约占一半高度，贯穿时间轴／曲线。
- 画布使用中灰棋盘背景；左上角切换 setup/animate；底部中央集中旋转、移动、缩放、倾斜及数值，旁边是网格、权重、路径和显示骨骼。
- 项目导入、原生另存、Defold 导出收纳至“项目”菜单；创建骨骼、附件和约束收纳至“创建”；动画和批量关键帧工具收纳至“动画设置”。
- 右侧增加层级搜索、绘制顺序／皮肤／动画／事件分类，骨骼树默认收起；属性用独立切换按钮展开，不常驻挤占层级区。
- 时间轴／曲线共用紧凑播放栏；支持逐帧、前后关键帧跳转，首次自动适配动画时长，时间标尺显示帧号。
- 实际点击验证旋转数值、工程撤销、搜索、属性展开／收起、倾斜关键帧、前后帧和关键帧、持续播放、时间轴／曲线来回切换。

完整单元集 **251 tests / 4162 assertions，0 failures / 0 errors**；最后布局调整后界面逻辑 **33 tests / 183 assertions** 再次通过。骨骼源码和测试 clj-kondo 均为 0 错误、0 警告。此前资源集成测试 **4 tests / 40 assertions** 通过。

截图见`skeletal-animation-evidence/2026-10-23` 中 `qa-reference-final.png`、`qa-reference-curves.png`、`qa-reference-properties.png`、`qa-reference-project-menu.png`、`qa-reference-search.png`、`qa-reference-shear-key.png`。截图由运行中的 Defold JavaFX 窗口生成，非设计稿。

**仍不能称为完整复刻**：参考图中的本地／父级／世界坐标切换、独立自动关键帧开关、全部层级筛选图标及时间轴工具入口还未等价实现；当前动画编辑会直接记录关键帧。曲线编辑尚不是 Spine 的完整多通道图表。当前图标、菜单结构与 Spine 仍有差异。原应用的真实交互对照仍受桌面不可用限制。

### 当时的原生工程兼容边界

独立读写限定经过验证的 **Spine 4.2.43 / schema 40**。Path 31 个样本（32 个骨架）字节回环一致；31 个独立生成文件由官方应用重开，原始投影 296 姿态 / 10248 核对，重写后 288 姿态 / 9948 核对；骨骼最大误差约 0.00000545。官方对 mixY 默认值及非恒速路径长度缓存有正常化行为，对照脚本单独处理，产品不改写数据迁就测试。

原生未支持版本／未知类、linked mesh、clipping、bounding box、point、皮肤骨骼或约束依赖、约束子目录、未验证 slot color/alpha/drawOrder 原生轨道、同一时刻多个事件、非 normal blend 和未验证曲线切线标志均明确拒绝；不静默降级。JSON 预览对部分轨道的支持不意味着原生工程已可编辑保存。路径自循环明确拒绝激活；相关循环样本仅用于 codec 验证。

### 本地增量交付验证

本次构建 `patch.82 → patch.83`，更新包约 194 MiB，仅包含配置和编辑器 JAR 两个变更文件，未重新打包 JDK、完整 App 或 DMG。验证使用 **本机回环 HTTP 更新源**，不是 GitHub 正式源。

已通过安装版 patch.82 的真实 `check! → download! → prepare! → restart!` 链路；SHA-256 校验通过，新版进程正常完成项目加载。安装后的 `/Applications/Defold.app` 共 **168 个清单文件的哈希和权限全部匹配** patch.83。安装版界面复查使用该 App 内的已编译 JAR，不使用源码覆盖。未提交、推送或发布远程补丁。

安装版额外验证：通过“项目”菜单实际另存 `layout-83.spine` 并重开，骨骼与动画 ID 保持一致，codec 为 `independent-4.2.43`，播放状态生效。截图 `qa-installed-patch83.png`、`qa-installed-patch83-native-playing.png`。已删除本次生成且核验为 patch.82 的备份，`/Applications` 只保留一个正式 Defold App。

已知诊断文案问题：导入报告中的旧 slots “not previewed” 提示尚未跟随插槽采样实现更新；当前 slots 预览行为已有独立测试，提示文案需要后续修正。事件仅保留／导出，不执行游戏事件回调。

最终完整工作区截图为 `qa-installed-patch83-focused.png`。通过应用自带停靠菜单把 Game 移入工具区，再使用 F6/F7/F8 收起周边面板后拍摄；不伪造正常默认布局。在窄停靠区域中，变换工具浮栏仍可能空间不足，尚需响应式折叠优化。

安装版还实际拖动了画布／层级分隔线至 70:30，验证分区可调整。窗口整体尺寸变化时右侧仍保持宽度优先，需要手动恢复比例。


### 当时的 patch.85 本地交付

坐标空间、自动关键帧、临时姿势、分组/可见轨道打帧、限轴工具、快捷键及显示入口的实现和实际操作截图见 [工具交互回归](skeletal-animation-tools-84.md)。最终全套 **262 tests / 4256 assertions，0 failures / 0 errors**，clj-kondo 0 错误、0 警告。

已通过本地源完成 `83 → 84 → 85` 增量下载、安装和重启，最终唯一 App 为 `/Applications/Defold.app` patch.85；168 个文件全部匹配。最终编译版验证包括“未打帧时不显示假关键帧”、手动打帧、撤销、数值精调和 FFD 编辑。旧版布局章节中的“坐标与自动关键帧尚未实现”是历史状态，现已更新；完整功能矩阵的其余缺口仍成立。

## 历史：2026-11-07 第一阶段源码开发版验收

本轮继续按 Spine 4.2 对齐编辑、制作及运行行为；运行时引擎未修改。
本机 Defold 当前会话已显式加载源码（含 IO 与资源注册），尚未制作新的增量包。
重启后使用的仍是已安装版本，不能将会话热加载视作持久安装。

- 创建菜单增加事件定义；右侧可编辑名称、默认整数/浮点/字符串及当前关键帧参数。
  事件轨道支持多选、移动、复制、删除、反向、缩放和吸附。
  事件记录页只在播放时触发，保留最近 100 条，暂停和拖动不补发历史事件。
- 插槽属性在动画模式提供附件切换/隐藏、RGBA/RGB/Alpha、绘制顺序的加帧/删帧。
  播放中按钮使用操作时的播放头；时间轴批量操作保留原始多通道曲线及未知字段。
- 骨骼及 IK/Transform/Path/Physics 约束可设置仅在所属蒙皮中启用、加入当前蒙皮。
  预览遵守 Spine 的激活规则，inactive 骨骼不会被动画或祖先约束间接更新。
- 动画设置提供第二轨动画、权重和从该动画到当前动画的过渡。
  两轨使用独立时间，暂停不倒退，过渡完成恢复单动画预览。
  混合预览期间禁止写回姿态与关键帧；结束预览再编辑，避免将合成坐标写入原动画。

验证结果：

- 组合回归 223 个测试、4454 条断言，零失败/错误；包含数据、摄影表、控件、
  混合、导出和原生格式。记录见 `skeletal-animation-evidence/2026-11-07/regression.log`。
- 双轨静态采样与官方 Spine 4.2.120 AnimationState 对照：插槽/颜色/附件/绘制顺序/
  FFD 的 200 个采样，骨骼/约束的 40 个采样均通过；黄金数据已保存为测试资源。
- 新事件 API → 实际导出 `.spinejson/.atlas/.spinescene/.spinemodel` → 当前 Bob
  标准 clean build → 本机现成 Spine 引擎运行，302 帧退出 0；收到六次正确回调，
  覆盖 0 秒、同刻两个事件、参数默认值/覆盖值和两轮循环。
  最终构建使用现行 Spine 插件，没有替换编译产物或混用 Bob 输出。
- cljfx.plorer 实际界面验证：新建/重命名/打事件帧/播放记录、插槽绘制顺序打帧、
  双轨权重操作。基础旋转 -25° 与第二轨 90° 在 0.5 权重下得到 32.5°。
  验证在临时编辑器窗口与隔离游戏项目中完成，未改用户游戏资产。

剩余边界保持明确：当前混合是双轨 replace 与过渡预览，不含 additive、运行队列、
累计多圈旋转历史；混合期间 Physics 暂停并提示，单动画 Physics 仍可用。
音频事件元数据保留，但没有新增音频试听；原生 `.spine` 同刻多事件仍按既有
编码限制明确拒绝，JSON/游戏回调支持同刻不同事件。双色染色、特殊混合模式、
Clipping、Sequence、多骨架工作区、完整曲线工具及序列图/视频导出仍未标记完成。
