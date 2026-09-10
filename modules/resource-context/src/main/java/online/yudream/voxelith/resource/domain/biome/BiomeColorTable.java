package online.yudream.voxelith.resource.domain.biome;

/**
 * 群系颜色表：原版 GrassColor/FoliageColor colormap 采样 + effects 覆盖与修饰器。
 *
 * colormap 数学（256×256，index = y&lt;&lt;8 | x）：
 * <ul>
 *   <li>草：x = (1-temp)×255，y = (1-downfall×temp)×255</li>
 *   <li>树叶：x = (1-temp)×255，y = (1-downfall)×255（无 downfall×temp 步骤）</li>
 *   <li>dark_forest 修饰器：((color &amp; 0xFEFEFE) + 0x28340A) &gt;&gt; 1；
 *       swamp 修饰器取主色 0x6A7039（原版为柏林噪声二值，从略）</li>
 * </ul>
 */
public final class BiomeColorTable {

    /** 无 colormap 时的固定兜底（平原群系附近的中性绿）。 */
    public static final int FALLBACK_GRASS = 0x91BD59;
    public static final int FALLBACK_FOLIAGE = 0x77AB2F;
    /** 原版默认水色（effects.water_color 缺失时）。 */
    public static final int FALLBACK_WATER = 0x3F76E4;

    private static final int SWAMP_GRASS = 0x6A7039;
    private static final int DARK_FOREST_BLEND = 0x28340A;

    private final int[] grassColormap;
    private final int[] foliageColormap;

    /**
     * @param grassColormap   草 colormap（行主序 RGB，256×256），可为空数组
     * @param foliageColormap 树叶 colormap，可为空数组
     */
    public BiomeColorTable(int[] grassColormap, int[] foliageColormap) {
        this.grassColormap = grassColormap == null ? new int[0] : grassColormap;
        this.foliageColormap = foliageColormap == null ? new int[0] : foliageColormap;
    }

    public int grassColor(BiomeEffects biome) {
        if (biome.grassColor() != null) {
            return biome.grassColor();
        }
        if ("swamp".equals(biome.grassColorModifier())) {
            return SWAMP_GRASS;
        }
        int color = sample(grassColormap, biome.temperature(),
                biome.temperature() * biome.downfall(), FALLBACK_GRASS);
        if ("dark_forest".equals(biome.grassColorModifier())) {
            color = ((color & 0xFEFEFE) + DARK_FOREST_BLEND) >> 1;
        }
        return color;
    }

    public int foliageColor(BiomeEffects biome) {
        if (biome.foliageColor() != null) {
            return biome.foliageColor();
        }
        return sample(foliageColormap, biome.temperature(), biome.downfall(), FALLBACK_FOLIAGE);
    }

    public int waterColor(BiomeEffects biome) {
        return biome.waterColor() != null ? biome.waterColor() : FALLBACK_WATER;
    }

    private static int sample(int[] colormap, double temperature, double downfall, int fallback) {
        if (colormap.length == 0) {
            return fallback;
        }
        double t = clamp(temperature);
        double d = clamp(downfall);
        int x = (int) ((1.0 - t) * 255.0);
        int y = (int) ((1.0 - d) * 255.0);
        int index = (y << 8) | x;
        return index < colormap.length ? colormap[index] & 0xFFFFFF : fallback;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
