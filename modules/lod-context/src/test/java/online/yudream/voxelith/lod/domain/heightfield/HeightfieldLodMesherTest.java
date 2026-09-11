package online.yudream.voxelith.lod.domain.heightfield;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeightfieldLodMesherTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;

    private final HeightfieldLodMesher mesher = new HeightfieldLodMesher();

    /** 构造 footprint=2 的高度场：columns[cx][cz] = 高度（NaN 跳过），颜色统一 colorizer。 */
    private static Heightfield field(float[][] heights, int[] rgbs) {
        List<LodSample> samples = new ArrayList<>();
        for (int cx = 0; cx < heights.length; cx++) {
            for (int cz = 0; cz < heights[cx].length; cz++) {
                float y = heights[cx][cz];
                if (Float.isNaN(y)) {
                    continue;
                }
                int rgb = rgbs[(cx + cz) % rgbs.length];
                samples.add(new LodSample(cx * 2 + 0.5f, y, cz * 2 + 0.5f, rgb));
            }
        }
        return Heightfield.fromSamples(samples, 2);
    }

    private static float[][] rect(int width, int depth, float y) {
        float[][] heights = new float[width][depth];
        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < depth; cz++) {
                heights[cx][cz] = y;
            }
        }
        return heights;
    }

    @Test
    void flatPlaneMergesIntoOneTopRunPerRow() {
        Heightfield field = field(rect(32, 32, 64), new int[]{0x00FF00});
        List<LodQuad> quads = mesher.meshTile(field, 0, 0);

        // 32 行各一条顶面游程；边界邻居下探到 floorY=64 与柱同高 → 无裙边
        assertThat(quads).hasSize(32);
        assertThat(quads).allMatch(q -> q.normal()[1] == 1f);
        LodQuad first = quads.getFirst();
        // 整条游程横跨瓦片全宽 64 方块，深度一柱 2 方块
        assertThat(first.positions()[0]).isEqualTo(0f);    // x0
        assertThat(first.positions()[3]).isEqualTo(64f);   // x1
        assertThat(first.positions()[1]).isEqualTo(64f);   // y
        assertThat(first.uvs()).containsExactly(0f, 1f / 32f, 1f, 1f / 32f, 1f, 0f, 0f, 0f);
    }

    @Test
    void sameHeightDifferentColorsMergeIntoOneTopRun() {
        List<LodSample> samples = new ArrayList<>();
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 1; cz++) {
                samples.add(new LodSample(cx * 2 + 0.5f, 64, cz * 2 + 0.5f, cx < 16 ? RED : BLUE));
            }
        }
        Heightfield field = Heightfield.fromSamples(samples, 2);
        List<LodQuad> quads = mesher.meshTile(field, 0, 0);
        List<LodQuad> tops = quads.stream().filter(q -> q.normal()[1] == 1f).toList();
        assertThat(tops).hasSize(1);
        assertThat(tops.getFirst().positions()[3]).isEqualTo(64f);
    }

    @Test
    void heightStepEmitsShadedSkirt() {
        float[][] heights = rect(32, 32, 64);
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                heights[cx][cz] = 70;
            }
        }
        // 西侧 70 红，东侧 64 蓝
        List<LodSample> samples = new ArrayList<>();
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                samples.add(new LodSample(cx * 2 + 0.5f, heights[cx][cz], cz * 2 + 0.5f,
                        cx < 16 ? RED : BLUE));
            }
        }
        Heightfield field = Heightfield.fromSamples(samples, 2);
        List<LodQuad> quads = mesher.meshTile(field, 0, 0);

        // 顶面：每行两条游程 → 64
        long tops = quads.stream().filter(q -> q.normal()[1] == 1f).count();
        assertThat(tops).isEqualTo(64);

        // 内部台阶裙边：cx=16 处东面一条（合并整列），x=32，y 64→70，z 0→64，红色 ×0.6 明暗
        List<LodQuad> eastSkirts = quads.stream()
                .filter(q -> q.normal()[0] == 1f).toList();
        assertThat(eastSkirts).hasSize(1);
        LodQuad skirt = eastSkirts.getFirst();
        assertThat(skirt.rgb()).isEqualTo(0x990000); // 255×0.6 = 153 = 0x99
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            assertThat(skirt.positions()[v * 3]).isEqualTo(32f);
            minY = Math.min(minY, skirt.positions()[v * 3 + 1]);
            maxY = Math.max(maxY, skirt.positions()[v * 3 + 1]);
            minZ = Math.min(minZ, skirt.positions()[v * 3 + 2]);
            maxZ = Math.max(maxZ, skirt.positions()[v * 3 + 2]);
        }
        assertThat(minY).isEqualTo(64f);
        assertThat(maxY).isEqualTo(70f);
        assertThat(minZ).isEqualTo(0f);
        assertThat(maxZ).isEqualTo(64f);

        // 西侧边界墙（70 → floorY 64）朝西面一条；东边界同高无裙边
        long westSkirts = quads.stream().filter(q -> q.normal()[0] == -1f).count();
        assertThat(westSkirts).isEqualTo(1);
        // 南北边界：高区 70>64 各一条（0.8 明暗）
        List<LodQuad> zSkirts = quads.stream()
                .filter(q -> Math.abs(q.normal()[2]) == 1f).toList();
        assertThat(zSkirts).hasSize(2);
        assertThat(zSkirts.getFirst().rgb()).isEqualTo(0xCC0000); // 255×0.8 = 204 = 0xCC
    }

    @Test
    void skirtAtTileBoundaryEmittedExactlyOnceByHigherSide() {
        // 两瓦片宽：col 31（tile 0）高 70，其余 64 → 边界裙边由 tile 0 发射
        float[][] heights = rect(64, 32, 64);
        for (int cz = 0; cz < 32; cz++) {
            heights[31][cz] = 70;
        }
        Heightfield field = field(heights, new int[]{RED});

        List<LodQuad> tile0 = mesher.meshTile(field, 0, 0);
        List<LodQuad> tile1 = mesher.meshTile(field, 1, 0);

        long tile0EastSkirts = tile0.stream().filter(q -> q.normal()[0] == 1f).count();
        // tile 0 内部 col31 与其西侧 col30 也有一条台阶裙边（col31 高 → col31 西面朝西）
        long tile0WestSkirts = tile0.stream().filter(q -> q.normal()[0] == -1f).count();
        long tile1EastSkirts = tile1.stream().filter(q -> q.normal()[0] == 1f).count();
        long tile1WestSkirts = tile1.stream().filter(q -> q.normal()[0] == -1f).count();

        // 边界边（col31|col32，col31 更高 → 朝东面）只能由 tile 0 发出
        assertThat(tile0EastSkirts).isEqualTo(1);
        assertThat(tile1EastSkirts).isZero();
        // tile 0 内部台阶（col30|col31，col31 更高 → 朝西面）也由 tile 0 发出
        assertThat(tile0WestSkirts).isEqualTo(1);
        // tile 1 侧无任何 x 向裙边
        assertThat(tile1WestSkirts).isZero();
    }

    @Test
    void skirtOwnedByRightTileWhenItsColumnIsHigher() {
        // col 32（tile 1）高 70 → 边界裙边由 tile 1 发射且朝西
        float[][] heights = rect(64, 32, 64);
        for (int cz = 0; cz < 32; cz++) {
            heights[32][cz] = 70;
        }
        Heightfield field = field(heights, new int[]{BLUE});

        List<LodQuad> tile0 = mesher.meshTile(field, 0, 0);
        List<LodQuad> tile1 = mesher.meshTile(field, 1, 0);

        assertThat(tile0.stream().filter(q -> q.normal()[0] != 0f && q.normal()[1] == 0f)).isEmpty();
        List<LodQuad> tile1West = tile1.stream()
                .filter(q -> q.normal()[0] == -1f).toList();
        List<LodQuad> tile1East = tile1.stream()
                .filter(q -> q.normal()[0] == 1f).toList();
        // 边界边（col31|col32，col32 更高 → 朝西面）一条
        assertThat(tile1West).hasSize(1);
        assertThat(tile1West.getFirst().rgb()).isEqualTo(0x000099); // BLUE ×0.6
        // 内部台阶（col32|col33，col32 更高 → 朝东面）一条
        assertThat(tile1East).hasSize(1);
    }

    @Test
    void emptyTileProducesNoQuads() {
        Heightfield field = field(rect(8, 8, 64), new int[]{RED});
        assertThat(mesher.meshTile(field, 10, 10)).isEmpty();
    }

    @Test
    void higherLevelsHaveCoarserFootprint() {
        Heightfield field = field(rect(32, 32, 64), new int[]{RED}).aggregate();
        // footprint=4：32×32 子柱聚合成 16×16 父柱，每行一条游程、单柱 z 向深 4
        List<LodQuad> quads = mesher.meshTile(field, 0, 0);
        assertThat(quads).hasSize(16);
        LodQuad first = quads.getFirst();
        assertThat(first.positions()[3]).isEqualTo(64f);   // x1 = 16 柱 × 4
        assertThat(first.positions()[2] - first.positions()[11]).isEqualTo(4f); // z 向一柱深
    }
}
