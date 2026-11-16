# 连接与协议

## 找到正确实例

需要本 fork 的骨骼编辑器和已经启用的本机 nREPL。开发版会在进程工作目录写 `.nrepl-port`；运行日志包含 `Started REPL at nrepl://127.0.0.1:PORT`。用项目/进程对应的文件或日志确认，不扫描随机端口，不误用 `.internal/editor.port`。

若日志/端口不可得，先检查目标 Defold 的启动配置与 `editor/src/clj/editor/debug.clj`。缺少 nREPL 或 `cljfx.plorer` 时明确报告依赖；不要为了连接自动重启用户的未保存会话。此脚本不会代为启用开发端口。

在客户端已提供 Defold nREPL 工具时也可使用它。当前脚本固定只连 `127.0.0.1`，不读取/输出项目 HTTP bearer token，不改 MCP 全局配置。

## 请求

所有命令：`python3 <skill>/scripts/spine_editor.py --port PORT --request request.json`。
省略 `--request` 从 stdin 读取 JSON；超时默认 30 秒，可设 `--timeout`，最高 180 秒。不要通过 shell 拼接未经转义的项目名或提示词。

| action | 必要字段 | 用途 |
|---|---|---|
| capabilities | 无 | 返回版本、会话、实际封装的操作；特性依当前编辑器而定 |
| list | 无 | 列出已打开的骨骼文档，不打开/保存任何资源 |
| inspect | project, resource | 简短对象清单、当前 UI 状态、revision、disk-revision、dirty、pending 状态 |
| read | project, resource, section, id | 精确读取对象，section 为 bones/slots/animations/ik/path/transform/physics |
| edit | project, resource, revision, operations | 1–100 个操作，默认 dry-run；apply=true 才提交 |
| undo | project, resource, revision, undo-token | 仅撤销本适配器最近且仍处于全局撤销栈顶的操作 |
| preview | project, resource, animation | 实际控件选择动画；frame 默认 0，playing 默认 false，fit=true 适配画布 |
| snapshot | project, resource | 返回真实 viewport PNG 的临时绝对路径和 UI 时间；需要保留时复制到证据目录 |
| sample | project, resource, animation, times, slot, bones | 离线求值该内存文档，返回各帧骨骼世界矩阵和附件 bounds；vertices=true 加顶点 |
| save | project, resource, revision, disk-revision, apply=true | 使用编辑器保存管线，只写目标资源；返回 saved-revision/current revision/dirty/disk-revision |

`project` 是真实游戏项目绝对目录，`resource` 是以 `/` 开始的项目资源路径，如 `/art/rat.skeleton`。两者使用 list 的返回值。不要用仓库源码目录代替游戏目录。

输出为 `{"ok":true,"result":...}` 或 `{"ok":false,"error":{"code":...,"message":...}}`。失败时退出码为 1。

## 编辑范例

先 inspect 获取 revision。下一次请求：

```json
{
  "action": "edit",
  "project": "/absolute/game",
  "resource": "/art/rat.skeleton",
  "revision": "COPY_FROM_INSPECT",
  "operations": [
    {"op":"animation.add","values":{"id":"tail_sway","duration":1.0}},
    {"op":"key.set","animation":"tail_sway","bone":"tail_control","property":"rotation","time":0.0,"value":-5.0},
    {"op":"key.set","animation":"tail_sway","bone":"tail_control","property":"rotation","time":0.5,"value":5.0},
    {"op":"key.set","animation":"tail_sway","bone":"tail_control","property":"rotation","time":1.0,"value":-5.0}
  ]
}
```

示例名称需换成项目中存在/新建的正确 ID；旋转值围绕 read 得到的设置姿势旋转调整。默认预演不写，审阅后添加 `"apply":true`。成功返回新 revision 与 undo-token。所有操作先在不可变文档上校验，成功后一次图事务；不重写磁盘 JSON。

`REVISION_CONFLICT`：重新 inspect，再生成针对新文档的操作。
`EDITOR_BUSY`：暂停播放，完成/取消拖拽、绑定或暂存姿势，再读。不要擅自清掉人工暂存。
`HISTORY_CHANGED`：不能继续自动撤销；检查当前历史，必要时在编辑器中选择正确操作。
`DOCUMENT_NOT_OPEN`：打开目标骨骼资源页签，再 list；不要任意切换别人的项目。
连接中断/超时：写入可能已完成，先读结果，禁止自动重试相同变更。

脚本依赖本 fork 的内部命名空间。升级后先 capabilities 和小批预演；不是官方 Defold 或官方 Spine 桌面产品的通用网络协议。

## 预览与保存（protocol 2）

`preview` 经 plorer 虚拟键鼠操作模式、折叠的动画菜单、当前帧和播放按钮。无需桌面焦点，但需要已打开、可布局的编辑器窗口。`frame` 单位为当前 UI 的 fps，不是秒；实际 fps 在响应 `editor.fps` 中。暂停使用 `playing:false` 并指定希望停留的帧。`snapshot` 不生成美术图，也不修改文档；播放中截图时间只是近似，精确画面先暂停定位再截图。

`sample` 时间单位是秒，最多 241 帧、64 个骨骼，slot 为当前皮肤的 mesh/region。使用 `bones:[]` 可仅检查网格。每个骨骼矩阵为 `[a,b,c,d,tx,ty]`，bounds 为 `[minX,minY,maxX,maxY]`；默认不返回全部顶点以节省 token。它验证编辑器求值，不代表官方 Spine runtime 或实际进包播放已通过。

保存前重新 inspect，核对 revision 仍是自己的最终结果。`disk-revision` 是磁盘文件 SHA-256；外部改动会返回 `DISK_CONFLICT`，绝不盲目覆盖。`save` 保存目标文档的全部未保存内容，因此先确认该文档本来的 dirty 状态；不保存其他资源。会锁定编辑器保存/重载队列，后台写入后更新资源缓存。

写入期间的新编辑仍保持 dirty，比较 `saved-revision` 与 `revision`。超时不得重试，先 inspect 并检查文件。首次打开的资源应已有磁盘文件；无 disk-revision 的未落盘资源需先通过编辑器另存为。
