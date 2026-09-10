package online.yudream.voxelith.runtime.domain;

import java.util.List;
import java.util.Map;

/**
 * worker 子进程一次运行的报告（对应 worker-result.json）。
 *
 * @param ok          整体是否成功（worker 自检 + 采集均通过）
 * @param checks      逐项自检结果（如 lwjgl-glfw / lwjgl-gl 链接冒烟）
 * @param harvested   成功采集的模型数量（骨架期为 0）
 * @param failures    失败明细（人类可读）
 * @param durationMillis worker 内部耗时
 */
public record HarvestReport(
        boolean ok,
        Map<String, Boolean> checks,
        int harvested,
        List<String> failures,
        long durationMillis) {

    public HarvestReport {
        checks = checks == null ? Map.of() : Map.copyOf(checks);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static HarvestReport failed(List<String> failures, long durationMillis) {
        return new HarvestReport(false, Map.of(), 0, failures, durationMillis);
    }
}
