package online.yudream.voxelith.orchestration.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * 单阶段执行器端口：由组合根装配各上下文 application 用例。
 *
 * @return 阶段产物相对路径（相对 runDir），续跑时用于存在性校验
 */
@FunctionalInterface
public interface PipelineStageExecutor {

    List<String> execute(PipelineStage stage, Path runDir) throws Exception;
}
