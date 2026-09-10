package online.yudream.voxelith.orchestration.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 一次管线运行（runId 幂等键：同 runDir 重跑时续跑而非重来）。
 */
public record PipelineRun(String runId, String mapId, Map<PipelineStage, StageState> stages) {

    public static PipelineRun fresh(String runId, String mapId) {
        Map<PipelineStage, StageState> stages = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            stages.put(stage, StageState.pending());
        }
        return new PipelineRun(runId, mapId, stages);
    }

    public PipelineRun withStage(PipelineStage stage, StageState state) {
        Map<PipelineStage, StageState> copy = new EnumMap<>(stages);
        copy.put(stage, state);
        return new PipelineRun(runId, mapId, copy);
    }

    public StageState stage(PipelineStage stage) {
        return stages.getOrDefault(stage, StageState.pending());
    }

    public boolean finished() {
        return stages.values().stream().allMatch(s -> s.status() == StageStatus.DONE);
    }
}
