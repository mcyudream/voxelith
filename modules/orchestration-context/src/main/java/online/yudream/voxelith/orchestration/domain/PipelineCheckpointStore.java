package online.yudream.voxelith.orchestration.domain;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 管线运行检查点存储端口：断点续跑的状态落盘抽象。
 */
public interface PipelineCheckpointStore {

    Optional<PipelineRun> load(Path runDir);

    void save(Path runDir, PipelineRun run);
}
