package online.yudream.voxelith.bake.domain.mesh;

import java.util.Map;

/**
 * 方块染色分类：原版 BlockColors 语义的方块 → 染色类别/固定色映射。
 *
 * 原版硬编码固定色（不随群系）：白桦叶、云杉叶、睡莲；
 * 其余可染色面按类别取样群系色（草系 → grass colormap，树叶/藤蔓 → foliage colormap）。
 * 无群系数据时 colorFor 退化为平原群系附近的中性固定色。
 */
public final class BlockTints {

    /** 草系绿（平原群系草色）。 */
    public static final int GRASS_GREEN = 0x91BD59;
    /** 树叶绿（平原群系通用叶色）。 */
    public static final int FOLIAGE_GREEN = 0x77AB2F;
    /** 白桦叶（原版硬编码）。 */
    public static final int BIRCH_FOLIAGE = 0x80A755;
    /** 云杉叶（原版硬编码）。 */
    public static final int SPRUCE_FOLIAGE = 0x619961;
    /** 睡莲（原版硬编码）。 */
    public static final int LILY_PAD = 0x208030;

    /** 染色类别：取群系草色还是树叶色。 */
    public enum Category {
        GRASS, FOLIAGE
    }

    private static final Map<String, Integer> FIXED = Map.of(
            "minecraft:birch_leaves", BIRCH_FOLIAGE,
            "minecraft:spruce_leaves", SPRUCE_FOLIAGE,
            "minecraft:lily_pad", LILY_PAD
    );

    private BlockTints() {
    }

    /** 原版硬编码固定色；非固定色方块返回 null。 */
    public static Integer fixedColorFor(String blockId) {
        return FIXED.get(blockId);
    }

    /**
     * 染色类别：*_leaves 与藤蔓类取树叶色，其余（草、蕨、甘蔗、海草、粉色花簇等）
     * 绝大多数可染色面都是草系植物，缺省取草色。
     */
    public static Category categoryOf(String blockId) {
        if (blockId.endsWith("_leaves")) {
            return Category.FOLIAGE;
        }
        return switch (blockId) {
            case "minecraft:vine", "minecraft:cave_vines", "minecraft:cave_vines_plant",
                 "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
                 "minecraft:twisting_vines", "minecraft:twisting_vines_plant" -> Category.FOLIAGE;
            default -> Category.GRASS;
        };
    }

    /** 无群系数据时的固定表染色（平原群系附近的中性色）。 */
    public static int colorFor(String blockId) {
        Integer fixed = fixedColorFor(blockId);
        if (fixed != null) {
            return fixed;
        }
        return categoryOf(blockId) == Category.FOLIAGE ? FOLIAGE_GREEN : GRASS_GREEN;
    }
}
