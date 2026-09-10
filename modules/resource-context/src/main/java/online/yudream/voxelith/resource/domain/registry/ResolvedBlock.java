package online.yudream.voxelith.resource.domain.registry;

import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.Set;

/**
 * 单个方块的解析结果：blockstate 定义 + 其引用到的全部模型 id。
 */
public record ResolvedBlock(
        Identifier block,
        BlockstateDefinition definition,
        Set<Identifier> referencedModels) {
}
