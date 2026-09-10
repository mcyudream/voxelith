# ADR 0002：region 增量更新 —— WatchService + 防抖 + 清单局部失效

- 状态：已接受（2026-09-10，Phase 6.3）
- 上下文：计划 Phase 6 要求「监听 region 文件变化 → 防抖/冷却 → 按 region 重渲染 3–6 链路 → 清单哈希局部失效」。全量重跑十万级区块不现实，增量必须以 region（32×32 区块）为最小调度单元。

## 决策

1. **监听**：`WatchServiceRegionWatch` 监听存档 `region/` 目录下 `r.X.Z.mca` 的 CREATE/MODIFY/DELETE。非 `.mca` 文件（`session.lock` 等）忽略。文件名解析收敛到 `RegionPos.parseFileName`（shared-kernel）。
2. **防抖窗口**：`IncrementalUpdateUseCase` 默认 2s（可注入）。窗口内同 region 多次修改合并为一次作业；窗口结束一次性 `flush`。冷却期内到达的事件并入下一次调度，不打断正在执行的作业。
3. **重渲染端口**：`IncrementalRenderPort.rerender(IncrementalJob)` 返回 `url → sha1`（空串 = 删除该瓦片）。组合根装配 bake→tile→lod 用例；orchestration 不直连对方 domain（ArchUnit）。
4. **清单局部失效**：`InvalidateManifestUseCase` 按 url 替换/删除指定 `TileEntry`，未被点名的条目（含包围盒、图集、settings）保持原值，再按全部条目重算 `contentVersion`（sha1 串联再 sha1 前 12 位，与全量发布同一算法），`.tmp` + 原子替换写回。前端缓存只对变化瓦片失效。
5. **关闭语义**：`close()` 取消未触发的调度、停止 WatchService，进行中的 flush 不强制打断（避免半写瓦片）。

## 未纳入本期

- 真实 bake/tile/lod 组合根装配（仍由 jshell 驱动全量管线；增量渲染端口先以测试替身验证编排）。
- S3 上的 Watch 等价物（Phase 6.4）。
- 跨 JVM 的作业队列（分布式分片预留，单机 ScheduledExecutor 足够）。
