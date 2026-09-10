package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateTilesUseCaseTest {

    @TempDir
    Path outputDir;

    private static BakedQuadData topQuad(float x, float y, float z) {
        return new BakedQuadData(
                new float[]{x, y, z + 1, x + 1, y, z + 1, x + 1, y, z, x, y, z},
                new float[]{0, 16, 16, 16, 16, 0, 0, 0},
                new float[]{0, 1, 0},
                "minecraft:stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], false);
    }

    @Test
    void generatesTilesAtlasAndReport() {
        // 两个相邻 chunk（同一瓦片 (0,0)）+ 一个远处 chunk（瓦片 (1,0)）
        BakedChunkMeshData chunkA = new BakedChunkMeshData(new ChunkPos(0, 0),
                List.of(topQuad(0, 64, 0), topQuad(1, 64, 0)), Map.of(), 2);
        BakedChunkMeshData chunkB = new BakedChunkMeshData(new ChunkPos(1, 1),
                List.of(topQuad(16, 64, 16)), Map.of(), 1);
        BakedChunkMeshData chunkC = new BakedChunkMeshData(new ChunkPos(2, 0),
                List.of(topQuad(32, 64, 0)), Map.of(), 1);

        int[] green = new int[256];
        java.util.Arrays.fill(green, 0xFF00FF00);
        GenerateTilesUseCase useCase = new GenerateTilesUseCase(
                id -> Optional.of(new AtlasTexture(id, 16, 16, green)),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());

        TileOutcome outcome = useCase.generate(new TileCommand(
                Map.of(new ChunkPos(0, 0), chunkA, new ChunkPos(1, 1), chunkB, new ChunkPos(2, 0), chunkC),
                outputDir));

        assertEquals(2, outcome.tiles().size());
        assertTrue(Files.isRegularFile(outputDir.resolve("tiles/hires/0/0.glb")));
        assertTrue(Files.isRegularFile(outputDir.resolve("tiles/hires/1/0.glb")));
        assertTrue(Files.isRegularFile(outcome.atlasFile()));
        assertTrue(Files.isRegularFile(outcome.reportFile()));

        TileOutcome.TileSummary first = outcome.tiles().getFirst();
        assertEquals(3, first.quads());
        assertEquals(12, first.vertices());
        assertTrue(first.bytes() > 0);
        assertEquals(40, first.sha1().length());
    }

    @Test
    void missingTextureStillGeneratesWithFallback() {
        BakedChunkMeshData chunk = new BakedChunkMeshData(new ChunkPos(0, 0),
                List.of(topQuad(0, 64, 0)), Map.of("minecraft:stone", 1), 1);
        GenerateTilesUseCase useCase = new GenerateTilesUseCase(
                id -> Optional.empty(),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());

        TileOutcome outcome = useCase.generate(new TileCommand(
                Map.of(new ChunkPos(0, 0), chunk), outputDir));

        assertEquals(1, outcome.tiles().size());
        assertTrue(Files.isRegularFile(outputDir.resolve("tiles/hires/0/0.glb")));
        // 图集 = 兜底格 + 缺失贴图占位格，共 2 格
        assertTrue(outcome.tiles().getFirst().bytes() > 0);
    }
}
