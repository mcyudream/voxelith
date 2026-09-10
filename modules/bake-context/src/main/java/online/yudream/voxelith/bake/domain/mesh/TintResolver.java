package online.yudream.voxelith.bake.domain.mesh;

/**
 * 染色解析器：方块 + 染色索引 + 世界坐标 → RGB（0xRRGGBB），-1 = 不染色。
 * 静态固定表与生物群系感知实现均满足本契约（流体染色按坐标取群系水色）。
 */
@FunctionalInterface
public interface TintResolver {

    /**
     * @param blockId   方块 id（minecraft:xxx）
     * @param tintIndex 模型面染色索引（&lt;0 的面应返回 -1）
     * @param x/y/z     世界绝对坐标（群系采样用）
     * @return 0xRRGGBB；-1 = 不染色（下游按白色处理）
     */
    int tint(String blockId, int tintIndex, int x, int y, int z);
}
