package online.yudream.voxelith.orchestration.domain;

import java.util.List;

/**
 * 一个分片作业（管线阶段 × 分片键，例如 BAKE × {@code r.0.0}）。
 *
 * @param stage          管线阶段
 * @param shard          分片键（region 语义：{@code r.X.Z}）
 * @param state          状态
 * @param workerId       当前/最后的领取者（未领取为 null）
 * @param leaseUntilEpochMs 租约到期时刻（毫秒时间戳；0 = 无租约）
 * @param attempts       被领取次数（> 1 说明发生过租约超时重领，即 worker 崩溃/卡死）
 * @param artifacts      完成后的产物相对路径（相对 runDir）
 * @param error          失败原因
 */
public record ShardJob(PipelineStage stage, String shard, ShardJobState state, String workerId,
                       long leaseUntilEpochMs, int attempts, List<String> artifacts, String error) {

    public ShardJob {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static ShardJob queued(PipelineStage stage, String shard) {
        return new ShardJob(stage, shard, ShardJobState.QUEUED, null, 0, 0, List.of(), null);
    }

    /** 租约是否已过期（可被其他 worker 重新领取）。 */
    public boolean leaseExpired(long nowEpochMs) {
        return state == ShardJobState.CLAIMED && leaseUntilEpochMs <= nowEpochMs;
    }

    public ShardJob claimed(String claimer, long leaseUntil) {
        return new ShardJob(stage, shard, ShardJobState.CLAIMED, claimer, leaseUntil, attempts + 1,
                artifacts, null);
    }

    public ShardJob done(List<String> doneArtifacts) {
        return new ShardJob(stage, shard, ShardJobState.DONE, workerId, 0, attempts,
                doneArtifacts, null);
    }

    public ShardJob failed(String failure) {
        return new ShardJob(stage, shard, ShardJobState.FAILED, workerId, 0, attempts,
                artifacts, failure);
    }

    /** 是否还需要（继续）执行。 */
    public boolean pending(long nowEpochMs) {
        return state == ShardJobState.QUEUED || leaseExpired(nowEpochMs);
    }

    /**
     * 是否已进入终态（DONE/FAILED）。
     *
     * <p>与 {@link #pending(long)} 的区别很关键：正在被别的 worker 执行（CLAIMED 且租约有效）
     * 既不是 pending、也不是 terminal——等待方必须把它当作「还要等」，
     * 否则会在别人干活时提前收工（阶段被误判完成）。</p>
     */
    public boolean terminal() {
        return state == ShardJobState.DONE || state == ShardJobState.FAILED;
    }
}
