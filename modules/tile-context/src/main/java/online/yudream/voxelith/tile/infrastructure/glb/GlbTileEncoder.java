package online.yudream.voxelith.tile.infrastructure.glb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.domain.tile.TileGeometry.Segment;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * glTF 2.0 binary (glb) 编码器：
 * 每分段 POSITION/NORMAL/TEXCOORD_0 float32 LE + COLOR_0/_LIGHT ubyte normalized + uint32 索引，
 * 内嵌图集 PNG，NEAREST 采样。
 *
 * 分段即 primitive：opaque（alphaMode MASK）在前；translucent（水等）非空时追加
 * 第二 primitive，材质 alphaMode BLEND + baseColorFactor alpha（WATER_ALPHA）。
 *
 * 无纹理模式（atlasPng = null，供 LOD 纯色瓦片）：分段无 uv 时省略 TEXCOORD_0，
 * 不写图集 image/texture，材质无 baseColorTexture，颜色全部由 COLOR_0 顶点色承载。
 *
 * COLOR_0：逐顶点染色 RGB（无染色面为白色）；_LIGHT：逐顶点 (sky, block, ao)，
 * sky/block = 光照等级/15，ao = 遮挡级数/3（亮度 = 1 - 0.75×ao）。
 */
public final class GlbTileEncoder implements TileEncoder {

    private static final int MAGIC = 0x46546C67;      // "glTF"
    private static final int VERSION = 2;
    private static final int CHUNK_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;  // "BIN\0"
    private static final int COMPONENT_FLOAT = 5126;
    private static final int COMPONENT_UINT = 5125;
    private static final int COMPONENT_UBYTE = 5121;
    private static final int TARGET_ARRAY_BUFFER = 34962;
    private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;
    private static final int FILTER_NEAREST = 9728;

    /** 水面不透明度（BLEND baseColorFactor alpha）。 */
    private static final float WATER_ALPHA = 0.8f;

    private final Gson gson = new Gson();

    @Override
    public byte[] encode(TileGeometry geometry, byte[] atlasPng) {
        byte[] bin = buildBin(geometry, atlasPng);
        byte[] json = gson.toJson(buildJson(geometry, atlasPng)).getBytes(StandardCharsets.UTF_8);

        int jsonPadded = pad4(json.length);
        int binPadded = pad4(bin.length);
        int total = 12 + 8 + jsonPadded + 8 + binPadded;

        ByteBuffer glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(MAGIC).putInt(VERSION).putInt(total);
        glb.putInt(jsonPadded).putInt(CHUNK_JSON);
        glb.put(json);
        for (int i = json.length; i < jsonPadded; i++) {
            glb.put((byte) 0x20);
        }
        glb.putInt(binPadded).putInt(CHUNK_BIN);
        glb.put(bin);
        for (int i = bin.length; i < binPadded; i++) {
            glb.put((byte) 0);
        }
        return glb.array();
    }

    private static byte[] buildBin(TileGeometry geometry, byte[] atlasPng) {
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        writeSegment(bin, geometry.opaque());
        if (!geometry.translucent().isEmpty()) {
            writeSegment(bin, geometry.translucent());
        }
        if (atlasPng != null) {
            bin.writeBytes(atlasPng);
        }
        return bin.toByteArray();
    }

    private static void writeSegment(ByteArrayOutputStream bin, Segment segment) {
        writeFloats(bin, segment.positions());
        writeFloats(bin, segment.normals());
        writeFloats(bin, segment.uvs());
        // 4 顶点/quad → 颜色与光照各 12B/quad，天然 4 字节对齐，索引无需补位
        bin.writeBytes(segment.colors());
        bin.writeBytes(segment.lights());
        ByteBuffer indices = ByteBuffer.allocate(segment.indices().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int index : segment.indices()) {
            indices.putInt(index);
        }
        bin.writeBytes(indices.array());
    }

    private JsonObject buildJson(TileGeometry geometry, byte[] atlasPng) {
        boolean textured = atlasPng != null;
        int pngBytes = textured ? atlasPng.length : 0;
        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "yudream-voxelith");
        root.add("asset", asset);

