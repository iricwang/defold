# AI Canvas 卡片关系与能力总览

> 适用版本：`1.14.1-patch.111`。对应源码：`editor/src/clj/editor/ai_*.clj`。
> 本文描述的是「卡片之间能怎么连、每张卡能做什么、什么状态下会被拦截」。

## 1. 画布（Canvas）类型

AI Canvas 文档（`.aicanvas`，`schemaVersion 1`）有两种工作流：

| workflow | 名称 | 内容 |
| --- | --- | --- |
| `:document` | 创作画布 | 由模板决定卡片组合，卡片之间连线构成创作 / 生产流水线 |
| `:roles` | 职能画布 | 只有职员（Role）卡片，用于维护 AI 职员库，没有连线 |

创作画布的模板（新建画布时选择）：

| 模板 id | 标题 | 预置卡片命名 |
| --- | --- | --- |
| `ai-pm` | AI-PM · 项目规划 | AI-PM、任务 |
| `concept` | 文本方案 | Action「设计目标」、Result「玩法方案候选」、Assets「方案文档」 |
| `gui` | GUI 方案 | Action「界面设计目标」、Result「界面效果图候选」、Assets「界面资源」、GUI「GUI 界面」 |
| `art-characters` | 美术方案 · 角色与怪物 | 对应命名的 Action / Result / Assets |
| `art-levels` | 美术方案 · 场景与关卡 | 同上 |
| `balancing` | 数值方案 | 同上 |

模板只影响**默认标题**和**新建时预置的卡**；连线规则对所有模板一致。

---

## 2. 卡片一览

| kind | 名称 | 颜色 | 出现在 | 输入接点 | 输出接点 | 由谁执行 |
| --- | --- | --- | --- | --- | --- | --- |
| `:pm` | AI-PM | `#E6AF57` | `ai-pm` 模板 | 隐藏 | 显示 | 远程 Codex（阶段规划） |
| `:task` | TaskCard | `#59BFD9` | `ai-pm` 模板 | 显示 | 显示 | 人工确认后分配 |
| `:person` | Person 负责人 | `#E6AF57` | 非 `ai-pm` 模板 | 隐藏 | 显示 | 本人（串行队列调度） |
| `:action` | Action | `#5B8CFF` | 非 `ai-pm` 模板 | 显示 | 显示但**禁用** | 远程 Codex |
| `:result` | Result | `#AD7BFF` | 非 `ai-pm` 模板 | 显示 | 显示 | 人工确认 |
| `:assets` | Assets | `#48C78E` | 非 `ai-pm` 模板 | 显示 | 显示 | 无（交付容器） |
| `:gui` | GUI | `#59BFD9` | `gui` 模板 | 显示 | 隐藏 | 无（预览 / 绑定） |
| `:role` | 职员 | 角色色 | `:roles` 画布 | 无 | 无 | 无 |

补充：

- 卡片宽度 268px（职员卡 `roles/card-width`）。
- 卡片被上游阻断时整体 55% 透明；其中 Result / Assets / GUI 的内容区直接禁用，Action 的「讨论」按钮禁用。
- 输出接点禁用的三种情况：队列在执行（`执行队列未结束，暂不能修改连线`）、Action（`产出卡片由执行结果自动创建并固定连接`）、上游配置不完整（`graph/output-error` 的文案）。

---

## 3. 关系图

```
【ai-pm 画布 · 规划】

  AI-PM ──task──▶ TaskCard ──task（前置）──▶ TaskCard
                     │
                     └── 人工确认分配 ──▶ 工作画布

【工作画布 · 生产】

  Person ──action──▶ Action ──result──▶ Result ──assets──▶ Assets ──gui────▶ GUI
                        │                                    └─action──▶ Action（资源入库）
                        └── 执行完成后自动产出 Result（固定绑定该 Action）
```

要点：

