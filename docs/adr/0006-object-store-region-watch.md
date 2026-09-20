# ADR 0006：对象存储侧的变更检测 —— 轮询 + ETag 比对

- 状态：已接受（2026-09-20，Phase 7）
- 上下文：增量更新（ADR 0002）在本地磁盘上用 `WatchService` 监听 `region/*.mca` 的
  CREATE/MODIFY/DELETE。存档放在 S3/MinIO/R2 上时**没有任何事件可监听**：
  对象存储只有「列出 + ETag + 修改时间」，没有 inotify，也没有长轮询/事件流的通用能力。

## 决策

1. **新增端口 `RegionObjectSource`（编排域）**：`list(prefix) → {key, version, size, modified}` 与
   `get(key)`。`version` 是变更检测的主判据（S3 的 ETag、本地文件的 size-mtime 派生串）。
2. **`PollingRegionWatch implements RegionWatchPort`**：定期列一次前缀，与上一轮快照比对：
   - 新增或版本变化的键 → 可选**镜像**到本地 `region/` 目录（原子替换）→ 通知监听者；
   - 消失的键 → 删掉本地镜像 → 通知；
   - 首次轮询只建立基线、不发通知（否则每次重启都会把整张图当成变更重跑一遍）。
3. **不做跨上下文的直接依赖**：`ObjectStore`（maps 上下文 domain）不能出现在编排域里，
   `RegionObjectSource` 是编排域自己声明的端口；把 `ObjectStore` 接成它的适配器
   （`ObjectStoreRegionSource`）放在**组合根** `apps/voxelith-server`——那里不属于任何限界上下文，
   正是放转接件的地方（ArchUnit 也不会拦）。
4. **配置**：`incremental.watch-mode: local | object-store`、`incremental.object-prefix`、
   `incremental.poll-seconds`（默认 15）。object-store 模式下变更的对象会被镜像到
   `incremental.world-dir/region/`，因为增量渲染读的是本地 Anvil 文件。
5. **元数据读取**：`ObjectStore` 增加 `listMeta(prefix)`（默认实现退化为只用键）。
   S3 实现解析 `ListObjectsV2` 的 `<Contents>`（Key / LastModified / ETag / Size），
   不引入 XML 库——只认这几个稳定字段。

## 后果

- 同一套增量更新用例（`IncrementalUpdateUseCase`）对本地磁盘与对象存储都成立，
  差别只在 `RegionWatchPort` 的实现，上层零改动。
- 检测延迟 = 轮询间隔（默认 15s），比本地 WatchService 的即时事件慢，但增量渲染本身
  也要跑几十秒，量级上可接受。
- 停机期间发生的变更不会补发（首次轮询只建基线）。需要「补齐停机期间变更」的场景，
  应该让调度层在启动时做一次全量对账（比较清单里的每瓦片 sha1 与对象版本），
  而不是把状态塞进监听器。
