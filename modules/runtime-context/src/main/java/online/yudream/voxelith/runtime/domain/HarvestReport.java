package online.yudream.voxelith.runtime.domain;

import java.util.List;
import java.util.Map;

/**
 * worker 子进程一次运行的报告（对应 worker-result.json）。
 *
 * @param ok          整体是否成功（worker 自检 + 采集均通过）
 * @param checks      逐项自检结果（如 lwjgl-glfw / lwjgl-gl 链接冒烟）
 * @param harvested   注册表中的方块数（采集覆盖范围基准）
 * @param failures    失败明细（人类可读）
 * @param durationMillis worker 内部耗时
 * @param statesExported  导出的 blockstate 数（无采集时为 0）
 * @param quadsExported   导出的烘焙 quad 数（无采集时为 0）
 * @param modelsFile      模型导出文件名（workDir 相对，无采集时为 null）
 */
public record HarvestReport(
        boolean ok,
        Map<String, Boolean> checks,
        int harvested,
        List<String> failures,
        long durationMillis,
        int statesExported,
        int quadsExported,
        String modelsFile) {

    public HarvestReport {
        checks = checks == null ? Map.of() : Map.copyOf(checks);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static HarvestReport failed(List<String> failures, long durationMillis) {
        return new HarvestReport(false, Map.of(), 0, failures, durationMillis, 0, 0, null);
    }
}