- **Person → Action**：执行关系（Person 拥有 Action 队列）。
- **Action → Result**：产出关系；Result 由执行结果自动创建并**固定绑定**产出它的 Action。
- **Result → Assets**：采用关系（人工确认候选方案后交付）。
- **Assets → GUI**：资源落地到界面；**Assets → Action**：资源入库 Action 的输入。
- **AI-PM → TaskCard**：归属关系；**TaskCard → TaskCard**：前置关系（不参与执行上游计算）。
- 职能画布的 Role 卡片不参与任何连线。

---
## 4. 连线规则

### 4.1 允许的类型矩阵

| 起点 ↓ / 终点 → | pm | task | person | action | result | assets | gui |
| --- | --- | --- | --- | --- | --- | --- | --- |
| pm | — | ✅ | — | — | — | — | — |
| task | — | ✅（前置） | — | — | — | — | — |
| person | — | — | — | ✅ | — | — | — |
| action | — | — | — | — | ✅ | — | — |
| result | — | — | — | — | — | ✅ | — |
| assets | — | — | — | ✅（资源入库） | — | — | ✅ |
| gui | — | — | — | — | — | — | — |

类型不匹配时报：`连线类型无效，请按 Person → Action → Result → Assets → GUI 重新连接`。

### 4.2 数量与唯一性

| 规则 | 说明 | 报错 |
| --- | --- | --- |
| 目标只有一个上游 | 除「同一来源重复连接」外，一个节点只能有一个执行上游 | `目标节点已有上游，请先断开原连线` |
| 来源只能有一个后继 | Result / GUI（以及职员卡）只有一个输出 | `此接点只支持一个后续节点，请先断开原连线` |
| Person 可接多个 Action | 队列按接点顺序执行 | — |
| AI-PM 可接多个 TaskCard | 规划产出 | — |
| TaskCard 可接多个前置 / 多个后继 | 依赖图，禁止自连与环 | `任务不能作为自己的前置` / `前置任务已连接` / `前置任务不能形成循环` |
| Assets 可同时接 GUI 与资源入库 Action | — | — |

### 4.3 特殊绑定

- **Result 固定绑定 Action**：`source-action` 不匹配时报 `Result 固定绑定产出它的 Action`。所以 Action 的输出接点是禁用的，人工无法手工拖出一条 Action → Result 的线。
- **TaskCard 归属 AI-PM**：`source-pm` 记录归属；在 AI-PM 下新建的 TaskCard 自动带上 pm → task 边。
- **资源入库 Action**：`task-type = "resource-import"`，必须**恰好一个** Assets 输入，否则 `资源入库 Action 需要连接一个 Assets 输入`；Assets → 非资源入库 Action 报 `Assets 只能作为资源入库 Action 的输入`。
- **任务依赖边不参与执行上游**：计算 Action / Person 的可执行性时会跳过 task → task 边，但会参与 `graph/dependency-cycle?` 的环检测。

### 4.4 配置指纹与失效连线

每条非依赖连线在建立时记录 `:source-version` / `:target-version`（资源入库边额外记录 `:input-version`），指纹来自卡片的 `configuration`（kind、标题、prompt、职员配置等）与输入版本（`source-candidate` / `source-revision` / production 清单）。

任何一端配置变化后：

- 连线在画布上显示为**灰色虚线**；
- 拖线 / 新建被拒绝，提示 `节点配置已变更，请右键连线重新确认或重新拉线`；
- 右键连线菜单：**重新确认连接**（重新盖章） / **断开连接**。

> Action → Result 边不能在右键菜单「断开连接」（该项置灰），需要重新确认时用「重新确认连接」重新盖章。

### 4.5 新建方式

| 方式 | 行为 |
| --- | --- |
| 画布空白处右键 | 弹出可创建类型：非 `ai-pm` 模板 → AI-PM / Person / Action / Assets / GUI；`ai-pm` 模板 → 仅 AI-PM |
| 从输出接点拖到空白 | 弹出该类型的后继菜单（`successors`），选中即「新建并连线」；取消则不创建 |
| 从输出接点拖到目标输入接点 | 直接建立连线（会先校验类型、指纹、上游完整性） |
| 卡片右上角「⋯」菜单 | Action：查看结果 / 讨论 / 删除；Assets：讨论 / 资源入库 / 删除；其它：讨论 / 删除 |

