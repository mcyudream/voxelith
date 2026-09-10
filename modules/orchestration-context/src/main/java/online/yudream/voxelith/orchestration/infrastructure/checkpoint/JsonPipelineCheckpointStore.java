package online.yudream.voxelith.orchestration.infrastructure.checkpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.orchestration.domain.PipelineCheckpointStore;
import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.StageState;
import online.yudream.voxelith.orchestration.domain.StageStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON 检查点存储：runDir/pipeline-checkpoint.json，.tmp + 原子替换防半写。
 */
public final class JsonPipelineCheckpointStore implements PipelineCheckpointStore {

    public static final String FILE_NAME = "pipeline-checkpoint.json";

    private final Gson gson = new GsonBuilder().create();

    @Override
    public Optional<PipelineRun> load(Path runDir) {
        Path file = runDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<PipelineStage, StageState> stages = new EnumMap<>(PipelineStage.class);
            for (PipelineStage stage : PipelineStage.ordered()) {
                stages.put(stage, StageState.pending());
            }
            JsonObject stagesJson = json.getAsJsonObject("stages");
            if (stagesJson != null) {
                for (Map.Entry<String, JsonElement> e : stagesJson.entrySet()) {
                    JsonObject s = e.getValue().getAsJsonObject();
                    List<String> artifacts = new ArrayList<>();
                    if (s.has("artifacts")) {
                        s.getAsJsonArray("artifacts").forEach(a -> artifacts.add(a.getAsString()));
                    }
                    Map<String, List<String>> shards = new java.util.LinkedHashMap<>();
                    if (s.has("completedShards")) {
                        for (Map.Entry<String, JsonElement> shard
                                : s.getAsJsonObject("completedShards").entrySet()) {
                            List<String> shardArtifacts = new ArrayList<>();
                            shard.getValue().getAsJsonArray()
                                    .forEach(a -> shardArtifacts.add(a.getAsString()));
                            shards.put(shard.getKey(), shardArtifacts);
                        }
                    }
                    stages.put(PipelineStage.valueOf(e.getKey()), new StageState(
                            StageStatus.valueOf(s.get("status").getAsString()),
                            s.get("startedAt").getAsLong(),
                            s.get("finishedAt").getAsLong(),
                            artifacts,
                            s.has("error") && !s.get("error").isJsonNull()
                                    ? s.get("error").getAsString() : null,
                            s.has("totalShards") ? s.get("totalShards").getAsInt() : 0,
                            shards));
                }
            }
            return Optional.of(new PipelineRun(
                    json.get("runId").getAsString(), json.get("mapId").getAsString(), stages));
        } catch (IOException e) {
            throw new UncheckedIOException("读取检查点失败: " + file, e);
        }
    }

    @Override
    public void save(Path runDir, PipelineRun run) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", run.runId());
        json.addProperty("mapId", run.mapId());
        JsonObject stages = new JsonObject();
        run.stages().forEach((stage, state) -> {
            JsonObject s = new JsonObject();
            s.addProperty("status", state.status().name());
            s.addProperty("startedAt", state.startedAt());
            s.addProperty("finishedAt", state.finishedAt());
            JsonArray artifacts = new JsonArray();
            state.artifacts().forEach(artifacts::add);
            s.add("artifacts", artifacts);
            if (state.totalShards() > 0) {
                s.addProperty("totalShards", state.totalShards());
                JsonObject shards = new JsonObject();
                state.completedShards().forEach((shard, shardArtifacts) -> {
                    JsonArray arr = new JsonArray();
                    shardArtifacts.forEach(arr::add);
                    shards.add(shard, arr);
                });
                s.add("completedShards", shards);
            }
            if (state.error() != null) {
                s.addProperty("error", state.error());
            }
            stages.add(stage.name(), s);
        });
        json.add("stages", stages);
        try {
            Files.createDirectories(runDir);
            Path tmp = runDir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, gson.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, runDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写检查点失败: " + runDir, e);
        }
    }
}
