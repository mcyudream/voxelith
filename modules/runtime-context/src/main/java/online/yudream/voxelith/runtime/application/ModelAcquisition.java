package online.yudream.voxelith.runtime.application;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次模型获取的结果（runtime 采集或静态兜底）。
 *
 * @param source          模型来源（runtime 采集 / 静态兜底）
 * @param ok              最终是否拿到可用模型集
 * @param statesExported  runtime 路径导出的 blockstate 数（静态兜底为 0）
 * @param quadsExported   runtime 路径导出的 quad 数（静态兜底为 0）
 * @param blocksResolved  静态兜底路径解析成功的方块数（runtime 路径为 0）
 * @param blocksFound     静态兜底路径发现的方块总数（runtime 路径为 0）
 * @param runtimeFailures runtime 采集的失败明细（兜底时非空，供报告标记原因）
 * @param artifactPath    产物路径：runtime 为 models.json.gz 文件，静态兜底为 resolve 输出目录
 */
public record ModelAcquisition(
        ModelSource source,
        boolean ok,
        int statesExported,
        int quadsExported,
        int blocksResolved,
        int blocksFound,
        List<String> runtimeFailures,
        Path artifactPath) {

    public ModelAcquisition {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        runtimeFailures = runtimeFailures == null ? List.of() : List.copyOf(runtimeFailures);
    }
}
