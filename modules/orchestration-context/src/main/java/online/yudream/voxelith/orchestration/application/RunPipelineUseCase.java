package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineCheckpointStore;
import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import online.yudream.voxelith.orchestration.domain.StageState;
import online.yudream.voxelith.orchestration.domain.StageStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors) {
        this(checkpoints, executors, Map.of());
    }

    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors,
                              Map<PipelineStage, ShardedStageExecutor> shardedExecutors) {
        this.checkpoints = checkpoints;
        this.executors = Map.copyOf(executors);
        this.shardedExecutors = Map.copyOf(shardedExecutors);
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

    private boolean artifactsIntact(Path runDir, List<String> artifacts) {
        return artifacts.stream().allMatch(rel -> Files.exists(runDir.resolve(rel)));
    }
}
