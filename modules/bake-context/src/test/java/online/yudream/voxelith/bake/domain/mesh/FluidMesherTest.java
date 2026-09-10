package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 流体烘焙：经 ChunkMeshBuilder 全链路验证水柱几何、液高、面剔除与染色。
 * 合成存档不写群系 NBT → biomeAt 为空 → 水色退回原版默认 0x3F76E4。
 */
class FluidMesherTest {

    @TempDir
    Path worldDir;

    private ChunkMeshResult bake(SyntheticWorldBuilder builder) {
        builder.write(worldDir);
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            return new ChunkMeshBuilder(StubCatalog.superflat(), world).buildChunk(new ChunkPos(0, 0));
        }
    }

    private static List<BakedQuad> waterQuads(ChunkMeshResult result) {
        return result.quads().stream()
                .filter(q -> q.texture().startsWith("minecraft:block/water"))
                .toList();
    }

    @Test
    void waterSourceEmitsTopAtVanillaHeightAndFourSides() {
        // 石头地面上孤立一格水源
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:water"));

        List<BakedQuad> water = waterQuads(result);
        // 顶面（still）+ 4 侧面（flow）；底面被下方石头遮挡剔除
        assertThat(water.stream().filter(q -> q.face().equals("up"))).hasSize(1);
        assertThat(water.stream().filter(q -> q.face().equals("down"))).isEmpty();
        assertThat(water.stream().filter(q -> !q.face().equals("up"))).hasSize(4);

        BakedQuad top = water.stream().filter(q -> q.face().equals("up")).findFirst().orElseThrow();
        assertThat(top.texture()).isEqualTo("minecraft:block/water_still");
        // 源头液高 8/9
        for (int i = 1; i < 12; i += 3) {
            assertThat(top.positions()[i]).isCloseTo(64f + 8f / 9f, within(1e-4f));
        }
        // 全部 quad：半透明 + tintIndex 0 + 默认水色
        for (BakedQuad quad : water) {
            assertThat(quad.translucent()).isTrue();
            assertThat(quad.tintIndex()).isEqualTo(0);
            assertThat(quad.tintRgb()).isEqualTo(FluidMesher.DEFAULT_WATER_COLOR);
        }
        // 侧面贴 water_flow 且高度同为 8/9
        for (BakedQuad side : water.stream().filter(q -> !q.face().equals("up")).toList()) {
            assertThat(side.texture()).isEqualTo("minecraft:block/water_flow");
            float maxY = -Float.MAX_VALUE;
            for (int i = 1; i < 12; i += 3) {
                maxY = Math.max(maxY, side.positions()[i]);
            }
            assertThat(maxY).isCloseTo(64f + 8f / 9f, within(1e-4f));
        }
    }

    @Test
    void waterColumnOmitsInternalTopAndFillsBelow() {
        // 两层水柱：下层无顶面且充满整格，上层保留顶面
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(4, 64, 4, "minecraft:water")
                .setBlock(4, 65, 4, "minecraft:water"));

        List<BakedQuad> water = waterQuads(result);
        // 整柱只有 1 个顶面（在上层）
        assertThat(water.stream().filter(q -> q.face().equals("up"))).hasSize(1);
        // 下层水的侧面充满整格高度（顶到 y=65）
        List<BakedQuad> lowerSides = water.stream()
                .filter(q -> !q.face().equals("up") && !q.face().equals("down"))
                .filter(q -> {
                    float minY = Float.MAX_VALUE;
                    for (int i = 1; i < 12; i += 3) {
                        minY = Math.min(minY, q.positions()[i]);
                    }
                    return minY == 64f;
                })
                .toList();
        assertThat(lowerSides).hasSize(4);
        for (BakedQuad side : lowerSides) {
            float maxY = -Float.MAX_VALUE;
            for (int i = 1; i < 12; i += 3) {
                maxY = Math.max(maxY, side.positions()[i]);
            }
            assertThat(maxY).isEqualTo(65f);
        }
        // 上层水底面被下层水省略（同流体）
        assertThat(water.stream().filter(q -> q.face().equals("down"))
                .filter(q -> q.positions()[1] == 65f)).isEmpty();
    }

    @Test
    void flowingWaterLevelControlsHeight() {
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(2, 63, 2, "minecraft:stone")
                .setBlock(2, 64, 2, "minecraft:water[level=7]"));

        BakedQuad top = waterQuads(result).stream()
                .filter(q -> q.face().equals("up")).findFirst().orElseThrow();
        // level=7 → 液高 (8-7)/9 = 1/9
        for (int i = 1; i < 12; i += 3) {
            assertThat(top.positions()[i]).isCloseTo(64f + 1f / 9f, within(1e-4f));
        }
    }

    @Test
    void sideFaceCulledAgainstOpaqueNeighbor() {
        // 水东侧紧邻石头 → east 面剔除，其余三面保留
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:water")
                .setBlock(9, 64, 8, "minecraft:stone"));

        List<BakedQuad> sides = waterQuads(result).stream()
                .filter(q -> !q.face().equals("up") && !q.face().equals("down"))
                .toList();
        assertThat(sides).hasSize(3);
        assertThat(sides).noneMatch(q -> q.face().equals("east"));
    }

    @Test
    void lavaIsOpaqueAndUntinted() {
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(12, 63, 12, "minecraft:stone")
                .setBlock(12, 64, 12, "minecraft:lava"));

        List<BakedQuad> lava = result.quads().stream()
                .filter(q -> q.texture().startsWith("minecraft:block/lava"))
                .toList();
        assertThat(lava).isNotEmpty();
        for (BakedQuad quad : lava) {
            assertThat(quad.translucent()).isFalse();
            assertThat(quad.tintIndex()).isEqualTo(-1);
            assertThat(quad.tintRgb()).isEqualTo(-1);
        }
    }

    @Test
    void fluidFacesCarryNoAo() {
        // 流体面不做 AO（原版行为；AO 会在水面形成棋盘噪点）
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:water"));

        for (BakedQuad quad : waterQuads(result)) {
            assertThat(quad.ao()).containsOnly((byte) 0);
        }
    }

    @Test
    void implicitWaterloggedSeagrassEmitsWaterBox() {
        // 石头地面上一棵海草：自身是水源介质 → 顶面 + 侧面水体（底面被石头剔除）
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:seagrass"));

        List<BakedQuad> water = waterQuads(result);
        assertThat(water.stream().filter(q -> q.face().equals("up"))).hasSize(1);
        assertThat(water.stream().filter(q -> q.face().equals("down"))).isEmpty();
        assertThat(water.stream().filter(q -> !q.face().equals("up") && !q.face().equals("down"))).hasSize(4);
        for (BakedQuad quad : water) {
            assertThat(quad.translucent()).isTrue();
            assertThat(quad.tintRgb()).isEqualTo(FluidMesher.DEFAULT_WATER_COLOR);
        }
    }

    @Test
    void waterSideFaceCulledAgainstWaterloggedNeighbor() {
        // 水东侧紧邻海草（隐含含水）→ east 面剔除；海草朝水的 west 面同样剔除
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(9, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:water")
                .setBlock(9, 64, 8, "minecraft:seagrass"));

        List<BakedQuad> water = waterQuads(result);
        // 两个位置各一个顶面；侧面：水 3 面（east 剔除）+ 海草 3 面（west 剔除）
        assertThat(water.stream().filter(q -> q.face().equals("up"))).hasSize(2);
        List<BakedQuad> sides = water.stream()
                .filter(q -> !q.face().equals("up") && !q.face().equals("down"))
                .toList();
        assertThat(sides).hasSize(6);
        // 东西向各剩外侧面 1 个（水的 west、海草的 east），内侧相邻面互剔；南北各 2
        assertThat(sides.stream().filter(q -> q.face().equals("east"))).hasSize(1);
        assertThat(sides.stream().filter(q -> q.face().equals("west"))).hasSize(1);
        assertThat(sides.stream().filter(q -> q.face().equals("north"))).hasSize(2);
        assertThat(sides.stream().filter(q -> q.face().equals("south"))).hasSize(2);
    }

    @Test
    void explicitWaterloggedPropertyEmitsWater() {
        // waterlogged=true 的非流体方块（目录无模型也无所谓，水体必须存在）
        ChunkMeshResult result = bake(new SyntheticWorldBuilder()
                .setBlock(8, 63, 8, "minecraft:stone")
                .setBlock(8, 64, 8, "minecraft:oak_slab[type=bottom,waterlogged=true]"));

        List<BakedQuad> water = waterQuads(result);
        assertThat(water.stream().filter(q -> q.face().equals("up"))).hasSize(1);
        assertThat(water.stream().filter(q -> q.face().equals("down"))).isEmpty();
        assertThat(water.stream().filter(q -> !q.face().equals("up") && !q.face().equals("down"))).hasSize(4);
    }
}
