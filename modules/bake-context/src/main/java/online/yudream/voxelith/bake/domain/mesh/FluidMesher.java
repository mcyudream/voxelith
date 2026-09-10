package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.geometry.FaceProjection;
import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.bake.domain.mesh.VertexLightSampler.SectionGrid;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.application.dto.BlockStateData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 流体（水/岩浆）网格化：原版 block/water 等流体模型无 elements，静态模型链烘焙为零面片，
 * 流体几何按 level 属性合成（原版 LiquidBlockRenderer 的简化版，角落坡度从略、顶面取平）。
 *
 * 规则：
 * <ul>
 *   <li>顶面：上方非同类流体时输出，液高 = level&gt;=8 ? 1 : (8-level)/9（源头 8/9）；
 *       上方为同类流体时整柱充满且省略顶面</li>
 *   <li>底面：下方非同类流体且不遮挡其顶面时输出</li>
 *   <li>侧面：邻居非同类流体且其朝向面不遮挡时输出，高度同液高（贴图底端锚定）</li>
 *   <li>含水方块（waterlogged=true 及海草/海带等隐含含水方块）：按水源规则在方块内合成水体，
 *       被宿主自身满覆盖的面剔除；水与含水方块互视为同一介质，相邻面剔除</li>
 *   <li>流体面不做 AO（原版液体渲染无 AO，逐角点 AO 会在水面形成棋盘状明暗噪点）</li>
 *   <li>水标记 translucent（tile 链路拆分 BLEND primitive），岩浆不透明</li>
 *   <li>染色：tintIndex=0，颜色经 TintResolver 解析（水取群系水色，岩浆不染色）</li>
 * </ul>
 */
public final class FluidMesher {

    /** 原版默认水色（无群系数据时的兜底，plains effects.water_color）。 */
    public static final int DEFAULT_WATER_COLOR = 0x3F76E4;

    private static final String WATER = "minecraft:water";
    private static final Map<String, String> STILL_TEXTURE = Map.of(
            WATER, "minecraft:block/water_still",
            "minecraft:lava", "minecraft:block/lava_still");
    private static final Map<String, String> FLOW_TEXTURE = Map.of(
            WATER, "minecraft:block/water_flow",
            "minecraft:lava", "minecraft:block/lava_flow");
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** 无 waterlogged 属性但流体状态恒为水源的方块（海草/海带类）。 */
    private static final Set<String> IMPLICIT_WATERLOGGED = Set.of(
            "minecraft:seagrass", "minecraft:tall_seagrass",
            "minecraft:kelp", "minecraft:kelp_plant");

    private final WorldBlockAccess world;
    /** 邻居面遮挡判定（邻居方块 id+状态缓存于调用方）：(邻居状态, 朝向本流体的方向) → 是否完全覆盖。 */
    private final BiPredicate<BlockStateData, Direction> occlusionTester;
    private final VertexLightSampler lightSampler;
    private final TintResolver tintResolver;

    public FluidMesher(WorldBlockAccess world,
                       BiPredicate<BlockStateData, Direction> occlusionTester,
                       VertexLightSampler lightSampler,
                       TintResolver tintResolver) {
        this.world = world;
        this.occlusionTester = occlusionTester;
        this.lightSampler = lightSampler;
        this.tintResolver = tintResolver;
    }

    public static boolean isFluid(String blockId) {
        return STILL_TEXTURE.containsKey(blockId);
    }

    /** 方块是否含水：显式 waterlogged=true，或海草/海带等隐含水源方块。 */
    public static boolean isWaterlogged(BlockStateData state) {
        return IMPLICIT_WATERLOGGED.contains(state.block())
                || "true".equals(state.properties().get("waterlogged"));
    }

    public List<BakedQuad> mesh(BlockStateData state, int x, int y, int z, SectionGrid lightGrid) {
        String blockId = state.block();
        String still = STILL_TEXTURE.get(blockId);
        String flow = FLOW_TEXTURE.get(blockId);
        if (still == null) {
            return List.of();
        }
        boolean water = WATER.equals(blockId);
        int level = parseLevel(state.properties().get("level"));
        float height = level >= 8 ? 1f : (8 - level) / 9f;
        int tintRgb = tintResolver.tint(blockId, 0, x, y, z);

        boolean aboveSame = isSameMedium(world.blockStateAt(x, y + 1, z), blockId, water);
        float top = aboveSame ? 1f : height;

        List<BakedQuad> quads = new ArrayList<>(6);
        if (!aboveSame) {
            quads.add(face(Direction.UP, top, still, blockId, tintRgb, water, x, y, z, lightGrid));
        }
        Optional<BlockStateData> below = world.blockStateAt(x, y - 1, z);
        if (!isSameMedium(below, blockId, water)
                && (below.isEmpty() || below.get().isAir() || !occlusionTester.test(below.get(), Direction.UP))) {
            quads.add(face(Direction.DOWN, top, still, blockId, tintRgb, water, x, y, z, lightGrid));
        }
        for (Direction side : SIDES) {
            Optional<BlockStateData> neighbor = world.blockStateAt(x + side.nx(), y, z + side.nz());
            if (isSameMedium(neighbor, blockId, water)) {
                continue;
            }
            if (neighbor.isPresent() && !neighbor.get().isAir()
                    && occlusionTester.test(neighbor.get(), side.opposite())) {
                continue;
            }
            quads.add(face(side, top, flow, blockId, tintRgb, water, x, y, z, lightGrid));
        }
        return quads;
    }

