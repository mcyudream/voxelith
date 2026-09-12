package online.yudream.voxelith.tile.infrastructure.artifact;

import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskTileIndexerTest {

    @Test
    void indexesHiresAndLodFromDisk(@TempDir Path tilesDir) throws Exception {
        byte[] glb = new GlbTileEncoder().encode(quad(0, 64, 0, 1, 1), new byte[]{9, 8, 7, 6});
        Path hires = tilesDir.resolve("tiles/hires/3/4.glb");
        Files.createDirectories(hires.getParent());
        Files.write(hires, glb);

        byte[] lod = new GlbTileEncoder().encode(quad(0, 10, 0, 8, 8), new byte[]{1, 2, 3, 4});
        Path lodFile = tilesDir.resolve("tiles/lod/2/-1/0.glb");
        Files.createDirectories(lodFile.getParent());
        Files.write(lodFile, lod);

        Map<String, Integer> cells = new LinkedHashMap<>();
        cells.put("minecraft:stone", 0);
        cells.put("minecraft:dirt", 1);
        AtlasLayoutFiles.write(tilesDir.resolve(AtlasLayoutFiles.FILE_NAME),
                new AtlasLayout(16, 2, 32, cells));
        Files.write(tilesDir.resolve("atlas.png"), new byte[]{1});

        TileOutcome outcome = DiskTileIndexer.index(tilesDir);
        assertEquals(2, outcome.tiles().size());
        assertEquals(32, outcome.atlasSize());
        assertEquals(2, outcome.textureCount());

        TileOutcome.TileSummary hiresTile = outcome.tiles().stream()
                .filter(t -> t.pos().isHires()).findFirst().orElseThrow();
        assertEquals(3, hiresTile.pos().x());
        assertEquals(4, hiresTile.pos().z());
        assertEquals(1, hiresTile.quads());
        assertEquals(3 * 32 + 0f, hiresTile.min()[0], 1e-4f);
        assertEquals(4 * 32 + 1f, hiresTile.max()[2], 1e-4f);
        assertEquals(64f, hiresTile.min()[1], 1e-4f);
        assertTrue(hiresTile.sha1().length() > 8);

        TileOutcome.TileSummary lodTile = outcome.tiles().stream()
                .filter(t -> !t.pos().isHires()).findFirst().orElseThrow();
        assertEquals(2, lodTile.pos().level());
        assertEquals(-1, lodTile.pos().x());
        // coverage = 32 << 2 = 128；originX = -128
        assertEquals(-128f, lodTile.min()[0], 1e-4f);
        assertEquals(-128f + 8f, lodTile.max()[0], 1e-4f);
    }

    private static TileGeometry quad(float x, float y, float z, float dx, float dz) {
        return new TileGeometry(
                new TileGeometry.Segment(
                        new float[]{x, y, z, x + dx, y, z, x + dx, y, z + dz, x, y, z + dz},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                        new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                        new byte[12], new byte[12],
                        new int[]{0, 1, 2, 0, 2, 3}),
                TileGeometry.Segment.empty(),
                new float[]{x, y, z}, new float[]{x + dx, y, z + dz});
    }
}
