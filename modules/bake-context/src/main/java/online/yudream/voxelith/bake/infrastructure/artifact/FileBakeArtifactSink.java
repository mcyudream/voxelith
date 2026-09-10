package online.yudream.voxelith.bake.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.bake.application.BakeArtifactSink;
import online.yudream.voxelith.bake.domain.mesh.BakedQuad;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * bake 产物落盘实现：
 * samples/chunk.{x}.{z}.json —— 区块几何样本（逐 quad 世界坐标/uv/贴图）；
 * bake-report.json —— 区块/方块/quad 总量与缺失模型清单。
 */
public final class FileBakeArtifactSink implements BakeArtifactSink {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public Path writeChunkSample(Path outputDir, ChunkPos pos, List<BakedQuad> quads) {
        JsonObject root = new JsonObject();
        root.addProperty("chunk", pos.x() + "," + pos.z());
        root.addProperty("quadCount", quads.size());
        JsonArray quadArray = new JsonArray();
        for (BakedQuad quad : quads) {
            JsonObject q = new JsonObject();
            q.add("positions", floats(quad.positions()));
            q.add("uvs", floats(quad.uvs()));
            q.add("normal", floats(quad.normal()));
            q.addProperty("texture", quad.texture());
            q.addProperty("tintIndex", quad.tintIndex());
            q.addProperty("shade", quad.shade());
            q.addProperty("face", quad.face());
            q.addProperty("tintRgb", quad.tintRgb());
            q.add("skyLight", bytes(quad.skyLight()));
            q.add("blockLight", bytes(quad.blockLight()));
            q.add("ao", bytes(quad.ao()));
            quadArray.add(q);
        }
        root.add("quads", quadArray);

        Path file = outputDir.resolve("samples").resolve("chunk." + pos.x() + "." + pos.z() + ".json");
        write(file, root);
        return file;
    }

    @Override
    public Path writeReport(Path outputDir, int chunksBaked, int blocksBaked, int quadsBaked,
                            Map<String, Integer> missing, List<Path> sampleFiles) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("link", "bake");
        root.addProperty("chunksBaked", chunksBaked);
        root.addProperty("blocksBaked", blocksBaked);
        root.addProperty("quadsBaked", quadsBaked);
        JsonObject missingObj = new JsonObject();
        missing.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> missingObj.addProperty(e.getKey(), e.getValue()));
        root.add("missing", missingObj);
        JsonArray samples = new JsonArray();
        sampleFiles.forEach(f -> samples.add(f.getFileName().toString()));
        root.add("samples", samples);

        Path file = outputDir.resolve("bake-report.json");
        write(file, root);
        return file;
    }

    private static JsonArray bytes(byte[] values) {
        JsonArray array = new JsonArray();
        for (byte value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static void write(Path file, JsonObject tree) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(tree), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 bake 产物失败: " + file, e);
        }
    }
}
