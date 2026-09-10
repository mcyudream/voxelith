# 瓦片 glb 格式（hires）

瓦片为二进制 glTF 2.0（glb），每瓦片一个 mesh；primitive 数量取决于是否含半透明几何（水）：无水面时 1 个 primitive，含水面时 2 个（opaque + translucent）。内嵌一张 PNG 贴图（图集切片，无损，前端 Nearest 过滤）。

## 顶点属性（每个 primitive 各一份，accessor 序号按段顺推）

| 属性 | componentType | type | normalized | 含义 |
|---|---|---|---|---|
| `POSITION` | 5126 (float) | VEC3 | 否 | 瓦片局部坐标（方块单位）；accessor 带 min/max |
| `NORMAL` | 5126 (float) | VEC3 | 否 | 面法线 |
| `TEXCOORD_0` | 5126 (float) | VEC2 | 否 | 内嵌贴图 UV |
| `COLOR_0` | 5121 (ubyte) | VEC3 | **是** | 群系染色（tintIndex≥0 的面：草/树叶按群系 colormap，水按群系 water_color；桦叶/杉叶/睡莲为原版固定色）；无染色 = 白 |
| `_LIGHT` | 5121 (ubyte) | VEC3 | **是** | 烘焙光照：R=天空光×17，G=方块光×17，B=AO×85（AO 为 0..3 级遮挡计数） |
| indices | 5125 (uint) | SCALAR | — | 每 quad 2 三角形，段内从 0 起编 |

- `_LIGHT` 为 glTF 自定义属性（下划线前缀约定）。three.js GLTFLoader 会将其小写化为 `_light` 挂到 `geometry.attributes`；`COLOR_0` 映射为 `color`。
- 天空光/方块光取值 0..15，来自存档 NBT（不自行传播），按 MC 角点规则在四顶点采样：取顶点朝向侧 4 个角格（base/side1/side2/corner）中非不透明格的光照平均。
- AO 为 MC 角点遮挡计数 0..3：两侧邻格均遮挡时强制 3；前端换算亮度 `1 - 0.25×n`（可乘 AO 强度参数）。
- 字节布局顺序：opaque 段（positions → normals → uvs → colors → lights → indices）→ translucent 段（同序，可空）→ PNG。colors/lights 每 quad 12B，保证后续 indices 4 字节对齐。
- 旧格式瓦片（无 `_light` 属性）前端回退为无光照基础材质。

## 流体（水/岩浆）几何

原版 `block/water` 模型无 elements，流体几何不走模型链，按 level 属性合成（LiquidBlockRenderer 简化版，角落坡度从略）：

- 顶面：上方非同类流体时输出，液高 = `level>=8 ? 1 : (8-level)/9`（源头 8/9）；上方同类流体时整柱充满并省略顶面。
- 底面：下方非同类流体且不遮挡其顶面时输出。
- 侧面：邻居非同类流体且不遮挡时输出，贴图底端锚定。
- 贴图：`block/water_still`（顶/底）+ `block/water_flow`（侧面），岩浆同理。
- 流体面不烘焙 AO（`_LIGHT.ao` 恒 0；原版液体无 AO，逐角点 AO 会在水面形成棋盘状明暗噪点），sky/block light 正常烘焙。
- 含水方块：`waterlogged=true` 及海草/海带（seagrass/tall_seagrass/kelp/kelp_plant，隐含水源）在自身模型之外按水源规则合成方块内水体；被宿主自身满覆盖的面剔除（如下半砖底面），水与含水方块互视为同一介质、相邻面互剔。
- 水为半透明（translucent 段），岩浆在不透明段；`COLOR_0` = 群系水色（岩浆不染色）。

## 材质

- material 0（opaque 段）：`baseColorTexture` 指向内嵌 PNG，`alphaMode: MASK` + `alphaCutoff: 0.5`（树叶等裁剪面）。无金属度/粗糙度概念（MeshBasicMaterial 渲染）。
- material 1（translucent 段，仅含水面瓦片）：同贴图，`alphaMode: BLEND`，`baseColorFactor: [1,1,1,0.8]`（水不透明度 0.8）。前端对该材质设 `depthWrite: true` 以避免瓦片间二次混合接缝。

## 图集

- 单元格边长 = 全部贴图最大边长（原版多为 16；`water_flow`/`lava_flow` 首帧为 32×32 时整图集按 32px 单元格）。
- 贴图尺寸不等于单元格时按最近邻缩放铺满单元格（UV 映射假定贴图铺满整格）。
- 动画贴图（竖条）只取第一帧（宽×宽）。

## 版本与缓存

瓦片 URL 为 `tiles/hires/{x}/{z}.glb`，前端请求附加 `?v={manifest.version}` 防止 HTTP 强缓存命中旧版（瓦片同 URL 覆盖发布）。

## 可选量化（KHR_mesh_quantization）

默认仍输出未压缩 float32 POSITION/NORMAL/TEXCOORD_0（与一期字节布局完全一致）。
`EncodeOptions.quantized()` 启用 glTF 扩展 `KHR_mesh_quantization`：

| 属性 | 量化 | 对齐 |
|---|---|---|
| POSITION | normalized int16 VEC3，node.scale = 瓦片坐标 maxAbs | 每顶点 8B（xyz + pad） |
| NORMAL | normalized int8 VEC3 | 每顶点 4B（xyz + pad） |
| TEXCOORD_0 | normalized uint16 VEC2 | 每顶点 4B |
| COLOR_0 / `_LIGHT` / indices | 不变 | 同未压缩 |

three.js `GLTFLoader` 原生解码该扩展，前端无需 meshopt decoder。
图集 PNG 不量化（像素贴图保持无损）。`EXT_meshopt_compression` 熵编码仍预留，需 JNI/CLI 编码器后再接。