        JsonArray bufferViews = new JsonArray();
        JsonArray accessors = new JsonArray();
        JsonArray primitives = new JsonArray();
        int offset = appendSegment(bufferViews, accessors, primitives, geometry.opaque(), 0, 0);
        if (!geometry.translucent().isEmpty()) {
            offset = appendSegment(bufferViews, accessors, primitives, geometry.translucent(), offset, 1);
        }
        if (textured) {
            bufferViews.add(bufferView(offset, pngBytes, 0));
            int pngViewIndex = bufferViews.size() - 1;

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", pngViewIndex);
            image.addProperty("mimeType", "image/png");
            JsonArray images = new JsonArray();
            images.add(image);
            root.add("images", images);

            JsonObject sampler = new JsonObject();
            sampler.addProperty("magFilter", FILTER_NEAREST);
            sampler.addProperty("minFilter", FILTER_NEAREST);
            JsonArray samplers = new JsonArray();
            samplers.add(sampler);
            root.add("samplers", samplers);

            JsonObject texture = new JsonObject();
            texture.addProperty("sampler", 0);
            texture.addProperty("source", 0);
            JsonArray textures = new JsonArray();
            textures.add(texture);
            root.add("textures", textures);
        }
        root.add("bufferViews", bufferViews);
        root.add("accessors", accessors);

        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", offset + pngBytes);
        JsonArray buffers = new JsonArray();
        buffers.add(buffer);
        root.add("buffers", buffers);

        JsonArray materials = new JsonArray();
        materials.add(material(textured ? "MASK" : "OPAQUE", 1f, textured));
        if (!geometry.translucent().isEmpty()) {
            materials.add(material("BLEND", WATER_ALPHA, textured));
        }
        root.add("materials", materials);

        JsonObject mesh = new JsonObject();
        mesh.add("primitives", primitives);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        root.add("meshes", meshes);

        JsonObject node = new JsonObject();
        node.addProperty("mesh", 0);
        JsonArray nodes = new JsonArray();
        nodes.add(node);
        root.add("nodes", nodes);

        JsonObject scene = new JsonObject();
        JsonArray sceneNodes = new JsonArray();
        sceneNodes.add(0);
        scene.add("nodes", sceneNodes);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        root.add("scenes", scenes);
        root.addProperty("scene", 0);

