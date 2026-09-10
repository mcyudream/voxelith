package online.yudream.voxelith.orchestration.infrastructure.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从 scan 产物 {@code chunks.json} 读出 region 分片键（{@code r.X.Z}），
 * 把实际 bake/tile 委托给注入的 {@link ShardedStageExecutor}（可为测试替身或组合根装配的真实用例）。
 *
 * <p>本类本身只负责「列出 shard」；执行仍走被装饰的 executor，便于压测时统计每 region 调用次数。
 */
public final class ScanRegionShardPlanner implements ShardedStageExecutor {

    public static final String CHUNKS_FILE = "chunks.json";

    private final ShardedStageExecutor inner;
    private final String dimension;

    public ScanRegionShardPlanner(ShardedStageExecutor inner) {
        this(inner, "minecraft:overworld");
    }

    public ScanRegionShardPlanner(ShardedStageExecutor inner, String dimension) {
        this.inner = inner;
        this.dimension = dimension;
    }

    @Override
    public List<String> shards(PipelineStage stage, Path runDir) throws Exception {
        Path file = runDir.resolve(CHUNKS_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has(dimension)) {
            return List.of();
        }
        JsonObject regions = root.getAsJsonObject(dimension);
        // TreeMap 保证 r.X.Z 按数值而非字典序稳定（"r.-1.0" 在 "r.0.0" 前）
        Map<String, String> ordered = new TreeMap<>((a, b) -> {
            int[] aa = coords(a);
            int[] bb = coords(b);
            int c = Integer.compare(aa[0], bb[0]);
            return c != 0 ? c : Integer.compare(aa[1], bb[1]);
        });
        for (Map.Entry<String, JsonElement> e : regions.entrySet()) {
            String[] xz = e.getKey().split(",");
            ordered.put("r." + xz[0] + "." + xz[1], e.getKey());
        }
        return List.copyOf(ordered.keySet());
    }

    @Override
    public List<String> executeShard(PipelineStage stage, String shard, Path runDir) throws Exception {
        return inner.executeShard(stage, shard, runDir);
    }

    private static int[] coords(String shard) {
        // r.X.Z
        int last = shard.lastIndexOf('.');
        int first = shard.indexOf('.');
        return new int[]{
                Integer.parseInt(shard.substring(first + 1, last)),
                Integer.parseInt(shard.substring(last + 1))};
    }
}
