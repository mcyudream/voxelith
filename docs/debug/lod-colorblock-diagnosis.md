# LOD 下半屏色块 / 4fps 故障诊断与修复方案

> **修复进展（2026-09-13 更新）**
> - **§1.1 判据（首要嫌疑）：已修复。** `GlbTileLoader` 不再用 `magFilter` 反推层级，
>   改由 `TileManager` 显式传 `level`（`load(url, level)` → `toLitMaterial(..., level)`，
>   hires 分支走 `resolveTileTexture`，LOD 分支走 `resolveLodTexture`）。
> - **下半屏彩虹噪点：上游已修。** 远端 `4d9e5f7` 关掉了共享图集的 mip + anisotropy
>   （256² 里 69 格跨格混合），并加了 `window.__map.setLayer('hires'|'lod'|'all')` 隔离开关。
> - **§6 的 LOD 逐瓦片纹理（4fps 主因）：已按 §6.5 后端方案实现。**
>   `GenerateLodPyramidUseCase` 每层把该层全部瓦片色图拼成 `tiles/lod/{level}/lod-atlas.png`，
>   瓦片 glb 只带重映射后的图集 UV（半纹素内缩防串色），清单新增
>   `lodAtlases: [{level,url,slotSize,sha1}]`，前端 `TileManager` 按层加载一张共享页。
>   槽位边长随层减半（L1 64 / L2 32 / 更深 32，页面上限 4096），整页像素量降到约 1/4。
>   增量重跑仍逐瓦片内嵌色图，前端按「内嵌优先」兼容两种瓦片。
> - **§6.4 的缓存上限：已按设备档位收紧**（高/中/低 → 4096/2048/1024 片）。
>   near/far 1e6 深度比与 polygonOffset 未动。
> - 剩余：几何合批 / instancing（§6.4 后半）与 meshopt 熵编码仍未做。

> 现象：顶点爆炸已消除，上半屏正常；下半屏出现大面积**红/青/黑色块**，帧率 **4fps**。
> HUD：`瓦片 2176/27947`、`视距 19 区块（自动）`。
> 关联线索：共享图集改造只覆盖 hires；「swust LOD 色图两极反转」未定位。

---

## 一、结论先行

| 候选原因 | 判断 | 依据 |
|---|---|---|
| **LOD 瓦片被误判成 hires，套上了方块图集** | ⭐ **首要嫌疑** | 唯一能精确解释「红/青/黑**多色块**」的机制（见 §1.1） |
| LOD 逐瓦片独立纹理造成显存/纹理对象爆炸 | ⭐ 次要且确凿 | 代码确为「一瓦片一纹理」，2176 瓦片 → 2000+ 纹理对象；解释 4fps |
| LOD 与 hires 同 Y 层 Z-Fighting | ❌ 不是色块成因 | 代码已给 LOD 加 `polygonOffset`；且 Z-Fighting 表现为**闪烁条纹**，不是整块纯色（但仍建议按 §5 规范化） |
| 坐标精度 / 浮点原点 | ❌ 本次不成立 | 上一轮已修；且症状是整块错色而非抖动 |

### 1.1 为什么「误判成 hires」能精确解释红/青/黑

`GlbTileLoader.toLitMaterial()` 用 **magFilter 反推瓦片类型**：

```ts
const linear = source.map.magFilter === THREE.LinearFilter;   // true=LOD, false=hires
if (!linear && this.sharedAtlas) { material.map = this.sharedAtlas; }  // 换共享方块图集
```

而 LOD 的 UV 是 **本瓦片色图的 0..1 全幅**（`HeightfieldLodMesher.uvRect`）。
一旦某个 LOD 瓦片的 `magFilter` 不是 `LinearFilter`（例如 glTF sampler 未声明、或 GLTFLoader 走了默认值 `LinearFilter`/`NearestFilter` 的边界情况），它就会被当作 hires：

> **拿「0..1 全幅 UV」去采样「整张方块图集」** → 每个 LOD 瓦片显示整张图集（几百种方块贴图缩放铺满） → 远看就是**红/青/黑的高饱和多色块**。

这与截图症状**完全吻合**，也是「共享图集改动只动 hires 路径」这句话埋下的雷：判据本身不可靠。

