package online.yudream.voxelith.orchestration.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * 分片阶段执行器端口（region 粒度任务分片，天然支持后续分布式调度）。
 * 分片键建议 region 文件名语义（如 {@code "r.0.0"}），由组合根装配时与世界扫描产物对齐。
 */
public interface ShardedStageExecutor {

    /** 该阶段的全部 shard 键（顺序即调度顺序）。 */
    List<String> shards(PipelineStage stage, Path runDir) throws Exception;

    /** 执行单个 shard，返回其产物相对路径（相对 runDir）。 */
    List<String> executeShard(PipelineStage stage, String shard, Path runDir) throws Exception;
}
