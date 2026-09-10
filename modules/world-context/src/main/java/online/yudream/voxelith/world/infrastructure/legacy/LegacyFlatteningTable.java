package online.yudream.voxelith.world.infrastructure.legacy;

import online.yudream.voxelith.world.domain.world.BlockStateSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1.12 及更早（pre-flattening）数字 ID/meta → 扁平化方块状态映射表。
 * 程序化构建：颜色 / 木质等规律族用循环展开，方向性方块按 1.12 meta 位规则推导属性。
 *
 * <p>未知 (id, meta) 一律降级为 {@link #FALLBACK_BLOCK} 并计入统计（供覆盖报告）。</p>
 *
 * <p>已知近似（记录在案，不影响地形与主体建筑）：门/床/活塞臂等由方块实体驱动的方块只给基础属性；
 * 栅栏/玻璃板等连接态在 1.12 无存储，统一按未连接处理（渲染为独立立柱）。</p>
 */
public final class LegacyFlatteningTable {

    public static final String FALLBACK_BLOCK = "minecraft:stone";

    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    /** 南瓜/熔炉类水平朝向：meta 0=south,1=west,2=north,3=east。 */
    private static final String[] HORIZONTAL = {"south", "west", "north", "east"};

    /** 六向：meta&7 → 0 down,1 up,2 north,3 south,4 west,5 east。 */
    private static final String[] FACING6 = {"down", "up", "north", "south", "west", "east"};

    private final Map<Integer, BlockStateSpec> table;
    private final ConcurrentHashMap<Integer, AtomicLong> unknownCounts = new ConcurrentHashMap<>();

    private LegacyFlatteningTable(Map<Integer, BlockStateSpec> table) {
        this.table = table;
    }

    /** 全局共享实例（表为常量，统计按实例隔离）。 */
    public static LegacyFlatteningTable shared() {
        return Holder.SHARED;
    }

    private static final class Holder {
        private static final LegacyFlatteningTable SHARED = new LegacyFlatteningTable(build());
    }

    /** 查 (id, meta)；未知时降级 fallback 并计数。 */
    public BlockStateSpec map(int id, int meta) {
        BlockStateSpec spec = table.get((id << 4) | (meta & 15));
        if (spec != null) {
            return spec;
        }
        unknownCounts.computeIfAbsent((id << 4) | (meta & 15), k -> new AtomicLong()).incrementAndGet();
        return BlockStateSpec.parse(FALLBACK_BLOCK);
    }

    /** 未知 (id&lt;&lt;4|meta) → 出现次数 快照，供扫描/烘焙覆盖报告。 */
    public Map<Integer, Long> unknownStats() {
        Map<Integer, Long> snapshot = new ConcurrentHashMap<>();
        unknownCounts.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    public int knownEntryCount() {
        return table.size();
    }

    private static Map<Integer, BlockStateSpec> build() {
        Map<Integer, String> t = new ConcurrentHashMap<>();

        // ---- 地形基础 ----
        t.put(key(0, 0), "minecraft:air");
        t.put(key(0, 1), "minecraft:cave_air");
        t.put(key(0, 2), "minecraft:void_air");
        String[] stones = {"stone", "granite", "polished_granite", "diorite", "polished_diorite",
                "andesite", "polished_andesite"};
        for (int m = 0; m < 16; m++) {
            t.put(key(1, m), "minecraft:" + stones[Math.min(m, 6)]);
        }
        t.put(key(2, 0), "minecraft:grass_block[snowy=false]");
        for (int m = 0; m < 16; m++) {
            t.put(key(3, m), switch (m) {
                case 1 -> "minecraft:coarse_dirt";
                case 2 -> "minecraft:podzol[snowy=false]";
                default -> "minecraft:dirt";
            });
        }
        putAll(t, 4, "minecraft:cobblestone");
        for (int m = 0; m < 16; m++) {
            t.put(key(5, m), "minecraft:" + WOODS[Math.min(m, 5)] + "_planks");
        }
        // 树苗：meta&7 树种，bit3 生长阶段标记（忽略）
        for (int m = 0; m < 16; m++) {
            t.put(key(6, m), "minecraft:" + WOODS[Math.min(m & 7, 5)] + "_sapling");
        }
        putAll(t, 7, "minecraft:bedrock");
        for (int m = 0; m < 16; m++) {
            t.put(key(8, m), "minecraft:water[level=" + m + "]");
            t.put(key(9, m), "minecraft:water[level=" + m + "]");
            t.put(key(10, m), "minecraft:lava[level=" + m + "]");
            t.put(key(11, m), "minecraft:lava[level=" + m + "]");
        }
        for (int m = 0; m < 16; m++) {
            t.put(key(12, m), m == 1 ? "minecraft:red_sand" : "minecraft:sand");
        }
        putAll(t, 13, "minecraft:gravel");
        putAll(t, 14, "minecraft:gold_ore");
        putAll(t, 15, "minecraft:iron_ore");
        putAll(t, 16, "minecraft:coal_ore");

        // ---- 原木 / 树叶 ----
        for (int m = 0; m < 16; m++) {
            String wood = WOODS[m & 3];
            int orientation = m & 12;
            if (orientation == 12) {
                t.put(key(17, m), "minecraft:" + wood + "_wood");
            } else {
                String axis = orientation == 4 ? "x" : orientation == 8 ? "z" : "y";
                t.put(key(17, m), "minecraft:" + wood + "_log[axis=" + axis + "]");
            }
            String wood2 = (m & 1) == 0 ? "acacia" : "dark_oak";
            int orientation2 = m & 12;
            if (orientation2 == 12) {
                t.put(key(162, m), "minecraft:" + wood2 + "_wood");
            } else {
                String axis = orientation2 == 4 ? "x" : orientation2 == 8 ? "z" : "y";
                t.put(key(162, m), "minecraft:" + wood2 + "_log[axis=" + axis + "]");
            }
            t.put(key(18, m), "minecraft:" + WOODS[m & 3] + "_leaves[distance=7,persistent=" + ((m & 4) == 0) + "]");
            t.put(key(161, m), "minecraft:" + wood2 + "_leaves[distance=7,persistent=" + ((m & 4) == 0) + "]");
        }

        putAll(t, 19, "minecraft:sponge");
        t.put(key(19, 1), "minecraft:wet_sponge");
        putAll(t, 20, "minecraft:glass");
        putAll(t, 21, "minecraft:lapis_ore");
        putAll(t, 22, "minecraft:lapis_block");
        // 发射器/投掷器：meta&7 六向，bit3 triggered
        for (int m = 0; m < 16; m++) {
            String facing = FACING6[Math.min(m & 7, 5)];
            t.put(key(23, m), "minecraft:dispenser[facing=" + facing + ",triggered=" + ((m & 8) != 0) + "]");
            t.put(key(158, m), "minecraft:dropper[facing=" + facing + ",triggered=" + ((m & 8) != 0) + "]");
        }
        for (int m = 0; m < 16; m++) {
            t.put(key(24, m), switch (m & 3) {
                case 1 -> "minecraft:chiseled_sandstone";
                case 2 -> "minecraft:cut_sandstone";
                default -> "minecraft:sandstone";
            });
            t.put(key(179, m), switch (m & 3) {
                case 1 -> "minecraft:chiseled_red_sandstone";
                case 2 -> "minecraft:cut_red_sandstone";
                default -> "minecraft:red_sandstone";
            });
        }
        putAll(t, 25, "minecraft:note_block");

        // 床：meta&3 朝向，bit2 occupied，bit3 head
        for (int m = 0; m < 16; m++) {
            t.put(key(26, m), "minecraft:red_bed[facing=" + HORIZONTAL[m & 3]
                    + ",occupied=" + ((m & 4) != 0) + ",part=" + ((m & 8) != 0 ? "head" : "foot") + "]");
        }

        // 动力/探测/激活铁轨：meta&7 形状，bit3 powered
        for (int m = 0; m < 16; m++) {
            String shape = railShape(m & 7);
            String powered = String.valueOf((m & 8) != 0);
            t.put(key(27, m), "minecraft:powered_rail[powered=" + powered + ",shape=" + shape + ",waterlogged=false]");
            t.put(key(28, m), "minecraft:detector_rail[powered=" + powered + ",shape=" + shape + ",waterlogged=false]");
            t.put(key(157, m), "minecraft:activator_rail[powered=" + powered + ",shape=" + shape + ",waterlogged=false]");
        }
        // 普通铁轨：0-5 直/坡，6-9 弯道
        String[] railShapes = {"north_south", "east_west", "ascending_east", "ascending_west",
                "ascending_north", "ascending_south", "south_east", "south_west", "north_west", "north_east"};
        for (int m = 0; m < 16; m++) {
            t.put(key(66, m), "minecraft:rail[shape=" + railShapes[Math.min(m, 9)] + ",waterlogged=false]");
        }

        // 活塞
        for (int m = 0; m < 16; m++) {
            String facing = FACING6[Math.min(m & 7, 5)];
            String extended = String.valueOf((m & 8) != 0);
            t.put(key(29, m), "minecraft:sticky_piston[extended=" + extended + ",facing=" + facing + "]");
            t.put(key(33, m), "minecraft:piston[extended=" + extended + ",facing=" + facing + "]");
        }

        // 高草丛：0 灌木（近似 dead_bush），1 草，2 蕨
        for (int m = 0; m < 16; m++) {
            t.put(key(31, m), switch (m & 3) {
                case 1 -> "minecraft:grass";
                case 2 -> "minecraft:fern";
                default -> "minecraft:dead_bush";
            });
        }
        putAll(t, 32, "minecraft:dead_bush");
        colored(t, 35, "_wool");
        putAll(t, 37, "minecraft:dandelion");
        String[] flowers = {"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"};
        for (int m = 0; m < 16; m++) {
            t.put(key(38, m), "minecraft:" + flowers[Math.min(m, 8)]);
        }
        putAll(t, 39, "minecraft:brown_mushroom");
        putAll(t, 40, "minecraft:red_mushroom");
        putAll(t, 41, "minecraft:gold_block");
        putAll(t, 42, "minecraft:iron_block");

        // 双层石台阶 → 对应全块
        String[] doubleSlabs = {"smooth_stone", "sandstone", "oak_planks", "cobblestone",
                "bricks", "stone_bricks", "nether_bricks", "quartz_block"};
        for (int m = 0; m < 16; m++) {
            t.put(key(43, m), "minecraft:" + (m == 9 ? "smooth_sandstone" : doubleSlabs[m & 7]));
            t.put(key(125, m), "minecraft:" + WOODS[Math.min(m & 7, 5)] + "_planks");
            t.put(key(181, m), (m & 7) == 1 ? "minecraft:purpur_block"
                    : ((m & 8) != 0 ? "minecraft:smooth_red_sandstone" : "minecraft:red_sandstone"));
        }

        // 石台阶：meta&7 材质，bit3 上半
        String[] stoneSlabs = {"stone_slab", "sandstone_slab", "petrified_oak_slab", "cobblestone_slab",
                "brick_slab", "stone_brick_slab", "nether_brick_slab", "quartz_slab"};
        for (int m = 0; m < 16; m++) {
            String type = (m & 8) != 0 ? "top" : "bottom";
            t.put(key(44, m), "minecraft:" + stoneSlabs[m & 7] + "[type=" + type + ",waterlogged=false]");
            t.put(key(126, m), "minecraft:" + WOODS[Math.min(m & 7, 5)] + "_slab[type=" + type + ",waterlogged=false]");
            t.put(key(182, m), "minecraft:" + (((m & 7) == 1) ? "purpur_slab" : "red_sandstone_slab")
                    + "[type=" + type + ",waterlogged=false]");
        }

        putAll(t, 45, "minecraft:bricks");
        putAll(t, 46, "minecraft:tnt");
        putAll(t, 47, "minecraft:bookshelf");
        putAll(t, 48, "minecraft:mossy_cobblestone");
        putAll(t, 49, "minecraft:obsidian");

        // 火把：1-4 墙上（1 east,2 west,3 south,4 north），5 站立
        String[] torchFacing = {"east", "east", "west", "south", "north"};
        for (int m = 0; m < 16; m++) {
            int variant = m & 7;
            if (variant >= 1 && variant <= 4) {
                t.put(key(50, m), "minecraft:wall_torch[facing=" + torchFacing[variant] + "]");
                t.put(key(75, m), "minecraft:redstone_wall_torch[facing=" + torchFacing[variant] + ",lit=false]");
                t.put(key(76, m), "minecraft:redstone_wall_torch[facing=" + torchFacing[variant] + ",lit=true]");
            } else {
                t.put(key(50, m), "minecraft:torch");
                t.put(key(75, m), "minecraft:redstone_torch[lit=false]");
                t.put(key(76, m), "minecraft:redstone_torch[lit=true]");
            }
        }
        putAll(t, 51, "minecraft:fire");
        putAll(t, 52, "minecraft:spawner");

        // 楼梯：meta&3 朝向（0 east,1 west,2 south,3 north），bit2 倒置
        String[] stairFacing = {"east", "west", "south", "north"};
        Map<Integer, String> stairs = Map.ofEntries(
                Map.entry(53, "oak_stairs"), Map.entry(67, "cobblestone_stairs"),
                Map.entry(108, "brick_stairs"), Map.entry(109, "stone_brick_stairs"),
                Map.entry(114, "nether_brick_stairs"), Map.entry(128, "sandstone_stairs"),
                Map.entry(134, "spruce_stairs"), Map.entry(135, "birch_stairs"),
                Map.entry(136, "jungle_stairs"), Map.entry(156, "quartz_stairs"),
                Map.entry(163, "acacia_stairs"), Map.entry(164, "dark_oak_stairs"),
                Map.entry(180, "red_sandstone_stairs"), Map.entry(203, "purpur_stairs"));
        for (Map.Entry<Integer, String> e : stairs.entrySet()) {
            for (int m = 0; m < 16; m++) {
                t.put(key(e.getKey(), m), "minecraft:" + e.getValue()
                        + "[facing=" + stairFacing[m & 3]
                        + ",half=" + ((m & 4) != 0 ? "top" : "bottom")
                        + ",shape=straight,waterlogged=false]");
            }
        }

        // 箱子类：meta 2-5 朝向（2 north,3 south,4 west,5 east）
        for (int m = 0; m < 16; m++) {
            String facing = m >= 2 && m <= 5 ? FACING6[m] : "north";
            t.put(key(54, m), "minecraft:chest[facing=" + facing + ",type=single,waterlogged=false]");
            t.put(key(146, m), "minecraft:trapped_chest[facing=" + facing + ",type=single,waterlogged=false]");
            t.put(key(130, m), "minecraft:ender_chest[facing=" + facing + ",waterlogged=false]");
            t.put(key(61, m), "minecraft:furnace[facing=" + facing + ",lit=false]");
            t.put(key(62, m), "minecraft:furnace[facing=" + facing + ",lit=true]");
        }

        putAll(t, 56, "minecraft:diamond_ore");
        putAll(t, 57, "minecraft:diamond_block");
        putAll(t, 58, "minecraft:crafting_table");
        for (int m = 0; m < 16; m++) {
            t.put(key(59, m), "minecraft:wheat[age=" + Math.min(m, 7) + "]");
            t.put(key(60, m), "minecraft:farmland[moisture=" + Math.min(m, 7) + "]");
        }

        // 拉杆 / 按钮（近似：常见墙面朝向）
        for (int m = 0; m < 16; m++) {
            int v = m & 7;
            String powered = String.valueOf((m & 8) != 0);
            String lever = switch (v) {
                case 1 -> "lever[face=wall,facing=east,powered=" + powered + "]";
                case 2 -> "lever[face=wall,facing=west,powered=" + powered + "]";
                case 3 -> "lever[face=wall,facing=south,powered=" + powered + "]";
                case 4 -> "lever[face=wall,facing=north,powered=" + powered + "]";
                case 6 -> "lever[face=floor,facing=west,powered=" + powered + "]";
                case 0, 7 -> "lever[face=ceiling,facing=west,powered=" + powered + "]";
                default -> "lever[face=floor,facing=north,powered=" + powered + "]";
            };
            t.put(key(69, m), "minecraft:" + lever);
            String button = switch (v) {
                case 1 -> "[face=wall,facing=east,powered=" + powered + "]";
                case 2 -> "[face=wall,facing=west,powered=" + powered + "]";
                case 3 -> "[face=wall,facing=south,powered=" + powered + "]";
                case 4 -> "[face=wall,facing=north,powered=" + powered + "]";
                case 0, 6 -> "[face=ceiling,facing=north,powered=" + powered + "]";
                default -> "[face=floor,facing=north,powered=" + powered + "]";
            };
            t.put(key(77, m), "minecraft:stone_button" + button);
            t.put(key(143, m), "minecraft:oak_button" + button);
            t.put(key(70, m), "minecraft:stone_pressure_plate[powered=" + ((m & 1) != 0) + "]");
            t.put(key(72, m), "minecraft:oak_pressure_plate[powered=" + ((m & 1) != 0) + "]");
            t.put(key(147, m), "minecraft:light_weighted_pressure_plate[power=" + (m & 15) + "]");
            t.put(key(148, m), "minecraft:heavy_weighted_pressure_plate[power=" + (m & 15) + "]");
        }

        putAll(t, 73, "minecraft:redstone_ore[lit=false]");
        putAll(t, 74, "minecraft:redstone_ore[lit=true]");
        for (int m = 0; m < 16; m++) {
            t.put(key(78, m), "minecraft:snow[layers=" + Math.min((m & 7) + 1, 8) + "]");
        }
        putAll(t, 79, "minecraft:ice");
        putAll(t, 80, "minecraft:snow_block");
        putAll(t, 81, "minecraft:cactus[age=0]");
        putAll(t, 82, "minecraft:clay");
        putAll(t, 83, "minecraft:sugar_cane[age=0]");
        putAll(t, 84, "minecraft:jukebox[has_record=false]");
        putAll(t, 85, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        for (int m = 0; m < 16; m++) {
            t.put(key(86, m), "minecraft:pumpkin");
            t.put(key(91, m), "minecraft:jack_o_lantern[facing=" + HORIZONTAL[m & 3] + "]");
        }
        putAll(t, 87, "minecraft:netherrack");
        putAll(t, 88, "minecraft:soul_sand");
        putAll(t, 89, "minecraft:glowstone");
        putAll(t, 90, "minecraft:nether_portal[axis=x]");

        colored(t, 95, "_stained_glass");
        // 活板门：meta&3 朝向，bit2 open，bit3 上半
        String[] trapdoorFacing = {"north", "south", "west", "east"};
        for (int m = 0; m < 16; m++) {
            String suffix = "[facing=" + trapdoorFacing[m & 3] + ",half=" + ((m & 8) != 0 ? "top" : "bottom")
                    + ",open=" + ((m & 4) != 0) + ",powered=false,waterlogged=false]";
            t.put(key(96, m), "minecraft:oak_trapdoor" + suffix);
            t.put(key(167, m), "minecraft:iron_trapdoor" + suffix);
        }
        String[] infested = {"infested_stone", "infested_cobblestone", "infested_stone_bricks",
                "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks"};
        for (int m = 0; m < 16; m++) {
            t.put(key(97, m), "minecraft:" + infested[Math.min(m & 7, 5)]);
            t.put(key(98, m), "minecraft:" + new String[]{"stone_bricks", "mossy_stone_bricks",
                    "cracked_stone_bricks", "chiseled_stone_bricks"}[m & 3]);
        }
        putAll(t, 99, "minecraft:brown_mushroom_block[down=false,east=false,north=false,south=false,up=true,west=false]");
        putAll(t, 100, "minecraft:red_mushroom_block[down=false,east=false,north=false,south=false,up=true,west=false]");
        putAll(t, 101, "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]");
        putAll(t, 102, "minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");
        putAll(t, 103, "minecraft:melon");
        putAll(t, 104, "minecraft:pumpkin_stem[age=0]");
        putAll(t, 105, "minecraft:melon_stem[age=0]");
        // 藤蔓：bit0 south,bit1 west,bit2 north,bit3 east
        for (int m = 0; m < 16; m++) {
            t.put(key(106, m), "minecraft:vine[east=" + ((m & 8) != 0) + ",north=" + ((m & 4) != 0)
                    + ",south=" + ((m & 1) != 0) + ",up=false,west=" + ((m & 2) != 0) + "]");
        }
        // 栅栏门：meta&3 朝向，bit2 open，bit3 in_wall
        Map<Integer, String> fenceGates = Map.of(107, "oak_fence_gate", 183, "spruce_fence_gate",
                184, "birch_fence_gate", 185, "jungle_fence_gate", 186, "dark_oak_fence_gate");
        for (Map.Entry<Integer, String> e : fenceGates.entrySet()) {
            for (int m = 0; m < 16; m++) {
                t.put(key(e.getKey(), m), "minecraft:" + e.getValue()
                        + "[facing=" + HORIZONTAL[m & 3] + ",in_wall=" + ((m & 8) != 0)
                        + ",open=" + ((m & 4) != 0) + ",powered=false]");
            }
        }
        Map<Integer, String> fences = Map.of(188, "spruce_fence", 189, "birch_fence",
                190, "jungle_fence", 191, "dark_oak_fence", 192, "acacia_fence");
        for (Map.Entry<Integer, String> e : fences.entrySet()) {
            putAll(t, e.getKey(), "minecraft:" + e.getValue()
                    + "[east=false,north=false,south=false,waterlogged=false,west=false]");
        }
        putAll(t, 110, "minecraft:mycelium[snowy=false]");
        putAll(t, 111, "minecraft:lily_pad");
        putAll(t, 112, "minecraft:nether_bricks");
        putAll(t, 113, "minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        for (int m = 0; m < 16; m++) {
            t.put(key(115, m), "minecraft:nether_wart[age=" + Math.min(m, 3) + "]");
        }
        putAll(t, 116, "minecraft:enchanting_table");
        putAll(t, 117, "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]");
        putAll(t, 118, "minecraft:cauldron[level=0]");
        putAll(t, 119, "minecraft:end_portal");
        for (int m = 0; m < 16; m++) {
            t.put(key(120, m), "minecraft:end_portal_frame[eye=" + ((m & 4) != 0)
                    + ",facing=" + HORIZONTAL[m & 3] + "]");
        }
        putAll(t, 121, "minecraft:end_stone");
        putAll(t, 122, "minecraft:dragon_egg");
        putAll(t, 123, "minecraft:redstone_lamp[lit=false]");
        putAll(t, 124, "minecraft:redstone_lamp[lit=true]");

        for (int m = 0; m < 16; m++) {
            t.put(key(127, m), "minecraft:cocoa[age=" + ((m >> 2) & 3) + ",facing=" + HORIZONTAL[m & 3] + "]");
        }
        putAll(t, 129, "minecraft:emerald_ore");
        for (int m = 0; m < 16; m++) {
            t.put(key(131, m), "minecraft:tripwire_hook[attached=false,facing=" + HORIZONTAL[m & 3]
                    + ",powered=" + ((m & 8) != 0) + "]");
        }
        putAll(t, 132, "minecraft:tripwire[attached=false,disarmed=false,east=false,north=false,powered=false,south=false,west=false]");
        putAll(t, 133, "minecraft:emerald_block");
        for (int m = 0; m < 16; m++) {
            t.put(key(137, m), "minecraft:command_block[conditional=" + ((m & 8) != 0)
                    + ",facing=" + FACING6[Math.min(m & 7, 5)] + "]");
            t.put(key(210, m), "minecraft:repeating_command_block[conditional=" + ((m & 8) != 0)
                    + ",facing=" + FACING6[Math.min(m & 7, 5)] + "]");
            t.put(key(211, m), "minecraft:chain_command_block[conditional=" + ((m & 8) != 0)
                    + ",facing=" + FACING6[Math.min(m & 7, 5)] + "]");
        }
        putAll(t, 138, "minecraft:beacon");
        for (int m = 0; m < 16; m++) {
            t.put(key(139, m), "minecraft:" + ((m & 1) == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall")
                    + "[east=false,north=false,south=false,up=true,waterlogged=false,west=false]");
        }
        for (int m = 0; m < 16; m++) {
            t.put(key(141, m), "minecraft:carrots[age=" + Math.min(m, 7) + "]");
            t.put(key(142, m), "minecraft:potatoes[age=" + Math.min(m, 7) + "]");
        }
        putAll(t, 144, "minecraft:skeleton_skull[rotation=0]");
        // 铁砧：meta&3 朝向，meta>>2 损伤
        for (int m = 0; m < 16; m++) {
            String anvil = switch ((m >> 2) & 3) {
                case 1 -> "chipped_anvil";
                case 2 -> "damaged_anvil";
                default -> "anvil";
            };
            t.put(key(145, m), "minecraft:" + anvil + "[facing=" + HORIZONTAL[m & 3] + "]");
        }
        putAll(t, 149, "minecraft:comparator[facing=north,mode=compare,powered=false]");
        putAll(t, 150, "minecraft:comparator[facing=north,mode=compare,powered=true]");
        putAll(t, 93, "minecraft:repeater[delay=1,facing=north,locked=false,powered=false]");
        putAll(t, 94, "minecraft:repeater[delay=1,facing=north,locked=false,powered=true]");
        putAll(t, 55, "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        for (int m = 0; m < 16; m++) {
            t.put(key(151, m), "minecraft:daylight_detector[inverted=false,power=" + (m & 15) + "]");
        }
        putAll(t, 152, "minecraft:redstone_block");
        putAll(t, 153, "minecraft:nether_quartz_ore");
        for (int m = 0; m < 16; m++) {
            int v = m & 7;
            String facing = v == 0 ? "down" : (v >= 2 ? FACING6[Math.min(v, 5)] : "down");
            t.put(key(154, m), "minecraft:hopper[enabled=true,facing=" + facing + "]");
        }
        for (int m = 0; m < 16; m++) {
            t.put(key(155, m), switch (m & 7) {
                case 1 -> "minecraft:chiseled_quartz_block";
                case 2 -> "minecraft:quartz_pillar[axis=y]";
                case 3 -> "minecraft:quartz_pillar[axis=x]";
                case 4 -> "minecraft:quartz_pillar[axis=z]";
                default -> "minecraft:quartz_block";
            });
        }
        colored(t, 159, "_terracotta");
        colored(t, 160, "_stained_glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");
        putAll(t, 165, "minecraft:slime_block");
        putAll(t, 166, "minecraft:barrier");
        for (int m = 0; m < 16; m++) {
            t.put(key(168, m), switch (m & 3) {
                case 1 -> "minecraft:prismarine_bricks";
                case 2 -> "minecraft:dark_prismarine";
                default -> "minecraft:prismarine";
            });
        }
        putAll(t, 169, "minecraft:sea_lantern");
        for (int m = 0; m < 16; m++) {
            String axis = (m & 12) == 4 ? "x" : (m & 12) == 8 ? "z" : "y";
            t.put(key(170, m), "minecraft:hay_block[axis=" + axis + "]");
            t.put(key(216, m), "minecraft:bone_block[axis=" + axis + "]");
            t.put(key(202, m), "minecraft:purpur_pillar[axis=" + axis + "]");
        }
        colored(t, 171, "_carpet");
        putAll(t, 172, "minecraft:terracotta");
        putAll(t, 173, "minecraft:coal_block");
        putAll(t, 174, "minecraft:packed_ice");
        // 大型植物：meta&7 种类，bit3 上半
        String[] doublePlants = {"sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony"};
        for (int m = 0; m < 16; m++) {
            String plant = doublePlants[Math.min(m & 7, 5)];
            t.put(key(175, m), "minecraft:" + plant + "[half=" + ((m & 8) != 0 ? "upper" : "lower") + "]");
        }
        for (int m = 0; m < 16; m++) {
            t.put(key(176, m), "minecraft:white_banner[rotation=" + (m & 15) + "]");
            t.put(key(177, m), "minecraft:white_wall_banner[facing=" + (m >= 2 && m <= 5 ? FACING6[m] : "north") + "]");
        }
        putAll(t, 178, "minecraft:daylight_detector[inverted=true,power=0]");
        putAll(t, 198, "minecraft:end_rod[facing=up]");
        for (int m = 0; m < 16; m++) {
            t.put(key(198, m), "minecraft:end_rod[facing=" + FACING6[Math.min(m & 7, 5)] + "]");
        }
        putAll(t, 199, "minecraft:chorus_plant[down=false,east=false,north=false,south=false,up=false,west=false]");
        for (int m = 0; m < 16; m++) {
            t.put(key(200, m), "minecraft:chorus_flower[age=" + Math.min(m, 5) + "]");
        }
        putAll(t, 201, "minecraft:purpur_block");
        putAll(t, 204, "minecraft:purpur_slab[type=double,waterlogged=false]");
        putAll(t, 205, "minecraft:purpur_block");
        putAll(t, 206, "minecraft:end_stone_bricks");
        putAll(t, 207, "minecraft:beetroots[age=0]");
        putAll(t, 208, "minecraft:dirt_path");
        putAll(t, 209, "minecraft:end_gateway");
        putAll(t, 212, "minecraft:frosted_ice[age=0]");
        putAll(t, 213, "minecraft:magma_block");
        putAll(t, 214, "minecraft:nether_wart_block");
        putAll(t, 215, "minecraft:red_nether_bricks");
        putAll(t, 217, "minecraft:structure_void");
        for (int m = 0; m < 16; m++) {
            t.put(key(218, m), "minecraft:observer[facing=" + FACING6[Math.min(m & 7, 5)] + ",powered=" + ((m & 8) != 0) + "]");
        }
        // 潜影盒 / 釉陶 / 混凝土
        for (int c = 0; c < 16; c++) {
            putAll(t, 219 + c, "minecraft:" + COLORS[c] + "_shulker_box[facing=up]");
            for (int m = 0; m < 16; m++) {
                t.put(key(235 + c, m), "minecraft:" + COLORS[c] + "_glazed_terracotta[facing=" + HORIZONTAL[m & 3] + "]");
            }
        }
        colored(t, 251, "_concrete");
        colored(t, 252, "_concrete_powder");
        // 门（近似：下半带朝向，上半 hinge；木门 64/193-197，铁门 71）
        Map<Integer, String> doors = Map.of(64, "oak_door", 71, "iron_door", 193, "spruce_door",
                194, "birch_door", 195, "jungle_door", 196, "acacia_door", 197, "dark_oak_door");
        String[] doorFacing = {"east", "south", "west", "north"};
        for (Map.Entry<Integer, String> e : doors.entrySet()) {
            for (int m = 0; m < 16; m++) {
                if ((m & 8) != 0) {
                    t.put(key(e.getKey(), m), "minecraft:" + e.getValue()
                            + "[facing=north,half=upper,hinge=" + ((m & 1) != 0 ? "right" : "left")
                            + ",open=false,powered=" + ((m & 2) != 0) + "]");
                } else {
                    t.put(key(e.getKey(), m), "minecraft:" + e.getValue()
                            + "[facing=" + doorFacing[m & 3] + ",half=lower,hinge=left,open="
                            + ((m & 4) != 0) + ",powered=false]");
                }
            }
        }
        putAll(t, 63, "minecraft:oak_sign[rotation=0,waterlogged=false]");
        putAll(t, 68, "minecraft:oak_wall_sign[facing=north,waterlogged=false]");
        putAll(t, 92, "minecraft:cake[bites=0]");
        putAll(t, 255, "minecraft:structure_block[mode=data]");

        Map<Integer, BlockStateSpec> result = new ConcurrentHashMap<>();
        t.forEach((k, v) -> result.put(k, BlockStateSpec.parse(v)));
        return Map.copyOf(result);
    }

    private static int key(int id, int meta) {
        return (id << 4) | (meta & 15);
    }

    private static void putAll(Map<Integer, String> t, int id, String state) {
        for (int m = 0; m < 16; m++) {
            t.put(key(id, m), state);
        }
    }

    private static void colored(Map<Integer, String> t, int id, String suffix) {
        for (int m = 0; m < 16; m++) {
            t.put(key(id, m), "minecraft:" + COLORS[m] + suffix);
        }
    }

    private static String railShape(int v) {
        return switch (v) {
            case 1 -> "east_west";
            case 2 -> "ascending_east";
            case 3 -> "ascending_west";
            case 4 -> "ascending_north";
            case 5 -> "ascending_south";
            default -> "north_south";
        };
    }
}
