# ADR 0002：region 增量更新 —— WatchService + 防抖 + 清单局部失效

- 状态：已接受（2026-09-10，Phase 6.3；Phase 6.7 补齐真实 bake→tile→lod 组合根）
- 上下文：计划 Phase 6 要求「监听 region 文件变化 → 防抖/冷却 → 按 region 重渲染 3–6 链路 → 清单哈希局部失效」。全量重跑十万级区块不现实，增量必须以 region（32×32 区块）为最小调度单元。

## 决策

1. **监听**：`WatchServiceRegionWatch` 监听存档 `region/` 目录下 `r.X.Z.mca` 的 CREATE/MODIFY/DELETE。非 `.mca` 文件（`session.lock` 等）忽略。文件名解析收敛到 `RegionPos.parseFileName`（shared-kernel）。
2. **防抖窗口**：`IncrementalUpdateUseCase` 默认 2s（可注入）。窗口内同 region 多次修改合并为一次作业；窗口结束一次性 `flush`。冷却期内到达的事件并入下一次调度，不打断正在执行的作业。
3. **重渲染端口**：`IncrementalRenderPort.rerender(IncrementalJob)` 返回 `url → sha1`（空串 = 删除该瓦片）。`RegionIncrementalRenderAdapter` 在 orchestration.infrastructure 装配 bake→tile→lod 用例：先 `WorldBlockAccess.invalidateRegion` 丢缓存，再 bake 该 region 全部 32×32 区块，用已发布图集（`PublishedAtlas` / `atlas-layout.json`）编码 hires，再生成覆盖这些瓦片的柱状 LOD。orchestration 不直连对方 domain（ArchUnit）。
4. **清单局部失效**：`InvalidateManifestUseCase` 按 url 替换/删除指定 `TileEntry`，未被点名的条目（含包围盒、图集、settings）保持原值，再按全部条目重算 `contentVersion`（sha1 串联再 sha1 前 12 位，与全量发布同一算法），`.tmp` + 原子替换写回。前端缓存只对变化瓦片失效。
5. **关闭语义**：`close()` 取消未触发的调度、停止 WatchService，进行中的 flush 不强制打断（避免半写瓦片）。
6. **组合根**：`IncrementalRenderConfig`（`yudream.voxelith.incremental.enabled=true`）装配 WatchService + 适配器 + 清单失效；默认关闭，避免无存档时启动失败。

## 图集复用

增量重跑不得现场重打包图集（单元格序号变化会打乱旧瓦片 UV）。全量 tile 应把 `atlas.png` + `atlas-layout.json` 写入发布目录；增量只读这两份文件经 `TileCommand.reuseAtlas` 编码新 glb。新贴图（新方块）本期不扩图集，映射到品红兜底格。

## 未纳入本期

- S3 上的 Watch 等价物。
- 跨 JVM 的作业队列（分布式分片预留，单机 ScheduledExecutor 足够）。
- 增量扩图集（新方块贴图仍映射品红兜底；需全量 tile 才更新 atlas-layout.json）。
