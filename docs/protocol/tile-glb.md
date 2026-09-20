# 瓦片 glb 格式

瓦片为二进制 glTF 2.0（glb），每瓦片一个 mesh；primitive 数量取决于是否含半透明几何（水）：无水面时 1 个 primitive，含水面时 2 个（opaque + translucent）。

贴图有两种承载方式：

- **hires 瓦片**：内嵌一张 PNG（图集切片，无损，前端 Nearest 过滤），前端按清单 `atlas` 用共享纹理替换内嵌副本。
- **LOD 瓦片**：全量生成时**不内嵌 PNG**，只带指向该层图集页的 UV（见「LOD 图集页」）；增量重跑或旧格式的瓦片仍内嵌自己的色图（前端按「内嵌优先」兼容）。

## 顶点属性（每个 primitive 各一份，accessor 序号按段顺推）

| 属性 | componentType | type | normalized | 含义 |
|---|---|---|---|---|
| `POSITION` | 5126 (float) | VEC3 | 否 | 瓦片局部坐标（方块单位）；accessor 带 min/max |
| `NORMAL` | 5126 (float) | VEC3 | 否 | 面法线 |
| `TEXCOORD_0` | 5126 (float) | VEC2 | 否 | 贴图 UV：hires 为图集坐标；LOD 为图集页坐标或瓦片局部 0..1（内嵌色图时） |
| `COLOR_0` | 5121 (ubyte) | VEC3 | **是** | 群系染色（tintIndex≥0 的面：草/树叶按群系 colormap，水按群系 water_color；桦叶/杉叶/睡莲为原版固定色）；无染色 = 白 |
| `_LIGHT` | 5121 (ubyte) | VEC3 | **是** | 烘焙光照：R=天空光×17，G=方块光×17，B=AO×85（AO 为 0..3 级遮挡计数） |
| indices | 5125 (uint) | SCALAR | — | 每 quad 2 三角形，段内从 0 起编 |

- `_LIGHT` 为 glTF 自定义属性（下划线前缀约定）。three.js GLTFLoader 会将其小写化为 `_light` 挂到 `geometry.attributes`；`COLOR_0` 映射为 `color`。
- 天空光/方块光取值 0..15，来自存档 NBT（不自行传播），按 MC 角点规则在四顶点采样：取顶点朝向侧 4 个角格（base/side1/side2/corner）中非不透明格的光照平均。
- AO 为 MC 角点遮挡计数 0..3：两侧邻格均遮挡时强制 3；前端换算亮度 `1 - 0.25×n`（可乘 AO 强度参数）。
- 字节布局顺序：opaque 段（positions → normals → uvs → colors → lights → indices）→ translucent 段（同序，可空）→ PNG（无内嵌贴图时省略）。
- 旧格式瓦片（无 `_light` 属性）前端回退为无光照基础材质。
- LOD 无内嵌贴图时材质无 `baseColorTexture`（`alphaMode: OPAQUE`），纹理由前端按清单 `lodAtlases` 挂上，`COLOR_0` 只承载方向明暗（顶面 255、东西 153、南北 204）。

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
- **布局文件**：`atlas-layout.json`（`cellSize` / `cols` / `width` / `height` / `cellIndex`）。
  全量打包是正方形（`height == width`，2 的幂）；**增量扩图集**只向下加行，宽度与列数不变，
  因此 `height` 可能大于 `width`。UV 的 `u` 除以宽度、`v` 除以高度——老单元格的 UV 分毫不动，
  已发布瓦片继续有效（`pixelSize` 作为历史字段仍写出，等于宽度，供老读者读）。
- **清单**：`atlas: { url, size, height, textureCount }`；`size` = 图集宽，`height` = 图集高
  （缺省 = 等于 `size`，老清单兼容）。前端只按 URL 加载 PNG，纹理尺寸由图片本身决定，
  所以非正方形图集无需额外处理。
- **扩容规则**：已发布图集里没有的贴图（新方块 / mod 方块）追加到下一个空闲单元格；
  格子用满（行列都满）时向下加一行，高度按 `cellSize` 增长。取不到像素的贴图不扩容，
  继续走第 0 格兜底（与全量打包一致）。

## LOD 图集页

LOD 瓦片的航拍色图按层拼成共享页，前端每层只解码一张纹理（此前是每瓦片一张 128² PNG，
2176 片即 2000+ 纹理对象与同等数量的解码，是 LOD 规模加载后掉帧的主因之一）。

