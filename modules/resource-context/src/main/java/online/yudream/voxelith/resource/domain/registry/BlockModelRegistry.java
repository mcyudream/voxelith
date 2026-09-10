package online.yudream.voxelith.resource.domain.registry;

import online.yudream.voxelith.resource.domain.model.ResolvedModel;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * 资源解析聚合根：一次 resolve 链路的完整产物（内存形态）。
 * 方块维度存 blockstate 定义与引用模型；模型维度全局去重存继承链展开后的模型。
 */
public record BlockModelRegistry(
        Map<Identifier, ResolvedBlock> blocks,
        Map<Identifier, ResolvedModel> models) {

    public Optional<ResolvedBlock> block(Identifier block) {
        return Optional.ofNullable(blocks.get(block));
    }

    public Optional<ResolvedModel> model(Identifier model) {
        return Optional.ofNullable(models.get(model));
    }

    public int blockCount() {
        return blocks.size();
    }

    public int modelCount() {
        return models.size();
    }
}