新建时的自动填充：

- PM 卡自动指派内置职员 **AI-PM**；新建 Person / 手动 Task 保持待指派；Action 从 Person 解析执行人；
- 从 TaskCard 拖出新 TaskCard：继承同一 AI-PM，并自动补上 pm → 新任务 的归属边；
- 从 AI-PM 拖出 TaskCard：`status=review`、`template=concept`、`depends-on=[]`、`source-pm=AI-PM`；
- 从 Assets 拖出 Action：自动带 `task-type=resource-import`；
- GUI 卡不是拖出来就有的：Assets 交付（`deliver-gui`）、打开 `gui` 模板文档、或新增一组流程时自动创建，同一 Assets 只会有一张 GUI 卡；也可右键手动新建。

> 注：TaskCard 拖出 TaskCard 自动补 PM 归属边 是当前源码的修正；已安装的 patch.111 之前没有这一条，需要下一版打包后才会生效。

---

## 5. 卡片能力详解

### 5.1 AI-PM（`:pm`）

- **存在性**：每个 `ai-pm` 画布自动有且只有一张 AI-PM 卡（`roles/ensure-ai-pm`），新增规划画布时预置 `pm-1`。
- **不可修改**：负责人固定为内置职员 AI-PM，职员配置、标题字段只读；不能删除、不能停用。
- **输入**：项目目标（可空）。**空目标也能拖出 TaskCard**，此时提示「未填写项目目标；可直接拖出 TaskCard 管理后续工作」。
- **能力按钮**：
  - `生成 / 继续阶段规划`：远程 Codex 规划，要求目标非空且节点无 `node-error`；结果只生成待确认 TaskCard，不执行工作；
  - `结束规划队列（当前请求继续）`：结束当前规划会话；
  - `保存修改`：保存标题 / 项目目标。
- **产物**：`ai-pm-plan` 结构化回复 → 拆成 TaskCard（`status=review`）+ pm → task 边 + task → task 前置边；旧的 review 任务标记为 `superseded`。
- **详情**：规划名称、项目目标、项目人力统计（每位职员名下 review/allocated 卡数）。

### 5.2 TaskCard（`:task`）

- **创建**：AI-PM 拖出 / 其它 TaskCard 拖出（前置）/ 规划生成 / 右键。
- **状态**：`review` 待人工确认 → `allocated` 已分配；`rejected` 已驳回；`superseded` 已被新规划替代。
- **字段**：任务名称、阶段、工作目录、工作内容（prompt）、交付要求与验收标准、工作 Canvas 类型。
- **前置任务**：只读列表，来自 task → task 连线（兼容旧的 `:depends-on` 字段）；提示「前置任务须先完成分配；右键连线可断开」。
- **能力按钮**（每行一个）：
  - `保存修改`：写回 `:save-task`；
  - `驳回本项分配`：`rejected`，从规划中移除（执行队列未结束时禁用）；
  - `确认分配并生成 Canvas`：校验任务与前置（前置必须已分配）→ 创建 / 复用工作画布，生成 Person → Action 链并把任务内容写进 Action；
  - `打开工作 Canvas ↗`：已分配时出现。
- **删除**：TaskCard 不允许直接删除，提示使用「驳回」；AI-PM 卡只要还有归属任务也不能直接删除。

### 5.3 Person 负责人（`:person`）

