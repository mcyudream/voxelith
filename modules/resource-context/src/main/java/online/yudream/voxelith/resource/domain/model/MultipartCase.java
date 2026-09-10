package online.yudream.voxelith.resource.domain.model;

import java.util.List;

/**
 * multipart 数组中的一项：条件命中时应用一组加权模型引用。
 */
public record MultipartCase(StateCondition when, List<ModelVariant> apply) {
}
