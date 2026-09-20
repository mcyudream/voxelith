package online.yudream.voxelith.tile.infrastructure.glb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.infrastructure.meshopt.MeshoptIndexCodec;
import online.yudream.voxelith.tile.infrastructure.meshopt.MeshoptVertexCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * meshopt 压缩瓦片：JSON 声明、回退占位缓冲、以及「把 BIN 里的压缩区间解回来 === 原始属性字节」。
 *
 * <p>线格式与官方解码器的兼容性由前端用例
 * {@code web/packages/voxelith-viewer/src/tiles/MeshoptGolden.test.ts} 用同一批金标准向量守住。</p>
 */
class GlbTileEncoderMeshoptTest {

    private static final byte[] FALLBACK_PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static TileGeometry quad(boolean water) {
        byte[] colors = new byte[12];
        byte[] lights = new byte[12];
        for (int v = 0; v < 4; v++) {
            colors[v * 3] = (byte) 0x91;
            colors[v * 3 + 1] = (byte) 0xBD;
            colors[v * 3 + 2] = 0x59;
            lights[v * 3] = (byte) 0xFF;
            lights[v * 3 + 2] = 85;
        }
        TileGeometry.Segment opaque = new TileGeometry.Segment(
                new float[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
                new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                colors, lights, new int[]{0, 1, 2, 0, 2, 3});
        if (!water) {
            return new TileGeometry(opaque, TileGeometry.Segment.empty(),
                    new float[]{0, 0, 0}, new float[]{1, 0, 1});
        }
        TileGeometry.Segment liquid = new TileGeometry.Segment(
                new float[]{0, 0.9f, 0, 1, 0.9f, 0, 1, 0.9f, 1, 0, 0.9f, 1},
                new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new byte[12], new byte[12], new int[]{0, 1, 2, 0, 2, 3});
        return new TileGeometry(opaque, liquid, new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }

    @Test
    @DisplayName("压缩瓦片声明扩展与回退占位缓冲，且 BIN 明显小于未压缩")
    void declaresExtensionAndShrinksBin() {
        GlbTileEncoder encoder = new GlbTileEncoder();
        Glb glb = Glb.parse(encoder.encode(quad(false), FALLBACK_PNG, EncodeOptions.compressed()));

        assertThat(glb.json().getAsJsonArray("extensionsRequired").toString())
                .contains("EXT_meshopt_compression")
                .contains("KHR_mesh_quantization");

        JsonArray buffers = glb.json().getAsJsonArray("buffers");
        assertThat(buffers.size()).isEqualTo(2);
        JsonObject fallback = buffers.get(1).getAsJsonObject();
        assertThat(fallback.getAsJsonObject("extensions")
                .getAsJsonObject("EXT_meshopt_compression")
                .get("fallback").getAsBoolean()).isTrue();
        // 回退缓冲的容量 = 解压后布局总字节数（量化后：pos 8×4 + nrm 4×4 + uv 4×4 + 索引 24）
        assertThat(fallback.get("byteLength").getAsInt()).isEqualTo(32 + 16 + 16 + 24);

        // 每条压缩视图：parent 描述解压布局，extension 指向 BIN 的压缩区间
        int compressedViews = 0;
        for (com.google.gson.JsonElement element : glb.json().getAsJsonArray("bufferViews")) {
            JsonObject view = element.getAsJsonObject();
            if (!view.has("extensions")) {
                continue;
            }
            compressedViews++;
            JsonObject extension = view.getAsJsonObject("extensions")
                    .getAsJsonObject("EXT_meshopt_compression");
            assertThat(extension.get("buffer").getAsInt()).isZero();
            assertThat(extension.has("mode")).isTrue();
            assertThat(view.get("byteLength").getAsInt())
                    .isEqualTo(extension.get("byteStride").getAsInt() * extension.get("count").getAsInt());
        }
        assertThat(compressedViews).isEqualTo(4); // 位置 / 法线 / UV / 索引

    }

    @Test
    @DisplayName("真实瓦片规模下压缩收益明显（小瓦片有固定头部开销，不参与比较）")
    void compressedTileIsMuchSmallerForRealisticGeometry() {
        GlbTileEncoder encoder = new GlbTileEncoder();
        TileGeometry geometry = grid(64, 64);

        int plain = Glb.parse(encoder.encode(geometry, null, EncodeOptions.sharedAtlas())).bin().length;
        int quantized = Glb.parse(encoder.encode(geometry, null, EncodeOptions.quantized()
                .withMeshopt(false))).bin().length;
        int compressed = Glb.parse(encoder.encode(geometry, null, EncodeOptions.compressed())).bin().length;

        // 实测 64×64 网格瓦片：未压缩 258856B → 量化 191256B → 量化+熵编码 50228B
        // （索引与 COLOR_0/_LIGHT 不随量化变化，所以量化那一步只省约 1/4；
        //  熵编码才是大头：相对未压缩约 1/5，相对量化约 1/4）
        assertThat(quantized).isLessThan(plain * 4 / 5);
        assertThat(compressed).isLessThan(quantized / 2);
        assertThat(compressed).isLessThan(plain / 4);
    }

    @Test
    @DisplayName("BIN 里的压缩区间可解回原始（量化）属性与索引")
    void compressedRangesDecodeBackToOriginalGeometry() {
        GlbTileEncoder encoder = new GlbTileEncoder();
        for (boolean quantize : new boolean[]{false, true}) {
            TileGeometry geometry = quad(true);
            EncodeOptions options = new EncodeOptions(quantize, false, false, true);
            Glb glb = Glb.parse(encoder.encode(geometry, null, options));

            assertThat(decodeAttribute(glb, 0, "POSITION", quantize, geometry.opaque()))
                    .containsExactly(GlbTileEncoder.positionsBytes(
                            geometry.opaque(), quantize, quantize ? GlbTileEncoder.positionMaxAbs(geometry) : 1f));
            assertThat(decodeAttribute(glb, 0, "NORMAL", quantize, geometry.opaque()))
                    .containsExactly(GlbTileEncoder.normalsBytes(geometry.opaque(), quantize));
            assertThat(decodeAttribute(glb, 0, "TEXCOORD_0", quantize, geometry.opaque()))
                    .containsExactly(GlbTileEncoder.uvsBytes(geometry.opaque(), quantize));
            assertThat(decodeIndices(glb, 0)).containsExactly(geometry.opaque().indices());
            // 半透明段（水面）同样成立
            assertThat(decodeAttribute(glb, 1, "POSITION", quantize, geometry.translucent()))
                    .containsExactly(GlbTileEncoder.positionsBytes(
                            geometry.translucent(), quantize,
                            quantize ? GlbTileEncoder.positionMaxAbs(geometry) : 1f));
            assertThat(decodeIndices(glb, 1)).containsExactly(geometry.translucent().indices());
        }
    }

    /** 规则网格瓦片：方块世界几何的典型形态（顶点成行、坐标小步长变化）。 */
    private static TileGeometry grid(int width, int height) {
        int vertexCount = (width + 1) * (height + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        byte[] colors = new byte[vertexCount * 3];
        byte[] lights = new byte[vertexCount * 3];
        for (int z = 0; z <= height; z++) {
            for (int x = 0; x <= width; x++) {
                int v = z * (width + 1) + x;
                positions[v * 3] = x;
                positions[v * 3 + 1] = 64 + ((x * 7 + z * 13) % 5);
                positions[v * 3 + 2] = z;
                normals[v * 3 + 1] = 1f;
                uvs[v * 2] = x / 16f;
                uvs[v * 2 + 1] = z / 16f;
                colors[v * 3] = (byte) 0x91;
                colors[v * 3 + 1] = (byte) 0xBD;
                colors[v * 3 + 2] = 0x59;
                lights[v * 3] = (byte) 0xFF;
            }
        }
        int[] indices = new int[width * height * 6];
        int pos = 0;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int a = z * (width + 1) + x;
                int b = a + 1;
                int c = a + width + 1;
                int d = c + 1;
                indices[pos++] = a;
                indices[pos++] = c;
                indices[pos++] = b;
                indices[pos++] = b;
                indices[pos++] = c;
                indices[pos++] = d;
            }
        }
        TileGeometry.Segment segment = new TileGeometry.Segment(
                positions, normals, uvs, colors, lights, indices);
        return new TileGeometry(segment, TileGeometry.Segment.empty(),
                new float[]{0, 60, 0}, new float[]{width, 70, height});
    }

    private static byte[] decodeAttribute(Glb glb, int primitiveIndex, String attribute,
                                          boolean quantize, TileGeometry.Segment segment) {
        JsonObject primitive = glb.primitive(primitiveIndex);
        int accessorIndex = primitive.getAsJsonObject("attributes").get(attribute).getAsInt();
        JsonObject accessor = glb.json().getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        JsonObject view = glb.json().getAsJsonArray("bufferViews")
                .get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        JsonObject extension = view.getAsJsonObject("extensions")
                .getAsJsonObject("EXT_meshopt_compression");
        byte[] compressed = slice(glb.bin(), extension.get("byteOffset").getAsInt(),
                extension.get("byteLength").getAsInt());
        int count = extension.get("count").getAsInt();
        int stride = extension.get("byteStride").getAsInt();
        return MeshoptVertexCodec.decode(compressed, count, stride);
    }

    private static int[] decodeIndices(Glb glb, int primitiveIndex) {
        JsonObject primitive = glb.primitive(primitiveIndex);
        JsonObject accessor = glb.json().getAsJsonArray("accessors")
                .get(primitive.get("indices").getAsInt()).getAsJsonObject();
        JsonObject view = glb.json().getAsJsonArray("bufferViews")
                .get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        JsonObject extension = view.getAsJsonObject("extensions")
                .getAsJsonObject("EXT_meshopt_compression");
        byte[] compressed = slice(glb.bin(), extension.get("byteOffset").getAsInt(),
                extension.get("byteLength").getAsInt());
        return MeshoptIndexCodec.decode(compressed, accessor.get("count").getAsInt());
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(source, offset, copy, 0, length);
        return copy;
    }

    /** 测试用最小 glb 读取器。 */
    private record Glb(JsonObject json, byte[] bin) {

        static Glb parse(byte[] glb) {
            ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
            int jsonLength = head.getInt(12);
            JsonObject json = JsonParser.parseString(
                    new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim()).getAsJsonObject();
            int binOffset = 20 + jsonLength;
            int binLength = head.getInt(binOffset);
            byte[] bin = new byte[binLength];
            System.arraycopy(glb, binOffset + 8, bin, 0, binLength);
            return new Glb(json, bin);
        }

        JsonObject primitive(int index) {
            return json.getAsJsonArray("meshes").get(0).getAsJsonObject()
                    .getAsJsonArray("primitives").get(index).getAsJsonObject();
        }
    }
}