> 反向误判（hires → LOD）同样有害：hires 会保留内嵌图集副本不释放 → 显存线性膨胀。

**所以第一步不是改，是量**（§2 的探测代码会直接打印出误判数量）。

---

## 二、第一步：先量后修 —— 显存 / 材质绑定探测

在浏览器控制台执行（`App.vue` 已暴露 `window.__map`）：

```js
(() => {
  const { engine, tileManager: tm } = window.__map;
  const r = engine.renderer;

  // ---------- 1. 渲染器计数（显存 / draw call 的真实读数）----------
  console.group('%c[1] Renderer.info', 'font-weight:bold');
  console.table({
    纹理对象: r.info.memory.textures,
    几何对象: r.info.memory.geometries,
    本帧drawCall: r.info.render.calls,
    本帧三角形: r.info.render.triangles,
    程序数: r.info.programs?.length ?? -1,
  });
  console.groupEnd();

  // ---------- 2. 按层级统计瓦片 & 纹理归属 ----------
  const shared = tm.sharedAtlas;               // 共享方块图集（hires 用）
  const byLevel = {};                          // level -> {tiles, meshes, ownTex, sharedTex, noTex}
  const textures = new Map();                  // texture -> 引用次数
  let misclassified = [];                      // 误判清单

  for (const [key, live] of tm.live) {
    const level = Number(key.split(':')[0]);
    const b = (byLevel[level] ??= { tiles: 0, meshes: 0, ownTex: 0, sharedTex: 0, noTex: 0 });
    b.tiles++;
    live.group.traverse((n) => {
      if (!n.isMesh) return;
      b.meshes++;
      const map = n.material?.map ?? null;
      if (!map) { b.noTex++; return; }
      textures.set(map, (textures.get(map) ?? 0) + 1);
      if (map === shared) b.sharedTex++; else b.ownTex++;
      // 误判判定：level>0 却用共享方块图集，或 level==0 却用私有纹理
      if (level > 0 && map === shared) misclassified.push({ key, level, kind: 'LOD用了方块图集' });
      if (level === 0 && map !== shared && shared) misclassified.push({ key, level, kind: 'hires未用共享图集' });
    });
  }

  console.group('%c[2] 按层级统计', 'font-weight:bold');
  console.table(byLevel);
  console.groupEnd();

  // ---------- 3. 去重后的真实纹理对象数 & 显存估算 ----------
  let vram = 0, lodTex = 0, hiresTex = 0;
  for (const [tex, refs] of textures) {
    const img = tex.image;
    const w = img?.width ?? 0, h = img?.height ?? 0;
    const bytes = w * h * 4 * (tex.generateMipmaps ? 1.34 : 1);
    vram += bytes;
    if (tex === shared) hiresTex++; else lodTex++;
  }
  console.group('%c[3] 显存估算', 'font-weight:bold');
  console.table({
    '不同纹理对象数': textures.size,
    'LOD私有纹理数': lodTex,
    '共享图集数': hiresTex,
    估算显存MB: +(vram / 1048576).toFixed(1),
  });
  console.groupEnd();

  // ---------- 4. 误判清单 ----------
  console.group('%c[4] 材质绑定误判', 'font-weight:bold');
  console.log('误判数:', misclassified.length);
  console.table(misclassified.slice(0, 20));
  if (misclassified.length) {
    console.warn('⚠ 存在 LOD/hires 误判 —— 这就是红/青色块的直接来源');
  } else {
    console.log('未发现误判；色块另有原因，继续看 §4 的 UV/flipY 探测');
  }
  console.groupEnd();

  // ---------- 5. 全局兜底：纹理对象 vs 瓦片数 ----------
  if (r.info.memory.textures > 1500) {
    console.warn(`⚠ 纹理对象 ${r.info.memory.textures} 个，已进入高开销区：逐瓦片纹理绑定会让 CPU 提交成为瓶颈（4fps 的主因之一）`);
  }
})();
```

**判读**：

- `[4]` 误判数 > 0 → 直接按 §3 修 `linear` 判据，色块问题即解决。
- `[3]` LOD 私有纹理数 ≈ 瓦片数（2000+）→ 按 §6 做图集化，解决 4fps。
- `[1]` drawCall ≈ 瓦片数 × 1~2 → 说明瓶颈在**提交次数**，图集化只能减轻纹理切换，还需合批（见 §6.4）。

