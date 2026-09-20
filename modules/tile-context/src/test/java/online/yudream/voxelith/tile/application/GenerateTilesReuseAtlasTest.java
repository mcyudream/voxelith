package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
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
        // 复用路径也要落一份布局：增量扩图集会改布局（追加新贴图），产物目录与已发布目录
        // 的 layout 必须一起前进，否则续跑/发布拿老布局就会把新格子当成不存在
        assertTrue(Files.isRegularFile(outputDir.resolve("atlas-layout.json")),
                "复用路径也应写出图集布局");
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

    /**
     * 回归：增量重跑（复用已发布图集）必须能像全量渲染那样走**共享图集**编码。
     *
     * <p>此前增量链路用的是默认 {@code uncompressed()}（embedImage=true），每片瓦片都把整张
     * 图集 PNG 内嵌一遍；这里把两种选项的产物摆在一起，钉住「增量也能不内嵌图集」这条契约。</p>
     */
    @Test
    void reuseAtlasCanEncodeWithoutEmbeddedImage() throws Exception {
        BakedQuadData quad = new BakedQuadData(
                new float[]{0, 64, 1, 1, 64, 1, 1, 64, 0, 0, 64, 0},
                new float[]{0, 16, 16, 16, 16, 0, 0, 0},
                new float[]{0, 1, 0},
                "minecraft:block/stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], false);
        BakedChunkMeshData chunk = new BakedChunkMeshData(new ChunkPos(0, 0), List.of(quad), Map.of(), 1);

        // 256² 的「噪声」图集：PNG 压缩后仍有一两百 KB，内嵌与不内嵌的体积差异才明显
        int[] pixels = new int[256 * 256];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
        }
        Map<String, Integer> cells = new LinkedHashMap<>();
        cells.put("minecraft:block/stone", 0);
        AtlasLayout layout = new AtlasLayout(16, 16, 256, cells);
        byte[] png = new PngImageCodec().encodePng(256, 256, pixels);
        AtlasReuse reuse = new AtlasReuse(layout, png);

        GenerateTilesUseCase useCase = new GenerateTilesUseCase(
                id -> Optional.empty(),
                new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());

        TileOutcome embedded = useCase.generate(new TileCommand(
                Map.of(new ChunkPos(0, 0), chunk), outputDir.resolve("embedded"), reuse));
        TileOutcome shared = useCase.generate(new TileCommand(
                Map.of(new ChunkPos(0, 0), chunk), outputDir.resolve("shared"),
                EncodeOptions.sharedAtlas().withMeshopt(true), reuse));

        java.nio.file.Path embeddedGlb = outputDir.resolve("embedded/tiles/hires/0/0.glb");
        java.nio.file.Path sharedGlb = outputDir.resolve("shared/tiles/hires/0/0.glb");
        String embeddedJson = glbJson(Files.readAllBytes(embeddedGlb));
        String sharedJson = glbJson(Files.readAllBytes(sharedGlb));

        assertTrue(embeddedJson.contains("image/png"), "默认选项仍内嵌图集（老行为/兼容性）");
        assertTrue(!sharedJson.contains("image/png"), "共享图集选项不得内嵌 PNG");
        assertTrue(sharedJson.contains("EXT_meshopt_compression"), "共享图集 + meshopt 应声明扩展");
        assertTrue(Files.size(sharedGlb) < Files.size(embeddedGlb),
                "不内嵌图集的瓦片必须明显更小");
        assertEquals(embedded.atlasSize(), shared.atlasSize());
    }

    /** 取 glb 的 JSON chunk 文本（测试里只想看声明了什么）。 */
    private static String glbJson(byte[] glb) {
        java.nio.ByteBuffer head = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int jsonLength = head.getInt(12);
        return new String(glb, 20, jsonLength, java.nio.charset.StandardCharsets.UTF_8);
    }
}