    /**
     * 含水方块内的水体：按水源（液高 8/9）合成，与 {@link #mesh} 的差异在于
     * 额外按宿主方块自身的满覆盖面剔除（如下半砖底面、上半砖顶面），
     * 宿主为十字/零体积模型（海草）时六个面全交由邻居剔除决定。
     */
    public List<BakedQuad> meshWaterlogged(BlockStateData host, int x, int y, int z, SectionGrid lightGrid) {
        int tintRgb = tintResolver.tint(WATER, 0, x, y, z);
        boolean aboveWater = isWaterContaining(world.blockStateAt(x, y + 1, z));
        float top = aboveWater ? 1f : 8f / 9f;
        String still = STILL_TEXTURE.get(WATER);
        String flow = FLOW_TEXTURE.get(WATER);

        List<BakedQuad> quads = new ArrayList<>(6);
        if (!aboveWater && !occlusionTester.test(host, Direction.UP)) {
            quads.add(face(Direction.UP, top, still, WATER, tintRgb, true, x, y, z, lightGrid));
        }
        Optional<BlockStateData> below = world.blockStateAt(x, y - 1, z);
        if (!isWaterContaining(below) && !occlusionTester.test(host, Direction.DOWN)
                && (below.isEmpty() || below.get().isAir() || !occlusionTester.test(below.get(), Direction.UP))) {
            quads.add(face(Direction.DOWN, top, still, WATER, tintRgb, true, x, y, z, lightGrid));
        }
        for (Direction side : SIDES) {
            Optional<BlockStateData> neighbor = world.blockStateAt(x + side.nx(), y, z + side.nz());
            if (isWaterContaining(neighbor) || occlusionTester.test(host, side)) {
                continue;
            }
            if (neighbor.isPresent() && !neighbor.get().isAir()
                    && occlusionTester.test(neighbor.get(), side.opposite())) {
                continue;
            }
            quads.add(face(side, top, flow, WATER, tintRgb, true, x, y, z, lightGrid));
        }
        return quads;
    }

    private BakedQuad face(Direction dir, float top, String texture, String blockId, int tintRgb,
                           boolean translucent, int x, int y, int z, SectionGrid lightGrid) {
        float[] from = {0, 0, 0};
        float[] to = {16, top * 16f, 16};
        float[][] corners = FaceProjection.corners(dir, from, to);
        float[][] uvs = FaceProjection.uvs(dir, from, to, null, 0);
        float[] normal = {dir.nx(), dir.ny(), dir.nz()};
        int tintIndex = tintRgb >= 0 ? 0 : -1;

        float[] localPositions = new float[12];
        float[] worldPositions = new float[12];
        float[] uvFlat = new float[8];
        for (int v = 0; v < Quad.VERTEX_COUNT; v++) {
            localPositions[v * 3] = corners[v][0];
            localPositions[v * 3 + 1] = corners[v][1];
            localPositions[v * 3 + 2] = corners[v][2];
            worldPositions[v * 3] = corners[v][0] / 16f + x;
            worldPositions[v * 3 + 1] = corners[v][1] / 16f + y;
            worldPositions[v * 3 + 2] = corners[v][2] / 16f + z;
            uvFlat[v * 2] = uvs[v][0];
            uvFlat[v * 2 + 1] = uvs[v][1];
        }

        String faceName = dir.name().toLowerCase();
        Quad synthetic = new Quad(localPositions, uvFlat, normal, texture, null, tintIndex, false, faceName);
        byte[] sky = new byte[4];
        byte[] block = new byte[4];
        byte[] ao = new byte[4];
        lightSampler.sample(synthetic, x, y, z, lightGrid, sky, block, ao);
        // 原版液体渲染无 AO；逐角点 AO 会在水面形成棋盘状明暗噪点
        Arrays.fill(ao, (byte) 0);
        return new BakedQuad(worldPositions, uvFlat, normal, texture, tintIndex, false, faceName,
                tintRgb, sky, block, ao, translucent);
    }

    /** 同一介质判定：同种流体恒同介质；水与含水方块互视为同一介质（相邻面剔除）。 */
    private static boolean isSameMedium(Optional<BlockStateData> state, String blockId, boolean water) {
        if (state.isEmpty()) {
            return false;
        }
        BlockStateData s = state.get();
        return s.block().equals(blockId) || (water && isWaterlogged(s));
    }

    private static boolean isWaterContaining(Optional<BlockStateData> state) {
        return state.map(s -> WATER.equals(s.block()) || isWaterlogged(s)).orElse(false);
    }

    private static int parseLevel(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
