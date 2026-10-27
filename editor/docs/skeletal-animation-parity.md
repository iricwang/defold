# 骨骼动画制作器：Spine Professional 兼容验收

目标是完成可独立使用的制作工具，并直接读写原生 `.spine` 工程。以 Spine
Professional 4.2 的制作能力为第一条验收基线；4.3 是另一个兼容版本，不能靠
修改版本字符串假装支持。当前状态是开发中，尚未达到全功能替代。

## 完成条件

每项能力必须同时具备可编辑数据模型、实际 UI 操作、保存/撤销/重做、导入导出、
预览和游戏运行时证据。原始字段保留不等于支持；按钮存在不等于可用；单元测试
通过不等于交互验收通过。任何未支持内容都应在导入兼容说明中明确显示。

原生格式的最终验收要求不依赖 Spine 可执行文件。当前的已安装 Spine CLI 桥接
只用于互操作和构建对照数据，不计为独立原生格式读写完成。通过 JSON 重新生成
工程不能保证保留工程专用设置；实测 Spine 4.2.43 会重排约束序号。因此桥接只
提供另存新文件，拒绝覆盖原文件，原生源文件始终只读。

最新工具交互进展和逐项截图见 [patch.84–85 回归记录](skeletal-animation-tools-84.md)。

## 功能矩阵

| 领域 | 子能力 | 当前状态 | 必要验收 |
| --- | --- | --- | --- |
| 工程 | `.skeleton` 保存、全局撤销重做 | 已有 | 改动、保存、关闭、重开一致 |
| 工程 | 原生 `.spine` 独立解析和编码 | 4.2.43 独立骨骼/区域/网格/权重/FFD/IK/Transform/Physics 已验证；完整范围见下文 | 无 Spine 安装也能读写，未知块保留 |
| 工程 | 已安装 Spine 原生工程转换桥 | 本机读写及原文件保护已验证 | 原文件不变、多骨架保留、失败不覆盖目标 |
| 工程 | 多骨架同屏编辑、切换、命名 | 未实现 | 多骨架各自动画与资源不串用 |
| 骨骼 | 层级、移动、旋转、缩放 | 已有 | 数值、操作柄、撤销和父子关系 |
| 骨骼 | X/Y 错切和错切动画 | 数据、本机交互及匹配 Spine 运行时已验证 | setup 与 animate、曲线、导出采样等价 |
| 骨骼 | 继承模式、反射、皮肤骨骼 | 五种继承及反射已实现并数值对照；皮肤骨骼仍待完成 | 各模式在负缩放与非均匀父缩放下匹配 |
| 插槽 | 区域图、静态颜色与透明度 | 已有基础 | 贴图、层序、透明边缘 |
| 插槽 | 多附件、附件切换关键帧 | 导入预览已验证；完整制作 UI 未实现 | 隐藏、空附件、缺失附件回退 |
| 插槽 | RGBA/RGB/Alpha、双色染色、混合 | RGBA/RGB/Alpha 导入预览已验证；双色/特殊混合未完成 | 逐通道曲线、混合顺序、PNG 输出 |
| 皮肤 | 多皮肤、组合皮肤、皮肤占位符 | 已支持皮肤切换/复制/独立编辑；组合和皮肤骨骼未完成 | 编辑每一皮肤，运行时切换无丢失 |
| 附件 | Region、Mesh | 已有 | PNG 和网格几何一致 |
| 附件 | Linked Mesh、Point、Bounding Box | 未实现 | 链接关系、碰撞/点输出及重命名 |
| 附件 | Clipping、Path | Path 编辑、FFD、约束及运行时已验证；Clipping 未完成 | 凹多边形剪裁、开闭曲线路径 |
| 附件 | Sequence 序列帧 | 导出会拒绝 | 全帧打包、索引、模式与延时 |
| 网格 | 顶点拖拽、加权变形、FFD | 已有基础 | 权重绑定空间与动画变形不漂移 |
| 网格 | 增删顶点/边、三角剖分、自动网格 | 增删/分割/连接/断开、生成/描摹/重置已实测；复杂多区域描摹明确拒绝 | 拓扑编辑后 UV、权重、FFD 有明确迁移 |
| 权重 | 数值赋权、归一化 | 已有 | 权重总和、零影响清理 |
| 权重 | 自动绑定、笔刷、平滑、剪枝、镜像 | 全部已实现并实际 UI 验证 | 选区、锁定、撤销、变形质量 |
| 约束 | 单/双骨 IK、mix 与 bend 动画 | 已有基础 | 目标操作、连续动画、采样一致 |
| 约束 | IK softness/stretch/compress/uniform | 已实现并数值/实际 UI 验证，softness 独立曲线 | 极限伸展、非均匀缩放、混合 |
| 约束 | Transform、Path、Physics | 参数、轨道、求值、导出已实现；官方运行时及本机实际游戏验证 | 所有模式与属性、约束顺序、重置/倒放 |
| 动画 | 变换/FFD/IK 键、关键帧移动删除 | 已有基础 | 时间、曲线、录制与撤销 |
| 动画 | 摄影表多选、复制粘贴、缩放、偏移 | 数据/API 已回归，UI 多选/复制粘贴/移动/反向/缩放/吸附已实测 | 跨轨跨动画、多选吸附和批量撤销 |
| 动画 | Bezier 控制柄、线性、阶梯 | 已有基础 | 导入导出后曲线采样一致 |
| 动画 | 曲线多选、切线、循环、过滤、匹配 | 未完成 | 曲线操作不损坏邻接区间 |
| 动画 | 事件及音频、绘制顺序轨道 | 绘制顺序导入预览已验证；事件制作与音频播放未完成 | 拖时间不误触事件，循环不重复漏发 |
| 预览 | GPU 区域图/网格、层序 | 已有基础 | 真实角色无三角接缝、重复播放无增长 |
| 预览 | Ghosting、洋葱皮、参考图、相机 | 未实现 | 选项持久化，不写入游戏数据 |
| 导出 | Defold atlas/scene/model、PNG 资源 | 已有基础 | 编译并在匹配版本引擎中播放 |
| 导出 | PNG 序列、GIF/视频、完整纹理打包 | 未实现 | 帧数、透明度、时长和图集元数据 |
| 工作流 | 快捷键、搜索、批量操作、工作区 | 坐标空间、自动关键帧、主要工具快捷键与分组打帧已实测；完整入口仍未完成 | macOS/Windows 实际交互回归 |

