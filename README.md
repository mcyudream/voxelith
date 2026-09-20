# Yudream Voxelith Map Core（VMC）

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white)
![Three.js](https://img.shields.io/badge/Three.js-0.179-000000?logo=threedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9-F69220?logo=pnpm&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

基于 Three.js 的 Minecraft 地图渲染核心：从存档解析到 Web 渲染的完整链路，支持 glTF 瓦片（可选 meshopt 熵编码）、多级 LOD 金字塔、光照烘焙、标注图层与 .vxt 瓦片格式。

- **组织**：Yudream
- **主仓库**：voxelith
- **项目全称**：Yudream Voxelith Map Core
- **简称**：VMC

## 概览

VMC 是一个类 BlueMap 的 Minecraft Web 地图渲染系统：

- **后端（Java 21 + Spring Boot 3.5）**：自动编排渲染管线 `resolve → scan → bake → tile → lod → manifest`，从存档（Anvil）/ schematic 解析世界，烘焙光照与 AO，产出 glb 瓦片金字塔与 JSON 清单；支持 region 级增量重渲染（含**增量扩图集**）、可选的**分布式分片作业队列**、对象存储侧的变更轮询。严格四层 DDD 限界上下文模块，ArchUnit 守护架构边界。
- **前端（Vue 3 + TS + Three.js，pnpm workspace）**：瓦片流式加载、LOD 四叉树逐级切换、LRU 缓存滞回淘汰、设备分档 + 自适应视距、三模式相机（自由飞行 / 生存式第一人称（重力、碰撞、上台阶、游泳沉水）/ 俯视倾斜）、按需生成的瓦片碰撞代理、**Y 轴切片**、**标注图层**（POI/折线/多边形/体块/盒子）、浮点原点重定基；网页里可上传存档 → 框选范围 → 后台渲染 → 直接切到新地图，并支持删除已发布地图。
- **协议**：自定义 JSON 清单 + glb 瓦片（光照/AO 烘焙进顶点属性，可选 `KHR_mesh_quantization` + `EXT_meshopt_compression`），像素贴图图集保持无损 + NearestFilter（支持增量向下扩行）；标注用独立的 `markers.json`（schema 与前端 zod 逐字段对齐）。

## 特性

- **全链路自动编排**：管线状态机逐链路产出落盘中间产物与校验报告，支持分片级断点续跑。
- **烘焙级画质**：sky/block light + 角点 AO 直接读取存档 NBT 烘进顶点属性；流体与含水方块、生物群系染色、混合分辨率图集均已打通。
- **无感 LOD**：视距内标准 hires 渲染，超视距按水平距离每翻倍粗一级 LOD，逐级过渡、无雾效遮掩、无硬剔除。
- **性能自适应**：设备分档（高/中/低）+ FPS 窗口化动态调节视距，桌面目标 60fps、移动端 30fps。
- **生存式第一人称**：MC 数值的移动 / 重力 / 跳跃 / 疾跑，撞墙停下并沿墙滑行、≤0.6 格自动上台阶（更高的坎要跳）、走出悬崖自由落体；草木花等 45° 交叉模型不进碰撞，水面可游泳（按住 Space 上浮）与缓慢下沉，浮出水面能借力跳上岸。
- **按需碰撞代理**：瓦片加载后按需把渲染几何切成「实体 / 水面」两套不可见代理（8×8 区域 × 高度每 4 格分片、共享顶点缓冲），射线只扫穿过的 1~2 片；hires 未流式到位时退到最细可用 LOD 兜底，切模式不再悬空或穿模。
- **色彩管理**：线性工作流 + sRGB 创作基准，可选 Display P3 广色域输出。
- **region 增量**：监听存档 region 变化 → 防抖合并 → 按 region 重跑 bake→tile→lod → 清单局部失效，前端只对变化瓦片失效。
- **增量扩图集**：新出现的贴图（新方块 / mod 方块）追加进已发布图集的下一个空位，格子用满才向下加行——宽度、列数、老单元格序号都不动，已发布瓦片的 UV 继续有效。
- **Y 轴切片**：只显示（且只参与第一人称碰撞）某个高度区间内的几何，预设以相机高度为界；用于「同一张图看地下/地表/建筑层」。
- **标注图层**：POI（图钉 + 标签，保持屏幕尺寸）、折线、XZ 多边形（可带洞）、拉伸体块、轴对齐盒子；按视距自动显隐，面板可开关图层、点列表飞到标注、在当前位置一键新增。
- **分布式分片**：region 分片进共享队列（文件系统租约，无中间件），多进程/多机抢同一批分片；worker 崩溃后租约到期自动回收，产物路径回填后本地检查点继续续跑。
- **对象存储变更检测**：S3/MinIO/R2 上没有 inotify，改按 `poll-seconds` 轮询 ETag 比对，变更的 region 自动镜像到本地再触发增量。
- **几何压缩实测**：64×64 网格瓦片 BIN 从 258 856 B（float32）→ 191 256 B（量化）→ 50 228 B（量化 + meshopt 熵编码，约 1/5）；索引流约 1 字节/三角。
- **产物工具链**：`voxelith-forge` CLI 能对已发布目录做审计（清单 ↔ 盘上产物交叉校验，CI 可直接用退出码）、导出 OGC 3D Tiles 1.1 `tileset.json`、打成单个**自包含** `.vxtbundle`（瓦片 + 清单 + 图集 + LOD 图集页）；`.vxt` 单文件瓦片自带元数据与 sha1 自检。
- **远景增强**：`voxelith-skyline` 用 LOD 图集页直接铺「天际线平面地毯」（不再下载远处 glb），配合层带滞回与远景雾化，让地平线不抖、边缘不硬切；应用壳里有「远景天际线（实验）」开关，默认关。
- **实测规模**：西南科大全校 20769 瓦片、默认存档 7866 区块已全量渲染发布，浏览器全图 67 次 draw call。

## Monorepo 结构

```
voxelith/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml   # Java 21 + Spring Boot 3.5
├── modules/                              # Java 限界上下文（每模块内含 interfaces/application/domain/infrastructure 四层）
│   ├── shared-kernel/                    # 坐标、标识、领域事件、Result、色彩空间
│   ├── resource-context/                 # 资源域：包栈叠加、blockstate/model/texture 解析、图集、群系色表
│   ├── world-context/                    # 世界域：Anvil/level.dat/schematic 读取、多版本适配（调色板 / flattening）
│   ├── runtime-context/                  # Headless 运行时域：模型获取用例、进程隔离 worker launcher、静态解析降级兜底
│   ├── runtime-worker/                   # headless 子进程入口：Fabric Knot 引导、LWJGL stub、BakedModel 全量采集（不进服务端四层扫描）
│   ├── bake-context/                     # 烘焙域：模型→quad、cullface、光照+AO 烘焙、流体/含水、群系染色
│   ├── tile-context/                     # 瓦片域：glb 编码（含可选量化）、图集打包、清单发布 / 局部失效
│   ├── lod-context/                      # LOD 域：柱状高度场 LOD 金字塔聚合
│   ├── orchestration-context/            # 编排域：管线状态机、任务分片、断点续跑、region 增量
│   ├── marker-context/                   # 标注域：schema / 校验 / markers.json 读写 / REST 接口
│   ├── map-context/                      # 地图域：地图聚合、清单发布、FILE/S3 对象存储
│   └── architecture-tests/               # ArchUnit 四层架构守护
├── apps/
│   └── voxelith-server/                  # Spring Boot 启动层 + REST / 静态瓦片 + 组合根
├── web/
│   ├── packages/voxelith-core/           # → @yudream/voxelith-core（协议 + zod 校验）
│   ├── packages/voxelith-tiles/          # → @yudream/voxelith-tiles（.vxt 容器 / 3D Tiles / SHA-1）
│   ├── packages/voxelith-viewer/         # → @yudream/voxelith-viewer（Three.js 渲染核心）
│   ├── packages/voxelith-skyline/        # → @yudream/voxelith-skyline（远景平面 LOD / 雾化）
│   ├── packages/voxelith-forge/          # → @yudream/voxelith-forge（产物审计 / 打包 CLI）
│   └── apps/voxelith-app/                # → @yudream/voxelith-app（私有应用壳）
└── docs/                                 # 协议规范、ADR、产物格式说明
```

## npm 分包

| 包名 | 目录 | 说明 |
|---|---|---|
| `@yudream/voxelith-core` | `web/packages/voxelith-core` | 核心协议：清单 / 瓦片索引 / 标注的 TS 类型与 zod 校验（与后端 schema 对齐） |
| `@yudream/voxelith-viewer` | `web/packages/voxelith-viewer` | 渲染核心：Three.js 瓦片流、LOD 四叉树、LRU 缓存、自适应视距、三模式相机（不依赖 Vue，可独立复用） |
| `@yudream/voxelith-tiles` | `web/packages/voxelith-tiles` | 瓦片格式工具链：`.vxt` 单文件瓦片容器（glb + 元数据 + sha1 自检）、OGC 3D Tiles 1.1 互操作、纯 TS SHA-1 |
| `@yudream/voxelith-skyline` | `web/packages/voxelith-skyline` | 远景增强：天际线平面 LOD（直接用 LOD 图集页贴图，不再下载远处 glb）、层带滞回策略、远景雾化 |
| `@yudream/voxelith-forge` | `web/packages/voxelith-forge` | 管线产物工具链：审计（清单 ↔ 盘上产物交叉校验）、`.vxtbundle` 归档、3D Tiles 导出；CLI `voxelith-forge` |
| `@yudream/voxelith-app` | `web/apps/voxelith-app` | Vue 3 应用壳（私有，不发布）：地图切换、设置面板、状态管理 |

> Java 根包名为 `online.yudream.voxelith`，后端配置前缀 `yudream.voxelith.*`。

## 快速开始

### 环境要求

- JDK 21+
- Node.js 20+ 与 pnpm 9+

### 后端：地图服务

```bash
./gradlew :apps:voxelith-server:bootRun
```

启动后提供 `/api/maps` 地图列表接口与 `/maps/{mapId}/**` 静态瓦片（`manifest.json` no-cache，`tiles/**` 与 `atlas.png` 强缓存 7 天）。

> 本机 8080/8081 被 WSL 的 `wslrelay.exe` 长期占用（连上去只会被直接掐断），voxelith-server 固定使用 **8090**（application.yml 默认值；前端 dev 代理同步指向 8090，可用 `MAP_SERVER_URL` 覆盖）。

> 全量渲染管线已有仓库内入口：模型采集 `harvestModels`、全量渲染 `renderMap`、分布式分片 worker `shardWorker`（见下节）。产物落盘 `work/` 与 `data/maps/{mapId}/`。

### 后端：网页上传 + 可视化框选渲染

不想再手敲 region 窗口时，可以在网页上完成「上传存档 → 二维框选范围 → 后台渲染」整条链路。前端入口是工具栏的 **＋ 上传地图**。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/uploads` | 已登记的存档列表 |
| `GET` | `/api/uploads/{id}` | 存档详情 + 各维度 region/区块规模与内容包围盒 |
| `POST` | `/api/uploads/archive` | multipart 上传 `.zip` 存档（字段 `file`、可选 `name`） |
| `POST` | `/api/uploads/local` | 登记本机已有存档目录（`{"path","name"}`），不复制文件 |
| `DELETE` | `/api/uploads/{id}` | 注销存档 |
| `POST` / `GET` | `/api/uploads/{id}/preview?dimension=` | 启动 / 查询二维地表预览生成进度 |
| `GET` | `/api/uploads/{id}/preview.png?dimension=` | 预览图（PNG） |
| `GET` | `/api/render/defaults` | 资源包、采集产物、本机原版 jar 候选的自动发现结果 |
| `POST` | `/api/render/jobs` | 提交渲染任务（见下） |
| `GET` | `/api/render/jobs` / `/api/render/jobs/{id}` | 任务列表 / 单个任务状态与阶段进度 |
| `GET` | `/api/render/jobs/{id}/log?since=N` | 增量拉取任务日志（`since` 为已读行数） |
| `GET` | `/api/local-worlds?root=&depth=` | 扫描目录下含 `level.dat` 的存档候选 |
| `DELETE` | `/api/maps/{mapId}` | 删除已发布地图（发布目录 + 该图渲染工作目录，不可恢复） |

`POST /api/render/jobs` 请求体（除 `uploadId`/`mapId`/范围外均可省略，缺省走 `application.yml`）：

```json
{
  "uploadId": "西南科大青义-e92e5",
  "mapId": "swust-campus",
  "mapName": "西南科大青义",
  "dimension": "minecraft:overworld",
  "minX": 2944, "maxX": 3456, "minZ": 256, "maxZ": 768,
  "minY": -16, "maxLevel": 0, "lodAtlas": true,
  "packs": ["<client.jar>"], "modelsFile": "<models.json.gz>"
}
```

实现要点：

- **预览（框选底座）** —— `WorldPreviewRenderer` 把存档压成二维俯视色图：按 region 并行采样，每像素取 `step`（2 的幂，1~64）个方块中最高非空气方块，走 `BiomeTintResolver` 群系染色 + `TextureColorSampler` 贴图平均色；拿不到资源包时退回内置地表色表。产物 PNG + 地理信息 JSON 缓存在 `work/preview/`，重启后直接复用。
- **建议 minY** —— 预览顺带统计抽样列的地表高度范围（`minSurfaceY`/`maxSurfaceY`）并透传给前端，前端据此给「最低渲染高度」一个默认值（`minSurfaceY - 16`，按 section 对齐）。存档高度差异极大，写死的默认值会把整片几何裁掉。
- **地理信息版本** —— 缓存的 JSON 带 `version`，字段增减时递增即可让旧缓存自动重算。
- **任务执行** —— `RenderJobService` 把框选范围翻译成 `RenderMapOptions`，直接复用 `RenderMapCli`（与命令行同一条管线），把管线输出按行解析成阶段进度。日志按 `\n` 成行后再落盘：`PrintStream` 会在每次 `printf` 片段后 flush，若把 flush 也当断行，一行会被拆成好几条。

> 进程内触发渲染时无法注入 Gradle 的 worker classpath，因此网页链路固定 `skipHarvest=true`——**模型几何需事先用 `harvestModels` 采集好**，否则 bake 会退化为静态解析、mod 方块缺几何。

### 后端：headless 采集（mod 方块模型）

`models.json.gz` 是 bake 的模型来源——它由 headless Fabric worker 全量烘焙导出，**mod 方块只要注册进 `Registries.BLOCK` 就会一并导出**。采集走独立 JVM 子进程（ADR 0001），因此由 Gradle 任务自动注入 worker classpath：

```bash
# 原版采集（首次会下载 MC/Fabric 依赖，约 60MB，缓存在 work/.provision-cache）
./gradlew :apps:voxelith-server:harvestModels -PpackDir=<原版 client.jar>

# 带 mod：mod jar 会作为资源包叠在原版之上，其方块模型一并采集
./gradlew :apps:voxelith-server:harvestModels \
    -PpackDir=<原版 client.jar> \
    -Pmods=<mod1.jar>,<mod2.jar>
```

产物与报告：`work/models.json.gz`、`work/model-acquisition.json`（来源 = `RUNTIME_HARVEST` / `STATIC_FALLBACK`）、`work/worker.log`。可选参数：`-PmcVersion -PloaderVersion -PworkDir -PprovisionCache -PtimeoutMinutes -PskipProvision`（`-PskipProvision=true` 只跑 LWJGL 自检，用于排障）。

采集失败会自动降级为 jar 资源静态解析，并把原因写进报告；`STATIC_FALLBACK` 下 mod 自定义模型加载器（loader/IBakedModel）无法静态还原，mod 方块会缺几何——**要完整渲染 mod 方块必须让采集成功**。

### 后端：region 增量更新（可选）

`application.yml` 中开启并填好路径即可（默认关闭，避免无存档时启动失败）：

```yaml
yudream:
  voxelith:
    work-dir: ./work        # bake 默认在此找 models.json.gz（可用 incremental.models-file 覆盖）
    incremental:
      enabled: true
      world-dir: <存档根目录（含 region/）>
      pack-dir: <资源包 / 原版 jar 路径>
      mod-jars: <mod jar 路径，逗号分隔>   # 叠在原版包之上，mod 方块/贴图才能解析
      models-file:                        # 留空 = 探测 work-dir/models.json.gz
      map-id: demo
```

> bake 会优先消费 `models.json.gz`（runtime 采集的真实 BakedModel），查不到的方块才落回静态模型目录；产物缺失时记一条 warn 并纯走静态解析，不影响启动。

存档放在 **S3 / MinIO / R2** 上时没有 inotify 可监听，把变更检测切成轮询：

```yaml
yudream:
  voxelith:
    storage:
      type: s3                 # 发布产物也走对象存储时
      s3: { endpoint: http://127.0.0.1:9000, bucket: voxelith, access-key: ..., secret-key: ... }
    incremental:
      enabled: true
      watch-mode: object-store # local（默认，WatchService）| object-store（轮询 ETag）
      object-prefix: region/   # 留空按维度推断 region/、DIM-1/region/、DIM1/region/
      poll-seconds: 15
      world-dir: <本地镜像目录>  # 变更的 region 会镜像到这里，增量渲染读本地 Anvil
```

> 首次轮询只建立基线（重启不会把整张图当成变更重跑）；停机期间的变更不补发——需要补齐时
> 应让调度层做一次全量对账（比对清单里每个瓦片的 sha1）。

### 后端：全量渲染与分布式分片（仓库内入口）

```bash
# 全量：一个窗口一次跑完 bake→tile→lod→manifest，直接发布到 ./data/maps/{mapId}
./gradlew :apps:voxelith-server:renderMap \
    -PworldDir=<存档> -PmapId=swust -Ppacks=<原版client.jar> \
    -PregionX0=.. -PregionX1=.. -PregionZ0=.. -PregionZ1=.. -Pheap=8g
# 大图分遍（内存只与单批相关）；关掉 meshopt 熵编码（默认开）
#   -PbatchChunks=2048   -PnoMeshopt=true

# 分布式：多个 worker（可跨机器）抢同一批 region 分片，各自增量重渲染
./gradlew :apps:voxelith-server:shardWorker \
    -PworldDir=<存档> -PmapId=swust -Ppacks=<原版client.jar> \
    -PqueueDir=<共享队列目录> -Pthreads=4
```

> 分片队列是文件系统租约队列：领取用 `Files.createFile` 的原子独占创建，作业文件里记租约，
> worker 崩溃后租约到期即被别的 worker 回收。多机只要挂同一个目录（NFS/SMB）就能协作；
> 检查点、产物、清单局部失效的语义与单机增量完全一致，可以中途加入/退出。

### 标注（markers.json）

标注是独立图层文件，与瓦片同源：`/maps/{mapId}/markers.json`，读写接口是
`GET/PUT/DELETE /api/maps/{mapId}/markers`（PUT 的请求体就是文件本身的结构）。
网页工具栏的 📍 面板可以开关图层、点列表飞到标注、在当前位置一键新增 POI。

```json
{
  "formatVersion": 1,
  "mapId": "swust",
  "sets": [
    { "id": "landmarks", "label": "地标", "sorting": 10,
      "markers": [
        { "type": "poi", "id": "library", "label": "图书馆",
          "position": { "x": 120, "y": 68, "z": -30 },
          "style": { "fillColor": "#e74c3c", "icon": "📚", "depthTest": false } },
        { "type": "shape", "id": "campus", "label": "校园", "shapeY": 64,
          "shape": [ { "x": 0, "z": 0 }, { "x": 256, "z": 0 }, { "x": 256, "z": 256 }, { "x": 0, "z": 256 } ],
          "style": { "fillColor": "#2f6fd0", "opacity": 0.35 } }
      ] }
  ]
}
```

完整字段（五种类型 + 样式 + 视距剔除规则）见 [docs/protocol/markers-json.md](docs/protocol/markers-json.md)。

> ⚠️ **部署提示**：服务端不内置鉴权——`/api/uploads/**`（上传存档与解包）、
> `/api/render/jobs`（触发渲染）、`PUT /api/maps/{id}/markers`、`DELETE /api/maps/{id}`
> 任何人都能调用（设计前提是「本机 / 内网自用」）。要放到公网请在反向代理上加认证与访问控制，
> 并限制上传体积；CORS 只放行了 localhost 的 Vite 开发源，生产同源托管无需改这里。

### 前端：开发调试

```bash
cd web
pnpm install
pnpm dev        # 等价于 pnpm --filter @yudream/voxelith-app dev
```

浏览器打开 Vite 输出的地址（默认 [http://127.0.0.1:5173](http://127.0.0.1:5173)），选择地图后即可飞行漫游。
`?map=<地图 id>` 直接打开某张图，`?pos=<x>,<y>,<z>` 直接落到指定世界坐标（排障/分享视角用，切第一人称后会再落到该列表面）。

### 测试与构建

```bash
# 前端
cd web
pnpm -r test                        # vitest（引擎层纯逻辑单测）
pnpm -r build                       # 全部包 + 应用构建

# 后端
./gradlew test                      # JUnit5 + ArchUnit 架构守护
./gradlew build
```

值得单独点名的测试：

- `MeshoptCodecTest` / `GlbTileEncoderMeshoptTest`（后端）与 `MeshoptGolden.test.ts`（前端）——
  后者把后端产出的金标准位流交给 three.js 自带的官方 WASM 解码器逐字节还原，
  跨实现守住压缩格式；
- `GlbTileLoaderMeshopt.test.ts` —— 后端产出的**量化 + meshopt** glb（仓库内 fixture）交给真实
  GLTFLoader 解析：五个顶点属性齐全、索引正确、量化 + `node.scale` 还原后世界坐标仍是瓦片局部包围盒；
- `LodAtlasPackerTest`（后端）与 `lodAtlas.test.ts`（前端）—— LOD 图集槽位 UV 用**同一组字面量**
  互为金标准，改任何一侧的公式都会有测试变红；
- `FileShardQueueTest` —— 两个队列实例（模拟两台机器）并发抢同一批分片，
  断言不重复领取、租约到期可回收；
- `MarkerApiTest` —— 标注走一遍「PUT → 落盘 → 静态文件读回 → DELETE 清空」的真实 HTTP 链路；
- `AtlasExpanderTest` / `EnsureAtlasCapacityUseCaseTest` —— 增量扩图集必须**不动老单元格**。

## 渲染管线

管线由编排域状态机自动驱动，每链路产出落盘中间产物 + 校验报告，支持分片级断点续跑：

1. **resolve** — 资源包栈叠加，解析 blockstate/model/texture，产出注册表与贴图图集；
2. **scan** — 读 level.dat 判 DataVersion，扫描 region 产出任务分片；
3. **bake** — 按 blockstate 去重烘焙 quad，cullface 剔除，sky/block light + 角点 AO 烘进顶点属性；
4. **tile** — 32×32 方块/片组装，glb 编码落盘 `tiles/hires/...`；
5. **lod** — 柱状 LOD 逐层聚合上采样，产出 `tiles/lod/{level}/...`，并按层把航拍色图拼成共享图集页 `tiles/lod/{level}/lod-atlas.png`（瓦片只带图集 UV，前端每层只解码一张纹理）；
6. **manifest** — 生成 `manifest.json`（瓦片索引、包围盒、图集、LOD 图集页、每瓦片 sha1），原子发布。

> 管线检查点写入 `work/`，同 runId 重跑时跳过「已完成且产物健在」的阶段/分片；世界 region 增减导致分片数变化时视为新阶段，丢弃旧分片进度。

### 渲染范围（哪些东西会被画出来）

**只渲染方块几何 + 地图画**，其余实体一律不渲染：

| 会被渲染 | 说明 |
|---|---|
| 方块 | 含 mod 方块（前提是 mod jar 在 `packs` 里且采集成功） |
| 地图画 | 持有已填地图的**物品展示框 / 发光展示框**：读实体拿坐标与朝向、读 `data/map_*.dat` 拿颜色，再作为 1×1 面片补进瓦片几何。实体存放位置与物品 NBT 两种写法都支持：Paper / 原版 1.20.2+ 的 `entities/r.X.Z.mca`，以及更早版本的区块 NBT；地图编号读 1.20.5+ 的 `Item.components."minecraft:map_id"`，回落 `Item.tag.map`；按维度定位（下界 `DIM-1`、末地 `DIM1`） |

**不会渲染**：盔甲架、画、掉落物、船/矿车、生物、玩家等**任何其它实体**——
它们不在方块数据里，当前也没有通用实体几何管线（Phase 7 只做了地图画这一种）。
空展示框（没放地图）同样不会渲染。

> 版本体检：渲染开始时会把**存档版本**（`level.dat`）与**采集版本**（`-PmcVersion` / `render.mc-version`）、
> **资源包 jar 文件名里的版本**对一遍，不一致就打印醒目警告——紫块（贴图名随版本改名，如 1.20.3 起
> `grass` → `short_grass`）和空洞（模型对不上）最常见的成因就是它。

分片阶段（bake/tile/lod 的 region 分片）有两种跑法：本地顺序/并行执行，或交给**分片作业队列**
（`ShardQueuePort`）——入队后本地 worker 池与其他进程一起抢占，检查点仍按分片记录，
两种方式可以混用、可以中途切换。队列模式下「队列说 DONE、产物却不在盘上」的分片会先被
`reset` 再重新入队，避免 DONE 记录永远挡住重跑。

增量重跑时，如果这次用到的贴图不在已发布图集里（新方块 / mod 方块），管线会先把它们
**追加**进图集（老单元格不动、格子用满向下加行），否则那些面会整片渲染成品红兜底格。

## 前端关键机制

- **三模式相机**：自由飞行（WASD + Space/Ctrl 升降 + Shift 加速 + 滚轮调速）、第一人称（MC 生存模式式移动：重力 / 跳跃 / 撞墙停下并沿墙滑行 / ≤0.6 格自动上台阶（更高的坎要跳），步行 4.317、疾跑 5.612 格每秒；水里则重力降到 8 格/秒²、松手以 3 格/秒缓慢下沉、按住 Space 以 3.2 格/秒上浮、浮出水面时借力起跳上岸、水平速度减半；切模式即落到当前站位的表面 —— 水面优先于水底，水平位置收在地图范围内，探不到地形时原地悬停不下沉）、俯视倾斜（拖拽旋转倾斜 + 缩放）；切换模式时以当前朝向重建控制器，平滑过渡。
- **瓦片碰撞代理**：第一人称的射线碰撞不直接扫整片瓦片 —— 瓦片加载后按需生成 `collision-proxy`（XZ 8×8 × 高度每 4 格分片，顶点缓冲与渲染网格共享，只有索引各一份、带紧凑包围球，挂在瓦片组下 `visible=false` 不参与渲染），每条射线只扫真正穿过的 1~2 个分片；同时按几何语义分成两套代理：**实体**（只保留轴平行面 —— 植物是绕 Y 轴 45° 的交叉面片，三个方向都斜跨，直接被剔除；实测 school 一片 5.9 万面的瓦片里有 1.6 万面是草木，全部不进碰撞）与**水面**（瓦片里单独的 translucent primitive，用材质 `transparent` 识别，游泳/沉水/判断人在水里用）。实测密集瓦片（5.9 万 / 9.0 万三角面）贴墙射线从 2.2 / 3.7 ms 降到 0.028 / 0.014 ms（约 80~260×），竖直地面射线降到 ~0.01 ms；一次性建索引约 20~55 ms（走进新瓦片时的一次抖动）。
- **碰撞层级兜底**：脚下地面/墙面优先用 hires 瓦片（精确），hires 还没流式到位时退到**已加载的最细 LOD**（层级 ≤ 3，footprint ≤ 8 方块）—— 粗层柱顶取的是 2^L 方块内的最高表面，L5 实测能比真实地面高 17 格，所以更粗的层级不参与碰撞。切模式/切图那一瞬间不再悬空穿模，hires 到位后自动贴合到精确表面。
- **多级 LOD**：从最粗层级向下四叉树遍历，几何距离 + 屏幕空间误差双判据细分；超出细节视距不剔除，按水平距离每翻倍允许的最细层级 +1，逐级过渡（无雾效遮掩边界）。期望瓦片未到时以已加载祖先垫底，中间缺失层不入队以免踏脚石占满带宽。
- **自适应视距**：设备分档（高/中/低 → 24/16/10 区块初始视距）+ FPS 窗口化调节（3 秒窗口，低于目标 85% 缩、连续两窗高于 97% 增，步进 1 区块，8–32 夹取，平滑过渡）；桌面目标 60fps、移动端 30fps；WebView 安全兜底，设备画像 localStorage 缓存。
- **烘焙光照**：skyLight/blockLight/AO 烘进 glb 顶点属性，着色器按昼夜参数化调光（天空光 / 方块光 / AO 三档实时滑杆）。
- **瓦片缓存**：数量 + 字节双阈值滞回 LRU，帧内 mark-used、超限淘汰最久未用（优先远离视点）；代际戳防快速切图竞态；上限按设备档位收紧（高/中/低 → 4096/2048/1024 片）。
- **加载容错**：失败瓦片按指数退避重试（1s 起、封顶 30s、默认 3 次），瞬时网络错误不再导致该瓦片本次会话永久缺失。
- **缓存版本戳**：瓦片 URL 带自身内容哈希 `?sha=<tile.sha1>`，未变动的瓦片跨地图版本继续命中 7 天强缓存（用全图聚合 version 会让任一片变动即全量失效）。
- **共享图集**：hires 每瓦片内嵌同一张图集 PNG，前端按清单 `atlas` 只解码一次；LOD 每层把该层全部瓦片的航拍色图拼成一张 `lod-atlas.png`（瓦片 UV 已烘焙成图集坐标），前端每层只解码一张纹理——此前 LOD 是每瓦片一张 128² PNG，2176 片即 2000+ 纹理对象与同等数量的解码。hires / LOD 由传入的 `level` 显式区分，不从纹理过滤参数反推。
- **Y 轴切片**：片元着色器按世界 Y 丢弃（uniform 开关常驻，切换切片不触发重编译）；第一人称的射线碰撞同时按碰撞代理的 **Y 分桶**过滤，被裁掉的高度既不显示也不挡路（否则会「站在空气上」）。面板提供「相机以上 / 相机以下 / 当前层 ±64 / 整图」预设，以相机高度为界（不写死 y=63 海平面）。
- **标注图层**：`markers.json` 里的五类标注各由一套几何生成；POI 用 Sprite + Canvas 标签并随视距缩放保持屏幕尺寸，`minDistance`/`maxDistance` 每帧做显隐剔除，`depthTest:false` 用于穿透地形；标注挂在场景根下，跟瓦片一起被浮点原点重定基，坐标写世界坐标即可。
- **meshopt 压缩瓦片**：glb 的 POSITION/NORMAL/TEXCOORD_0 与索引用 `EXT_meshopt_compression` 位流，前端由 three.js 自带 WASM 解码器还原；未压缩瓦片与压缩瓦片可以在同一张图里共存（增量发布不必整图重渲）。
- **浮点原点**：超远坐标（边疆量级）自动重定基，场景 / 相机 / 控制器目标同步平移，防 float32 精度撕裂。

## 工具链与互操作（tiles / skyline / forge）

这三个包不参与渲染主链路，但把「渲染核心」补成了「能产、能查、能发、能对接别人」的完整工具链。

### `@yudream/voxelith-tiles` —— 瓦片格式与 3D Tiles 互操作

- **`.vxt` 单文件瓦片**：`VXT1` 容器（magic + 头部 JSON + glb payload），头部带清单条目
  （层级/坐标/包围盒/sha1）与内容标志（meshopt / 量化 / 是否内嵌图集）。适合离线分发与冷归档：
  拿到一个文件就知道它是什么、怎么校验、需不需要解压器。
  ```ts
  import { writeVxt, readVxt, verifyVxt } from "@yudream/voxelith-tiles";
  const bytes = writeVxt(tile, glb, { meshopt: true, quantized: true });
  const { header, glb: payload } = readVxt(bytes);
  const check = verifyVxt(bytes);        // sha1 与字节数自检
  ```
- **3D Tiles 1.1**：`toTileset(manifest, { anchor: { lonDeg, latDeg }, metersPerBlock })`
  把清单翻成 `tileset.json`（1.1 直接以 glb 为 content，无需 b3dm）：最粗 LOD 层为根、
  逐层四叉细化，`geometricError = 瓦片边长 / sseFactor`，包围体用 `region`（经纬高，弧度/米）。
  图不连通时自动套一个无 content 的合成根，**不丢瓦片**。`tilesetContents()` 也能反向摊平出内容列表。
- **纯 TS SHA-1**：浏览器与 Node 都能算，用于内容指纹（与后端 `MessageDigest` 结果一致）。

### `@yudream/voxelith-skyline` —— 远景 LOD / 天际线

- **天际线平面 LOD**（`SkylineLayer`）：远景不再下载并解析粗层 glb，而是直接拿该层
  `lod-atlas.png` 的槽位贴到**与瓦片同 footprint 的水平面片**上（UV 换算与后端
  `LodAtlasPacker` 同一套规则：行主序槽位 + 半纹素内缩）。
- **层带策略**（`skylineBands` / `planSkyline` / `levelForFarDistance`）：层级按 2 的幂分带、
  相邻带重叠、退出带滞回；地毯默认选一层（远景带约 8 片铺满），每片面片进出地平线带也带 15% 滞回。
- **远景雾化**（`SkylineHaze`）：`detailDistance → farDistance` 的雾带（three 内置 `Fog`，
  连续插值、无硬边），用于让地毯接缝与地图外缘自然消失；`apply/dispose` 会保留并还原场景原有雾。

应用壳已接上这个包：设置面板的 **「远景天际线（实验）」**（默认关）打开后即用地毯替换远处粗层瓦片，
关掉时图层与雾一并拆除、场景雾还原——开关是零残留的。

### `@yudream/voxelith-forge` —— 管线产物工具链（CLI）

```bash
pnpm -r build            # 产出 dist/（CLI 会被 esbuild 打成单文件）

# 审计：清单 ↔ 盘上产物交叉校验（url 唯一性、sha1/字节数、glb 结构、图集与 LOD 图集页尺寸、层级）
pnpm --filter @yudream/voxelith-forge exec node dist/cli.js audit --map-dir ./data/maps/swust
# 统计 / 抽样看扩展（确认 meshopt、量化是否真的生效）
pnpm --filter @yudream/voxelith-forge exec node dist/cli.js stats --map-dir ./data/maps/swust
pnpm --filter @yudream/voxelith-forge exec node dist/cli.js ext   --map-dir ./data/maps/swust
# 导出 3D Tiles（Cesium 等可直接加载）；打包成单个 .vxtbundle 供离线分发
# （默认把清单/图集/LOD 图集页一起打进去，落地即自包含；--no-assets 可只打瓦片）
pnpm --filter @yudream/voxelith-forge exec node dist/cli.js tileset \
    --map-dir ./data/maps/swust --out tileset.json --lon 104.06 --lat 30.67
pnpm --filter @yudream/voxelith-forge exec node dist/cli.js pack \
    --map-dir ./data/maps/swust --out swust.vxtbundle
```

审计的判定标准是「**能不能正确渲染**」，不是「文件在不在」：

- `error`：瓦片缺失、字节数/sha1 不符、glb magic 或声明长度不对、图集尺寸与清单不符、
  LOD 图集页尺寸 ≠ 该层网格 × slotSize（UV 会整片错位）、层级超过 `lodCount`、没有 hires 瓦片；
- `warning`：目录名与 `mapId` 不一致、LOD 瓦片既没内嵌色图也没有该层图集页（只剩方向明暗）、
  包围盒超过该层边长。

退出码：`0` 通过、`1` 有 error（CI 可直接用）、`2` 参数错误。

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | Monorepo 骨架、四层 DDD + ArchUnit 守护、前后端壳互通 | ✅ 已完成 |
| Phase 1 | 垂直切片：resolve→scan→bake→tile→manifest→浏览器漫游 | ✅ 已完成 |
| Phase 2 | 光照 + AO 烘焙、流体、生物群系染色、图集无损打包 | ✅ 已完成 |
| Phase 3 | LOD 金字塔 + 前端四叉树逐级切换 / LRU 缓存 | ✅ 已完成 |
| Phase 4 | 全版本兼容（版本适配 SPI、1.13–1.17 调色板 + 1.12 flattening 映射、多版本回归测试） | ✅ 已完成 |
| Phase 5 | Headless mod 运行时（进程隔离、LWJGL stub、BakedModel 全量采集导出 models.json.gz + 静态解析降级兜底 + bake 链路优先消费采集产物） | ✅ 已完成（采集有仓库内入口 `harvestModels`，mod jar 走 `-Pmods`） |
| Phase 6 | 规模化与增量：管线状态机 + 断点续跑 + region 分片 + WatchService 增量 + FILE/S3 SPI + 可选量化 + 增量 bake→tile→lod + 图集复用 + 高度场合并 | ✅ 已完成 |
| Phase 7 | 打磨与扩展：meshopt 熵编码、Y 轴切片、标注渲染（marker 渲染器）、增量扩图集、分布式分片作业队列、S3 侧 Watch 等价物、仓库内全量管线入口 | ✅ 已完成 |

Phase 7 的落地形态（逐条对照）：

| 条目 | 实现 | 入口 / 验证 |
|---|---|---|
| meshopt 熵编码 | `EXT_meshopt_compression` 线格式的**纯 Java 编解码移植**（顶点 v0 + 索引 v1），glb 里带 gltfpack 同形的占位回退缓冲；前端 `GlbTileLoader` 挂官方 WASM 解码器 | `EncodeOptions.meshopt()`；`renderMap` 默认开、`-PnoMeshopt=true` 关；后端 `MeshoptCodecTest` + 前端 `MeshoptGolden.test.ts`（金标准位流 → 官方解码器逐字节还原） |
| Y 轴切片 | 片元丢弃（uniform 开关，切换不重编译）+ 碰撞代理按 Y 分桶过滤 | 设置面板「Y 轴切片」（含「相机以上 / 以下 / 当前层 ±64 / 整图」预设）；`YSlice.test.ts` |
| 标注渲染 | 五种标注（POI 图钉+标签 / 折线 / 多边形含洞 / 拉伸体 / 盒子）的渲染层 + `markers.json` 协议 + REST 读写 + 图层面板 | `MarkerLayer`；`GET/PUT/DELETE /api/maps/{id}/markers`；`MarkerLayer.test.ts` + `MarkerJsonRoundTripTest` + `MarkerApiTest` |
| 增量扩图集 | 新贴图追加到空位，格子用满向下加行（宽/列/老单元格序号不变）；布局与清单增加 `height` | `EnsureAtlasCapacityUseCase`、`AtlasExpander`；`AtlasExpanderTest` + `EnsureAtlasCapacityUseCaseTest` |
| 分布式分片作业队列 | `ShardQueuePort` + 文件系统租约队列（原子独占创建 + 租约回收）+ worker 池 + 管线队列模式 | `gradlew :apps:voxelith-server:shardWorker`；`FileShardQueueTest`（两实例并发抢片）+ `RunPipelineQueueTest` |
| S3 侧 Watch 等价物 | `RegionObjectSource` 端口 + 轮询 ETag 比对 + 变更镜像回本地 | `incremental.watch-mode=object-store`；`PollingRegionWatchTest` + `S3SignerTest`；ADR 0006 |
| 仓库内全量管线入口 | `harvestModels` / `renderMap` / `shardWorker` 三个 Gradle 任务 + CLI | 见「快速开始 · 全量渲染与分布式分片」 |

Phase 7 之外，`npm 分包`里原计划的三项也已落地（不在阶段表内，属配套工具链）：
`@yudream/voxelith-tiles`（.vxt 容器 + 3D Tiles 1.1）、`@yudream/voxelith-skyline`
（天际线平面 LOD + 远景雾化）、`@yudream/voxelith-forge`（产物审计 / 归档 / 导出 CLI）。

## 文档

- `docs/protocol/tile-glb.md` — 瓦片 glb 顶点属性 / 流体几何 / 图集（含增量扩行）/ 可选量化与 meshopt 熵编码规范
- `docs/protocol/markers-json.md` — 标注协议（五种类型 + 样式 + 视距剔除 + REST 读写）
- `docs/protocol/models-json.md` — runtime 采集产物 `models.json.gz` 契约（bake 链路消费）
- `docs/adr/0001-headless-runtime-fabric-first.md` — Headless 运行时：进程隔离 + Fabric 优先 + LWJGL stub/真实 core 混合
- `docs/adr/0002-region-incremental-update.md` — region 增量更新（WatchService + 防抖 + 清单局部失效 + 图集复用/扩行 + 高度场合并）
- `docs/adr/0003-object-store-s3-spi.md` — 发布对象存储 FILE 默认 + S3 兼容 SPI（无 AWS SDK）
- `docs/adr/0004-mesh-quantization.md` — 可选 KHR_mesh_quantization + meshopt 熵编码（纯 Java 移植、顶点 v0/索引 v1、实测 1/5）
- `docs/adr/0005-distributed-shard-queue.md` — 分布式分片作业队列：文件系统租约、原子独占创建、崩溃回收
- `docs/adr/0006-object-store-region-watch.md` — 对象存储侧变更检测：轮询 ETag 比对 + 变更镜像回本地

## License

[MIT](LICENSE)