---

## 三、修复：把「类型判据」从 magFilter 换成**显式层级**

`magFilter` 是渲染参数，不该承担「我是 LOD 还是 hires」的语义。改为由 `TileManager` 显式告知。

**`GlbTileLoader.ts`** —— 改签名并显式传层级：

```ts
export interface TileLoadRequest {
  url: string;
  /** 0 = hires；>0 = LOD 层级。取代原先靠 magFilter 反推。 */
  level: number;
}

export class GlbTileLoader {
  private readonly loader = new GLTFLoader();
  private readonly sharedAtlas?: THREE.Texture;
  /** LOD 色图图集（可选，见 §6）；为空则 LOD 保留逐瓦片纹理。 */
  private readonly lodAtlas?: LodColormapAtlas;

  constructor(options: GlbTileLoaderOptions = {}) {
    this.sharedAtlas = options.sharedAtlas;
    this.lodAtlas = options.lodAtlas;
    if (this.sharedAtlas) {
      this.sharedAtlas.userData[SHARED_TEXTURE_KEY] = true;
      this.sharedAtlas.magFilter = THREE.NearestFilter;
      this.sharedAtlas.minFilter = THREE.NearestMipmapLinearFilter;
      this.sharedAtlas.generateMipmaps = true;
      this.sharedAtlas.anisotropy = 8;
    }
  }

  async load(req: TileLoadRequest): Promise<THREE.Group> {
    const gltf = await this.loader.loadAsync(req.url);
    const root = gltf.scene;
    validateTileGroup(root, req.url);
    const isLod = req.level > 0;                 // ★ 显式判据，不再猜 magFilter
    const replaced = new Set<THREE.Texture>();
    const rewritten = new Set<THREE.BufferGeometry>();
    root.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        node.material = this.toLitMaterial(node, isLod, replaced, rewritten);
        node.matrixAutoUpdate = false;
        node.updateMatrix();
      }
    });
    return root;
  }

  private toLitMaterial(
    mesh: THREE.Mesh,
    isLod: boolean,
    replaced: Set<THREE.Texture>,
    rewritten: Set<THREE.BufferGeometry>,
  ): THREE.MeshBasicMaterial {
    const source = mesh.material as THREE.MeshStandardMaterial;
    const geometry = mesh.geometry;
    const hasBakedLight = geometry.hasAttribute("_light");
    const material = new THREE.MeshBasicMaterial({
      map: source.map ?? null,
      alphaTest: source.alphaTest > 0 ? source.alphaTest : 0,
      transparent: source.transparent,
      opacity: source.transparent ? source.opacity : 1,
      side: THREE.FrontSide,
      vertexColors: geometry.hasAttribute("color"),
    });
    if (source.transparent) material.depthWrite = true;

    if (material.map && source.map) {
      if (isLod) {
        // LOD：航拍色图，线性过滤 + ClampToEdge
        material.map.magFilter = THREE.LinearFilter;
        material.map.minFilter = THREE.LinearMipmapLinearFilter;
        material.map.wrapS = THREE.ClampToEdgeWrapping;
        material.map.wrapT = THREE.ClampToEdgeWrapping;
        material.map.generateMipmaps = true;
        material.map.anisotropy = 1;

        // 可选：并入共享图集（§6），成功则改 UV 并释放私有纹理
        if (this.lodAtlas) {
          const embedded = material.map;
          const img = embedded.image as { width?: number; height?: number } | null;
          const slot = img?.width && img?.height
            ? this.lodAtlas.add(embedded.image as CanvasImageSource, img.width, img.height)
            : null;
          if (slot && !rewritten.has(geometry)) {
            rewritten.add(geometry);
            LodColormapAtlas.remapUv(geometry, slot);
            material.map = slot.texture;
          }
          if (slot) {
            if (!replaced.has(embedded)) { replaced.add(embedded); embedded.dispose(); }
          } else {
            material.map = embedded;               // 图集满：回退独占纹理
          }
        }
      } else {
        // hires：方块图集，像素锐利
        material.map.magFilter = THREE.NearestFilter;
        material.map.minFilter = THREE.NearestMipmapLinearFilter;
        material.map.generateMipmaps = true;
        material.map.anisotropy = 8;
        if (this.sharedAtlas) {
          const embedded = material.map;
          material.map = this.sharedAtlas;
          if (!replaced.has(embedded)) { replaced.add(embedded); embedded.dispose(); }
        }
      }
      material.map.needsUpdate = true;
    }
    // …… onBeforeCompile / customProgramCacheKey / source.dispose() 保持不变 ……
    source.dispose();
    return material;
  }
}
```

