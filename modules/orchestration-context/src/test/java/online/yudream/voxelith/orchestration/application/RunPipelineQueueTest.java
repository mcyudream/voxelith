package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardJobState;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import online.yudream.voxelith.orchestration.infrastructure.checkpoint.JsonPipelineCheckpointStore;
import online.yudream.voxelith.orchestration.infrastructure.shard.FileShardQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 队列驱动的分片阶段：runDir 里的检查点仍按分片记录，队列负责「谁在跑」。
 *
 * <p>同时验证「远端 worker 先跑掉一部分、本地再接手」的混合形态——分布式渲染里
 * 这是常态（调度进程自己也干活，别的机器同时在抢）。</p>
 */
class RunPipelineQueueTest {

    @TempDir
    Path runDir;

    @TempDir
    Path queueDir;

    private static final List<String> REGIONS = List.of("r.0.0", "r.0.1", "r.1.0", "r.1.1");

    private ShardedStageExecutor bakeShards(Set<String> executed, Set<String> failed) {
        return new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return REGIONS;
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) throws IOException {
                if (failed.contains(shard)) {
                    throw new IllegalStateException("模拟 " + shard + " 失败");
                }
                executed.add(shard);
                String artifact = "tiles/" + shard + ".done";
                Files.createDirectories(dir.resolve("tiles"));
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            }
        };
    }

    private RunPipelineUseCase useCase(FileShardQueue queue, int workers, ShardedStageExecutor sharded) {
        Map<PipelineStage, PipelineStageExecutor> plain = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            plain.put(stage, (s, dir) -> List.of());
        }
        Map<PipelineStage, ShardedStageExecutor> shardedMap = new EnumMap<>(PipelineStage.class);
        shardedMap.put(PipelineStage.BAKE, sharded);
        return new RunPipelineUseCase(new JsonPipelineCheckpointStore(), plain, shardedMap, queue, workers);
    }

    @Test
    @DisplayName("入队后本地并行跑完，检查点按分片回填，产物与队列状态一致")
    void runsShardsThroughQueue() {
        FileShardQueue queue = new FileShardQueue(queueDir);
        Set<String> executed = ConcurrentHashMap.newKeySet();
        RunPipelineUseCase useCase = useCase(queue, 2, bakeShards(executed, Set.of()));

        PipelineRun run = useCase.run(runDir, "run-1", "swust");

        assertThat(run.finished()).isTrue();
        assertThat(run.stage(PipelineStage.BAKE).completedShards()).containsOnlyKeys(REGIONS);
        assertThat(executed).containsExactlyInAnyOrderElementsOf(REGIONS);
        assertThat(queue.jobs(PipelineStage.BAKE))
                .allMatch(job -> job.state() == ShardJobState.DONE)
                .allMatch(job -> job.workerId().startsWith("local#"));
    }

    @Test
    @DisplayName("远端 worker 已跑过的分片不再重复执行（队列即真相）")
    void remoteWorkIsNotRedone() throws Exception {
        FileShardQueue queue = new FileShardQueue(queueDir);
        Set<String> executed = ConcurrentHashMap.newKeySet();
        ShardedStageExecutor sharded = bakeShards(executed, Set.of());

        // 模拟另一台机器先跑掉两片：入队 → 领取 → 执行 → 回填
        queue.enqueue(PipelineStage.BAKE, REGIONS);
        for (String shard : List.of("r.0.0", "r.0.1")) {
            queue.claim("remote#0", java.time.Duration.ofMinutes(5)).orElseThrow();
            executed.add(shard);
            Files.createDirectories(runDir.resolve("tiles"));
            Files.write(runDir.resolve("tiles/" + shard + ".done"), new byte[0]);
            queue.complete(PipelineStage.BAKE, shard, List.of("tiles/" + shard + ".done"));
        }
        executed.clear();

        PipelineRun run = useCase(queue, 1, sharded).run(runDir, "run-1", "swust");

        assertThat(run.finished()).isTrue();
        assertThat(executed).containsExactlyInAnyOrder("r.1.0", "r.1.1");
        assertThat(queue.jobs(PipelineStage.BAKE))
                .filteredOn(job -> job.workerId().startsWith("remote#"))
                .hasSize(2);
    }

    @Test
    @DisplayName("分片失败：阶段标 FAILED，原因带分片名，其余分片照跑完")
    void failedShardFailsStage() throws Exception {
        FileShardQueue queue = new FileShardQueue(queueDir);
        Set<String> executed = ConcurrentHashMap.newKeySet();
        RunPipelineUseCase useCase = useCase(queue, 2, bakeShards(executed, Set.of("r.1.0")));

        PipelineRun run = useCase.run(runDir, "run-1", "swust");

        assertThat(run.finished()).isFalse();
        assertThat(run.stage(PipelineStage.BAKE).error()).contains("r.1.0");
        assertThat(executed).containsExactlyInAnyOrder("r.0.0", "r.0.1", "r.1.1");
        assertThat(queue.jobs(PipelineStage.BAKE))
                .filteredOn(job -> job.state() == ShardJobState.FAILED)
                .hasSize(1);
    }

    @Test
    @DisplayName("续跑：完成的分片不重新入队，产物缺失的分片重跑")
    void resumeOnlyRunsMissingShards() throws Exception {
        FileShardQueue queue = new FileShardQueue(queueDir);
        AtomicInteger attempts = new AtomicInteger();
        Set<String> executed = ConcurrentHashMap.newKeySet();
        ShardedStageExecutor counting = new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return REGIONS;
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) throws IOException {
                attempts.incrementAndGet();
                executed.add(shard);
                String artifact = "tiles/" + shard + ".done";
                Files.createDirectories(dir.resolve("tiles"));
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            }
        };

        useCase(queue, 2, counting).run(runDir, "run-1", "swust");
        assertThat(attempts.get()).isEqualTo(REGIONS.size());

        Files.delete(runDir.resolve("tiles/r.1.1.done"));
        executed.clear();
        PipelineRun resumed = useCase(queue, 2, counting).run(runDir, "run-1", "swust");

        assertThat(resumed.finished()).isTrue();
        assertThat(executed).containsExactly("r.1.1");
    }
}
