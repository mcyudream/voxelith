package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineCheckpointStore;
import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.StageState;
import online.yudream.voxelith.orchestration.domain.StageStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 管线运行用例：按 resolve→scan→bake→tile→lod→manifest 顺序驱动各阶段，
 * 每阶段完成即落检查点；同 runDir 重跑时跳过已完成且产物健在的阶段（断点续跑），
 * 产物缺失或上次失败的阶段原地重跑。任一阶段失败即标 FAILED 并中断，后续阶段保持 PENDING。
 */
public class RunPipelineUseCase {

    private final PipelineCheckpointStore checkpoints;
    private final Map<PipelineStage, PipelineStageExecutor> executors;

    public RunPipelineUseCase(PipelineCheckpointStore checkpoints,
                              Map<PipelineStage, PipelineStageExecutor> executors) {
        this.checkpoints = checkpoints;
        this.executors = Map.copyOf(executors);
    }

    public PipelineRun run(Path runDir, String runId, String mapId) {
        PipelineRun run = checkpoints.load(runDir)
                .filter(r -> r.runId().equals(runId))
                .orElse(PipelineRun.fresh(runId, mapId));

        for (PipelineStage stage : PipelineStage.ordered()) {
            StageState state = run.stage(stage);
            if (state.status() == StageStatus.DONE && artifactsIntact(runDir, state)) {
                continue;
            }
            PipelineStageExecutor executor = executors.get(stage);
            if (executor == null) {
                throw new IllegalStateException("阶段无执行器: " + stage);
            }
            long startedAt = System.currentTimeMillis();
            run = run.withStage(stage, StageState.running(startedAt));
            checkpoints.save(runDir, run);
            try {
                List<String> artifacts = executor.execute(stage, runDir);
                run = run.withStage(stage,
                        StageState.done(startedAt, System.currentTimeMillis(), artifacts));
            } catch (Exception e) {
                run = run.withStage(stage,
                        StageState.failed(startedAt, System.currentTimeMillis(), String.valueOf(e)));
                checkpoints.save(runDir, run);
                return run;
            }
            checkpoints.save(runDir, run);
        }
        return run;
    }

    private boolean artifactsIntact(Path runDir, StageState state) {
        return state.artifacts().stream()
                .allMatch(rel -> Files.exists(runDir.resolve(rel)));
    }
}
