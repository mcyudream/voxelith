package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.infrastructure.artifact.FileTileArtifactSink;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateTilesReuseAtlasTest {

    @TempDir
    Path outputDir;

    @Test
    void reuseAtlasSkipsRepackAndStillWritesGlb() {
        BakedQuadData quad = new BakedQuadData(
                new float[]{0, 64, 1, 1, 64, 1, 1, 64, 0, 0, 64, 0},
                new float[]{0, 16, 16, 16, 16, 0, 0, 0},
                new float[]{0, 1, 0},
                "minecraft:block/stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], false);
        BakedChunkMeshData chunk = new BakedChunkMeshData(new ChunkPos(0, 0), List.of(quad), Map.of(), 1);

        int[] green = new int[256];
        java.util.Arrays.fill(green, 0xFF00FF00);
        Map<String, Integer> cells = new LinkedHashMap<>();
        cells.put("minecraft:block/stone", 0);
        AtlasLayout layout = new AtlasLayout(16, 1, 16, cells);
        byte[] png = new PngImageCodec().encodePng(16, 16, green);

        GenerateTilesUseCase useCase = new GenerateTilesUseCase(
                id -> Optional.empty(),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());

        TileOutcome outcome = useCase.generate(new TileCommand(
                Map.of(new ChunkPos(0, 0), chunk),
                outputDir,
                new AtlasReuse(layout, png)));

        assertEquals(1, outcome.tiles().size());
        assertEquals(16, outcome.atlasSize());
        assertTrue(Files.isRegularFile(outputDir.resolve("tiles/hires/0/0.glb")));
        assertTrue(Files.isRegularFile(outcome.atlasFile()));
    }

    @Test
    void packerResultCanBuildReuseRecord() {
        int[] argb = new int[256];
        java.util.Arrays.fill(argb, 0xFF112233);
        var packed = new AtlasPacker().pack(List.of(
                new AtlasTexture(new Identifier("minecraft", "block/stone"), 16, 16, argb)));
        AtlasReuse reuse = new AtlasReuse(packed.layout(), new byte[]{9});
        assertEquals(packed.layout().pixelSize(), reuse.layout().pixelSize());
    }
}
