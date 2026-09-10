# Yudream Voxelith Map Core（VMC）

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?logo=springboot&logoColor=white)
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

- **后端（Java 21 + Spring Boot 3）**：自动编排渲染管线 `resolve → scan → bake → tile → lod → manifest`，从存档（Anvil）/ schematic 解析世界，烘焙光照与 AO，产出 glb 瓦片金字塔与 JSON 清单。严格四层 DDD 限界上下文模块，ArchUnit 守护架构边界。
- **前端（Vue 3 + TS + Three.js，pnpm workspace）**：瓦片流式加载、多级 LOD 逐级过渡、LRU 缓存滞回淘汰、设备分档 + 自适应视距、自由飞行 / 俯视倾斜控制器、Y 轴切片、标注层。
- **协议**：自定义 JSON 清单 + glb 瓦片（光照/AO 烘焙进顶点属性），像素贴图图集保持无损 + NearestFilter。

## 特性

- **全链路自动编排**：管线状态机逐链路产出落盘中间产物与校验报告，支持断点续跑。
- **烘焙级画质**：sky/block light + 角点 AO 直接读取存档 NBT 烘进顶点属性；流体与含水方块、生物群系染色、混合分辨率图集均已打通。
- **无感 LOD**：视距内标准 hires 渲染，超视距按水平距离每翻倍粗一级 LOD，逐级过渡、无雾效遮掩、无硬剔除。
- **性能自适应**：设备分档（高/中/低）+ FPS 窗口化动态调节视距，桌面目标 60fps、移动端 30fps。
- **色彩管理**：线性工作流 + sRGB 创作基准，可选 Display P3 广色域输出。
- **实测规模**：西南科大全校 20769 瓦片、默认存档 7866 区块已全量渲染发布，浏览器全图 67 次 draw call。

## Monorepo 结构

```
voxelith/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml   # Java 21 + Spring Boot 3
├── modules/                              # Java 限界上下文（每模块内含 interfaces/application/domain/infrastructure 四层）
│   ├── shared-kernel/                    # 坐标、标识、领域事件、Result
│   ├── resource-context/                 # 资源域：包栈叠加、blockstate/model/texture 解析、图集
│   ├── world-context/                    # 世界域：Anvil/level.dat/schematic 读取、版本适配 SPI
│   ├── runtime-context/                  # Headless 运行时域：mod 加载、模型采集（预留）
│   ├── bake-context/                     # 烘焙域：模型→quad、cullface、光照+AO 烘焙
│   ├── tile-context/                     # 瓦片域：glb 编码、图集打包
│   ├── lod-context/                      # LOD 域：柱状 LOD 金字塔聚合
│   ├── orchestration-context/            # 编排域：管线状态机、任务分片、断点续跑
│   ├── marker-context/                   # 标注域：点/线/区域/盒体/多边形 + 样式
│   └── map-context/                      # 地图域：地图聚合、清单发布、存储布局
├── apps/
│   └── voxelith-server/                  # Spring Boot 启动层 + REST/静态瓦片/任务 API
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
| `@yudream/voxelith-viewer` | `web/packages/voxelith-viewer` | 渲染核心：Three.js 瓦片流、多级 LOD、LRU 缓存、自适应视距、控制器（不依赖 Vue，可独立复用） |
| `@yudream/voxelith-app` | `web/apps/voxelith-app` | Vue 3 应用壳（私有，不发布）：地图切换、设置面板、状态管理 |
| `@yudream/voxelith-forge` | 规划 | 世界烘焙与瓦片产线工具链（管线产物处理 / 转换） |
| `@yudream/voxelith-skyline` | 规划 | 远景 LOD / 天际线渲染增强 |
| `@yudream/voxelith-tiles` | 规划 | .vxt 瓦片格式与 3D Tiles 互操作 |

> Java 根包名为 `online.yudream.voxelith`，后端配置前缀 `yudream.voxelith.*`。

## 快速开始

### 环境要求

- JDK 21+
- Node.js 20+ 与 pnpm 9+

### 后端：渲染管线 + 地图服务

```bash
# 运行完整管线（resolve→scan→bake→tile→lod→manifest），产物落盘 data/
./gradlew :apps:voxelith-server:bootRun
```

> 本机 8080 端口被其他服务占用，voxelith-server 固定使用 **8081**（application.yml 默认值）。

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

管线由编排域状态机自动驱动，每链路产出落盘中间产物 + 校验报告，支持断点续跑：

1. **resolve** — 资源包栈叠加，解析 blockstate/model/texture，产出注册表与贴图图集；
2. **scan** — 读 level.dat 判 DataVersion，扫描 region 产出任务分片；
3. **bake** — 按 blockstate 去重烘焙 quad，cullface 剔除，sky/block light + 角点 AO 烘进顶点属性；
4. **tile** — 32×32 方块/片组装，glb 编码落盘 `tiles/hires/...`；
5. **lod** — 柱状 LOD 逐层聚合上采样，产出 `tiles/lod/{level}/...`；
6. **manifest** — 生成 `manifest.json`（瓦片索引、包围盒、图集、每瓦片 sha1），原子发布。

## 前端关键机制

- **多级 LOD**：视距内标准 hires 渲染；超出视距不剔除，按水平距离每翻倍允许的最细层级 +1，逐级过渡到最粗层级（无雾效遮掩边界）。
- **自适应视距**：设备分档（高/中/低 → 24/16/10 区块初始视距）+ FPS 窗口化调节（3 秒窗口，低于目标 85% 缩、连续两窗高于 97% 增，步进 1 区块，8–32 夹取，平滑过渡）；桌面目标 60fps、移动端 30fps；WebView 安全兜底，设备画像 localStorage 缓存。
- **烘焙光照**：skyLight/blockLight/AO 烘进 glb 顶点属性，着色器按昼夜参数化调光。
- **瓦片缓存**：双阈值滞回 LRU（数量 + 字节），帧内 mark-used / microtask 淘汰。

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | Monorepo 骨架、四层 DDD + ArchUnit 守护、前后端壳互通 | ✅ 已完成 |
| Phase 1 | 垂直切片：resolve→scan→bake→tile→manifest→浏览器漫游 | ✅ 已完成 |
| Phase 2 | 光照 + AO 烘焙、流体、生物群系染色、图集无损打包 | ✅ 已完成 |
| Phase 3 | LOD 金字塔 + 前端逐级切换 / LRU 缓存 / Y 切片 | ✅ 已完成 |
| Phase 4 | 全版本兼容（legacy 1.12 flattening 映射、nibble 光照） | 🔲 未开始 |
| Phase 5 | Headless mod 运行时（进程隔离、LWJGL stub、模型采集） | 🔲 未开始 |
| Phase 6 | 规模化与增量：十万级区块压测、region 监听增量更新、S3 存储 | 🔲 未开始 |

## 文档

- `docs/protocol/` — 传输协议与清单 schema 规范（前后端共享，变更须三方同步）
- `docs/` — 各链路产物格式说明与 ADR

## License

[MIT](LICENSE)
