package online.yudream.voxelith.resource.application.dto;

import java.util.List;

/**
 * 一组加权互斥的模型引用：渲染时从组内按权重取其一；组与组之间叠加渲染
 * （variants 至多一组，multipart 每个命中 case 一组）。
 */
public record VariantGroup(List<ModelVariantData> alternatives) {
}
