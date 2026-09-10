package online.yudream.voxelith.tile.domain.tile;

import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TileMeshAssemblerTest {

    private static AtlasLayout singleCellLayout() {
        return new AtlasLayout(16, 1, 16, Map.of("minecraft:stone", 0));
    }

    private static BakedQuadData quad(float x, float y, float z) {
        return quad(x, y, z, false);
    }

    private static BakedQuadData quad(float x, float y, float z, boolean translucent) {
        return new BakedQuadData(
                new float[]{x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1},
                new float[]{0, 0, 16, 0, 16, 16, 0, 16},
                new float[]{0, 1, 0},
                "minecraft:stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], translucent);
    }

    @Test
    void singleQuadProduces4Verts6Indices() {
        // 瓦片 (1,0) 原点 (32,0)；世界坐标 (33,64,2) → 局部 (1,64,2)
        TileGeometry geometry = new TileMeshAssembler()
                .assemble(1, 0, List.of(quad(33, 64, 2)), singleCellLayout());

        assertEquals(4, geometry.vertexCount());
        assertEquals(1, geometry.quadCount());
        assertEquals(0, geometry.translucent().vertexCount());
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, geometry.opaque().indices());
        assertArrayEquals(new float[]{1, 64, 2},
                new float[]{geometry.opaque().positions()[0], geometry.opaque().positions()[1], geometry.opaque().positions()[2]},
                1e-6f);
        // uv 经图集重映射：单格 16×16 图集，内缩半纹素，
        // 局部 (0,0)→(0.5/16,0.5/16)，(16,16)→(15.5/16,15.5/16)
        float lo = 0.5f / 16f, hi = 15.5f / 16f;
        assertArrayEquals(new float[]{lo, lo, hi, lo, hi, hi, lo, hi}, geometry.opaque().uvs(), 1e-6f);
        // 无染色 → COLOR_0 白色；sky=15 → 255，block=0，ao=0
        byte[] expectedColors = new byte[12];
        java.util.Arrays.fill(expectedColors, (byte) 0xFF);
        assertArrayEquals(expectedColors, geometry.opaque().colors());
        byte[] expectedLights = new byte[12];
        for (int v = 0; v < 4; v++) {
            expectedLights[v * 3] = (byte) 0xFF; // sky 15 × 17
        }
        assertArrayEquals(expectedLights, geometry.opaque().lights());
        // 世界包围盒保持世界坐标
        assertArrayEquals(new float[]{33, 64, 2}, geometry.worldMin(), 1e-6f);
        assertArrayEquals(new float[]{34, 64, 3}, geometry.worldMax(), 1e-6f);
    }

    @Test
    void translucentQuadsGoToTheirOwnSegment() {
        TileGeometry geometry = new TileMeshAssembler()
                .assemble(0, 0, List.of(quad(1, 64, 1), quad(2, 64, 2, true)), singleCellLayout());

        assertEquals(4, geometry.opaque().vertexCount());
        assertEquals(1, geometry.opaque().quadCount());
        assertEquals(4, geometry.translucent().vertexCount());
        assertEquals(1, geometry.translucent().quadCount());
        assertEquals(8, geometry.vertexCount());
        // 透明段索引从 0 重新开始（独立 primitive）
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, geometry.translucent().indices());
        // 包围盒合并两段
        assertArrayEquals(new float[]{1, 64, 1}, geometry.worldMin(), 1e-6f);
        assertArrayEquals(new float[]{3, 64, 3}, geometry.worldMax(), 1e-6f);
    }

    @Test
    void chunkToTileGrouping() {
        assertEquals(new online.yudream.voxelith.sharedkernel.vo.TilePos(0, 0, 0),
                TileMeshAssembler.tileOf(0, 0));
        assertEquals(new online.yudream.voxelith.sharedkernel.vo.TilePos(0, 0, 0),
                TileMeshAssembler.tileOf(1, 1));
        assertEquals(new online.yudream.voxelith.sharedkernel.vo.TilePos(0, -1, -1),
                TileMeshAssembler.tileOf(-1, -1));
        assertEquals(new online.yudream.voxelith.sharedkernel.vo.TilePos(0, -1, 0),
                TileMeshAssembler.tileOf(-2, 1));
    }
}