**`TileManager.ts`** 对应改动（一处）：

```ts
const group = await this.loader.load({
  url: `${this.mapBaseUrl}/${tile.url}?v=${this.manifest.version}`,
  level: tile.level,                      // ★ 显式层级
});
```

> 这一步是**最小、最高优先级**的修复。改完先跑 §2 的探测确认误判归零、色块消失，再决定是否继续做 §6。

---

## 四、「两极反转」诊断：UV / flipY / colorSpace

先看代码事实（已核对）：

| 环节 | 现状 | 结论 |
|---|---|---|
| `AerialRaster.downsample` | `argb[tz * texSize + tx]`，tz 随世界 **Z 增大** | 行 0 = 最小 Z |
| `PngImageCodec` | `image.setRGB(0,0,w,h,argb,0,w)` 顶行在前 | PNG 行 0 = argb 行 0 ✅ |
| `HeightfieldLodMesher.uvRect` | `v = (z - originZ)/coverage` | v=0 ↔ 最小 Z ✅ |
| glTF / three.js | `flipY = false`（GLTFLoader 对 glTF 贴图的约定） | v=0 ↔ 图行 0 ✅ |

**即：静态代码路径上不存在 V 翻转**，四段自洽。所以「两极反转」要么是 **① 上游色值空间被双重编码**（见下），要么是 **② 图集替换后 UV 未同步**（上一轮改动的遗留）。

### 4.1 控制台探测（运行时实证，而非读代码）

```js
(() => {
  const tm = window.__map.tileManager;
  const shared = tm.sharedAtlas;

  // 取一个 LOD 瓦片和它的 mesh
  const lodEntry = [...tm.live.entries()].find(([k]) => Number(k.split(':')[0]) > 0);
  const hiresEntry = [...tm.live.entries()].find(([k]) => Number(k.split(':')[0]) === 0);
  if (!lodEntry) return console.warn('暂无已加载 LOD 瓦片，等瓦片多加载一些再跑');

  const pick = (entry) => {
    let m = null;
    entry[1].group.traverse((n) => { if (!m && n.isMesh) m = n; });
    return m;
  };
  const lm = pick(lodEntry), hm = hiresEntry && pick(hiresEntry);

  const describe = (mesh, tag) => {
    const map = mesh?.material?.map;
    const uv = mesh?.geometry?.getAttribute('uv');
    let uMin = 0, uMax = 0, vMin = 0, vMax = 0;
    if (uv) {
      uMin = uMax = uv.getX(0); vMin = vMax = uv.getY(0);
      for (let i = 1; i < uv.count; i++) {
        const u = uv.getX(i), v = uv.getY(i);
        uMin = Math.min(uMin, u); uMax = Math.max(uMax, u);
        vMin = Math.min(vMin, v); vMax = Math.max(vMax, v);
      }
    }
    console.group(`%c${tag}`, 'font-weight:bold');
    console.table({
      'level': Number(mesh.__tileKey?.split?.(':')[0] ?? -1),
      'map.width': map?.image?.width ?? -1,
      'map.height': map?.image?.height ?? -1,
      'map.flipY': map?.flipY,
      'map.colorSpace': map?.colorSpace,      // 期望 'srgb'
      'map.magFilter': map?.magFilter,        // 9729?1006?
      'map.minFilter': map?.minFilter,
      'map.wrapS': map?.wrapS,                // 期望 1001 (ClampToEdge)
      'map.wrapT': map?.wrapT,
      'isSharedAtlas': map === shared,
      'uv.count': uv?.count ?? 0,
      'uv.u范围': `${uMin.toFixed(4)} ~ ${uMax.toFixed(4)}`,
      'uv.v范围': `${vMin.toFixed(4)} ~ ${vMax.toFixed(4)}`,
    });
    console.groupEnd();
    return { mesh, map, uMin, uMax, vMin, vMax };
  };

  const L = describe(lm, 'LOD 瓦片');
  if (hm) describe(hm, 'hires 瓦片');

  // 期望值断言
  const errs = [];
  if (L.map && L.map.colorSpace !== 'srgb') errs.push(`LOD colorSpace=${L.map.colorSpace}，应为 srgb（否则线性工作流下会整体偏色）`);
  if (L.map && L.map.flipY !== false)        errs.push(`LOD flipY=${L.map.flipY}，应为 false（否则上下颠倒 = 两极反转）`);
  if (L.map && (L.map.wrapS !== 1001 || L.map.wrapT !== 1001)) errs.push('LOD wrap 非 ClampToEdge，边缘会串色');
  if (L.vMax - L.vMin < 0.001) errs.push('LOD 的 v 跨度≈0：UV 未随 Z 变化（几何 UV 生成错误）');
  if (errs.length) console.error('❌ 发现问题：\n' + errs.join('\n'));
  else console.log('✅ LOD 贴图配置与 UV 范围均在预期内');
})();
```