- **创建**：右键新建（非 `ai-pm` 模板）；从 Person 输出接点拖出即新建并接入一个 Action。
- **字段**：负责人（职员，含模型 / 思考强度 / 职责 / 交付要求）、Codex 项目子任务（首次执行自动创建并绑定）、任务队列。
- **队列**：入边顺序即执行顺序，可用 ↑ / ↓ 调整；`+ 添加任务` 选择已有 Action 加入队列。
- **能力按钮**：
  - `按顺序执行 / 继续`：校验 `execution-error`（Person 配置、至少一个 Action、各 Action 上游）；
  - `暂停后续任务`：当前任务跑完即停；
  - `结束队列（当前任务继续）`。
- **执行语义**：逐个提交 Action；Action 完成产出 Result 后暂停，等人工「确认此方案并排队」再继续；Result 确认任务也进入同一队列。
- **状态**：`idle 待执行` / `queued 排队中` / `submitting 提交中` / `running 执行中` / `waiting 等待确认` / `completed 已完成` / `failed 执行失败` / `interrupted 已中断` / `unknown 等待核对` / `paused 已暂停` / `cancelled 已结束`。

### 5.4 Action（`:action`）

- **创建**：Person 拖出 / 右键；Assets 拖出则为资源入库 Action。
- **必填**：负责人（来自 Person 上游）、任务描述（prompt 非空）、上游完整。
- **卡片面**：负责人、状态徽标、任务摘要、输入依据（参考资料）、`+ 添加参考资料`。
- **详情**：负责人、执行状态（含错误与进度）、GUI 上下文（gui 模板）、图片与文档。
  - 任务内容**不在详情编辑**：在卡片底部输入框写，点「保存」（Ctrl / ⌘ + Enter）写回卡片。
- **执行**：远程 Codex 回合；要求已连接可执行 Person、无运行中任务、prompt 非空。完成后自动产出 Result 并固定连线。
- **资源入库**：详情显示输入 Assets、规则文件（`/ai-workspace/config/resource-rules.json`）与「编辑入库规则」；先生成入库方案，人工确认后再写入正式目录、更新图集 / GUI 引用并编译。

### 5.5 Result（`:result`）

- **创建**：Action 执行产出（自动），一个 Action 可有多张候选。
- **内容**：文字方案 / 图片 / 文件列表；可切换候选、人工改写。
- **能力按钮**：`保存修改`（改文字）、`替换图片`、`添加文件`、`移除文件`（×）、`确认此方案并排队`。
- **确认**：把确认任务排入 Person 队列；`pending` 时按钮禁用并显示「已加入负责人队列」。确认后队列继续下一个 Action。
- **锁定**：`tasks/confirmation-locked?`（排队中 / 活跃 / 是该 Person 队列的当前项）时禁止编辑。

### 5.6 Assets（`:assets`）

- **创建**：右键新建；Result 采用候选、开始生产后，它作为交付容器承接资源与拆分计划。
- **内容**：采用的资源（预览 / 来源 / 上下文字段）、归档文档、拆分计划（每个组件的区域坐标与尺寸）、Defold 资源（组件数、已入库 / 暂存）。
- **能力按钮**：`打开 Atlas`、`新建资源入库 Action`（未入库时可用）。
- **输出去向**：GUI（界面）、资源入库 Action。

### 5.7 GUI（`:gui`）

- **创建**：右键；`gui` 模板在 Assets 交付后自动 `ensure-gui-card`，同一 Assets 只有一张 GUI 卡。
- **能力**：界面预览、资源路径（`/game/layouts/screen.gui`）、`绑定` / `↗ 打开` / `↻ 刷新`。
- **注意**：没有输出接点，不参与后续生产。

### 5.8 职员 Role（`:role`，职能画布）

- **用途**：维护 AI 职员库（名称、职能类型、模型、思考强度、职责、交付要求、启用状态）。
- **能力**：新增 / 编辑 / 停用 / 删除；按名称搜索；职能类型配色。
- **内置 AI-PM**：固定存在、只读、不可删除；作为 PM 卡的规划身份；制作人员须明确指派。

---

## 6. 执行与状态机

### 6.1 工作画布执行链