- **路径**：`tiles/lod/{level}/lod-atlas.png`（放在 `tiles/` 下，随全量发布的整树拷贝一起走）。
- **布局**：按世界瓦片网格定位，槽位 = `(tileX - 该层最小 tileX, tileZ - 该层最小 tileZ)`，行主序。
  空洞瓦片只浪费一个槽位；好处是槽位与「该层存在哪些瓦片」无关，增量重跑后 UV 依然稳定。
- **槽位边长**：L1 = 64、L2 = 32、更深 32，再按 4096 页面上限收缩并对齐到 8 像素（下限 16）。
  随层减半的依据是层级 L 瓦片的屏幕跨度约为 L1 的 1/2^(L-1)，同一分辨率纯属浪费。
- **UV**：瓦片 glb 的 `TEXCOORD_0` 已重映射成图集坐标（瓦片局部 0..1 → 槽位矩形），
  并做**半纹素内缩**——线性过滤下采样点落在槽位边界会与相邻槽位插值出串色条纹。
- **清单**：`lodAtlases: [{ level, url, slotSize, sha1 }]`；`sha1` 是页内容哈希，前端拼
  `?sha=` 做缓存版本戳（页路径固定，不带戳会被 7 天强缓存挡住更新）。
  缺省/空数组 = 该层瓦片各自内嵌色图，前端按内嵌色图渲染。
- **仅全量生成**：增量重跑手上只有被替换 region 的栅格，重拼整页会把未变区域抹成透明，
  因此增量瓦片继续内嵌自己的色图；同一张地图里两种瓦片共存，前端按「内嵌色图优先、否则用该层页」渲染。

> 重新发布时：全量链路把 LOD 阶段的 `LodOutcome.atlasPages()` 传给
> `PublishManifestUseCase.publish(..., atlasPages, ...)`；若改为从盘重建产物
> （`DiskTileIndexer`），需要一并声明各层的图集页，否则清单会丢掉 `lodAtlases`，
> 已按图集 UV 生成的瓦片将只有方向明暗、没有色图。

## 版本与缓存

瓦片 URL 为 `tiles/hires/{x}/{z}.glb` 与 `tiles/lod/{level}/{x}/{z}.glb`，前端按**瓦片自身 sha1**
附加 `?sha={tile.sha1}`（`manifest.tiles[].sha1`）：内容未变的瓦片跨地图版本继续命中 7 天强缓存，
而聚合哈希 `manifest.version` 任一瓦片变动即全图失效。

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
图集 PNG 不量化（像素贴图保持无损）。熵编码见下一节（与量化可叠加）。

## 可选熵编码（EXT_meshopt_compression）

`EncodeOptions.meshopt()` 时，POSITION / NORMAL / TEXCOORD_0 与索引改写成
`EXT_meshopt_compression`：

```json
{
  "buffer": 1, "byteOffset": 0, "byteLength": 96, "byteStride": 12, "target": 34962,
  "extensions": {
    "EXT_meshopt_compression": {
      "buffer": 0, "byteOffset": 0, "byteLength": 41, "byteStride": 12,
      "mode": "ATTRIBUTES", "count": 8, "filter": "NONE"
    }
  }
}
```

- **两条 buffer**：buffer 0 = BIN（真实压缩数据）；buffer 1 = 无 URI 的**占位回退缓冲**，
  `byteLength` = 解压后总字节数，并带 `extensions.EXT_meshopt_compression.fallback = true`
  （与 gltfpack 产物一致）。被压缩的 bufferView 按解压后的布局引用 buffer 1。
- **mode**：顶点属性用 `ATTRIBUTES`（byteStride = 元素字节数：pos 12/8、nrm 12/4、uv 8/4），
  索引用 `TRIANGLES`（byteStride 4，count = 索引数）。
- **不压的部分**：COLOR_0 / `_LIGHT` 每顶点 3 字节（不满足「byteStride 被 4 整除」），
  保持原样写在 BIN 里；图集 PNG 也不压。
- **顶点流版本 0**：three.js 内置解码器（meshoptimizer 0.22 构建）只认 v0。
  索引流用 v1。编码器是仓库内纯 Java 移植（`tile-context/infrastructure/meshopt`），
  前端由 `GlbTileLoader` 构造时 `setMeshoptDecoder(MeshoptDecoder)` 提供解码。
- **扩展是 required**：`extensionsRequired` 会声明 `EXT_meshopt_compression`（与量化叠加时同时声明
  `KHR_mesh_quantization`），没有解码器的加载器会明确报错而不是读出占位数据。
- **压缩收益**（64×64 网格瓦片）：未压缩 258 856 B → 量化 191 256 B → 量化+熵编码 50 228 B（约 1/5）。