### 4.2 运行时「V 翻转」一键验证

若肉眼怀疑上下颠倒，直接翻转 UV 的 v 分量即可判定（不动后端）：

```js
// 把全部 LOD 瓦片的 v 翻转为 1-v（判定用；确认后再改后端或保留）
window.__map.tileManager.live.forEach((live, key) => {
  if (Number(key.split(':')[0]) === 0) return;   // 只动 LOD
  live.group.traverse((n) => {
    if (!n.isMesh) return;
    const uv = n.geometry.getAttribute('uv');
    if (!uv) return;
    for (let i = 0; i < uv.count; i++) uv.setY(i, 1 - uv.getY(i));
    uv.needsUpdate = true;
  });
});
```

- 翻转后**变正常** → 确认是 V 翻转：修 `HeightfieldLodMesher.uvRect`（`v0`/`v1` 对调）或 `AerialRaster.downsample` 的行序。
- 翻转后**无变化** → 不是 UV 翻转，转查色值空间。

### 4.3 色值空间双重编码（「两极反转」的另一可能）

`ColorSpace` 的类注释自己就写明了这个坑：

> 凡涉及平均、乘法等运算必须在线性空间进行，否则输出端再经一次线性→sRGB 编码会导致**中间调被提亮、LOD 与 hires 色域不一致**。

链路要求：`TextureColorSampler.sampleUvAverageRgb()` 必须返回**线性**字节。请核对该实现——若它返回的是 **sRGB** 字节，则 `AerialRaster.splat` 会把它当线性做加权平均，`boxAverage` 再套一次 `linearToSrgbRgb`，**等于做了两次 gamma**：暗部被大幅抬亮、对比度塌陷，观感即「两极反转」。

快速验证（把 LOD 色图直接抓出来看）：

```js
// 把第一个 LOD 瓦片的色图存成图片，肉眼比对是否「发灰/过曝」
(() => {
  const tm = window.__map.tileManager;
  for (const [key, live] of tm.live) {
    if (Number(key.split(':')[0]) === 0) continue;
    let map = null;
    live.group.traverse((n) => { if (!map && n.isMesh) map = n.material?.map; });
    const img = map?.image;
    if (!img) continue;
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    c.getContext('2d').drawImage(img, 0, 0);
    const a = document.createElement('a');
    a.href = c.toDataURL('image/png');
    a.download = `lod-colormap-${key.replace(/:/g, '_')}.png`;
    a.click();
    console.log('已导出', key, '—— 与同区域 hires 截图对比：若明显发灰/发白即为双重 gamma');
    break;
  }
})();
```

---

## 五、排障隔离：一键只渲染 LOD / 只渲染 hires

`TileManager.update()` 每帧会重写 `group.visible`，所以直接改 `visible` 会被覆盖。用**包装 update、在原逻辑之后覆盖**的方式：