## 架构推进顺序

1. 工程互操作：明确原生桥接与独立格式编码的边界；建立多骨架、版本和原始数据保留的测试。
2. 完整表现数据：皮肤/多附件、颜色、绘制顺序、事件和序列帧，解决导入后画面与原动画不一致。
3. 约束求值：统一带版本的姿态求值接口，覆盖继承、Transform/Path/Physics 与完整 IK。
4. 制作工具：拓扑、权重笔刷、自动绑定、摄影表和曲线批量编辑；所有手势纳入同一撤销事务。
5. 导出和性能：逐项做运行时回归、前台帧率/首次载入/内存检查及 Windows UI 验证。
6. 原生工程编码：以版本化适配器隔离文件格式，未知工程数据必须保留并验证；未通过前不宣称无损覆盖。

## 对照资料

- [Spine 用户指南](https://esotericsoftware.com/spine-user-guide)
- [JSON 数据格式](https://esotericsoftware.com/spine-json-format)
- [导出和 Nonessential data](https://esotericsoftware.com/spine-export)
- [官方命令行接口](https://esotericsoftware.com/spine-command-line-interface)

当前实际工程对照为用户的 fire 4.2.43：36 根骨骼、12 个插槽、5 段动画。
独立编码路径不调用本机 Spine；已安装 Spine 只作为兼容性对照。
不向远程原生扩展构建服务上传用户工程。

## 2026-10-23 本地验收（patch 82）

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


## 2026-10-23 独立制作器扩展验收（源码开发版）

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

### 原生工程兼容的最终边界

独立读写限定经过验证的 **Spine 4.2.43 / schema 40**。Path 31 个样本（32 个骨架）字节回环一致；31 个独立生成文件由官方应用重开，原始投影 296 姿态 / 10248 核对，重写后 288 姿态 / 9948 核对；骨骼最大误差约 0.00000545。官方对 mixY 默认值及非恒速路径长度缓存有正常化行为，对照脚本单独处理，产品不改写数据迁就测试。

原生未支持版本／未知类、linked mesh、clipping、bounding box、point、皮肤骨骼或约束依赖、约束子目录、未验证 slot color/alpha/drawOrder 原生轨道、同一时刻多个事件、非 normal blend 和未验证曲线切线标志均明确拒绝；不静默降级。JSON 预览对部分轨道的支持不意味着原生工程已可编辑保存。路径自循环明确拒绝激活；相关循环样本仅用于 codec 验证。

### 本地增量交付验证

本次构建 `patch.82 → patch.83`，更新包约 194 MiB，仅包含配置和编辑器 JAR 两个变更文件，未重新打包 JDK、完整 App 或 DMG。验证使用 **本机回环 HTTP 更新源**，不是 GitHub 正式源。

已通过安装版 patch.82 的真实 `check! → download! → prepare! → restart!` 链路；SHA-256 校验通过，新版进程正常完成项目加载。安装后的 `/Applications/Defold.app` 共 **168 个清单文件的哈希和权限全部匹配** patch.83。安装版界面复查使用该 App 内的已编译 JAR，不使用源码覆盖。未提交、推送或发布远程补丁。

安装版额外验证：通过“项目”菜单实际另存 `layout-83.spine` 并重开，骨骼与动画 ID 保持一致，codec 为 `independent-4.2.43`，播放状态生效。截图 `qa-installed-patch83.png`、`qa-installed-patch83-native-playing.png`。已删除本次生成且核验为 patch.82 的备份，`/Applications` 只保留一个正式 Defold App。

已知诊断文案问题：导入报告中的旧 slots “not previewed” 提示尚未跟随插槽采样实现更新；当前 slots 预览行为已有独立测试，提示文案需要后续修正。事件仅保留／导出，不执行游戏事件回调。

最终完整工作区截图为 `qa-installed-patch83-focused.png`。通过应用自带停靠菜单把 Game 移入工具区，再使用 F6/F7/F8 收起周边面板后拍摄；不伪造正常默认布局。在窄停靠区域中，变换工具浮栏仍可能空间不足，尚需响应式折叠优化。

安装版还实际拖动了画布／层级分隔线至 70:30，验证分区可调整。窗口整体尺寸变化时右侧仍保持宽度优先，需要手动恢复比例。


### patch.85 最新本地交付

坐标空间、自动关键帧、临时姿势、分组/可见轨道打帧、限轴工具、快捷键及显示入口的实现和实际操作截图见 [工具交互回归](skeletal-animation-tools-84.md)。最终全套 **262 tests / 4256 assertions，0 failures / 0 errors**，clj-kondo 0 错误、0 警告。

已通过本地源完成 `83 → 84 → 85` 增量下载、安装和重启，最终唯一 App 为 `/Applications/Defold.app` patch.85；168 个文件全部匹配。最终编译版验证包括“未打帧时不显示假关键帧”、手动打帧、撤销、数值精调和 FFD 编辑。旧版布局章节中的“坐标与自动关键帧尚未实现”是历史状态，现已更新；完整功能矩阵的其余缺口仍成立。
