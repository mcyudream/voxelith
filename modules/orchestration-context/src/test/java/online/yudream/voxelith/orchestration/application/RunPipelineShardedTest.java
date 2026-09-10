package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import online.yudream.voxelith.orchestration.domain.StageStatus;
import online.yudream.voxelith.orchestration.infrastructure.checkpoint.JsonPipelineCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunPipelineShardedTest {

    @TempDir
    Path runDir;

    private static final List<String> REGIONS = List.of("r.0.0", "r.0.1", "r.1.0");

    /** bake 阶段按 region 分片：每分片写一个 tiles/r.X.Z.done 产物。 */
    private ShardedStageExecutor bakeShards(Map<String, AtomicInteger> shardCalls) {
        return new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return REGIONS;
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) throws IOException {
                shardCalls.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
                String artifact = "tiles/" + shard + ".done";
                Files.createDirectories(dir.resolve("tiles"));
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            }
        };
    }

    private RunPipelineUseCase useCase(Map<String, AtomicInteger> shardCalls,
                                       JsonPipelineCheckpointStore store) {
        Map<PipelineStage, PipelineStageExecutor> plain = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            plain.put(stage, (s, dir) -> List.of());
        }
        Map<PipelineStage, ShardedStageExecutor> sharded = new EnumMap<>(PipelineStage.class);
        sharded.put(PipelineStage.BAKE, bakeShards(shardCalls));
        return new RunPipelineUseCase(store, plain, sharded);
    }

    @Test
    void shardedStageRunsAllShardsAndCompletes() {
        Map<String, AtomicInteger> shardCalls = new ConcurrentHashMap<>();
        PipelineRun run = useCase(shardCalls, new JsonPipelineCheckpointStore())
                .run(runDir, "run-1", "swust");

        assertThat(run.finished()).isTrue();
        assertThat(run.stage(PipelineStage.BAKE).totalShards()).isEqualTo(3);
        assertThat(run.stage(PipelineStage.BAKE).completedShards()).containsOnlyKeys(REGIONS);
        shardCalls.values().forEach(c -> assertThat(c.get()).isEqualTo(1));
    }

    @Test
    void resumeContinuesMidStageSkippingDoneShards() {
        Map<String, AtomicInteger> shardCalls = new ConcurrentHashMap<>();
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        // 第一次跑崩在第二个分片
        AtomicInteger attempts = new AtomicInteger();
        Map<PipelineStage, ShardedStageExecutor> sharded = new EnumMap<>(PipelineStage.class);
        sharded.put(PipelineStage.BAKE, new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return REGIONS;
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) throws IOException {
                if (shard.equals("r.0.1") && attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("模拟 r.0.1 崩溃");
                }
                shardCalls.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
                String artifact = "tiles/" + shard + ".done";
                Files.createDirectories(dir.resolve("tiles"));
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            }
        });
        Map<PipelineStage, PipelineStageExecutor> plain = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            plain.put(stage, (s, dir) -> List.of());
        }
        RunPipelineUseCase useCase = new RunPipelineUseCase(store, plain, sharded);

        PipelineRun crashed = useCase.run(runDir, "run-1", "swust");
        assertThat(crashed.stage(PipelineStage.BAKE).status()).isEqualTo(StageStatus.FAILED);
        assertThat(crashed.stage(PipelineStage.BAKE).completedShards()).containsOnlyKeys("r.0.0");

        PipelineRun resumed = useCase.run(runDir, "run-1", "swust");
        assertThat(resumed.finished()).isTrue();
        // r.0.0 不重跑，r.0.1 / r.1.0 补上
        assertThat(shardCalls.get("r.0.0").get()).isEqualTo(1);
        assertThat(shardCalls.get("r.0.1").get()).isEqualTo(1);
        assertThat(shardCalls.get("r.1.0").get()).isEqualTo(1);
    }

    @Test
    void missingShardArtifactRerunsOnlyThatShard() throws IOException {
        Map<String, AtomicInteger> shardCalls = new ConcurrentHashMap<>();
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        useCase(shardCalls, store).run(runDir, "run-1", "swust");

        Files.delete(runDir.resolve("tiles/r.0.1.done"));
        PipelineRun resumed = useCase(shardCalls, store).run(runDir, "run-1", "swust");

        assertThat(resumed.finished()).isTrue();
        assertThat(shardCalls.get("r.0.0").get()).isEqualTo(1);
        assertThat(shardCalls.get("r.0.1").get()).isEqualTo(2);
        assertThat(shardCalls.get("r.1.0").get()).isEqualTo(1);
    }
}