```js
(() => {
  const tm = window.__map.tileManager;
  if (tm.__origUpdate) { console.log('已安装过，跳过'); return; }
  tm.__origUpdate = tm.update.bind(tm);
  tm.__layerFilter = 'all';       // 'all' | 'lodOnly' | 'hiresOnly' | 'none'

  tm.update = function (camera) {
    tm.__origUpdate(camera);
    const f = tm.__layerFilter;
    if (f === 'all') return;
    for (const [key, live] of this.live) {
      const level = Number(key.split(':')[0]);
      const isLod = level > 0;
      const show =
        f === 'lodOnly' ? isLod :
        f === 'hiresOnly' ? !isLod : false;
      live.group.visible = show;
    }
  };

  // 用法：
  window.__layer = {
    lodOnly()   { tm.__layerFilter = 'lodOnly';   console.log('只渲染 LOD'); },
    hiresOnly() { tm.__layerFilter = 'hiresOnly'; console.log('只渲染 hires'); },
    none()      { tm.__layerFilter = 'none';      console.log('全部隐藏'); },
    all()       { tm.__layerFilter = 'all';       console.log('恢复全部'); },
    /** 只显示 <= maxLevel 的层级（逐层排除法） */
    upTo(maxLevel) { tm.__layerFilter = 'custom'; tm.__maxLevel = maxLevel; },
  };
  console.log('已安装：__layer.lodOnly() / hiresOnly() / none() / all()');
})();
```

**判读矩阵**：

| 操作 | 结果 | 结论 |
|---|---|---|
| `__layer.hiresOnly()` | 色块消失 | 色块来自 **LOD 路径** → 主攻 §3 判据 + §6 图集 |
| `__layer.hiresOnly()` | 色块仍在 | 色块来自 **hires 路径** → 检查共享图集本身是否损坏（导出 `tm.sharedAtlas.image` 看） |
| `__layer.lodOnly()` | 色块消失 | 色块是 **LOD 遮挡/深度**问题 → 主攻 §5 polygonOffset + 深度精度 |
| `__layer.none()` | 仍有色块 | 不是瓦片问题（可能是背景/后处理） |

补充：`hiresOnly` 时若 FPS 明显回升，说明 4fps 主要由 LOD 的纹理/提交量贡献，§6 收益可量化。

---

## 六、根治：LOD 色图并入共享图集

### 6.1 目标

- **纹理对象**：从「≈LOD 瓦片数」降到「图集页数」（约 1/256）。
- **纹理绑定**：每帧从 2000+ 次降到页数次。
- **不动后端**即可上线（前端运行时合图）；后端方案见 §6.5。

### 6.2 新增 `LodColormapAtlas.ts`

