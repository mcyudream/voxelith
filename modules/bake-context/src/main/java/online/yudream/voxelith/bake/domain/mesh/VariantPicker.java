package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.resource.application.dto.ModelVariantData;

import java.util.List;

/**
 * 加权变体选择：以方块坐标为种子的确定性选取（同一坐标多次烘焙结果一致）。
 */
public final class VariantPicker {

    private VariantPicker() {
    }

    public static ModelVariantData pick(List<ModelVariantData> alternatives, int x, int y, int z) {
        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }
        int totalWeight = 0;
        for (ModelVariantData alternative : alternatives) {
            totalWeight += Math.max(1, alternative.weight());
        }
        int roll = (int) (hash(x, y, z) % totalWeight);
        int cumulative = 0;
        for (ModelVariantData alternative : alternatives) {
            cumulative += Math.max(1, alternative.weight());
            if (roll < cumulative) {
                return alternative;
            }
        }
        return alternatives.getFirst();
    }

    private static long hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= y * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }
}
