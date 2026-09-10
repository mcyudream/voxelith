package online.yudream.voxelith.world.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.application.ScanArtifactSink;
import online.yudream.voxelith.world.domain.world.WorldReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * scan 链路产物落盘实现：scan-report.json（概要）+ chunks.json（逐维度区块清单，供 bake 分片）。
 */
public final class FileScanArtifactSink implements ScanArtifactSink {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void writeScanArtifacts(Path outputDir,
                                   String versionName,
                                   int dataVersion,
                                   Map<String, Map<RegionPos, List<WorldReader.ChunkRef>>> dimensions) {
        JsonObject report = new JsonObject();
        report.addProperty("versionName", versionName);
        report.addProperty("dataVersion", dataVersion);

        JsonObject chunksRoot = new JsonObject();
        int totalRegions = 0;
        int totalChunks = 0;

        for (Map.Entry<String, Map<RegionPos, List<WorldReader.ChunkRef>>> dimension : dimensions.entrySet()) {
            JsonObject regionsJson = new JsonObject();
            for (Map.Entry<RegionPos, List<WorldReader.ChunkRef>> region : dimension.getValue().entrySet()) {
                JsonArray chunkArray = new JsonArray();
                for (WorldReader.ChunkRef chunk : region.getValue()) {
                    JsonObject chunkJson = new JsonObject();
                    chunkJson.addProperty("x", chunk.pos().x());
                    chunkJson.addProperty("z", chunk.pos().z());
                    chunkJson.addProperty("timestamp", chunk.timestampSeconds());
                    chunkArray.add(chunkJson);
                }
                regionsJson.add(region.getKey().x() + "," + region.getKey().z(), chunkArray);
                totalRegions++;
                totalChunks += region.getValue().size();
            }
            chunksRoot.add(dimension.getKey(), regionsJson);
        }

        report.addProperty("dimensions", dimensions.size());
        report.addProperty("regions", totalRegions);
        report.addProperty("chunks", totalChunks);

        writeJson(outputDir.resolve("scan-report.json"), report);
        writeJson(outputDir.resolve("chunks.json"), chunksRoot);
    }

    private static void writeJson(Path target, JsonObject json) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(json));
        } catch (IOException e) {
            throw new UncheckedIOException("写入 scan 产物失败: " + target, e);
        }
    }
}
