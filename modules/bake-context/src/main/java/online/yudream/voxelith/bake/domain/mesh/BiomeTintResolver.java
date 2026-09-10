package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.world.application.WorldBlockAccess;

/**
 * 群系感知染色：按世界坐标读取群系，经资源目录的群系颜色表取色。
 * 白桦叶/云杉叶/睡莲等原版硬编码固定色不随群系；无群系数据时退回固定表
 * （BlockTints 中性色 / 原版默认水色），行为与旧实现一致。
 */
public final class BiomeTintResolver implements TintResolver {

    private final ResolvedResourceCatalog catalog;
    private final WorldBlockAccess world;

    public BiomeTintResolver(ResolvedResourceCatalog catalog, WorldBlockAccess world) {
        this.catalog = catalog;
        this.world = world;
    }

    @Override
    public int tint(String blockId, int tintIndex, int x, int y, int z) {
        if (FluidMesher.isFluid(blockId)) {
            if (!"minecraft:water".equals(blockId)) {
                return -1;
            }
            return world.biomeAt(x, y, z)
                    .map(catalog::biomeWaterColor)
                    .orElse(FluidMesher.DEFAULT_WATER_COLOR);
        }
        if (tintIndex < 0) {
            return -1;
        }
        Integer fixed = BlockTints.fixedColorFor(blockId);
        if (fixed != null) {
            return fixed;
        }
        return world.biomeAt(x, y, z)
                .map(biome -> BlockTints.categoryOf(blockId) == BlockTints.Category.FOLIAGE
                        ? catalog.biomeFoliageColor(biome)
                        : catalog.biomeGrassColor(biome))
                .orElseGet(() -> BlockTints.colorFor(blockId));
    }
}
