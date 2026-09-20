package online.yudream.voxelith.resource.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 贴图旧名 ↔ 新名候选表：**贴图名随版本改名时自动兜底**，避免整片品红。
 *
 * <p>为什么需要它：方块/模型引用的是资源包里的贴图 id，而 id 会随版本改名——
 * 最典型的是 1.20.3 把 {@code block/grass} 改名 {@code block/short_grass}，
 * 1.17 把 {@code grass_path_*} 改名 {@code dirt_path_*}，1.13 flattening 更是把
 * 羊毛/陶瓦/木板/原木等一大批「前缀式」名字改成「后缀式」。
 * 一旦存档版本、采集版本（models.json.gz）与资源包版本三者有偏差，
 * 引用的旧名在新包里查不到，那个面就会落进品红兜底格。</p>
 *
 * <p>这里是**候选**而不是强制映射：调用方先按原名查，查不到才按候选顺序试，
 * 命中后应把「谁顶替了谁」报给用户（版本不一致本身仍要修，只是画面不再碎）。</p>
 *
 * <p>纯函数、无框架依赖，resource/tile/bake 各链路都能用。</p>
 */
public final class TextureAliases {

    /** 16 种染料颜色（1.13 前后的命名都基于这套词）。 */
    private static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** 木板与原木的木材种类（1.13/1.14 前后命名）。 */
    private static final List<String> WOODS = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak");

    /**
     * 1.13 flattening 的「前缀 ↔ 后缀」颜色族：旧名 {@code wool_colored_red}、
     * 新名 {@code red_wool}，模板里 {@code %s} 是颜色词。
     */
    private static final List<String[]> COLOR_FAMILIES = List.of(
            new String[]{"block/%s_wool", "block/wool_colored_%s"},
            new String[]{"block/%s_terracotta", "block/hardened_clay_stained_%s"},
            new String[]{"block/%s_concrete", "block/concrete_%s"},
            new String[]{"block/%s_concrete_powder", "block/concrete_powder_%s"},
            new String[]{"block/%s_stained_glass", "block/stained_glass_%s"},
            new String[]{"block/%s_stained_glass_pane", "block/stained_glass_pane_%s"},
            new String[]{"block/%s_carpet", "block/carpet_%s"},
            new String[]{"block/%s_shulker_box", "block/shulker_box_%s"});

    /** 木板/原木：旧名 {@code planks_oak}/{@code log_oak}，新名 {@code oak_planks}/{@code oak_log}。 */
    private static final List<String[]> WOOD_FAMILIES = List.of(
            new String[]{"block/%s_planks", "block/planks_%s"},
            new String[]{"block/%s_log", "block/log_%s"},
            new String[]{"block/%s_log_top", "block/log_%s_top"},
            new String[]{"block/%s_sapling", "block/sapling_%s"});

    /**
     * 定名改变（非族式）的常见贴图：左右互为候选。
     * 只收「确实发生过改名、且新旧都有人用」的那些，不猜。
     */
    private static final Map<String, String> RENAMES = Map.ofEntries(
            // 1.20.3：草 → 矮草
            Map.entry("block/grass", "block/short_grass"),
            // 1.17：草径 → 土径
            Map.entry("block/grass_path_top", "block/dirt_path_top"),
            Map.entry("block/grass_path_side", "block/dirt_path_side"),
            // 1.13：草方块三件套
            Map.entry("block/grass_top", "block/grass_block_top"),
            Map.entry("block/grass_side", "block/grass_block_side"),
            Map.entry("block/grass_side_overlay", "block/grass_block_side_overlay"),
            // 1.13：石头/砖类复数化
            Map.entry("block/stonebrick", "block/stone_bricks"),
            Map.entry("block/stonebrick_cracked", "block/cracked_stone_bricks"),
            Map.entry("block/stonebrick_mossy", "block/mossy_stone_bricks"),
            Map.entry("block/stonebrick_carved", "block/chiseled_stone_bricks"),
            Map.entry("block/brick", "block/bricks"),
            Map.entry("block/nether_brick", "block/nether_bricks"),
            Map.entry("block/red_nether_brick", "block/red_nether_bricks"),
            Map.entry("block/hardened_clay", "block/terracotta"),
            Map.entry("block/stone_slab_side", "block/smooth_stone_slab_side"),
            Map.entry("block/stone_slab_top", "block/smooth_stone_slab_top"),
            // 1.14：木头类
            Map.entry("block/log_oak", "block/oak_log"),
            Map.entry("block/log_spruce", "block/spruce_log"),
            Map.entry("block/log_birch", "block/birch_log"),
            Map.entry("block/log_jungle", "block/jungle_log"),
            Map.entry("block/log_acacia", "block/acacia_log"),
            Map.entry("block/log_big_oak", "block/dark_oak_log"));

    private TextureAliases() {
    }

    /**
     * 贴图 id 的候选替代名（有序、去重、不含原名本身）。
     *
     * <p>只处理 {@code minecraft:} 命名空间：mod 的贴图命名由各自作者决定，猜不得。</p>
     */
    public static List<String> candidates(String textureId) {
        if (textureId == null || !textureId.startsWith("minecraft:")) {
            return List.of();
        }
        String path = textureId.substring("minecraft:".length());
        Set<String> out = new LinkedHashSet<>();

        String renamed = RENAMES.get(path);
        if (renamed != null && !renamed.equals(path)) {
            out.add("minecraft:" + renamed);
        }
        for (Map.Entry<String, String> entry : RENAMES.entrySet()) {
            if (entry.getValue().equals(path)) {
                out.add("minecraft:" + entry.getKey());
            }
        }
        out.addAll(familyCandidates(path));
        out.remove(textureId);
        return List.copyOf(out);
    }

    /** 族式改名：颜色/木材的前后缀互换（1.13/1.14 flattening）。 */
    private static List<String> familyCandidates(String path) {
        List<String> out = new ArrayList<>();
        for (String[] family : COLOR_FAMILIES) {
            for (String color : COLORS) {
                collect(out, path, family[0].formatted(color), family[1].formatted(color));
            }
        }
        for (String[] family : WOOD_FAMILIES) {
            for (String wood : WOODS) {
                collect(out, path, family[0].formatted(wood), family[1].formatted(wood));
            }
        }
        // 深色橡木在两代命名里一个叫 dark_oak 一个叫 big_oak
        for (String[] family : WOOD_FAMILIES) {
            collect(out, path, family[0].formatted("dark_oak"), family[1].formatted("big_oak"));
        }
        return out;
    }

    private static void collect(List<String> out, String path, String modern, String legacy) {
        if (modern.equals(path) && !legacy.equals(path)) {
            out.add("minecraft:" + legacy);
        } else if (legacy.equals(path) && !modern.equals(path)) {
            out.add("minecraft:" + modern);
        }
    }
}
