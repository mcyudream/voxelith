package online.yudream.voxelith.tile.infrastructure.glb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.domain.tile.TileGeometry.Segment;
import online.yudream.voxelith.tile.infrastructure.meshopt.MeshoptIndexCodec;
import online.yudream.voxelith.tile.infrastructure.meshopt.MeshoptVertexCodec;

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
 *
 * <p>{@link EncodeOptions#meshopt()} 时属性与索引改写为 {@code EXT_meshopt_compression}：
 * 每个属性一条 bufferView（mode ATTRIBUTES，byteStride = 元素字节数），索引一条
 * （mode TRIANGLES，byteStride 4，count = 索引数）。COLOR_0/_LIGHT 元素宽 3 字节，
 * 不满足 meshopt「byteStride 必须被 4 整除」的约束，保持原样写在 BIN 里。</p>
 *
 * <p>与官方 gltfpack 一致的落盘形态：compressed-only 的 glb 需要两条 buffer ——
 * buffer 0 = BIN（真实压缩数据），buffer 1 = 无 URI 的**占位回退缓冲**
 * （{@code extensions.EXT_meshopt_compression.fallback = true}，byteLength = 解压后总字节数）。
 * 被压缩的 bufferView 按「解压后的布局」引用 buffer 1（byteStride × count = byteLength），
 * 扩展对象再指向 buffer 0 的真实压缩区间；这样不支持该扩展的加载器读到的是占位数据，
 * 而声明了 extensionsRequired 时它们本就会拒绝加载。</p>
 */
public final class GlbTileEncoder implements TileEncoder {

    private static final int MAGIC = 0x46546C67;      // "glTF"
    private static final int VERSION = 2;
    private static final int CHUNK_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;  // "BIN\0"
    private static final int COMPONENT_FLOAT = 5126;
    private static final int COMPONENT_UINT = 5125;
    private static final int COMPONENT_BYTE = 5120;
    private static final int COMPONENT_SHORT = 5122;
    private static final int COMPONENT_UBYTE = 5121;
    private static final int COMPONENT_USHORT = 5123;
    private static final int TARGET_ARRAY_BUFFER = 34962;
    private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;
    private static final int FILTER_NEAREST = 9728;
    private static final int FILTER_LINEAR = 9729;

    /** 水面不透明度（BLEND baseColorFactor alpha）。 */
    private static final float WATER_ALPHA = 0.8f;

    private final Gson gson = new Gson();

    @Override
    public byte[] encode(TileGeometry geometry, byte[] atlasPng) {
        return encode(geometry, atlasPng, EncodeOptions.uncompressed());
    }

    @Override
    public byte[] encode(TileGeometry geometry, byte[] atlasPng, EncodeOptions options) {
        boolean quantize = options != null && options.quantize();
        boolean linearFilter = options != null && options.linearFilter();
        boolean embedImage = options == null || options.embedImage();
        boolean meshopt = options != null && options.meshopt();
        // 不内嵌时 PNG 完全不进 BIN（这正是省掉每片 1.7MB 的那一步）
        byte[] embeddedPng = embedImage ? atlasPng : null;
        float posScale = quantize ? positionMaxAbs(geometry) : 1f;
        if (meshopt) {
            return wrapGlb(encodeCompressed(geometry, embeddedPng, quantize, posScale, linearFilter));
        }
        byte[] bin = buildBin(geometry, embeddedPng, quantize, posScale);
        byte[] json = gson.toJson(buildJson(geometry, embeddedPng, quantize, posScale, linearFilter))
                .getBytes(StandardCharsets.UTF_8);
        return wrapGlb(new JsonAndBin(json, bin));
    }

    private record JsonAndBin(byte[] json, byte[] bin) {
    }

    /** 组装 glb 容器：12 字节头 + JSON chunk（空格补齐）+ BIN chunk（0 补齐）。 */
    private byte[] wrapGlb(JsonAndBin content) {
        byte[] json = content.json();
        byte[] bin = content.bin();

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

    private static byte[] buildBin(TileGeometry geometry, byte[] atlasPng, boolean quantize, float posScale) {
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        writeSegment(bin, geometry.opaque(), quantize, posScale);
        if (!geometry.translucent().isEmpty()) {
            writeSegment(bin, geometry.translucent(), quantize, posScale);
        }
        if (atlasPng != null) {
            bin.writeBytes(atlasPng);
        }
        return bin.toByteArray();
    }

    private static void writeSegment(ByteArrayOutputStream bin, Segment segment, boolean quantize, float posScale) {
        bin.writeBytes(positionsBytes(segment, quantize, posScale));
        bin.writeBytes(normalsBytes(segment, quantize));
        bin.writeBytes(uvsBytes(segment, quantize));
        bin.writeBytes(segment.colors());
        bin.writeBytes(segment.lights());
        bin.writeBytes(indicesBytes(segment));
    }

    /** POSITION 字节（量化时 i16 + 每顶点 2 字节对齐填充，与 accessor 的 byteStride 一致）。 */
    static byte[] positionsBytes(Segment segment, boolean quantize, float posScale) {
        return quantize
                ? quantizedPositions(segment.positions(), posScale)
                : floatsBytes(segment.positions());
    }

    /** NORMAL 字节（量化时 i8 + 对齐填充）。 */
    static byte[] normalsBytes(Segment segment, boolean quantize) {
        return quantize ? quantizedNormals(segment.normals()) : floatsBytes(segment.normals());
    }

    /** TEXCOORD_0 字节（量化时 u16）；无 uv 的分段返回空数组。 */
    static byte[] uvsBytes(Segment segment, boolean quantize) {
        if (segment.uvs().length == 0) {
            return new byte[0];
        }
        return quantize ? quantizedUvs(segment.uvs()) : floatsBytes(segment.uvs());
    }

    /** 索引字节（uint32 LE，每 quad 2 个三角形）。 */
    static byte[] indicesBytes(Segment segment) {
        ByteBuffer indices = ByteBuffer.allocate(segment.indices().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int index : segment.indices()) {
            indices.putInt(index);
        }
        return indices.array();
    }

    private JsonObject buildJson(TileGeometry geometry, byte[] atlasPng, boolean quantize, float posScale,
                                 boolean linearFilter) {
        // textured：材质按纹理瓦片输出（MASK 裁剪）。共享图集模式下没有内嵌图，但 UV 仍在，
        // 贴图由前端按清单挂上——所以「是否纹理瓦片」与「是否内嵌图」必须分开判断。
        boolean hasImage = atlasPng != null;
        boolean textured = hasImage || geometry.opaque().uvs().length > 0
                || geometry.translucent().uvs().length > 0;
        int pngBytes = hasImage ? atlasPng.length : 0;
        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "yudream-voxelith");
        root.add("asset", asset);
        if (quantize) {
            JsonArray extensionsUsed = new JsonArray();
            extensionsUsed.add("KHR_mesh_quantization");
            root.add("extensionsUsed", extensionsUsed);
            JsonArray extensionsRequired = new JsonArray();
            extensionsRequired.add("KHR_mesh_quantization");
            root.add("extensionsRequired", extensionsRequired);
        }

        JsonArray bufferViews = new JsonArray();
        JsonArray accessors = new JsonArray();
        JsonArray primitives = new JsonArray();
        int offset = appendSegment(bufferViews, accessors, primitives, geometry.opaque(), 0, 0, quantize, posScale);
        if (!geometry.translucent().isEmpty()) {
            offset = appendSegment(bufferViews, accessors, primitives, geometry.translucent(), offset, 1, quantize, posScale);
        }
        if (hasImage) {
            bufferViews.add(bufferView(offset, pngBytes, 0));
            int pngViewIndex = bufferViews.size() - 1;

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", pngViewIndex);
            image.addProperty("mimeType", "image/png");
            JsonArray images = new JsonArray();
            images.add(image);
            root.add("images", images);

            JsonObject sampler = new JsonObject();
            int filter = linearFilter ? FILTER_LINEAR : FILTER_NEAREST;
            sampler.addProperty("magFilter", filter);
            sampler.addProperty("minFilter", filter);
            if (linearFilter) {
                sampler.addProperty("wrapS", 33071);
                sampler.addProperty("wrapT", 33071);
            }
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

        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", offset + pngBytes);
        buffers.add(buffer);
        root.add("buffers", buffers);

        JsonArray materials = new JsonArray();
        materials.add(material(textured ? "MASK" : "OPAQUE", 1f, hasImage));
        if (!geometry.translucent().isEmpty()) {
            materials.add(material("BLEND", WATER_ALPHA, hasImage));
        }
        root.add("materials", materials);

        JsonObject mesh = new JsonObject();
        mesh.add("primitives", primitives);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        root.add("meshes", meshes);

        JsonObject node = new JsonObject();
        node.addProperty("mesh", 0);
        if (quantize) {
            JsonArray scale = new JsonArray();
            scale.add(posScale);
            scale.add(posScale);
            scale.add(posScale);
            node.add("scale", scale);
        }
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
                                     Segment segment, int offset, int materialIndex, boolean quantize,
                                     float posScale) {
        if (segment.isEmpty()) {
            return offset;
        }
        int vertexCount = segment.vertexCount();
        boolean hasUv = segment.uvs().length > 0;
        int posBytes = quantize ? vertexCount * 8 : segment.positions().length * 4;
        int normalBytes = quantize ? pad4(vertexCount * 4) : segment.normals().length * 4;
        int uvBytes = !hasUv ? 0 : (quantize ? vertexCount * 4 : segment.uvs().length * 4);
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
        int posType = quantize ? COMPONENT_SHORT : COMPONENT_FLOAT;
        int nrmType = quantize ? COMPONENT_BYTE : COMPONENT_FLOAT;
        int uvType = quantize ? COMPONENT_USHORT : COMPONENT_FLOAT;
        accessors.add(accessor(viewBase, posType, vertexCount, "VEC3", quantize,
                quantizedPositionMinMax(minMax, posScale, quantize)[0],
                quantizedPositionMinMax(minMax, posScale, quantize)[1]));
        accessors.add(accessor(viewBase + 1, nrmType, vertexCount, "VEC3", quantize, null, null));
        int uvAccessor = -1;
        if (hasUv) {
            uvAccessor = accessors.size();
            accessors.add(accessor(uvView, uvType, vertexCount, "VEC2", quantize, null, null));
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

    // -----------------------------------------------------------------------
    // meshopt（EXT_meshopt_compression）路径
    // -----------------------------------------------------------------------

    /**
     * 压缩 glb：BIN 里只有压缩位流与 3 字节宽的属性，JSON 侧多一条无 URI 的占位回退缓冲。
     * 与未压缩路径的差别只在 bufferView/accessor 的挂接方式，材质/节点/场景结构完全一致。
     */
    private JsonAndBin encodeCompressed(TileGeometry geometry, byte[] atlasPng, boolean quantize,
                                        float posScale, boolean linearFilter) {
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        JsonArray bufferViews = new JsonArray();
        JsonArray accessors = new JsonArray();
        JsonArray primitives = new JsonArray();
        int[] fallbackOffset = {0};
        appendCompressedSegment(bin, bufferViews, accessors, primitives, geometry.opaque(), 0,
                quantize, posScale, fallbackOffset);
        if (!geometry.translucent().isEmpty()) {
            appendCompressedSegment(bin, bufferViews, accessors, primitives, geometry.translucent(), 1,
                    quantize, posScale, fallbackOffset);
        }

        boolean hasImage = atlasPng != null;
        boolean textured = hasImage || geometry.opaque().uvs().length > 0
                || geometry.translucent().uvs().length > 0;
        int pngBytes = hasImage ? atlasPng.length : 0;

        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "yudream-voxelith");
        root.add("asset", asset);

        JsonArray extensionsUsed = new JsonArray();
        JsonArray extensionsRequired = new JsonArray();
        if (quantize) {
            extensionsUsed.add("KHR_mesh_quantization");
            extensionsRequired.add("KHR_mesh_quantization");
        }
        extensionsUsed.add("EXT_meshopt_compression");
        extensionsRequired.add("EXT_meshopt_compression");
        root.add("extensionsUsed", extensionsUsed);
        root.add("extensionsRequired", extensionsRequired);

        if (hasImage) {
            int pngOffset = bin.size();
            bin.writeBytes(atlasPng);
            bufferViews.add(bufferView(pngOffset, pngBytes, 0));
            int pngViewIndex = bufferViews.size() - 1;

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", pngViewIndex);
            image.addProperty("mimeType", "image/png");
            JsonArray images = new JsonArray();
            images.add(image);
            root.add("images", images);

            JsonObject sampler = new JsonObject();
            int filter = linearFilter ? FILTER_LINEAR : FILTER_NEAREST;
            sampler.addProperty("magFilter", filter);
            sampler.addProperty("minFilter", filter);
            if (linearFilter) {
                sampler.addProperty("wrapS", 33071);
                sampler.addProperty("wrapT", 33071);
            }
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

        JsonArray buffers = new JsonArray();
        JsonObject binBuffer = new JsonObject();
        binBuffer.addProperty("byteLength", bin.size());
        buffers.add(binBuffer);
        // 占位回退缓冲：无 URI、无数据，只声明解压后布局的容量（与 gltfpack 产物一致）
        JsonObject fallback = new JsonObject();
        fallback.addProperty("byteLength", fallbackOffset[0]);
        JsonObject fallbackExt = new JsonObject();
        fallbackExt.addProperty("fallback", true);
        JsonObject fallbackSlot = new JsonObject();
        fallbackSlot.add("EXT_meshopt_compression", fallbackExt);
        fallback.add("extensions", fallbackSlot);
        buffers.add(fallback);
        root.add("buffers", buffers);

        JsonArray materials = new JsonArray();
        materials.add(material(textured ? "MASK" : "OPAQUE", 1f, hasImage));
        if (!geometry.translucent().isEmpty()) {
            materials.add(material("BLEND", WATER_ALPHA, hasImage));
        }
        root.add("materials", materials);

        JsonObject mesh = new JsonObject();
        mesh.add("primitives", primitives);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        root.add("meshes", meshes);

        JsonObject node = new JsonObject();
        node.addProperty("mesh", 0);
        if (quantize) {
            JsonArray scale = new JsonArray();
            scale.add(posScale);
            scale.add(posScale);
            scale.add(posScale);
            node.add("scale", scale);
        }
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

        return new JsonAndBin(gson.toJson(root).getBytes(StandardCharsets.UTF_8), bin.toByteArray());
    }

    /**
     * 追加一个压缩分段的 bufferView/accessor/primitive。
     *
     * <p>位置/法线/UV 走 ATTRIBUTES 模式，索引走 TRIANGLES 模式；
     * COLOR_0/_LIGHT 元素宽 3 字节（不满足 byteStride 被 4 整除的扩展约束）保持原样。</p>
     */
    private static void appendCompressedSegment(ByteArrayOutputStream bin, JsonArray bufferViews,
                                                JsonArray accessors, JsonArray primitives, Segment segment,
                                                int materialIndex, boolean quantize, float posScale,
                                                int[] fallbackOffset) {
        if (segment.isEmpty()) {
            return;
        }
        int vertexCount = segment.vertexCount();
        boolean hasUv = segment.uvs().length > 0;
        int posSize = quantize ? 8 : 12;
        int nrmSize = quantize ? 4 : 12;
        int uvSize = quantize ? 4 : 8;

        int posView = appendCompressedAttribute(bin, bufferViews, positionsBytes(segment, quantize, posScale),
                vertexCount, posSize, fallbackOffset);
        int nrmView = appendCompressedAttribute(bin, bufferViews, normalsBytes(segment, quantize),
                vertexCount, nrmSize, fallbackOffset);
        int uvView = -1;
        if (hasUv) {
            uvView = appendCompressedAttribute(bin, bufferViews, uvsBytes(segment, quantize),
                    vertexCount, uvSize, fallbackOffset);
        }

        int colorView = bufferViews.size();
        int colorOffset = bin.size();
        bin.writeBytes(segment.colors());
        bufferViews.add(bufferView(colorOffset, segment.colors().length, TARGET_ARRAY_BUFFER));
        int lightView = bufferViews.size();
        int lightOffset = bin.size();
        bin.writeBytes(segment.lights());
        bufferViews.add(bufferView(lightOffset, segment.lights().length, TARGET_ARRAY_BUFFER));

        int indexCount = segment.indices().length;
        byte[] compressedIndices = MeshoptIndexCodec.encode(segment.indices(), vertexCount);
        int indexOffset = bin.size();
        bin.writeBytes(compressedIndices);
        int indexView = bufferViews.size();
        appendCompressedView(bufferViews, indexOffset, compressedIndices.length, fallbackOffset[0],
                4, indexCount, TARGET_ELEMENT_ARRAY_BUFFER, "TRIANGLES");
        fallbackOffset[0] += indexCount * 4;

        int accessorBase = accessors.size();
        float[] minMax = positionMinMax(segment.positions());
        int posType = quantize ? COMPONENT_SHORT : COMPONENT_FLOAT;
        int nrmType = quantize ? COMPONENT_BYTE : COMPONENT_FLOAT;
        int uvType = quantize ? COMPONENT_USHORT : COMPONENT_FLOAT;
        accessors.add(accessor(posView, posType, vertexCount, "VEC3", quantize,
                quantizedPositionMinMax(minMax, posScale, quantize)[0],
                quantizedPositionMinMax(minMax, posScale, quantize)[1]));
        accessors.add(accessor(nrmView, nrmType, vertexCount, "VEC3", quantize, null, null));
        int uvAccessor = -1;
        if (hasUv) {
            uvAccessor = accessors.size();
            accessors.add(accessor(uvView, uvType, vertexCount, "VEC2", quantize, null, null));
        }
        int colorAccessor = accessors.size();
        accessors.add(accessor(colorView, COMPONENT_UBYTE, vertexCount, "VEC3", true, null, null));
        accessors.add(accessor(lightView, COMPONENT_UBYTE, vertexCount, "VEC3", true, null, null));
        accessors.add(accessor(indexView, COMPONENT_UINT, indexCount, "SCALAR", false, null, null));

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
    }

    /** 压缩顶点属性：写 BIN 并追加 bufferView，返回其下标。 */
    private static int appendCompressedAttribute(ByteArrayOutputStream bin, JsonArray bufferViews, byte[] raw,
                                                 int vertexCount, int stride, int[] fallbackOffset) {
        byte[] compressed = MeshoptVertexCodec.encode(raw, vertexCount, stride);
        int offset = bin.size();
        bin.writeBytes(compressed);
        int view = bufferViews.size();
        appendCompressedView(bufferViews, offset, compressed.length, fallbackOffset[0],
                stride, vertexCount, TARGET_ARRAY_BUFFER, "ATTRIBUTES");
        fallbackOffset[0] += raw.length;
        return view;
    }

    /**
     * 追加一条被压缩的 bufferView：自身描述「解压后布局」（引用占位回退缓冲 1），
     * 扩展对象描述 BIN（buffer 0）里的真实压缩区间。
     */
    private static void appendCompressedView(JsonArray bufferViews, int binOffset, int compressedBytes,
                                             int fallbackOffset, int stride, int count,
                                             int target, String mode) {
        JsonObject view = new JsonObject();
        view.addProperty("buffer", 1);
        view.addProperty("byteOffset", fallbackOffset);
        view.addProperty("byteLength", stride * count);
        if (target == TARGET_ARRAY_BUFFER) {
            view.addProperty("byteStride", stride);
        }
        if (target != 0) {
            view.addProperty("target", target);
        }

        JsonObject extension = new JsonObject();
        extension.addProperty("buffer", 0);
        extension.addProperty("byteOffset", binOffset);
        extension.addProperty("byteLength", compressedBytes);
        extension.addProperty("byteStride", stride);
        extension.addProperty("mode", mode);
        extension.addProperty("count", count);
        if (!"TRIANGLES".equals(mode)) {
            extension.addProperty("filter", "NONE");
        }
        JsonObject extensions = new JsonObject();
        extensions.add("EXT_meshopt_compression", extension);
        view.add("extensions", extensions);
        bufferViews.add(view);
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

    private static byte[] floatsBytes(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * POSITION → normalized int16，按整瓦片统一 posScale 归一化（node.scale 还原）。
     * 每顶点 8 字节（xyz + 对齐 pad），满足 VEC3 int16 的 4 字节对齐。
     */
    private static byte[] quantizedPositions(float[] positions, float posScale) {
        ByteBuffer buffer = ByteBuffer.allocate(positions.length / 3 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < positions.length; i += 3) {
            buffer.putShort(quantizeSnorm16(positions[i] / posScale));
            buffer.putShort(quantizeSnorm16(positions[i + 1] / posScale));
            buffer.putShort(quantizeSnorm16(positions[i + 2] / posScale));
            buffer.putShort((short) 0);
        }
        return buffer.array();
    }

    /** NORMAL → normalized int8 VEC3 + pad 字节，单位向量直接量化（不用 octahedron，three.js 默认识别）。 */
    private static byte[] quantizedNormals(float[] normals) {
        int vertices = normals.length / 3;
        byte[] packed = new byte[pad4(vertices * 4)];
        for (int i = 0; i < vertices; i++) {
            packed[i * 4] = quantizeSnorm8(normals[i * 3]);
            packed[i * 4 + 1] = quantizeSnorm8(normals[i * 3 + 1]);
            packed[i * 4 + 2] = quantizeSnorm8(normals[i * 3 + 2]);
        }
        return packed;
    }

    /** TEXCOORD_0 → normalized uint16，UV 假定 0..1（图集已重映射）。 */
    private static byte[] quantizedUvs(float[] uvs) {
        if (uvs.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(uvs.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float uv : uvs) {
            buffer.putShort(quantizeUnorm16(uv));
        }
        return buffer.array();
    }

    static float positionMaxAbs(TileGeometry geometry) {
        float maxAbs = 1f;
        maxAbs = maxAbsOf(geometry.opaque().positions(), maxAbs);
        maxAbs = maxAbsOf(geometry.translucent().positions(), maxAbs);
        return maxAbs;
    }

    private static float maxAbsOf(float[] values, float start) {
        float max = start;
        for (float v : values) {
            max = Math.max(max, Math.abs(v));
        }
        return max;
    }

    private static float[][] quantizedPositionMinMax(float[] minMax, float posScale, boolean quantize) {
        if (!quantize) {
            return new float[][]{
                    new float[]{minMax[0], minMax[1], minMax[2]},
                    new float[]{minMax[3], minMax[4], minMax[5]}};
        }
        return new float[][]{
                new float[]{minMax[0] / posScale, minMax[1] / posScale, minMax[2] / posScale},
                new float[]{minMax[3] / posScale, minMax[4] / posScale, minMax[5] / posScale}};
    }

    private static short quantizeSnorm16(float v) {
        float c = Math.max(-1f, Math.min(1f, v));
        return (short) Math.round(c < 0f ? c * 32768f : c * 32767f);
    }

    private static byte quantizeSnorm8(float v) {
        float c = Math.max(-1f, Math.min(1f, v));
        return (byte) Math.round(c < 0f ? c * 128f : c * 127f);
    }

    private static short quantizeUnorm16(float v) {
        float c = Math.max(0f, Math.min(1f, v));
        return (short) Math.round(c * 65535f);
    }

    private static int pad4(int length) {
        return (length + 3) & ~3;
    }
}
