# Yudream Voxelith Map Core（VMC）

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white)
![Three.js](https://img.shields.io/badge/Three.js-0.179-000000?logo=threedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9-F69220?logo=pnpm&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

基于 Three.js 的 Minecraft 地图渲染核心：从存档解析到 Web 渲染的完整链路，支持 glTF 瓦片、多级 LOD 金字塔、光照烘焙与 .vxt 瓦片格式。

- **组织**：Yudream
- **主仓库**：voxelith
- **项目全称**：Yudream Voxelith Map Core
- **简称**：VMC

## 概览

VMC 是一个类 BlueMap 的 Minecraft Web 地图渲染系统：

- **后端（Java 21 + Spring Boot 3.5）**：自动编排渲染管线 `resolve → scan → bake → tile → lod → manifest`，从存档（Anvil）/ schematic 解析世界，烘焙光照与 AO，产出 glb 瓦片金字塔与 JSON 清单。严格四层 DDD 限界上下文模块，ArchUnit 守护架构边界。
- **前端（Vue 3 + TS + Three.js，pnpm workspace）**：瓦片流式加载、LOD 四叉树逐级切换、LRU 缓存滞回淘汰、设备分档 + 自适应视距、三模式相机（自由飞行 / 第一人称 / 俯视倾斜）、浮点原点重定基。
- **协议**：自定义 JSON 清单 + glb 瓦片（光照/AO 烘焙进顶点属性），像素贴图图集保持无损 + NearestFilter；标注（marker）schema 已在 `voxelith-core` 定义，渲染层待实现。

## 特性

- **全链路自动编排**：管线状态机逐链路产出落盘中间产物与校验报告，支持分片级断点续跑。
- **烘焙级画质**：sky/block light + 角点 AO 直接读取存档 NBT 烘进顶点属性；流体与含水方块、生物群系染色、混合分辨率图集均已打通。
- **无感 LOD**：视距内标准 hires 渲染，超视距按水平距离每翻倍粗一级 LOD，逐级过渡、无雾效遮掩、无硬剔除。
- **性能自适应**：设备分档（高/中/低）+ FPS 窗口化动态调节视距，桌面目标 60fps、移动端 30fps。
- **色彩管理**：线性工作流 + sRGB 创作基准，可选 Display P3 广色域输出。
- **region 增量**：监听存档 region 变化 → 防抖合并 → 按 region 重跑 bake→tile→lod → 清单局部失效，前端只对变化瓦片失效。
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
│   ├── marker-context/                   # 标注域：schema 已定义，渲染待实现
│   ├── map-context/                      # 地图域：地图聚合、清单发布、FILE/S3 对象存储
│   └── architecture-tests/               # ArchUnit 四层架构守护
├── apps/
│   └── voxelith-server/                  # Spring Boot 启动层 + REST / 静态瓦片 + 组合根
├── web/
│   ├── packages/voxelith-core/           # → @yudream/voxelith-core
│   ├── packages/voxelith-viewer/         # → @yudream/voxelith-viewer
│   └── apps/voxelith-app/                # → @yudream/voxelith-app（私有应用壳）
└── docs/                                 # 协议规范、ADR、产物格式说明
```

## npm 分包

| 包名 | 目录 | 说明 |
|---|---|---|
| `@yudream/voxelith-core` | `web/packages/voxelith-core` | 核心协议：清单 / 瓦片索引 / 标注的 TS 类型与 zod 校验（与后端 schema 对齐） |
| `@yudream/voxelith-viewer` | `web/packages/voxelith-viewer` | 渲染核心：Three.js 瓦片流、LOD 四叉树、LRU 缓存、自适应视距、三模式相机（不依赖 Vue，可独立复用） |
| `@yudream/voxelith-app` | `web/apps/voxelith-app` | Vue 3 应用壳（私有，不发布）：地图切换、设置面板、状态管理 |
| `@yudream/voxelith-forge` | 规划 | 世界烘焙与瓦片产线工具链（管线产物处理 / 转换） |
| `@yudream/voxelith-skyline` | 规划 | 远景 LOD / 天际线渲染增强 |
| `@yudream/voxelith-tiles` | 规划 | .vxt 瓦片格式与 3D Tiles 互操作 |

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

> 本机 8080 端口被其他服务占用，voxelith-server 固定使用 **8081**（application.yml 默认值）。

> 全量渲染管线（resolve→scan→bake→tile→lod→manifest）当前由 jshell 串联各限界上下文用例执行，产物落盘 `work/` 与 `data/maps/{mapId}/`；仓库内暂无独立 CLI / Gradle 任务（列入 Phase 7）。

### 后端：region 增量更新（可选）

`application.yml` 中开启并填好路径即可（默认关闭，避免无存档时启动失败）：

```yaml
yudream:
  voxelith:
    incremental:
      enabled: true
      world-dir: <存档根目录（含 region/）>
      pack-dir: <资源包 / 原版 jar 路径>
      map-id: demo
```

### 前端：开发调试

```bash
cd web
pnpm install
pnpm dev        # 等价于 pnpm --filter @yudream/voxelith-app dev
```

浏览器打开 Vite 输出的地址（默认 [http://127.0.0.1:5173](http://127.0.0.1:5173)），选择地图后即可飞行漫游。

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

## 渲染管线

管线由编排域状态机自动驱动，每链路产出落盘中间产物 + 校验报告，支持分片级断点续跑：

1. **resolve** — 资源包栈叠加，解析 blockstate/model/texture，产出注册表与贴图图集；
2. **scan** — 读 level.dat 判 DataVersion，扫描 region 产出任务分片；
3. **bake** — 按 blockstate 去重烘焙 quad，cullface 剔除，sky/block light + 角点 AO 烘进顶点属性；
4. **tile** — 32×32 方块/片组装，glb 编码落盘 `tiles/hires/...`；
5. **lod** — 柱状 LOD 逐层聚合上采样，产出 `tiles/lod/{level}/...`；
6. **manifest** — 生成 `manifest.json`（瓦片索引、包围盒、图集、每瓦片 sha1），原子发布。

> 管线检查点写入 `work/`，同 runId 重跑时跳过「已完成且产物健在」的阶段/分片；世界 region 增减导致分片数变化时视为新阶段，丢弃旧分片进度。

## 前端关键机制

- **三模式相机**：自由飞行（WASD + Space/Ctrl 升降 + Shift 加速 + 滚轮调速）、第一人称（WASD 行走 + 跳跃 / 疾跑 + 可调地面高度）、俯视倾斜（拖拽旋转倾斜 + 缩放）；切换模式时以当前朝向重建控制器，平滑过渡。
- **多级 LOD**：从最粗层级向下四叉树遍历，几何距离 + 屏幕空间误差双判据细分；超出细节视距不剔除，按水平距离每翻倍允许的最细层级 +1，逐级过渡（无雾效遮掩边界）。期望瓦片未到时以已加载祖先垫底，中间缺失层不入队以免踏脚石占满带宽。
- **自适应视距**：设备分档（高/中/低 → 24/16/10 区块初始视距）+ FPS 窗口化调节（3 秒窗口，低于目标 85% 缩、连续两窗高于 97% 增，步进 1 区块，8–32 夹取，平滑过渡）；桌面目标 60fps、移动端 30fps；WebView 安全兜底，设备画像 localStorage 缓存。
- **烘焙光照**：skyLight/blockLight/AO 烘进 glb 顶点属性，着色器按昼夜参数化调光（天空光 / 方块光 / AO 三档实时滑杆）。
- **瓦片缓存**：数量 + 字节双阈值滞回 LRU，帧内 mark-used、超限淘汰最久未用（优先远离视点）；代际戳防快速切图竞态。
- **共享图集**：hires 瓦片内嵌同一张图集 PNG，前端按清单 `atlas` 引用只解码一次，避免逐瓦片解码耗尽显存。
- **浮点原点**：超远坐标（边疆量级）自动重定基，场景 / 相机 / 控制器目标同步平移，防 float32 精度撕裂。

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | Monorepo 骨架、四层 DDD + ArchUnit 守护、前后端壳互通 | ✅ 已完成 |
| Phase 1 | 垂直切片：resolve→scan→bake→tile→manifest→浏览器漫游 | ✅ 已完成 |
| Phase 2 | 光照 + AO 烘焙、流体、生物群系染色、图集无损打包 | ✅ 已完成 |
| Phase 3 | LOD 金字塔 + 前端四叉树逐级切换 / LRU 缓存 | ✅ 已完成 |
| Phase 4 | 全版本兼容（版本适配 SPI、1.13–1.17 调色板 + 1.12 flattening 映射、多版本回归测试） | ✅ 已完成 |
| Phase 5 | Headless mod 运行时（进程隔离、LWJGL stub、BakedModel 全量采集导出 models.json.gz + 静态解析降级兜底 + bake 链路优先消费采集产物） | ✅ 已完成 |
| Phase 6 | 规模化与增量：管线状态机 + 断点续跑 + region 分片 + WatchService 增量 + FILE/S3 SPI + 可选量化 + 增量 bake→tile→lod + 图集复用 + 高度场合并 | ✅ 已完成（meshopt 熵编码见 Phase 7；十万级全量烘焙仍走 jshell） |
| Phase 7 | 打磨与扩展：meshopt 熵编码、Y 轴切片、标注渲染（marker 渲染器）、增量扩图集、分布式分片作业队列、S3 侧 Watch 等价物、仓库内全量管线入口 | 🚧 规划中 |

## 文档

- `docs/protocol/tile-glb.md` — 瓦片 glb 顶点属性 / 流体几何 / 图集 / 可选量化规范
- `docs/protocol/models-json.md` — runtime 采集产物 `models.json.gz` 契约（bake 链路消费）
- `docs/adr/0001-headless-runtime-fabric-first.md` — Headless 运行时：进程隔离 + Fabric 优先 + LWJGL stub/真实 core 混合
- `docs/adr/0002-region-incremental-update.md` — region 增量更新（WatchService + 防抖 + 清单局部失效 + 图集复用 + 高度场合并）
- `docs/adr/0003-object-store-s3-spi.md` — 发布对象存储 FILE 默认 + S3 兼容 SPI（无 AWS SDK）
- `docs/adr/0004-mesh-quantization.md` — 可选 KHR_mesh_quantization；meshopt 熵编码预留

## License

[MIT](LICENSE)