```
Person(队列) → Action(远程 Codex) → Result(人工确认) → Assets(交付/入库) → GUI(绑定预览)
```

1. Person 队列按入边顺序取任务；
2. Action 提交远程回合，期间状态 `submitting → running`；
3. 回合完成 → 产出 Result → Action `completed`，队列 `waiting` 等人工确认；
4. 人工在 Result 上「确认此方案并排队」→ 确认任务入队执行；
5. 队列继续下一个 Action；全部完成 → `completed`。

### 6.2 AI-PM 规划链

```
AI-PM(目标) → ai-pm-plan → TaskCard(review) → 人工确认 → 工作画布(Person→Action…)
```

- 规划只产出卡，不执行；
- 「确认分配并生成 Canvas」会把任务写进工作画布的 Action 并记录 `allocated-path` / `allocated-revision`；
- TaskCard 的前置必须已分配，否则分配报错。

### 6.3 锁定规则

- **画布锁** `tasks/locked?`：任一节点 `submitting/running/waiting/unknown`，或任一 Person 队列 `running/paused` → 禁止改连线、拖出新建。
- **Result 锁**：`confirmation-locked?` → 禁止编辑 / 重复确认。
- **AI-PM 只读**：内置职员与内置卡，任何入口都拒绝修改。

---

## 7. 校验层级

| 校验 | 位置 | 用途 |
| --- | --- | --- |
| `node-error` | 单卡自身 | 名称、职员配置、Action prompt 是否完整 |
| `connection-error` | 单条边 | 类型、数量、指纹、输入边约束 |
| `upstream-error` | 回溯上游 | 从任一上游找不到可执行链时报错 |
| `output-error` | 卡 + 上游 | 输出接点能否拖出 / 新建 |
| `task-error` | 卡 + 上游 | 详情操作按钮是否可用 |
| `execution-error` | Person 起点 | 整条队列能否开始执行 |

典型文案：

| 文案 | 触发 |
| --- | --- |
| `请先填写节点名称` | 标题为空 |
| `请先为 Person 选择职员和角色职能` | Person 未分配 / 被停用 / 缺配置 |
| `请先填写并保存 Action 任务描述` | Action prompt 为空 |
| `请先连接已配置的 Person 负责人` | Action 没有 Person 上游 |
| `此节点尚未连接可执行的上游任务` | 中间卡没有有效上游 |
| `请先从 Person 接点拖出 Action 任务` | Person 没有 Action |
| `资源入库 Action 需要连接一个 Assets 输入` | 入库 Action 输入数量 ≠ 1 |
| `Assets 只能作为资源入库 Action 的输入` | Assets 连到普通 Action |
| `节点配置已变更，请右键连线重新确认或重新拉线` | 指纹失效 |
| `执行队列未结束，暂不能修改连线` | 画布锁 |
| `AI-PM 是内置职员，配置不可修改` / `不能删除` | 内置卡保护 |

---

## 8. 代码索引

| 关注点 | 文件 |
| --- | --- |
| 卡片渲染 / 详情 / 新建菜单 / 连线 UI | `editor/src/clj/editor/ai_canvas.clj` |
| 连接规则、校验、指纹、环检测 | `editor/src/clj/editor/ai_graph.clj` |
| AI-PM 规划、TaskCard 分配 | `editor/src/clj/editor/ai_pm.clj`、`ai_pm_view.clj` |
| 职员模型、内置 AI-PM | `editor/src/clj/editor/ai_roles.clj` |
| 队列、状态、锁定 | `editor/src/clj/editor/ai_tasks.clj` |
| 远程回合 / 应用层流程 | `editor/src/clj/editor/ai_workbench.clj` |
| Result 候选与确认 | `editor/src/clj/editor/ai_results.clj` |
| 资源入库 | `editor/src/clj/editor/ai_resource_import.clj` |
| 文档读写 | `editor/src/clj/editor/ai_document.clj` |
| 画布样式 | `editor/resources/ai-canvas.css` |
