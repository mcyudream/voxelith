package online.yudream.voxelith.bake.infrastructure.prebaked;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.sharedkernel.vo.Direction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * models.json.gz（runtime-worker 采集产物，格式 {@code voxelith-models/1}，gzip NDJSON）读取器。
 * 按 (block, 规范化状态串) 建索引；状态串两侧都做 k=v 字典序规范化，
 * 与导出侧的定义顺序写法、世界侧的属性表乱序均解耦。
 * 贴图/方向名驻留去重以控制内存（原版 1.20.1 全量约 30 万 quad）。
 */
public final class NdjsonPrebakedQuadSource implements PrebakedQuadSource {

    public static final String FORMAT = "voxelith-models/1";

    /** block id → (规范化状态串 → quads)。 */
    private final Map<String, Map<String, List<Quad>>> index = new HashMap<>();

    private NdjsonPrebakedQuadSource() {
    }

    public static NdjsonPrebakedQuadSource load(Path modelsFile) {
        NdjsonPrebakedQuadSource source = new NdjsonPrebakedQuadSource();
        Map<String, String> intern = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(modelsFile)), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException("models 文件为空: " + modelsFile);
            }
            JsonObject meta = JsonParser.parseString(line).getAsJsonObject();
            if (!FORMAT.equals(meta.get("format").getAsString())) {
                throw new IllegalArgumentException("models 格式不符: " + meta.get("format"));
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                source.readBlock(JsonParser.parseString(line).getAsJsonObject(), intern);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 models 文件失败: " + modelsFile, e);
        }
        return source;
    }

    private void readBlock(JsonObject blockJson, Map<String, String> intern) {
        String block = blockJson.get("block").getAsString();
        Map<String, List<Quad>> byState = new HashMap<>();
        for (JsonElement stateEl : blockJson.getAsJsonArray("states")) {
            JsonObject stateJson = stateEl.getAsJsonObject();
            String key = canonicalKey(stateJson.get("state").getAsString());
            JsonArray quadsJson = stateJson.getAsJsonArray("quads");
            List<Quad> quads = new ArrayList<>(quadsJson.size());
            for (JsonElement quadEl : quadsJson) {
                quads.add(readQuad(quadEl.getAsJsonObject(), intern));
            }
            byState.put(key, List.copyOf(quads));
        }
        index.put(block, byState);
    }

    private Quad readQuad(JsonObject q, Map<String, String> intern) {
        float[] pos = floats(q.getAsJsonArray("pos"), 12);
        float[] uv = floats(q.getAsJsonArray("uv"), 8);
        String face = intern(q.get("face").getAsString(), intern);
        Direction dir = Direction.byName(face);
        float[] normal = {dir.nx(), dir.ny(), dir.nz()};
        String cull = q.has("cull") ? intern(q.get("cull").getAsString(), intern) : null;
        return new Quad(pos, uv, normal,
                intern(q.get("tex").getAsString(), intern),
                cull, q.get("tint").getAsInt(), q.get("shade").getAsBoolean(), face);
    }

    private static float[] floats(JsonArray array, int expected) {
        float[] out = new float[expected];
        for (int i = 0; i < expected; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    private static String intern(String value, Map<String, String> intern) {
        return intern.computeIfAbsent(value, v -> v);
    }

    @Override
    public Optional<List<Quad>> quads(String block, Map<String, String> properties) {
        Map<String, List<Quad>> byState = index.get(block);
        if (byState == null) {
            return Optional.empty();
        }
        List<Quad> quads = byState.get(canonicalKey(properties));
        return quads == null ? Optional.empty() : Optional.of(quads);
    }

    /** 导出侧状态串（"k=v,k=v" 定义顺序）与查询侧属性表统一为字典序 k=v 串。 */
    private static String canonicalKey(String state) {
        if (state.isEmpty()) {
            return "";
        }
        String[] pairs = state.split(",");
        Arrays.sort(pairs);
        return String.join(",", pairs);
    }

    private static String canonicalKey(Map<String, String> properties) {
        if (properties.isEmpty()) {
            return "";
        }
        String[] pairs = new String[properties.size()];
        int i = 0;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            pairs[i++] = e.getKey() + "=" + e.getValue();
        }
        Arrays.sort(pairs);
        return String.join(",", pairs);
    }
}
