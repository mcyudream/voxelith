package online.yudream.voxelith.resource.domain.model;

import java.util.List;
import java.util.Map;

/**
 * blockstate 定义的两种形态：变体映射或多部件组合。
 */
public sealed interface BlockstateDefinition {

    /**
     * variants 形态：键为 "prop=value,prop2=value2"（或空串表示默认），值为加权模型引用列表。
     */
    record Variants(Map<String, List<ModelVariant>> variants) implements BlockstateDefinition {
    }

    /**
     * multipart 形态：按序求值，所有命中的 case 的 apply 都参与渲染。
     */
    record Multipart(List<MultipartCase> cases) implements BlockstateDefinition {
    }
}
