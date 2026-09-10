package online.yudream.voxelith.resource.application;

import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已解析资源目录：资源上下文对外的查询契约（烘焙/瓦片链路消费）。
 * 实现按需解析并缓存；线程安全。
 */
public interface ResolvedResourceCatalog extends AutoCloseable {

    /** 全部具有 blockstate 的方块。 */
    List<Identifier> blocks();

    /** 为给定方块状态选择应渲染的模型组（组内加权取一，组间叠加渲染）。 */
    List<VariantGroup> selectVariantGroups(Identifier block, Map<String, String> state);

    /** 取继承链展开后的模型。 */
    Optional<ModelData> model(Identifier modelId);

    /** 取贴图原始字节（PNG）。 */
    Optional<byte[]> texture(Identifier textureId);

    /** 群系草色（colormap/effects 覆盖/修饰器，未知群系按平原兜底）。 */
    int biomeGrassColor(String biomeId);

    /** 群系树叶色。 */
    int biomeFoliageColor(String biomeId);

    /** 群系水色（effects.water_color，缺失取原版默认）。 */
    int biomeWaterColor(String biomeId);

    @Override
    default void close() {
    }
}
