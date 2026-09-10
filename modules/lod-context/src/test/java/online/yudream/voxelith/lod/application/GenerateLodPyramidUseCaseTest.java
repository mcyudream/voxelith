package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.application.VertexColorTileExporter;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateLodPyramidUseCaseTest {

    /** 记录每次编码的几何与图集参数的编码器桩。 */
    private static final class RecordingEncoder implements TileEncoder {
        final List<TileGeometry> geometries = new ArrayList<>();
        final List<byte[]> atlases = new ArrayList<>();

        @Override
        public byte[] encode(TileGeometry geometry, byte[] atlasPng) {
            geometries.add(geometry);
            atlases.add(atlasPng);
            return new byte[]{1, 2, 3};
        }
    }

    private static VertexColorTileExporter exporter(RecordingEncoder encoder) {
        return new VertexColorTileExporter(encoder, new online.yudream.voxelith.tile.application.TileArtifactSink() {
            @Override
            public Path writeTile(Path outputDir, TilePos pos, byte[] glb) {
                return outputDir.resolve("tile.glb");
            }

            @Override
            public Path writeAtlas(Path outputDir, byte[] png) {
                return outputDir.resolve("atlas.png");
            }

            @Override
            public Path writeReport(Path outputDir, List<TileOutcome.TileSummary> tiles,
                                    int textureCount, int atlasSize) {
                return outputDir.resolve("report.json");
            }
        });
    }

    /** 均色 0x808080 的贴图源（含一个全透明像素验证 alpha 过滤）；未知贴图返回空。 */
    private static TextureColorSampler graySampler() {
        return new TextureColorSampler(id -> "block/stone".equals(id.path())
                ? Optional.of(new AtlasTexture(id, 2, 2,
                        new int[]{0xFF808080, 0xFF808080, 0x00808080, 0xFF808080}))
                : Optional.empty());
    }

    private static BakedQuadData quad(float x0, float y, float z0, float[] normal,
                                      String texture, int tintRgb) {
        return new BakedQuadData(
                new float[]{x0, y, z0, x0 + 1, y, z0, x0 + 1, y, z0 + 1, x0, y, z0 + 1},
                new float[8], normal, texture, -1, false, "up", tintRgb,
                new byte[4], new byte[4], new byte[4], false);
    }

    private static Map<ChunkPos, BakedChunkMeshData> meshes(BakedQuadData... quads) {
        return Map.of(new ChunkPos(0, 0),
                new BakedChunkMeshData(new ChunkPos(0, 0), List.of(quads), Map.of(), quads.length));
    }

    @Test
    void samplesOnlyUpFacingQuadsAndTintsTextureAverage() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));

        LodOutcome outcome = useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                // 朝下面即使更高也不得被采样
                quad(0, 200, 0, new float[]{0, -1, 0}, "minecraft:block/stone", -1),
                // 群系染色：线性灰 0x37 × 纯红（sRGB→线性不变）→ 0x370000
                quad(2, 70, 0, new float[]{0, 1, 0}, "minecraft:block/stone", 0xFF0000)
        ), Path.of("build/lod-test"), 0));

        assertThat(outcome.levels()).isEqualTo(1);
        assertThat(outcome.tiles()).hasSize(1);
        assertThat(outcome.tiles().getFirst().pos()).isEqualTo(new TilePos(1, 0, 0));

        TileGeometry geometry = encoder.geometries.getFirst();
        // 采样忽略朝下面：最高 y = 70（而非 200）
        assertThat(geometry.worldMax()[1]).isEqualTo(70f);
        // 无纹理编码：atlasPng 为 null
        assertThat(encoder.atlases.getFirst()).isNull();

        // 顶面颜色为线性空间字节：sRGB 0x808080 → 线性 0x373737；染色列 0x370000
        List<int[]> topColors = topFaceColors(geometry);
        assertThat(topColors).anySatisfy(c -> assertThat(c).containsExactly(0x37, 0x37, 0x37));
        assertThat(topColors).anySatisfy(c -> assertThat(c).containsExactly(0x37, 0x00, 0x00));
        // 全亮天空光合成
        byte[] lights = geometry.opaque().lights();
        assertThat(lights[0]).isEqualTo((byte) 255);
        assertThat(lights[1]).isEqualTo((byte) 0);
    }

    @Test
    void missingTextureFallsBackToWhite() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));

        useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/nonexistent", -1)
        ), Path.of("build/lod-test"), 0));

        assertThat(topFaceColors(encoder.geometries.getFirst()))
                .anySatisfy(c -> assertThat(c).containsExactly(0xFF, 0xFF, 0xFF));
    }

    @Test
    void autoModeAggregatesUntilMapFitsTwoByTwoTiles() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));

        // 两根相距 1000 方块的柱子：level1 跨 16 瓦片 → 逐层聚合
        LodOutcome outcome = useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                quad(1000, 80, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)
        ), Path.of("build/lod-test"), 0));

        assertThat(outcome.levels()).isEqualTo(4);
        assertThat(outcome.tiles().stream().map(t -> t.pos().level()).distinct().sorted())
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void explicitMaxLevelStopsEarly() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));

        LodOutcome outcome = useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                quad(1000, 80, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)
        ), Path.of("build/lod-test"), 2));

        assertThat(outcome.levels()).isEqualTo(2);
        assertThat(outcome.tiles().stream().map(t -> t.pos().level()).distinct().sorted())
                .containsExactly(1, 2);
    }

    @Test
    void incrementalMergeKeepsUnrelatedColumnsAndOnlyMeshesOverlappingTiles() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));
        InMemoryHeightfieldStore store = new InMemoryHeightfieldStore();

        useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                quad(600, 50, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)
        ), Path.of("build/lod-test"), 0, store, List.of()));
        assertThat(store.field.topY(0, 0)).isEqualTo(64f);
        int farCx = Math.floorDiv(600, 2);
        assertThat(store.field.topY(farCx, 0)).isEqualTo(50f);

        encoder.geometries.clear();
        LodOutcome incremental = useCase.generate(new LodCommand(
                Map.of(new ChunkPos(0, 0), new BakedChunkMeshData(new ChunkPos(0, 0),
                        List.of(quad(2, 80, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)),
                        Map.of(), 1)),
                Path.of("build/lod-test"), 0, store, List.of(new RegionPos(0, 0))));

        assertThat(store.field.topY(0, 0)).isNaN();
        assertThat(store.field.topY(1, 0)).isEqualTo(80f);
        assertThat(store.field.topY(farCx, 0)).isEqualTo(50f);
        assertThat(incremental.tiles()).isNotEmpty();
        assertThat(incremental.tiles().getFirst().pos().level()).isEqualTo(1);
    }

    private static final class InMemoryHeightfieldStore
            implements online.yudream.voxelith.lod.application.HeightfieldStore {
        Heightfield field;

        @Override
        public java.util.Optional<Heightfield> load() {
            return java.util.Optional.ofNullable(field);
        }

        @Override
        public void save(Heightfield next) {
            field = next;
        }
    }

    /** 提取 opaque 分段中朝上面的逐顶点 RGB。 */
    private static List<int[]> topFaceColors(TileGeometry geometry) {
        float[] normals = geometry.opaque().normals();
        byte[] colors = geometry.opaque().colors();
        List<int[]> result = new ArrayList<>();
        for (int v = 0; v < normals.length / 3; v++) {
            if (normals[v * 3 + 1] == 1f) {
                result.add(new int[]{
                        colors[v * 3] & 0xFF, colors[v * 3 + 1] & 0xFF, colors[v * 3 + 2] & 0xFF});
            }
        }
        return result;
    }
}
