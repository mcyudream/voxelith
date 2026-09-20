package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.TileArtifactSink;
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

    /** 记录落盘调用的 sink 桩：瓦片 glb 由 encoder 记录，LOD 图集页在此记录。 */
    private static final class RecordingSink implements TileArtifactSink {
        final List<Integer> lodAtlasLevels = new ArrayList<>();
        final List<byte[]> lodAtlasPngs = new ArrayList<>();

        @Override
        public Path writeTile(Path outputDir, TilePos pos, byte[] glb) {
            return outputDir.resolve("tile.glb");
        }

        @Override
        public Path writeAtlas(Path outputDir, byte[] png) {
            return outputDir.resolve("atlas.png");
        }

        @Override
        public String writeLodAtlas(Path outputDir, int level, byte[] png) {
            lodAtlasLevels.add(level);
            lodAtlasPngs.add(png);
            return TileArtifactSink.lodAtlasUrl(level);
        }

        @Override
        public Path writeReport(Path outputDir, List<TileOutcome.TileSummary> tiles,
                                int textureCount, int atlasSize) {
            return outputDir.resolve("report.json");
        }
    }

    private static VertexColorTileExporter exporter(RecordingEncoder encoder) {
        return exporter(encoder, new RecordingSink());
    }

    private static VertexColorTileExporter exporter(RecordingEncoder encoder, RecordingSink sink) {
        return new VertexColorTileExporter(encoder, sink);
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
                quad(2, 70, 0, new float[]{0, 1, 0}, "minecraft:block/stone", 0xFF0000),
                // 细面（花）不得进高度场
                new BakedQuadData(
                        new float[]{0.4f, 72, 0.4f, 0.6f, 72, 0.4f, 0.6f, 72, 0.6f, 0.4f, 72, 0.6f},
                        new float[8], new float[]{0, 1, 0}, "minecraft:block/stone", -1, false, "up",
                        0xFF0000, new byte[4], new byte[4], new byte[4], false)
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
    void entityGeometryIsExcludedFromSurfaceHeightfield() {
        RecordingEncoder encoder = new RecordingEncoder();
        GenerateLodPyramidUseCase useCase =
                new GenerateLodPyramidUseCase(graySampler(), exporter(encoder));

        // 盔甲架的底座顶面：朝上、投影面积 0.5625（过 0.5 的线），但它是实体几何。
        // 不排除的话地表高度会被抬到实体所在的 y，航拍色也会变成实体贴图。
        float[] baseTop = {0, 201, 0, 0.75f, 201, 0, 0.75f, 201, 0.75f, 0, 201, 0.75f};
        BakedQuadData armorStandTop = new BakedQuadData(baseTop, new float[8],
                new float[]{0, 1, 0}, "voxelith:entity/armor_stand", -1, true,
                BakedQuadData.NON_TERRAIN_FACE, -1,
                new byte[4], new byte[4], new byte[4], false);

        InMemoryHeightfieldStore store = new InMemoryHeightfieldStore();
        useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                armorStandTop
        ), Path.of("build/lod-test"), 0, store, List.of()));

        assertThat(store.field.topY(0, 0)).as("地表高度仍是方块，不被实体抬高").isEqualTo(64f);
        assertThat(store.field.topY(0, 0)).isNotEqualTo(201f);
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

    @Test
    void fullRunPacksColormapsIntoLevelAtlasPageInsteadOfEmbeddingPerTile() {
        RecordingEncoder encoder = new RecordingEncoder();
        RecordingSink sink = new RecordingSink();
        GenerateLodPyramidUseCase useCase = new GenerateLodPyramidUseCase(
                graySampler(), exporter(encoder, sink), (w, h, argb) -> new byte[]{(byte) 0x89, 'P'});

        LodOutcome outcome = useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)
        ), Path.of("build/lod-test"), 0));

        // 该层只写一页图集，不再逐瓦片内嵌 PNG
        assertThat(sink.lodAtlasLevels).containsExactly(1);
        assertThat(encoder.atlases.getFirst()).isNull();
        assertThat(outcome.atlasPages()).hasSize(1);
        assertThat(outcome.atlasPages().getFirst().level()).isEqualTo(1);
        assertThat(outcome.atlasPages().getFirst().url()).isEqualTo("tiles/lod/1/lod-atlas.png");
        // 单瓦片网格：槽位 64（L1 基础值，整页 64×64 未触及 4096 上限）
        assertThat(outcome.atlasPages().getFirst().slotSize()).isEqualTo(64);
        assertThat(outcome.atlasPages().getFirst().sha1()).isNotBlank();

        // 顶点 UV 已重映射进图集槽位：落在 UV 矩形内，且不再从 0 起（证明做了重映射）
        TileGeometry geometry = encoder.geometries.getFirst();
        float[] uvs = geometry.opaque().uvs();
        assertThat(uvs).isNotEmpty();
        float inset = 0.5f / 64f;
        for (int v = 0; v < uvs.length; v += 2) {
            assertThat(uvs[v]).isBetween(inset, 1f - inset);
            assertThat(uvs[v + 1]).isBetween(inset, 1f - inset);
        }
        // 有色图时 COLOR_0 只承载方向明暗
        assertThat(topFaceColors(geometry))
                .allSatisfy(c -> assertThat(c).containsExactly(255, 255, 255));
    }

    @Test
    void incrementalRunKeepsPerTileEmbeddedColormapAndEmitsNoAtlasPage() {
        RecordingEncoder encoder = new RecordingEncoder();
        RecordingSink sink = new RecordingSink();
        GenerateLodPyramidUseCase useCase = new GenerateLodPyramidUseCase(
                graySampler(), exporter(encoder, sink), (w, h, argb) -> new byte[]{(byte) 0x89, 'P'});
        InMemoryHeightfieldStore store = new InMemoryHeightfieldStore();

        // 增量：手上只有被替换 region 的栅格，重拼整页会抹掉其余区域 → 不能出图集页
        LodOutcome incremental = useCase.generate(new LodCommand(
                meshes(quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)),
                Path.of("build/lod-test"), 0, store, List.of(new RegionPos(0, 0))));

        assertThat(sink.lodAtlasLevels).isEmpty();
        assertThat(incremental.atlasPages()).isEmpty();
        // 退回逐瓦片内嵌色图（尺寸为 AerialRaster.TILE_TEXTURE_SIZE）
        assertThat(encoder.atlases.getFirst()).isNotNull();
    }

    @Test
    void siblingTilesLandInDisjointAtlasSlots() {
        RecordingEncoder encoder = new RecordingEncoder();
        RecordingSink sink = new RecordingSink();
        GenerateLodPyramidUseCase useCase = new GenerateLodPyramidUseCase(
                graySampler(), exporter(encoder, sink), (w, h, argb) -> new byte[]{(byte) 0x89, 'P'});

        // 两片相邻的 L1 瓦片（覆盖 64 方块/片）：x=0 → tx 0，x=100 → tx 1
        LodOutcome outcome = useCase.generate(new LodCommand(meshes(
                quad(0, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1),
                quad(100, 64, 0, new float[]{0, 1, 0}, "minecraft:block/stone", -1)
        ), Path.of("build/lod-test"), 1));

        assertThat(outcome.tiles().stream().map(t -> t.pos().x()).distinct().sorted())
                .containsExactly(0, 1);
        assertThat(sink.lodAtlasLevels).containsExactly(1);

        // 两片瓦片的 u 区间必须互不重叠：否则相邻瓦片会采到对方色图（串色）
        float[] first = encoder.geometries.get(0).opaque().uvs();
        float[] second = encoder.geometries.get(1).opaque().uvs();
        assertThat(maxOf(first, 0)).isLessThan(minOf(second, 0));
    }

    private static float minOf(float[] uvs, int offset) {
        float min = Float.MAX_VALUE;
        for (int i = offset; i < uvs.length; i += 2) {
            min = Math.min(min, uvs[i]);
        }
        return min;
    }

    private static float maxOf(float[] uvs, int offset) {
        float max = -Float.MAX_VALUE;
        for (int i = offset; i < uvs.length; i += 2) {
            max = Math.max(max, uvs[i]);
        }
        return max;
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
