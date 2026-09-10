package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestPublisher;
import online.yudream.voxelith.tile.infrastructure.artifact.FileTileArtifactSink;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishManifestUseCaseTest {

    @TempDir
    Path workDir;

    @TempDir
    Path publishRoot;

    private static BakedQuadData topQuad(float x, float y, float z) {
        return new BakedQuadData(
                new float[]{x, y, z + 1, x + 1, y, z + 1, x + 1, y, z, x, y, z},
                new float[]{0, 16, 16, 16, 16, 0, 0, 0},
                new float[]{0, 1, 0},
                "minecraft:stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], false);
    }

    @Test
    void publishesManifestAndCopiesArtifacts() throws Exception {
        int[] green = new int[256];
        java.util.Arrays.fill(green, 0xFF00FF00);
        GenerateTilesUseCase tiles = new GenerateTilesUseCase(
                id -> Optional.of(new AtlasTexture(id, 16, 16, green)),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());
        TileOutcome outcome = tiles.generate(new TileCommand(Map.of(
                new ChunkPos(0, 0), new BakedChunkMeshData(new ChunkPos(0, 0),
                        List.of(topQuad(0, 64, 0)), Map.of(), 1),
                new ChunkPos(2, 0), new BakedChunkMeshData(new ChunkPos(2, 0),
                        List.of(topQuad(32, 64, 0)), Map.of(), 1)),
                workDir));

        MapManifest manifest = new PublishManifestUseCase(new FileManifestPublisher())
                .publish("demo", "演示地图", workDir, outcome, publishRoot);

        assertEquals("demo", manifest.mapId());
        assertEquals(2, manifest.tiles().size());
        assertEquals(12, manifest.version().length());
        assertEquals(32, manifest.settings().hiresTileSize());
        assertEquals(1, manifest.settings().lodCount());
        assertEquals(0f, manifest.boundsMin()[0], 1e-6f);
        assertEquals(33f, manifest.boundsMax()[0], 1e-6f);

        Path mapDir = publishRoot.resolve("demo");
        assertTrue(Files.isRegularFile(mapDir.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(mapDir.resolve("atlas.png")));
        assertTrue(Files.isRegularFile(mapDir.resolve("tiles/hires/0/0.glb")));
        assertTrue(Files.isRegularFile(mapDir.resolve("tiles/hires/1/0.glb")));
        assertFalse(Files.exists(mapDir.resolve("manifest.json.tmp")), "临时文件应已原子改名");

        String json = Files.readString(mapDir.resolve("manifest.json"));
        assertTrue(json.contains("\"mapId\": \"demo\""), json);
        assertTrue(json.contains("\"sha1\""), json);
        assertTrue(json.contains("tiles/hires/0/0.glb"), json);

        // 重复发布：旧目录被清理后重建，不残留
        new PublishManifestUseCase(new FileManifestPublisher())
                .publish("demo", "演示地图", workDir, outcome, publishRoot);
        assertTrue(Files.isRegularFile(mapDir.resolve("manifest.json")));
    }
}
