package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineCheckpointStore;
import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardJob;
import online.yudream.voxelith.orchestration.domain.ShardJobState;
import online.yudream.voxelith.orchestration.domain.ShardQueuePort;
import online.yudream.voxelith.orchestration.domain.StageState;
import online.yudream.voxelith.orchestration.domain.StageStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管线运行用例：按 resolve→scan→bake→tile→lod→manifest 顺序驱动各阶段，
 * 每阶段（分片阶段则每分片）完成即落检查点；同 runDir 同 runId 重跑时跳过已完成
 * 且产物健在的阶段/分片（断点续跑），产物缺失或上次失败的原地重跑。
 * 任一阶段失败即标 FAILED 并中断，后续阶段保持 PENDING。
 */
public class RunPipelineUseCase {

    private final PipelineCheckpointStore checkpoints;
    private final Map<PipelineStage, PipelineStageExecutor> executors;
    private final Map<PipelineStage, ShardedStageExecutor> shardedExecutors;
    /** 可选：分片作业队列（null = 本地顺序执行）。 */
    private final ShardQueuePort queue;
    private final ShardWorkerPool workerPool;
    private final int workers;
    /**
     * 队列模式下校验作业产物是否存在时使用的基准目录；null = 用本次运行的 runDir。
     *
     * <p>远端 worker 回填的产物路径是**相对它自己写入的目录**的（例如发布目录
     * {@code data/maps/{mapId}}），而调度进程的 runDir 可能是另一个工作目录。
     * 两者不一致时，调度侧会误判「产物丢失」→ 清掉队列里的 DONE 记录并重跑，
     * 白白丢掉别人已经做完的活。跨进程部署时把这里指向 worker 的写入根即可。</p>
     */
    private final Path artifactRoot;

    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors) {
        this(checkpoints, executors, Map.of());
    }

    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors,
                              Map<PipelineStage, ShardedStageExecutor> shardedExecutors) {
        this(checkpoints, executors, shardedExecutors, null, 1);
    }

    /**
     * @param queue   分片作业队列；null = 本地顺序执行
     * @param workers 本地 worker 线程数（队列模式下 >1 才本地并行；远端 worker 另算）
     */
    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors,
                              Map<PipelineStage, ShardedStageExecutor> shardedExecutors,
                              ShardQueuePort queue, int workers) {
        this(checkpoints, executors, shardedExecutors, queue, workers, null);
    }

    /**
     * @param artifactRoot 队列作业产物的基准目录；null = 用 runDir（本机同目录部署）
     */
    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors,
                              Map<PipelineStage, ShardedStageExecutor> shardedExecutors,
                              ShardQueuePort queue, int workers, Path artifactRoot) {
        this.checkpoints = checkpoints;
        this.executors = Map.copyOf(executors);
        this.shardedExecutors = Map.copyOf(shardedExecutors);
        this.queue = queue;
        this.workers = Math.max(1, workers);
        this.artifactRoot = artifactRoot;
        this.workerPool = queue == null
                ? null
                : new ShardWorkerPool(queue, java.time.Duration.ofMinutes(30),
                        java.time.Duration.ofMinutes(10));
    }

    public PipelineRun run(Path runDir, String runId, String mapId) {
        PipelineRun run = checkpoints.load(runDir)
                .filter(r -> r.runId().equals(runId))
                .orElse(PipelineRun.fresh(runId, mapId));

        for (PipelineStage stage : PipelineStage.ordered()) {
            StageState state = run.stage(stage);
            if (state.status() == StageStatus.DONE && artifactsIntact(runDir, state.artifacts())) {
                continue;
            }
            long startedAt = System.currentTimeMillis();
            try {
                run = executeStage(runDir, run, stage, startedAt);
            } catch (Exception e) {
                // 分片执行器可能已在检查点里记下完成的分片——以盘上最新状态为准再标 FAILED
                PipelineRun latest = checkpoints.load(runDir).orElse(run);
                run = latest.withStage(stage, latest.stage(stage)
                        .failed(System.currentTimeMillis(), String.valueOf(e)));
                checkpoints.save(runDir, run);
                return run;
            }
            checkpoints.save(runDir, run);
        }
        return run;
    }

    private PipelineRun executeStage(Path runDir, PipelineRun run, PipelineStage stage,
                                     long startedAt) throws Exception {
        ShardedStageExecutor sharded = shardedExecutors.get(stage);
        if (sharded != null) {
            return executeSharded(runDir, run, stage, sharded, startedAt);
        }
        PipelineStageExecutor executor = executors.get(stage);
        if (executor == null) {
            throw new IllegalStateException("阶段无执行器: " + stage);
        }
        run = run.withStage(stage, StageState.running(startedAt));
        checkpoints.save(runDir, run);
        List<String> artifacts = executor.execute(stage, runDir);
        return run.withStage(stage, run.stage(stage)
                .done(System.currentTimeMillis(), artifacts));
    }

    private PipelineRun executeSharded(Path runDir, PipelineRun run, PipelineStage stage,
                                       ShardedStageExecutor executor, long startedAt) throws Exception {
        List<String> shards = executor.shards(stage, runDir);
        StageState state = run.stage(stage);
        // 分片数变化（世界增减 region）视为新阶段，丢弃旧分片进度；否则保留已完成分片续跑
        if (state.totalShards() != shards.size()) {
            state = StageState.running(startedAt).sharded(shards.size());
        } else {
            state = state.resumeRunning(startedAt);
        }
        run = run.withStage(stage, state);
        checkpoints.save(runDir, run);

        if (queue != null) {
            return executeShardedViaQueue(runDir, run, stage, executor, shards);
        }

        List<String> allArtifacts = new ArrayList<>();
        for (String shard : shards) {
            StageState current = run.stage(stage);
            if (current.shardDone(shard)
                    && artifactsIntact(runDir, current.completedShards().get(shard))) {
                allArtifacts.addAll(current.completedShards().get(shard));
                continue;
            }
            List<String> shardArtifacts = executor.executeShard(stage, shard, runDir);
            run = run.withStage(stage, run.stage(stage).withShardDone(shard, shardArtifacts));
            checkpoints.save(runDir, run);
            allArtifacts.addAll(shardArtifacts);
        }
        return run.withStage(stage, run.stage(stage)
                .done(System.currentTimeMillis(), allArtifacts));
    }

    /**
     * 队列模式：入队 → 本地 worker 与远端 worker 一起抢 → 按作业回填分片检查点。
     *
     * <p>已完成且产物健在的分片不重复入队；其余入队（enqueue 幂等：DONE 的不重置、
     * 别人正在跑的不抢）。产物缺失的分片会被重新执行——这正是「检查点说有、盘上没有」
     * 场景需要的语义。</p>
     */
    private PipelineRun executeShardedViaQueue(Path runDir, PipelineRun run, PipelineStage stage,
                                               ShardedStageExecutor executor, List<String> shards)
            throws Exception {
        StageState current = run.stage(stage);
        // 队列作业的产物可能写在与 runDir 不同的根下（远端 worker 写进发布目录）
        Path artifactBase = artifactRoot != null ? artifactRoot : runDir;
        Map<String, ShardJob> queued = new java.util.HashMap<>();
        for (ShardJob job : queue.jobs(stage)) {
            queued.put(job.shard(), job);
        }
        List<String> toEnqueue = new ArrayList<>();
        for (String shard : shards) {
            boolean checkpointOk = current.shardDone(shard)
                    && artifactsIntact(runDir, current.completedShards().get(shard));
            // 队列里的 DONE 是「别的 worker 已经跑过」的凭据：产物还在就不重跑，
            // 产物没了（被清理/磁盘丢失）才回收重跑
            ShardJob job = queued.get(shard);
            boolean queueOk = job != null && job.state() == ShardJobState.DONE
                    && artifactsIntact(artifactBase, job.artifacts());
            if (checkpointOk || queueOk) {
                continue;
            }
            toEnqueue.add(shard);
        }
        if (!toEnqueue.isEmpty()) {
            // 队列里的 DONE 记录会挡住重跑（产物被删/被清），先丢弃记录再入队
            for (String shard : toEnqueue) {
                queue.reset(stage, shard);
            }
            queue.enqueue(stage, toEnqueue);
            workerPool.drainStage(stage, "local", workers,
                    (s, shard) -> executor.executeShard(stage, shard, runDir));
        }

        List<String> allArtifacts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();
        Set<String> recorded = new HashSet<>();
        for (ShardJob job : queue.jobs(stage)) {
            recorded.add(job.shard());
            if (job.state() == ShardJobState.DONE) {
                run = run.withStage(stage, run.stage(stage).withShardDone(job.shard(), job.artifacts()));
                allArtifacts.addAll(job.artifacts());
            } else if (job.state() == ShardJobState.FAILED) {
                failures.add(job.shard() + ": " + job.error());
            } else {
                // QUEUED / CLAIMED（别人还在跑）：绝不当成完成——
                // 静默跳过会让阶段带着缺口的产物进入下一阶段
                unfinished.add(job.shard() + "(" + job.state() + ")");
            }
        }
        checkpoints.save(runDir, run);
        // 队列里根本没见到某个分片（作业文件被删/不可读）时同样不能收工：
        // 阶段带着缺口的产物进入下一阶段比直接失败更难查
        List<String> missing = shards.stream().filter(shard -> !recorded.contains(shard)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("队列里找不到这些分片（作业文件被删或不可读）: " + missing);
        }
        if (!unfinished.isEmpty()) {
            throw new IllegalStateException("分片尚未完成（不应发生：等待逻辑结束后仍有未终态分片）: "
                    + unfinished);
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("分片失败: " + failures);
        }
        return run.withStage(stage, run.stage(stage)
                .done(System.currentTimeMillis(), allArtifacts));
    }

    private boolean artifactsIntact(Path runDir, List<String> artifacts) {
        return artifacts.stream().allMatch(rel -> Files.exists(runDir.resolve(rel)));
    }
}
