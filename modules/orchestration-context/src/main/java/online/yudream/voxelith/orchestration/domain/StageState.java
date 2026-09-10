package online.yudream.voxelith.orchestration.domain;

import java.util.List;

/**
 * 单阶段执行状态。
 *
 * @param artifacts 阶段产物相对路径（相对 runDir），断点续跑时逐一校验存在性，缺失则重跑该阶段
 */
public record StageState(StageStatus status, long startedAt, long finishedAt,
                         List<String> artifacts, String error) {

    public static StageState pending() {
        return new StageState(StageStatus.PENDING, 0, 0, List.of(), null);
    }

    public static StageState running(long startedAt) {
        return new StageState(StageStatus.RUNNING, startedAt, 0, List.of(), null);
    }

    public static StageState done(long startedAt, long finishedAt, List<String> artifacts) {
        return new StageState(StageStatus.DONE, startedAt, finishedAt, List.copyOf(artifacts), null);
    }

    public static StageState failed(long startedAt, long finishedAt, String error) {
        return new StageState(StageStatus.FAILED, startedAt, finishedAt, List.of(), error);
    }
}
