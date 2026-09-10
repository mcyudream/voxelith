package online.yudream.voxelith.world.infrastructure.legacy;

import java.util.Map;

/**
 * 数字群系 ID → 现代群系名（minecraft:*）映射。
 * 覆盖 1.12 数字 ID 与 1.13–1.17 注册表整型 ID（两者 0–39 段一致），
 * 已删除的群系映射到染色效果最接近的现代群系（群系仅用于 tint，允许近似）。
 * 未知 ID 一律降级为 minecraft:plains。
 */
public final class LegacyBiomeIds {

    public static final String FALLBACK = "minecraft:plains";

    private static final String NS = "minecraft:";

    private static final Map<Integer, String> TABLE = buildTable();

    private LegacyBiomeIds() {
    }

    public static String nameOf(int id) {
        return TABLE.getOrDefault(id, FALLBACK);
    }

    private static Map<Integer, String> buildTable() {
        String[] names = {
                "ocean", "plains", "desert", "windswept_hills", "forest", "taiga", "swamp", "river",
                "nether_wastes", "the_end", "frozen_ocean", "frozen_river", "snowy_plains", "snowy_plains",
                "mushroom_fields", "mushroom_fields", "beach", "desert", "forest", "taiga",
                "windswept_hills", "jungle", "jungle", "sparse_jungle", "deep_ocean", "stony_shore",
                "snowy_beach", "birch_forest", "birch_forest", "dark_forest", "snowy_taiga", "snowy_taiga",
                "old_growth_pine_taiga", "old_growth_pine_taiga", "windswept_forest", "savanna", "savanna_plateau",
                "badlands", "wooded_badlands", "badlands", "small_end_islands", "end_midlands", "end_highlands",
                "end_barrens", "warm_ocean", "lukewarm_ocean", "cold_ocean", "warm_ocean", "lukewarm_ocean",
                "deep_cold_ocean", "deep_frozen_ocean"
        };
        Map<Integer, String> table = new java.util.HashMap<>();
        for (int i = 0; i < names.length; i++) {
            table.put(i, NS + names[i]);
        }
        table.put(127, NS + "the_void");
        // 变种群系（原 id + 128）
        int[] mutated = {
                129, 130, 131, 132, 133, 134, 140, 149, 151, 155,
                156, 157, 158, 160, 161, 162, 163, 164, 165, 166, 167
        };
        String[] mutatedNames = {
                "sunflower_plains", "desert", "windswept_gravelly_hills", "flower_forest", "taiga",
                "swamp", "ice_spikes", "jungle", "sparse_jungle", "old_growth_birch_forest",
                "old_growth_birch_forest", "dark_forest", "snowy_taiga", "old_growth_spruce_taiga",
                "old_growth_spruce_taiga", "windswept_gravelly_hills", "windswept_savanna", "windswept_savanna",
                "eroded_badlands", "wooded_badlands", "badlands"
        };
        for (int i = 0; i < mutated.length; i++) {
            table.put(mutated[i], NS + mutatedNames[i]);
        }
        // 1.16+ 下界 / 洞穴群系（1.16–1.17 注册表整型 ID）
        table.put(170, NS + "crimson_forest");
        table.put(171, NS + "warped_forest");
        table.put(172, NS + "soul_sand_valley");
        table.put(173, NS + "basalt_deltas");
        table.put(174, NS + "dripstone_caves");
        table.put(175, NS + "lush_caves");
        return Map.copyOf(table);
    }
}
