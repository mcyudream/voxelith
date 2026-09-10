# models.json.gz —— runtime 模型采集产物格式

runtime-worker（headless MC 子进程，ADR 0001）对真实 `BakedModel` 全量采集的落盘产物，
bake 链路优先于静态模型解析消费（mod 方块的真实几何由此进入烘焙）。

- 文件：`models.json.gz`，gzip 压缩的 NDJSON（每行一个 JSON 对象，UTF-8）。
- 首行 meta：`{"format":"voxelith-models/1","sprites":<图集 sprite 数>}`。
  消费方必须校验 `format`，不符即拒绝。
- 其后每行一个方块：

```json
{"block":"minecraft:stone","states":[
  {"state":"","model":"minecraft:block/stone","quads":[
    {"cull":"up","face":"up","tint":-1,"shade":true,
     "tex":"minecraft:block/stone",
     "pos":[0,16,0, 16,16,0, 16,16,16, 0,16,16],
     "uv":[0,0, 16,0, 16,16, 0,16]}]}]}
```

| 字段 | 语义 |
|---|---|
| `state` | 方块状态串 `k=v,k=v`（导出侧按注册表定义顺序；消费侧须做字典序规范化再匹配，属性顺序不保证） |
| `model` | bakedModels 注册表键（调试用） |
| `cull` | 采集时的剔除方向（≈ cullface），无该字段 = 不参与邻居遮挡剔除 |
| `face` | quad 自身朝向（轴向），法向由此推导 |
| `tint` | 染色索引，-1 = 不染色 |
| `shade` | 是否参与明暗着色 |
| `tex` | 图集 sprite id（如 `minecraft:block/stone`） |
| `pos` | 12 个浮点 = 4 顶点 × xyz，模型局部 0~16 空间，已含变体旋转 |
| `uv` | 8 个浮点 = 4 顶点 × uv，0~16 贴图坐标 |

语义对齐 bake-context `Quad` record 1:1；光照 / AO / 群系染色不在产物内，
由 bake 链路逐顶点烘焙。消费实现：`bake.infrastructure.prebaked.NdjsonPrebakedQuadSource`
（端口 `bake.domain.geometry.PrebakedQuadSource`，接入点 `ChunkMeshBuilder.bakeBlock`）。

原版 1.20.1 参考规模：863 方块 / 24135 状态 / 295546 quad / 文件约 1.3MB。
空气类与纯 BER 方块（箱、告示牌等约 140 个）导出状态存在但 quads 为空 ——
消费方对"存在但空"的状态应视为已消费（不降级静态解析），避免把无几何方块画出来。
