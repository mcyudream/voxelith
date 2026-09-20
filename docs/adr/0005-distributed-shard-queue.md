# ADR 0005：分布式分片作业队列 —— 文件系统租约，不引外部中间件

- 状态：已接受（2026-09-20，Phase 7）
- 上下文：一根管线的 bake/tile/lod 已经天然按 region 分片（`ShardedStageExecutor`，
  分片键 `r.X.Z`）。单机多线程能跑，但「多台机器一起渲染同一张图」需要一层作业队列：
  谁领了哪一片、谁跑完了、worker 崩了怎么办。可选方案有 ZooKeeper / Redis / 数据库，
  但它们的运维成本远超这个项目的部署规模（一台开发机 + 偶尔一两台渲染机）。

## 决策

1. **队列端口放在编排域**：`ShardQueuePort`（enqueue / claim / complete / fail / jobs / clear / reset），
   语义为「入队幂等、领取互斥、租约到期可回收、失败不自动重试」。
2. **实现是文件系统租约队列**（`FileShardQueue`）：`{queueDir}/{stage}/{shard}.json` 记状态，
   同目录 `{shard}.lock` 抢锁。并发安全靠两个**文件系统本身原子**的原语：
   - 独占创建 `Files.createFile`（= `O_CREAT|O_EXCL`）：多进程同时抢只有一个人成功。
     **刻意不用**「写临时文件再 rename」——POSIX 的 `rename` 会静默覆盖，两个 worker
     会同时以为自己拿到了分片；
   - 作业文件里的 `leaseUntil`：worker 崩了锁文件会留下，但租约一过别人就删锁重抢。
     锁文件只是「有人在场」的标记，超时语义在作业文件里。
3. **worker 池只有一个实现**（`ShardWorkerPool`）：本地 N 线程与远端进程走同一条路径，
   差别只是有几个进程连到那个目录。等待语义是「所有分片进终态才返回；只要还有人在推进就不算超时；
   连续 N 分钟没有新完成才判卡住」。
4. **检查点仍然是本地 runDir 的 `pipeline-checkpoint.json`**：队列负责「谁在跑」，
   检查点负责「跑到哪了」。两者用**产物存在性**对齐——队列说 DONE 但产物不见了，
   `RunPipelineUseCase` 会先 `reset` 再入队重跑（否则 DONE 记录会永远挡住重跑）。
5. **仓库内入口**：`gradlew :apps:voxelith-server:shardWorker -PworldDir=... -PmapId=... -Ppacks=... -PqueueDir=...`。
   一个 worker 进程只做一件小事：领 region → 读世界 → bake → tile/lod → 局部失效清单 → 回填产物路径。

## 后果

- 部署形态极简：多机只要挂同一个目录（NFS/SMB）就能协作；排查时直接看 JSON 文件。
- 已知边界：文件系统租约队列不适合几千个 worker 的高并发（每次领取要列目录 + 抢锁），
  当前规模（几十个 region 分片到几万个）足够；要扩展时实现同一个 `ShardQueuePort` 换成
  Redis/etcd 即可，上层不动。
- 崩溃恢复是「至少一次」语义：分片可能被执行两次（租约超时后原 worker 又活了过来）。
  分片是**幂等**的（重跑一个 region 覆盖同样的瓦片文件），所以这比「丢了不跑」安全。
