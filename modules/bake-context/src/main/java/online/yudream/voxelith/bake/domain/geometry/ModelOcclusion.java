package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.sharedkernel.vo.Direction;

import java.util.Set;

/**
 * 模型遮挡判定：决定"该方块在某方向上是否完全遮住邻居的面"，用于 cullface 邻居剔除。
 * <p>
 * 一期近似：存在满方块元素（0,0,0 → 16,16,16）且该方向面声明了同向 cullface 即视为遮挡；
 * 已知透明满方块（玻璃家族、树叶等，游戏内本就不遮挡）列入白名单排除。
 */
public final class ModelOcclusion {

    /** 满方块但游戏内不遮挡邻居的方块（fancy 图形语义）。 */
    private static final Set<String> NON_OCCLUDING = Set.of(
            "minecraft:glass", "minecraft:tinted_glass",
            "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
            "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
            "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
            "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
            "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
            "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
            "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
            "minecraft:red_stained_glass", "minecraft:black_stained_glass",
            "minecraft:ice", "minecraft:slime_block", "minecraft:honey_block",
            "minecraft:barrier", "minecraft:light", "minecraft:structure_void",
            "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
            "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
            "minecraft:mangrove_leaves", "minecraft:cherry_leaves", "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves");

    private ModelOcclusion() {
    }

    public static boolean occludes(String blockId, ModelData model, Direction dir) {
        if (NON_OCCLUDING.contains(blockId)) {
            return false;
        }
        String dirName = dir.name().toLowerCase();
        for (ModelData.ElementData element : model.elements()) {
            if (!isFullCube(element)) {
                continue;
            }
            ModelData.FaceData face = element.faces().get(dirName);
            if (face != null && dirName.equals(face.cullface())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFullCube(ModelData.ElementData element) {
        if (element.rotation() != null && element.rotation().angle() != 0) {
            return false;
        }
        float[] from = element.from();
        float[] to = element.to();
        return from[0] == 0 && from[1] == 0 && from[2] == 0
                && to[0] == 16 && to[1] == 16 && to[2] == 16;
    }
}
