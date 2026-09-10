package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.bake.domain.mesh.VertexLightSampler.SectionGrid;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.application.dto.BlockStateData;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VertexLightSamplerTest {

    /** 顶面 quad（方块局部 0~16 坐标系）：(0,16,0) (16,16,0) (16,16,16) (0,16,16)。 */
    private static Quad topFaceQuad() {
        return new Quad(
                new float[]{0, 16, 0, 16, 16, 0, 16, 16, 16, 0, 16, 16},
                new float[]{0, 0, 16, 0, 16, 16, 0, 16},
                new float[]{0, 1, 0},
                "minecraft:stone", "up", -1, true, "up");
    }

    @Test
    void sideOccluderDarkensOnlyAdjacentCorners() {
        FakeWorld world = new FakeWorld();
        // 被测方块 (8,64,8)，顶面朝向空气格 (8,65,8)；东侧上方一格放遮挡方块
        world.setBlock(9, 65, 8, "minecraft:stone");
        VertexLightSampler sampler = new VertexLightSampler(world, state -> true);
        SectionGrid grid = sampler.buildGrid(0, 64, 0);

        byte[] sky = new byte[4];
        byte[] block = new byte[4];
        byte[] ao = new byte[4];
        sampler.sample(topFaceQuad(), 8, 64, 8, grid, sky, block, ao);

        // 顶点顺序：(0,16,0) (16,16,0) (16,16,16) (0,16,16)
        // +x 侧两个顶点（1、2 号）的 side1 = (9,65,8) 遮挡 → n=1；-x 侧（0、3 号）无遮挡 → n=0
        assertArrayEquals(new byte[]{0, 1, 1, 0}, ao);
        // 遮挡格不参与光照平均，其余格 sky=15 → 全部 15
        assertArrayEquals(new byte[]{15, 15, 15, 15}, sky);
        assertArrayEquals(new byte[4], block);
    }

    @Test
    void twoSideOccludersForceAoLevel3() {
        FakeWorld world = new FakeWorld();
        // 顶点 2 号 (16,16,16) 的 side1=(9,65,8)、side2=(8,65,9) 同时遮挡 → 原版规则 n=3
        world.setBlock(9, 65, 8, "minecraft:stone");
        world.setBlock(8, 65, 9, "minecraft:stone");
        VertexLightSampler sampler = new VertexLightSampler(world, state -> true);
        SectionGrid grid = sampler.buildGrid(0, 64, 0);

        byte[] ao = new byte[4];
        sampler.sample(topFaceQuad(), 8, 64, 8, grid, new byte[4], new byte[4], ao);
        assertEquals(3, ao[2]);
        assertEquals(1, ao[1]);
        assertEquals(1, ao[3]);
        assertEquals(0, ao[0]);
    }

    @Test
    void skyLightAveragesCornerCells() {
        FakeWorld world = new FakeWorld();
        world.skyOverride(8, 65, 9, 7); // 南侧上方一格光照 7
        VertexLightSampler sampler = new VertexLightSampler(world, state -> true);
        SectionGrid grid = sampler.buildGrid(0, 64, 0);

        byte[] sky = new byte[4];
        sampler.sample(topFaceQuad(), 8, 64, 8, grid, sky, new byte[4], new byte[4]);
        // +z 侧顶点（2、3 号）：(15+15+7+15)/4 = 13；-z 侧（0、1 号）不受影响
        assertEquals(13, sky[2]);
        assertEquals(13, sky[3]);
        assertEquals(15, sky[0]);
        assertEquals(15, sky[1]);
    }

    @Test
    void nonOpaqueNeighborDoesNotOcclude() {
        FakeWorld world = new FakeWorld();
        world.setBlock(9, 65, 8, "minecraft:glass"); // 遮挡测试器判定非满方块
        VertexLightSampler sampler = new VertexLightSampler(world,
                state -> !state.block().equals("minecraft:glass"));
        SectionGrid grid = sampler.buildGrid(0, 64, 0);

        byte[] ao = new byte[4];
        sampler.sample(topFaceQuad(), 8, 64, 8, grid, new byte[4], new byte[4], ao);
        assertArrayEquals(new byte[4], ao);
    }

    /** 简易假世界：默认全空、天空光 15、方块光 0。 */
    private static final class FakeWorld implements WorldBlockAccess {
        private final Map<Long, BlockStateData> blocks = new HashMap<>();
        private final Map<Long, Integer> sky = new HashMap<>();

        void setBlock(int x, int y, int z, String id) {
            blocks.put(key(x, y, z), new BlockStateData(id, Map.of()));
        }

        void skyOverride(int x, int y, int z, int level) {
            sky.put(key(x, y, z), level);
        }

        @Override
        public Optional<BlockStateData> blockStateAt(int x, int y, int z) {
            return Optional.ofNullable(blocks.get(key(x, y, z)));
        }

        @Override
        public int skyLightAt(int x, int y, int z) {
            return sky.getOrDefault(key(x, y, z), 15);
        }

        @Override
        public int blockLightAt(int x, int y, int z) {
            return 0;
        }

        @Override
        public int[] sectionYs(ChunkPos chunk) {
            return new int[0];
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
        }
    }
}
