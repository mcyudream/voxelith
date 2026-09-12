package online.yudream.voxelith.tile.infrastructure.glb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 只读 glb JSON 头：POSITION min/max 与索引面数，不解码 BIN。
 * 用于全量烘焙后从落盘瓦片重建 manifest，避免再把网格载入内存。
 */
public final class GlbTileInspector {

    private static final int MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;

    private GlbTileInspector() {
    }

    /**
     * @param localMin 瓦片局部包围盒最小点（已乘 node.scale）
     * @param localMax 瓦片局部包围盒最大点
     * @param quads    三角形索引数 / 6
     */
    public record GeometryInfo(float[] localMin, float[] localMax, int quads) {
    }

    public static GeometryInfo inspect(Path glb) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(glb, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, head);
            head.flip();
            int magic = head.getInt();
            if (magic != MAGIC) {
                throw new IOException("不是 glb: " + glb);
            }
            head.getInt();
            head.getInt();
            int jsonLength = head.getInt();
            int jsonType = head.getInt();
            if (jsonType != CHUNK_JSON || jsonLength < 2) {
                throw new IOException("glb JSON chunk 无效: " + glb);
            }
            ByteBuffer jsonBuf = ByteBuffer.allocate(jsonLength);
            readFully(channel, jsonBuf);
            String json = new String(jsonBuf.array(), StandardCharsets.UTF_8).trim();
            return parseJson(json);
        }
    }

    static GeometryInfo parseJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray accessors = root.getAsJsonArray("accessors");
        float scale = 1f;
        if (root.has("nodes") && root.getAsJsonArray("nodes").size() > 0) {
            JsonObject node = root.getAsJsonArray("nodes").get(0).getAsJsonObject();
            if (node.has("scale")) {
                scale = node.getAsJsonArray("scale").get(0).getAsFloat();
            }
        }
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int quads = 0;
        JsonArray meshes = root.getAsJsonArray("meshes");
        if (meshes != null) {
            for (int m = 0; m < meshes.size(); m++) {
                JsonArray primitives = meshes.get(m).getAsJsonObject().getAsJsonArray("primitives");
                if (primitives == null) {
                    continue;
                }
                for (int p = 0; p < primitives.size(); p++) {
                    JsonObject primitive = primitives.get(p).getAsJsonObject();
                    if (primitive.has("indices")) {
                        int idx = primitive.get("indices").getAsInt();
                        quads += accessors.get(idx).getAsJsonObject().get("count").getAsInt() / 6;
                    }
                    JsonObject attributes = primitive.getAsJsonObject("attributes");
                    if (attributes == null || !attributes.has("POSITION")) {
                        continue;
                    }
                    JsonObject pos = accessors.get(attributes.get("POSITION").getAsInt()).getAsJsonObject();
                    if (!pos.has("min") || !pos.has("max")) {
                        continue;
                    }
                    JsonArray amin = pos.getAsJsonArray("min");
                    JsonArray amax = pos.getAsJsonArray("max");
                    for (int i = 0; i < 3; i++) {
                        min[i] = Math.min(min[i], amin.get(i).getAsFloat() * scale);
                        max[i] = Math.max(max[i], amax.get(i).getAsFloat() * scale);
                    }
                }
            }
        }
        if (quads == 0) {
            min = new float[]{0, 0, 0};
            max = new float[]{0, 0, 0};
        }
        return new GeometryInfo(min, max, quads);
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("glb 截断");
            }
        }
    }
}