```ts
import * as THREE from "three";

export interface LodAtlasSlot {
  texture: THREE.Texture;
  /** 图集内 UV 子矩形（已内缩半纹素，防跨格串色） */
  u0: number; v0: number; u1: number; v1: number;
}

interface Page {
  canvas: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D;
  texture: THREE.CanvasTexture;
}

/**
 * LOD 航拍色图运行时图集。
 *
 * 每页 cols×rows 个 cell×cell 的格子，整页一张 CanvasTexture（单页纹理对象）。
 * 约定：flipY = false + SRGBColorSpace —— 与 glTF 贴图及 PNG 行序（行 0 = 最小 Z）一致，
 * 因此合图**不改变** v 方向，不会引入「上下反转」。
 */
export class LodColormapAtlas {
  private readonly cell: number;
  private readonly cols: number;
  private readonly rows: number;
  private readonly perPage: number;
  private readonly maxPages: number;
  private readonly pages: Page[] = [];
  private next = 0;
  private readonly dirty = new Set<number>();

  constructor(cell = 128, cols = 16, rows = 16, maxPages = 32) {
    this.cell = cell; this.cols = cols; this.rows = rows;
    this.perPage = cols * rows;
    this.maxPages = maxPages;
  }

  get used(): number { return this.next; }
  get pageCount(): number { return this.pages.length; }

  /** 估算显存（字节），用于 HUD 观测。 */
  get estimatedBytes(): number {
    return this.pages.length * (this.cols * this.cell) * (this.rows * this.cell) * 4 * 1.34;
  }

  /**
   * 写入一张 LOD 色图，返回其在图集中的 UV 子矩形；图集满返回 null（调用方回退独占纹理）。
   */
  add(image: CanvasImageSource, srcW: number, srcH: number): LodAtlasSlot | null {
    const pageIndex = Math.floor(this.next / this.perPage);
    if (pageIndex >= this.maxPages) return null;
    const page = this.page(pageIndex);
    const slot = this.next % this.perPage;
    const cx = (slot % this.cols) * this.cell;
    const cy = Math.floor(slot / this.cols) * this.cell;

    // 清格后再画：避免上一轮残留影响 mip 生成
    page.ctx.clearRect(cx, cy, this.cell, this.cell);
    page.ctx.imageSmoothingEnabled = true;
    page.ctx.drawImage(image, 0, 0, srcW, srcH, cx, cy, this.cell, this.cell);

    this.next++;
    this.dirty.add(pageIndex);

    // 半纹素内缩：防止 Linear + mip 采样到相邻格
    const texW = this.cols * this.cell;
    const texH = this.rows * this.cell;
    const pad = 0.5;
    return {
      texture: page.texture,
      u0: (cx + pad) / texW,
      v0: (cy + pad) / texH,
      u1: (cx + this.cell - pad) / texW,
      v1: (cy + this.cell - pad) / texH,
    };
  }

  /** 每帧末调用一次：脏页一次性上传（避免逐瓦片重传整页，2048² 一页 16MB）。 */
  flush(): void {
    for (const i of this.dirty) {
      const p = this.pages[i];
      p.texture.needsUpdate = true;
    }
    this.dirty.clear();
  }

  dispose(): void {
    for (const p of this.pages) p.texture.dispose();
    this.pages.length = 0;
    this.next = 0;
    this.dirty.clear();
  }

  private page(index: number): Page {
    let p = this.pages[index];
    if (p) return p;
    const w = this.cols * this.cell;
    const h = this.rows * this.cell;
    const canvas = document.createElement("canvas");
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext("2d", { willReadFrequently: false })!;
    const texture = new THREE.CanvasTexture(canvas);
    texture.flipY = false;                       // ★ 与 glTF 约定一致，保证 v 方向不变
    texture.colorSpace = THREE.SRGBColorSpace;   // ★ 色图是 sRGB 创作色
    texture.magFilter = THREE.LinearFilter;
    texture.minFilter = THREE.LinearMipmapLinearFilter;
    texture.wrapS = THREE.ClampToEdgeWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;
    texture.generateMipmaps = true;
    texture.anisotropy = 1;
    p = { canvas, ctx, texture };
    this.pages[index] = p;
    return p;
  }

  /** 把几何的 0..1 UV 重映射到图集子矩形（原地改写，只做一次）。 */
  static remapUv(geometry: THREE.BufferGeometry, slot: LodAtlasSlot): void {
    const uv = geometry.getAttribute("uv") as THREE.BufferAttribute | undefined;
    if (!uv) return;
    const du = slot.u1 - slot.u0;
    const dv = slot.v1 - slot.v0;
    for (let i = 0; i < uv.count; i++) {
      uv.setXY(i, slot.u0 + uv.getX(i) * du, slot.v0 + uv.getY(i) * dv);
    }
    uv.needsUpdate = true;
    // UV 变了，包围球/包围盒与几何缓存键同步失效
    geometry.computeBoundingSphere();
    geometry.computeBoundingBox();
  }
}
```

### 6.3 接线（`TileManager`）

```ts
// 构造时
this.lodAtlas = typeof document !== "undefined" ? new LodColormapAtlas(128, 16, 16, 32) : undefined;
this.loader = options.loader ?? new GlbTileLoader({
  sharedAtlas: this.sharedAtlas ?? undefined,
  lodAtlas: this.lodAtlas,
});
// 帧末一次性上传脏页（放在 update 末尾或 App 的 frameHook 里）
this.lodAtlas?.flush();

// dispose 时
this.lodAtlas?.dispose();
```

### 6.4 还治不了 4fps？看 draw call

图集化把**纹理绑定**降到个位数，但**每瓦片仍是独立 mesh**。若 §2 的 `renderInfo.calls` ≈ 瓦片数，说明瓶颈在提交次数，需要进一步：

- 把同层 LOD 瓦片 `mergeGeometries`（同页纹理可合并）——收益最大；
- 或对 LOD 用 `InstancedMesh`（每页一批）；
- 或降低 LOD 加载上限：`TileManager` 的 `maxLoaded` 当前默认 **4096**，对 LOD 偏高，可按设备档位下调（如 1024）。

