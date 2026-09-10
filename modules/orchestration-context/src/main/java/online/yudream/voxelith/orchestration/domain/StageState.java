package online.yudream.voxelith.orchestration.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单阶段执行状态。
 *
 * @param artifacts       阶段整体产物相对路径（相对 runDir），断点续跑时逐一校验存在性，缺失则重跑
 * @param totalShards     分片总数（0 = 非分片阶段）
 * @param completedShards 已完成分片 → 其产物相对路径；产物缺失的分片续跑时重跑
 */
public record StageState(StageStatus status, long startedAt, long finishedAt,
                         List<String> artifacts, String error,
                         int totalShards, Map<String, List<String>> completedShards) {

    public static StageState pending() {
        return new StageState(StageStatus.PENDING, 0, 0, List.of(), null, 0, Map.of());
    }

    public static StageState running(long startedAt) {
        return new StageState(StageStatus.RUNNING, startedAt, 0, List.of(), null, 0, Map.of());
    }

    public StageState sharded(int totalShards) {
        return new StageState(status, startedAt, finishedAt, artifacts, error,
                totalShards, completedShards);
    }

    /** 续跑进入分片循环：保留已完成分片，仅把状态翻回 RUNNING。 */
    public StageState resumeRunning(long startedAt) {
        return new StageState(StageStatus.RUNNING, startedAt, 0, artifacts, null,
                totalShards, completedShards);
    }

    public StageState withShardDone(String shard, List<String> shardArtifacts) {
        Map<String, List<String>> copy = new LinkedHashMap<>(completedShards);
        copy.put(shard, List.copyOf(shardArtifacts));
        return new StageState(StageStatus.RUNNING, startedAt, 0, artifacts, null, totalShards, copy);
    }

    public StageState done(long finishedAt, List<String> doneArtifacts) {
        return new StageState(StageStatus.DONE, startedAt, finishedAt,
                List.copyOf(doneArtifacts), null, totalShards, completedShards);
    }

    public StageState failed(long finishedAt, String failure) {
        return new StageState(StageStatus.FAILED, startedAt, finishedAt,
                artifacts, failure, totalShards, completedShards);
    }

    public boolean shardDone(String shard) {
        return completedShards.containsKey(shard);
    }
}
