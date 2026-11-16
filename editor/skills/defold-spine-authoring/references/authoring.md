# 对象、操作与创作配方

## 对象

| 对象 | 含义与注意点 |
|---|---|
| bone | 本地 x/y、rotation、scale-x/y、length、parent；设置姿势与动画轨道分开 |
| slot | 将附件挂到骨骼，包含绘制顺序和混合；不是图片文件本身 |
| region | 矩形图片附件，含 path、尺寸和本地变换 |
| mesh | UV、三角形、顶点及可选多骨骼权重；变形关键帧叫 FFD/deform |
| linkedmesh | 共享源网格，可继承源 FFD；不能直接随意重建其拓扑 |
| skin | 插槽名＋附件名＋皮肤名确定附件身份，不能只按文件名判断 |
| IK / path | 约束骨骼的解算方式；控制骨骼与受驱动骨骼分开 |

自动布骨产生的 `*_follow` 是跟随约束，`*_path` 是路径附件，`*_controlN` 是创作控制点，`*_boneN` 是受驱动链。优先关键帧控制骨骼，不要同时反向写驱动链打架。

## 已封装操作

字段遵循编辑器数据模型，结构化请求内对象字段是 JSON；键名用短横线，不是 Spine 原始 JSON 的 camelCase。

| op | 参数 |
|---|---|
| bone.add | values: {id,parent,x,y,rotation,scale-x,scale-y,length}，未给字段走编辑器默认 |
| bone.update | id, values（仅要改的字段）；设置姿势修改 |
| bone.remove | id；沿用编辑器删除/权重处理和校验 |
| slot.add | values: {id,bone,attachment:{type:"region",path:"/images/a.png",width,height,...}} |
| animation.add / animation.update | values 或 id+values；id,duration 等字段 |
| key.set | animation,bone,property,time,value,interpolation（默认 linear） |
| key.remove | animation,bone,property,time |
| deform.set | animation,slot,time,vertices,interpolation；vertices 是位移，不是绝对坐标 |
| mesh.grid | slot,columns,rows；默认 3×3 单元，产生 4×4=16 顶点，仅 region 可用 |
| mesh.bind | slot,bones（目标完整骨骼列表）；保留已有影响，新骨骼可为零权重 |
| mesh.auto-weight | slot,bones,options；常用 options:{"max-influences":4,"falloff":2.0} |
| mesh.restore-image | skin,slot,name；三者来自 read，不猜测 |
| ik.add / ik.update | values 或 id+values，约束字段使用编辑器数据模型 |
| path.add / path.update | values 或 id+values，约束字段使用编辑器数据模型 |

骨骼 key.property：`x`, `y`, `rotation`, `scale-x`, `scale-y`, `shear-x`, `shear-y`。值是**绝对本地值**，时间是秒。interpolation 为 `linear`、`stepped`，或者 `{"type":"bezier","curve":[x1,y1,x2,y2]}`；曲线需遵循现有字段的插值域，修改前读已有轨道。

FFD 加权网格通常按每个 influence 的 XY 存储，不是每顶点只两个数。先读附件，使用 `data/deform-size` 或现有 FFD 推导尺寸，不自行假设。未激活皮肤、关联网格独立时间线不使用通用 deform.set；走高级附件接口。

## 配方

**图片到简单网格动画**：slot.add → mesh.grid → bone.add（如需要）→ mesh.bind → mesh.auto-weight → animation.add → 控制骨骼 key.set → 验证循环和极值。不要重复新建已有插槽或动画；先 inspect/read。

**尾巴**：需要整条弯曲时用现有自动布骨的路径方案；骨骼数量按画面和预算调整。控制点数不等于受驱动骨骼数。默认保持长度，IK 设置 `stretch:false`、`compress:false`，路径避免 `rotate-mode:"chainScale"`。这些名字并非通用“整条链 IK”开关。

**恢复图片**：mesh.restore-image 可撤销。它移除被转换附件的拓扑、权重及 FFD，保留图片引用、插槽与骨骼；关联后代会先脱离为独立网格并保留继承的变形。图片不能表达网格的非刚性扭曲，所以恢复后检查外观。

**保留原始贴图但缩小进包资源**：使用编辑器贴图导出缩放配置，见高级/验证参考。不要直接覆盖源 PNG 或只改附件 width/height 来声称降低内存。
