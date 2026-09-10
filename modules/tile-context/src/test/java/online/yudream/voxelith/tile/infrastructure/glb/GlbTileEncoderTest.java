package online.yudream.voxelith.tile.infrastructure.glb;

import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlbTileEncoderTest {

    private static TileGeometry oneQuad() {
        byte[] colors = {(byte) 0x91, (byte) 0xBD, 0x59, (byte) 0x91, (byte) 0xBD, 0x59,
                (byte) 0x91, (byte) 0xBD, 0x59, (byte) 0x91, (byte) 0xBD, 0x59};
        byte[] lights = new byte[12];
        for (int v = 0; v < 4; v++) {
            lights[v * 3] = (byte) 0xFF;      // sky = 15 × 17
            lights[v * 3 + 2] = (byte) 85;    // ao 级数 1 × 85
        }
        return new TileGeometry(
                new TileGeometry.Segment(
                        new float[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                        new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                        colors, lights,
                        new int[]{0, 1, 2, 0, 2, 3}),
                TileGeometry.Segment.empty(),
                new float[]{0, 0, 0}, new float[]{1, 0, 1});
    }

    private static TileGeometry oneQuadWithWater() {
        TileGeometry.Segment opaque = oneQuad().opaque();
        TileGeometry.Segment water = new TileGeometry.Segment(
                new float[]{0, 0.9f, 0, 1, 0.9f, 0, 1, 0.9f, 1, 0, 0.9f, 1},
                new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new byte[12], new byte[12],
                new int[]{0, 1, 2, 0, 2, 3});
        return new TileGeometry(opaque, water, new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }

    private static byte[] fakePng() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    }

    @Test
    void glbHeaderAndChunksAreValid() {
        byte[] glb = new GlbTileEncoder().encode(oneQuad(), fakePng());

        ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46546C67, head.getInt(0), "magic");
        assertEquals(2, head.getInt(4), "version");
        assertEquals(glb.length, head.getInt(8), "total length");

        int jsonLength = head.getInt(12);
        assertEquals(0x4E4F534A, head.getInt(16), "JSON chunk type");
        String json = new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"POSITION\""), json);
        assertTrue(json.contains("\"TEXCOORD_0\""), json);
        assertTrue(json.contains("\"NORMAL\""), json);
        assertTrue(json.contains("image/png"), json);
        assertTrue(json.contains("\"alphaMode\":\"MASK\""), json);
        // 染色与烘焙光照属性：ubyte normalized
        assertTrue(json.contains("\"COLOR_0\":3"), json);
        assertTrue(json.contains("\"_LIGHT\":4"), json);
        assertTrue(json.contains("\"componentType\":5121"), json);
        assertTrue(json.contains("\"normalized\":true"), json);
        // POSITION accessor 必须带 min/max（瓦片局部坐标）
        assertTrue(json.contains("\"min\":[0.0,0.0,0.0]"), json);
        assertTrue(json.contains("\"max\":[1.0,0.0,1.0]"), json);

        int binChunkOffset = 20 + jsonLength;
        int binLength = head.getInt(binChunkOffset);
        assertEquals(0x004E4942, head.getInt(binChunkOffset + 4), "BIN chunk type");
        // bin = 12 floats 位置 + 12 floats 法线 + 8 floats uv + 12B 颜色 + 12B 光照 + 6 uint 索引 + 8 字节 png
        int expectedBin = 12 * 4 + 12 * 4 + 8 * 4 + 12 + 12 + 6 * 4 + 8;
        assertEquals((expectedBin + 3) & ~3, binLength);
    }

    @Test
    void emptyGeometryEncodesMeshWithoutPrimitives() {
        byte[] glb = new GlbTileEncoder().encode(
                new TileGeometry(TileGeometry.Segment.empty(), TileGeometry.Segment.empty(),
                        new float[]{0, 0, 0}, new float[]{0, 0, 0}), fakePng());
        ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(glb.length, head.getInt(8));
        int jsonLength = head.getInt(12);
        String json = new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"primitives\":[]"), json);
    }

    @Test
    void translucentSegmentEmitsSecondPrimitiveWithBlendMaterial() {
        byte[] glb = new GlbTileEncoder().encode(oneQuadWithWater(), fakePng());

        ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = head.getInt(12);
        String json = new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
        // 两个 primitive（opaque + translucent）与 BLEND 材质
        assertTrue(json.contains("\"alphaMode\":\"MASK\""), json);
        assertTrue(json.contains("\"alphaMode\":\"BLEND\""), json);
        assertTrue(json.contains("\"material\":1"), json);
        // POSITION accessor min/max 覆盖到透明段顶点
        assertTrue(json.contains("\"min\":[0.0,0.9,0.0]"), json);
    }

    /** 无 uv 的纯色分段（LOD 瓦片形态）。 */
    private static TileGeometry vertexColorQuad() {
        byte[] colors = new byte[12];
        byte[] lights = new byte[12];
        for (int v = 0; v < 4; v++) {
            colors[v * 3] = (byte) 0x80;
            colors[v * 3 + 1] = (byte) 0x80;
            colors[v * 3 + 2] = (byte) 0x80;
            lights[v * 3] = (byte) 0xFF;
        }
        return new TileGeometry(
                new TileGeometry.Segment(
                        new float[]{0, 64, 0, 2, 64, 0, 2, 64, 2, 0, 64, 2},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                        new float[0],
                        colors, lights,
                        new int[]{0, 1, 2, 0, 2, 3}),
                TileGeometry.Segment.empty(),
                new float[]{0, 64, 0}, new float[]{2, 64, 2});
    }

    @Test
    void texturelessQuadEncodesOpaqueVertexColorMaterial() {
        byte[] glb = new GlbTileEncoder().encode(vertexColorQuad(), null);

        ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(glb.length, head.getInt(8), "total length");
        int jsonLength = head.getInt(12);
        String json = new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();

        // 无纹理：无 TEXCOORD_0、无图像/采样器/纹理资源、OPAQUE 材质不带 baseColorTexture
        assertFalse(json.contains("TEXCOORD_0"), json);
        assertFalse(json.contains("image/png"), json);
        assertFalse(json.contains("baseColorTexture"), json);
        assertTrue(json.contains("\"alphaMode\":\"OPAQUE\""), json);
        // 纯色顶点色与合成光照仍在（无 uv 视图，COLOR_0 前移）
        assertTrue(json.contains("\"COLOR_0\":2"), json);
        assertTrue(json.contains("\"_LIGHT\":3"), json);

        // bin = 12 floats 位置 + 12 floats 法线 + 12B 颜色 + 12B 光照 + 6 uint 索引（无 uv、无 png）
        int binChunkOffset = 20 + jsonLength;
        int binLength = head.getInt(binChunkOffset);
        int expectedBin = 12 * 4 + 12 * 4 + 12 + 12 + 6 * 4;
        assertEquals((expectedBin + 3) & ~3, binLength);
    }
}
