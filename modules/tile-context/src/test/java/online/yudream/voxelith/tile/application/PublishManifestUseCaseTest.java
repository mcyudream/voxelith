package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestPublisher;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
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
        assertTrue(Files.isRegularFile(mapDir.resolve("atlas-layout.json")));
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

    @Test
    void publishesLodAtlasPagesAndRoundTripsThemThroughDisk() throws Exception {
        int[] green = new int[256];
        java.util.Arrays.fill(green, 0xFF00FF00);
        GenerateTilesUseCase tiles = new GenerateTilesUseCase(
                id -> Optional.of(new AtlasTexture(id, 16, 16, green)),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());
        TileOutcome outcome = tiles.generate(new TileCommand(Map.of(
                new ChunkPos(0, 0), new BakedChunkMeshData(new ChunkPos(0, 0),
                        List.of(topQuad(0, 64, 0)), Map.of(), 1)),
                workDir));

        List<LodAtlasPage> pages = List.of(
                new LodAtlasPage(1, "tiles/lod/1/lod-atlas.png", 64, "aaaa1111"),
                new LodAtlasPage(2, "tiles/lod/2/lod-atlas.png", 32, "bbbb2222"));
        MapManifest manifest = new PublishManifestUseCase(new FileManifestPublisher())
                .publish("lodmap", "LOD 地图", workDir, outcome, pages, publishRoot);

        assertEquals(2, manifest.lodAtlases().size());

        // 落盘 JSON 必须有 lodAtlases，且能被仓储读回（协议三方同步的一部分）
        String json = Files.readString(publishRoot.resolve("lodmap/manifest.json"));
        assertTrue(json.contains("\"lodAtlases\""), json);
        assertTrue(json.contains("tiles/lod/1/lod-atlas.png"), json);
        assertTrue(json.contains("\"slotSize\": 64"), json);

        MapManifest reloaded = new FileManifestStore(publishRoot).load("lodmap").orElseThrow();
        assertEquals(2, reloaded.lodAtlases().size());
        assertEquals(1, reloaded.lodAtlases().get(0).level());
        assertEquals(64, reloaded.lodAtlases().get(0).slotSize());
        assertEquals("bbbb2222", reloaded.lodAtlases().get(1).sha1());
        assertEquals(manifest.version(), reloaded.version());
    }

    @Test
    void loadsLegacyManifestWithoutLodAtlases() throws Exception {
        // 老产物没有该字段：必须能读回（空列表），否则升级即炸旧地图
        Path mapDir = publishRoot.resolve("legacy");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("manifest.json"), """
                {"formatVersion":1,"mapId":"legacy","name":"旧图","version":"0123456789ab",
                 "generatedAt":"2026-01-01T00:00:00Z",
                 "settings":{"hiresTileSize":32,"lodCount":1},
                 "boundsMin":[0,0,0],"boundsMax":[1,1,1],
                 "atlas":{"url":"atlas.png","size":16,"textureCount":1},
                 "tiles":[]}
                """);

        MapManifest loaded = new FileManifestStore(publishRoot).load("legacy").orElseThrow();
        assertTrue(loaded.lodAtlases().isEmpty());
        assertEquals("legacy", loaded.mapId());
    }

    @Test
    void inPlacePublishWritesManifestWithoutCopyingTiles() throws Exception {
        Path mapDir = publishRoot.resolve("inplace");
        Files.createDirectories(mapDir.resolve("tiles/hires/0"));
        Files.write(mapDir.resolve("atlas.png"), new byte[]{1, 2, 3});
        Files.write(mapDir.resolve("tiles/hires/0/0.glb"), new byte[]{4, 5});
        Files.write(mapDir.resolve("heightfield.bin"), new byte[]{6});

        MapManifest.TileEntry entry = new MapManifest.TileEntry(
                0, 0, 0, "tiles/hires/0/0.glb", "abc", 2, 1,
                new float[]{0, 0, 0}, new float[]{1, 1, 1});
        MapManifest manifest = new MapManifest(1, "inplace", "就地", "0123456789ab", "now",
                new MapManifest.Settings(32, 1),
                new float[]{0, 0, 0}, new float[]{1, 1, 1},
                new MapManifest.AtlasRef("atlas.png", 16, 1),
                List.of(entry));

        Path written = new FileManifestPublisher().publish("inplace", mapDir, manifest, publishRoot);
        assertEquals(mapDir.resolve("manifest.json"), written);
        assertTrue(Files.isRegularFile(mapDir.resolve("tiles/hires/0/0.glb")));
        assertTrue(Files.isRegularFile(mapDir.resolve("heightfield.bin")));
        assertTrue(Files.readString(written).contains("\"mapId\": \"inplace\""));
    }
}