另外 `MapEngine` 的相机 `near=0.1 / far=100000`，深度比 1e6，远端精度很差；建议 `near` 提到 `1`（或启用 `logarithmicDepthBuffer`），可顺带缓解 LOD/hires 重叠处的深度抖动。

### 6.5 后端方案（更彻底，后续迭代）

前端合图是止血；根治应让 **LOD 色图按层级打成一整张图集**，与 hires 图集同构：

1. `GenerateLodPyramidUseCase` 每层生成后，把该层全部瓦片色图拼成 `lod-atlas-{level}.png` + `lod-atlas-{level}.json`（格号 → 世界瓦片）；
2. `HeightfieldLodMesher` 的 UV 直接输出**图集坐标**（不再 0..1）；
3. `manifest.json` 增加 `lodAtlases: [{ level, url, cellSize }]`；
4. 前端 LOD 与 hires 走**同一套**共享图集逻辑，`GlbTileLoader` 的 `isLod` 分支可完全合并。

这样纹理对象恒定为「层数 + 1」，且 UV 在后端一次算对，前端不再改写几何。

---

## 七、polygonOffset：LOD / hires 深度共存

现有代码只给 LOD 加了偏移（`TileManager.loadTile`），建议**显式化 + 可配置**，并补上 hires 的基准：

```ts
// TileManager 内，加载完成后统一设置
private applyDepthBias(group: THREE.Group, level: number): void {
  group.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) return;
    const m = node.material as THREE.MeshBasicMaterial;
    if (level > 0) {
      // LOD 作为背景层：整体往深度后方推，且层级越粗推得越多
      m.polygonOffset = true;
      m.polygonOffsetFactor = level * 1.0;
      m.polygonOffsetUnits = level * 2.0;
      m.depthWrite = true;
    } else {
      // hires 基准层：不偏移，确保在与 LOD 重叠处胜出
      m.polygonOffset = false;
      m.polygonOffsetFactor = 0;
      m.polygonOffsetUnits = 0;
    }
    m.needsUpdate = true;    // polygonOffset 变更需触发材质刷新
  });
}

// loadTile() 中，scene.add(group) 之前：
this.applyDepthBias(group, tile.level);
```

**要点**

- `polygonOffset` 作用于**光栅化深度**，不影响几何；LOD 偏移为正 = 推向远处，hires 自然胜出。
- **不要**对共享材质改偏移（同一材质被多瓦片复用会互相覆盖）。本项目每瓦片新建材质，安全。
- 半透明水面（`transparent=true`）也吃 `polygonOffset`，但若仍有接缝，可对水面材质单独再加大 `polygonOffsetUnits`。
- 若偏移后远处出现「LOD 穿透 hires」的闪斑，说明偏移量过大，把 `polygonOffsetUnits` 减半再试。

---

## 八、执行顺序（建议）

1. **§2 探测** → 拿到三个数：误判数、LOD 私有纹理数、drawCall。**先看数，再动手。**
2. **§5 隔离** → 用 `hiresOnly()` / `lodOnly()` 锁定色块归属（5 分钟出结论）。
3. **§3 判据修复** → 显式 `level`，误判清零；色块若消失即收工。
4. **§4 两极反转** → 先跑 4.1 断言；若 flipY/colorSpace 异常则修配置，否则用 4.2 判定 V 翻转、用 4.3 判定双重 gamma。
5. **§6 图集化** → 解决 4fps；配合 §6.4 看 drawCall 决定是否合批。
6. **§7 polygonOffset** → 规范化深度共存。
7. 每步完成后重跑 §2，对比 `纹理对象 / drawCall / fps` 三个指标，确认单调改善。

---

## 九、遗留待确认（我无法从静态代码断言的部分）

1. `TextureColorSampler.sampleUvAverageRgb()` 返回的是**线性**还是 **sRGB** 字节 —— 决定 §4.3 是否成立。
2. 实际 glb 里 LOD sampler 的 `magFilter` 到底是什么值 —— 决定 §3 误判是否真实发生（§2 的探测会直接给出答案）。
3. 截图下半屏究竟对应 hires 还是 LOD —— 由 §5 的隔离实验回答。
