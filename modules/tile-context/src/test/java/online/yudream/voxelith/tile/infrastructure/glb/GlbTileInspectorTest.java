package online.yudream.voxelith.tile.infrastructure.glb;

import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlbTileInspectorTest {

    private static TileGeometry oneQuad() {
        byte[] colors = new byte[12];
        byte[] lights = new byte[12];
        return new TileGeometry(
                new TileGeometry.Segment(
                        new float[]{0, 4, 0, 2, 4, 0, 2, 4, 3, 0, 4, 3},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                        new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                        colors, lights,
                        new int[]{0, 1, 2, 0, 2, 3}),
                TileGeometry.Segment.empty(),
                new float[]{0, 4, 0}, new float[]{2, 4, 3});
    }

    @Test
    void inspectsUncompressedPositionBoundsAndQuads(@TempDir Path dir) throws Exception {
        byte[] glb = new GlbTileEncoder().encode(oneQuad(), new byte[]{1, 2, 3, 4});
        Path file = dir.resolve("0.glb");
        Files.write(file, glb);

        GlbTileInspector.GeometryInfo info = GlbTileInspector.inspect(file);
        assertEquals(1, info.quads());
        assertEquals(0f, info.localMin()[0], 1e-5f);
        assertEquals(4f, info.localMin()[1], 1e-5f);
        assertEquals(0f, info.localMin()[2], 1e-5f);
        assertEquals(2f, info.localMax()[0], 1e-5f);
        assertEquals(4f, info.localMax()[1], 1e-5f);
        assertEquals(3f, info.localMax()[2], 1e-5f);
    }

    @Test
    void inspectsQuantizedBoundsUsingNodeScale(@TempDir Path dir) throws Exception {
        byte[] glb = new GlbTileEncoder().encode(oneQuad(), new byte[]{1, 2, 3, 4}, EncodeOptions.quantized());
        Path file = dir.resolve("q.glb");
        Files.write(file, glb);

        GlbTileInspector.GeometryInfo info = GlbTileInspector.inspect(file);
        assertEquals(1, info.quads());
        assertEquals(0f, info.localMin()[0], 0.02f);
        assertEquals(4f, info.localMin()[1], 0.02f);
        assertEquals(2f, info.localMax()[0], 0.02f);
        assertEquals(3f, info.localMax()[2], 0.02f);
    }
}
