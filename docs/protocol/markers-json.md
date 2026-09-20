# 标注协议（markers.json）

标注是地图上的**业务图层**：地标、路线、区域、体块。它不参与瓦片与几何管线，
单独一份文件（`{publishDir}/{mapId}/markers.json`），与瓦片同源、同样静态可读。

## 文件位置与访问路径

| 用途 | 路径 | 缓存 |
|---|---|---|
| 静态读取（前端渲染用） | `/maps/{mapId}/markers.json` | `no-cache`（标注随时会改） |
| 读取（没有静态文件时也返回空列表） | `GET /api/maps/{mapId}/markers` | — |
| 整表写回 | `PUT /api/maps/{mapId}/markers` | — |
| 清空 | `DELETE /api/maps/{mapId}/markers` | — |

PUT 的请求体就是本文档描述的文件结构（也允许直接传 `sets` 数组），
所以「导出文件 / 贴一段 JSON 回去」与接口调用是同一份格式。

## 结构

```json
{
  "formatVersion": 1,
  "mapId": "swust-campus",
  "sets": [
    {
      "id": "landmarks",
      "label": "地标",
      "toggleable": true,
      "defaultHidden": false,
      "sorting": 10,
      "markers": [ /* ... */ ]
    }
  ]
}
```

- `sets` 按 `sorting` **降序**返回（大的先渲染，前端的图层列表也按此顺序）。
- `markers[].id` 在**同一标注集内唯一**（后端 `MarkerSet` 与前端 zod 都会拦重复）。
- 单张地图的标注总数上限 5000（`SaveMarkerSetsUseCase.MAX_MARKERS`）；再多应该换数据源。

## 标注类型

公共字段（五种类型都有）：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | string | 必填 | 标注集内唯一 |
| `type` | string | 必填 | `poi` / `line` / `shape` / `extrude` / `box` |
| `label` | string | 必填 | 展示名（POI 会画成标签） |
| `minDistance` | number | `0` | 低于该视距隐藏（**方块**距离） |
| `maxDistance` | number | `Number.MAX_SAFE_INTEGER` | 高于该视距隐藏 |
| `style` | object | `{}` | 见下表 |

`style` 字段：

| 字段 | 说明 |
|---|---|
| `fillColor` | 填充色（面/盒/图钉底色），CSS 颜色串 |
| `lineColor` | 描边色（折线、多边形轮廓、盒子边） |
| `lineWidth` | 线宽（像素）。WebGL 的 `LineBasicMaterial` 忽略该值，留给后续线带实现 |
| `opacity` | 0~1，缺省由渲染器按类型给（POI 1.0、面 0.45、体 0.35） |
| `icon` | 图钉内的图标字符（如 `"🏫"`） |
| `depthTest` | `false` = 穿透地形显示（总在最前），适合标记地下的点 |

### poi — 点标注

```json
{ "type": "poi", "id": "library", "label": "图书馆",
  "minDistance": 0, "maxDistance": 512,
  "style": { "fillColor": "#e74c3c", "icon": "📚", "depthTest": false },
  "position": { "x": 120.5, "y": 68, "z": -30.25 },
  "detailHtml": "<b>图书馆</b>（可选，前端面板展示用）" }
```

坐标是**世界坐标**（方块）。渲染层挂在场景根下，跟着浮点原点一起重定基，
所以标注坐标不需要减原点——写世界坐标即可。

### line — 折线

```json
{ "type": "line", "id": "patrol", "label": "巡逻线",
  "style": { "lineColor": "#ffcc00" },
  "points": [ { "x": 0, "y": 64, "z": 0 }, { "x": 16, "y": 64, "z": 8 } ] }
```

至少 2 个点。

### shape — XZ 多边形（可带洞）

```json
{ "type": "shape", "id": "campus", "label": "校园",
  "shape": [ { "x": 0, "z": 0 }, { "x": 32, "z": 0 }, { "x": 32, "z": 32 }, { "x": 0, "z": 32 } ],
  "holes": [ [ { "x": 8, "z": 8 }, { "x": 16, "z": 8 }, { "x": 16, "z": 16 }, { "x": 8, "z": 16 } ] ],
  "shapeY": 64,
  "style": { "fillColor": "#2f6fd0", "lineColor": "#ffffff", "opacity": 0.4 } }
```

外环至少 3 个顶点；`holes` 每条同样至少 3 个。`shapeY` 是铺开的高度（方块）。

### extrude — 多边形拉伸成棱柱

```json
{ "type": "extrude", "id": "tower", "label": "塔楼",
  "shape": [ { "x": 0, "z": 0 }, { "x": 8, "z": 0 }, { "x": 8, "z": 8 } ],
  "holes": [], "shapeMinY": 64, "shapeMaxY": 96 }
```

`shapeMaxY` 必须大于 `shapeMinY`。

### box — 轴对齐盒子

```json
{ "type": "box", "id": "dorm", "label": "宿舍楼",
  "min": { "x": 0, "y": 64, "z": 0 }, "max": { "x": 12, "y": 84, "z": 30 } }
```

`max` 各轴都必须 ≥ `min`。

## 渲染行为

- **POI**：图钉（Sprite，永远面向相机）+ 文字标签（Canvas 纹理）。距离越远整体放大，
  保持屏幕尺寸基本恒定；每帧按 `minDistance`/`maxDistance` 开关可见性。
- **line / shape / extrude / box**：按 `style` 生成 three.js 几何；`renderOrder` 设为 1（面）
  或 2（线），避免与地形 z-fighting。
- 标注**不参与碰撞**，第一人称可以穿过。
- 标注集开关由 `toggleable` 与 `defaultHidden` 决定初始状态，前端可再手动切换。

## 与前端 schema 的一致性

前端 `@yudream/voxelith-core` 的 `markerSetSchema` 是同一份协议的 zod 实现：
后端 `marker-context` 的 record（`Marker` sealed interface + `MarkerStyle` + `MarkerSet`）
与它逐字段对齐。改协议要**三处同时改**：本文档、后端 record、前端 zod。

后端在任何一处校验失败都会返回 **400** 并且**不写文件**（不会留下半个坏文件）；
前端加载时逐组校验，坏的标注集跳过并在面板上提示剩余数量。
