package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * LOD 瓦片导出的两种纹理形态：逐瓦片内嵌色图（UV 为瓦片局部 0..1）与
 * 层级图集页（UV 重映射进槽位、不内嵌 PNG）。这是图集化最关键的一步——
 * UV 若没重映射，瓦片会去整张图集上取 0..1 全幅，渲染成乱色。
 */
class VertexColorTileExporterTest {

    private static final class RecordingEncoder implements TileEncoder {
        final List<TileGeometry> geometries = new ArrayList<>();
        final List<byte[]> embedded = new ArrayList<>();

        @Override
        public byte[] encode(TileGeometry geometry, byte[] atlasPng) {
            geometries.add(geometry);
            embedded.add(atlasPng);
            return new byte[]{1, 2, 3};
        }
    }

    private static final class NoopSink implements TileArtifactSink {
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
    }

    /** 一片覆盖瓦片局部 (0,0)-(8,8) 的顶面。 */
    private static List<VertexColorQuadData> topQuad() {
        return List.of(new VertexColorQuadData(
                new float[]{0, 64, 8, 8, 64, 8, 8, 64, 0, 0, 64, 0},
                new float[]{0, 1, 0},
                0xFF102030,
                new float[]{0, 1, 1, 1, 1, 0, 0, 0}));
    }

    @Test
    void embeddedColormapKeepsTileLocalUvs() {
        RecordingEncoder encoder = new RecordingEncoder();
        VertexColorTileExporter exporter = new VertexColorTileExporter(encoder, new NoopSink());

        exporter.export(Path.of("build/x"), new TilePos(1, 0, 0), topQuad(),
                new byte[]{(byte) 0x89, 'P'});

        assertThat(encoder.embedded.getFirst()).isNotNull();
        // 内嵌形态：UV 原样保留（瓦片局部 0..1）
        assertThat(encoder.geometries.getFirst().opaque().uvs())
                .containsExactly(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f);
    }

    @Test
    void atlasUvRectRemapsAndDropsEmbeddedPng() {
        RecordingEncoder encoder = new RecordingEncoder();
        VertexColorTileExporter exporter = new VertexColorTileExporter(encoder, new NoopSink());

        // 槽位 (0.25,0.5)-(0.5,0.75)：每个局部 UV 线性映射进该矩形
        exporter.export(Path.of("build/x"), new TilePos(1, 0, 0), topQuad(), null,
                new float[]{0.25f, 0.5f, 0.5f, 0.75f});

        // 不内嵌 PNG：纹理由该层图集页提供
        assertThat(encoder.embedded.getFirst()).isNull();
        assertThat(encoder.geometries.getFirst().opaque().uvs())
                .containsExactly(0.25f, 0.75f, 0.5f, 0.75f, 0.5f, 0.5f, 0.25f, 0.5f);
        // 有色图时 COLOR_0 只承载方向明暗（顶面 = 255）
        assertThat(encoder.geometries.getFirst().opaque().colors()[0]).isEqualTo((byte) 255);
    }

    @Test
    void withoutTextureUsesVertexColorsAndNoUvs() {
        RecordingEncoder encoder = new RecordingEncoder();
        VertexColorTileExporter exporter = new VertexColorTileExporter(encoder, new NoopSink());

        exporter.export(Path.of("build/x"), new TilePos(2, 0, 0), topQuad());

        assertThat(encoder.embedded.getFirst()).isNull();
        assertThat(encoder.geometries.getFirst().opaque().uvs()).isEmpty();
        // 纯顶点色：COLOR_0 承载完整颜色 0x102030
        byte[] colors = encoder.geometries.getFirst().opaque().colors();
        assertThat(new int[]{colors[0] & 0xFF, colors[1] & 0xFF, colors[2] & 0xFF})
                .containsExactly(0x10, 0x20, 0x30);
    }

    @Test
    void rejectsMalformedUvRect() {
        VertexColorTileExporter exporter = new VertexColorTileExporter(new RecordingEncoder(), new NoopSink());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> exporter.export(
                        Path.of("build/x"), new TilePos(1, 0, 0), topQuad(), null, new float[]{0, 0}))
                .withMessageContaining("uvRect");
    }
}
