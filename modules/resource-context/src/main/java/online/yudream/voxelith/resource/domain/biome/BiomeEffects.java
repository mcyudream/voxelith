package online.yudream.voxelith.resource.domain.biome;

/**
 * 群系效果参数（data/{ns}/worldgen/biome/{path}.json 的子集）。
 *
 * @param temperature        温度（0~2，采样前钳制到 0~1）
 * @param downfall           降水（0~1）
 * @param grassColor         effects.grass_color 显式覆盖（可空，空则走 colormap）
 * @param foliageColor       effects.foliage_color 显式覆盖（可空）
 * @param grassColorModifier effects.grass_color_modifier：none / dark_forest / swamp
 * @param waterColor         effects.water_color（可空，空取默认水色）
 */
public record BiomeEffects(double temperature, double downfall,
                           Integer grassColor, Integer foliageColor,
                           String grassColorModifier, Integer waterColor) {

    /** 未知群系兜底：平原（temperature 0.8 / downfall 0.4 / 默认水色）。 */
    public static final BiomeEffects DEFAULT =
            new BiomeEffects(0.8, 0.4, null, null, "none", null);
}