        return root;
    }

    /**
     * 追加一个分段的 bufferView/accessor/primitive（空分段跳过）。
     *
     * @return 追加后的 bin 偏移
     */
    private static int appendSegment(JsonArray bufferViews, JsonArray accessors, JsonArray primitives,
                                     Segment segment, int offset, int materialIndex) {
        if (segment.isEmpty()) {
            return offset;
        }
        int vertexCount = segment.vertexCount();
        boolean hasUv = segment.uvs().length > 0;
        int posBytes = segment.positions().length * 4;
        int normalBytes = segment.normals().length * 4;
        int uvBytes = segment.uvs().length * 4;
        int colorBytes = segment.colors().length;
        int lightBytes = segment.lights().length;
        int indexBytes = segment.indices().length * 4;

        int viewBase = bufferViews.size();
        bufferViews.add(bufferView(offset, posBytes, TARGET_ARRAY_BUFFER));
        bufferViews.add(bufferView(offset + posBytes, normalBytes, TARGET_ARRAY_BUFFER));
        int uvView = -1;
        int colorOffset = offset + posBytes + normalBytes;
        if (hasUv) {
            uvView = bufferViews.size();
            bufferViews.add(bufferView(colorOffset, uvBytes, TARGET_ARRAY_BUFFER));
            colorOffset += uvBytes;
        }
        bufferViews.add(bufferView(colorOffset, colorBytes, TARGET_ARRAY_BUFFER));
        bufferViews.add(bufferView(colorOffset + colorBytes, lightBytes, TARGET_ARRAY_BUFFER));
        int indexOffset = colorOffset + colorBytes + lightBytes;
        bufferViews.add(bufferView(indexOffset, indexBytes, TARGET_ELEMENT_ARRAY_BUFFER));

        int accessorBase = accessors.size();
        float[] minMax = positionMinMax(segment.positions());
        accessors.add(accessor(viewBase, COMPONENT_FLOAT, vertexCount, "VEC3", false,
                new float[]{minMax[0], minMax[1], minMax[2]},
                new float[]{minMax[3], minMax[4], minMax[5]}));
        accessors.add(accessor(viewBase + 1, COMPONENT_FLOAT, vertexCount, "VEC3", false, null, null));
        int uvAccessor = -1;
        if (hasUv) {
            uvAccessor = accessors.size();
            accessors.add(accessor(uvView, COMPONENT_FLOAT, vertexCount, "VEC2", false, null, null));
        }
        int colorAccessor = accessors.size();
        accessors.add(accessor(bufferViews.size() - 3, COMPONENT_UBYTE, vertexCount, "VEC3", true, null, null));
        accessors.add(accessor(bufferViews.size() - 2, COMPONENT_UBYTE, vertexCount, "VEC3", true, null, null));
        accessors.add(accessor(bufferViews.size() - 1, COMPONENT_UINT, segment.indices().length, "SCALAR", false, null, null));

        JsonObject attributes = new JsonObject();
        attributes.addProperty("POSITION", accessorBase);
        attributes.addProperty("NORMAL", accessorBase + 1);
        if (hasUv) {
            attributes.addProperty("TEXCOORD_0", uvAccessor);
        }
        attributes.addProperty("COLOR_0", colorAccessor);
        attributes.addProperty("_LIGHT", colorAccessor + 1);
        JsonObject primitive = new JsonObject();
        primitive.add("attributes", attributes);
        primitive.addProperty("indices", colorAccessor + 2);
        primitive.addProperty("material", materialIndex);
        primitives.add(primitive);

        return indexOffset + indexBytes;
    }

    private static JsonObject material(String alphaMode, float alpha, boolean textured) {
        JsonObject pbr = new JsonObject();
        if (textured) {
            JsonObject baseColorTexture = new JsonObject();
            baseColorTexture.addProperty("index", 0);
            pbr.add("baseColorTexture", baseColorTexture);
        }
        pbr.addProperty("metallicFactor", 0);
        pbr.addProperty("roughnessFactor", 1);
        if (alpha < 1f) {
            JsonArray baseColorFactor = new JsonArray();
            baseColorFactor.add(1f);
            baseColorFactor.add(1f);
            baseColorFactor.add(1f);
            baseColorFactor.add(alpha);
            pbr.add("baseColorFactor", baseColorFactor);
        }
        JsonObject material = new JsonObject();
        material.add("pbrMetallicRoughness", pbr);
        material.addProperty("alphaMode", alphaMode);
        if ("MASK".equals(alphaMode)) {
            material.addProperty("alphaCutoff", 0.5);
        }
        return material;
    }

    private static JsonObject bufferView(int byteOffset, int byteLength, int target) {
        JsonObject view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", byteOffset);
        view.addProperty("byteLength", byteLength);
        if (target != 0) {
            view.addProperty("target", target);
        }
        return view;
    }

    private static JsonObject accessor(int bufferView, int componentType, int count, String type,
                                       boolean normalized, float[] min, float[] max) {
        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", bufferView);
        accessor.addProperty("componentType", componentType);
        accessor.addProperty("count", count);
        accessor.addProperty("type", type);
        if (normalized) {
            accessor.addProperty("normalized", true);
        }
        if (min != null) {
            accessor.add("min", floatArray(min));
            accessor.add("max", floatArray(max));
        }
        return accessor;
    }

    private static JsonArray floatArray(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    /** @return {minX,minY,minZ,maxX,maxY,maxZ}（瓦片局部坐标，glTF 规范要求 POSITION 带 min/max） */
    private static float[] positionMinMax(float[] positions) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < positions.length; i += 3) {
            minX = Math.min(minX, positions[i]);
            maxX = Math.max(maxX, positions[i]);
            minY = Math.min(minY, positions[i + 1]);
            maxY = Math.max(maxY, positions[i + 1]);
            minZ = Math.min(minZ, positions[i + 2]);
            maxZ = Math.max(maxZ, positions[i + 2]);
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static void writeFloats(ByteArrayOutputStream out, float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        out.writeBytes(buffer.array());
    }

    private static int pad4(int length) {
        return (length + 3) & ~3;
    }
}
