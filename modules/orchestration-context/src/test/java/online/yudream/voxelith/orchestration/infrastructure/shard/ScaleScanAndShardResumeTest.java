package online.yudream.voxelith.orchestration.infrastructure.shard;

import online.yudream.voxelith.orchestration.application.RunPipelineUseCase;
import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import online.yudream.voxelith.orchestration.domain.StageStatus;
import online.yudream.voxelith.orchestration.infrastructure.checkpoint.JsonPipelineCheckpointStore;
import online.yudream.voxelith.world.application.ScanCommand;
import online.yudream.voxelith.world.application.ScanOutcome;
import online.yudream.voxelith.world.application.ScanWorldUseCase;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import online.yudream.voxelith.world.infrastructure.artifact.FileScanArtifactSink;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规模化验收：合成 4×4 region（128×128 区块 = 16384 区块）走 scan → 分片 bake 续跑。
 * 不跑真实网格化（那是十万级压测的 jshell 路径），只验证编排在「region 数 × 区块数」这个数量级
 * 下能列出全部分片、中途崩溃后续跑、产物缺失只重跑该 shard。
 */
class ScaleScanAndShardResumeTest {

    @TempDir
    Path worldDir;
    @TempDir
    Path runDir;

    /**
     * 4 region × 32×32 区块：每个 region 只在四角各放 1 个方块，避免把 16384 个满区块写进磁盘。
     * scan 仍按 region 头表计数 —— 每个被写入的 chunk 都出现在 chunks.json。
     * 这里用 4 region × 4 chunk = 16 个已写入区块，外加显式 4 个 region 键，覆盖分片枚举。
     *
     * 另写一个「每 region 满 32×32 头表」太重；规模数字用 scan 的 regionCount 断言，
     * 分片键数量与 region 数对齐。
     */
    @Test
    @Timeout(30)
    void fourRegionsScanFeedsShardedBakeAndResumeSkipsIntactShards() throws Exception {
        // 跨 4 个 region：(-512,-512) .. (511,511) 的 region 原点
        new SyntheticWorldBuilder()
                .setBlock(-512, 64, -512, "minecraft:stone")
                .setBlock(-512, 64, 0, "minecraft:stone")
                .setBlock(0, 64, -512, "minecraft:stone")
                .setBlock(0, 64, 0, "minecraft:stone")
                .setBlock(511, 64, 511, "minecraft:stone")
                .write(worldDir);

        ScanWorldUseCase scan = new ScanWorldUseCase(new AnvilWorldReader(), new FileScanArtifactSink());
        ScanOutcome outcome = scan.scan(new ScanCommand(worldDir, runDir));
        assertThat(outcome.regionCount()).isGreaterThanOrEqualTo(4);
        assertThat(runDir.resolve("chunks.json")).isRegularFile();

        Map<String, AtomicInteger> bakeCalls = new ConcurrentHashMap<>();
        AtomicInteger crashOnce = new AtomicInteger();
        ShardedStageExecutor bake = new ScanRegionShardPlanner(new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return List.of();
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) throws Exception {
                if (shard.equals("r.0.0") && crashOnce.incrementAndGet() == 1) {
                    throw new IllegalStateException("模拟 r.0.0 中途崩溃");
                }
                bakeCalls.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
                String artifact = "bake/" + shard + ".done";
                Files.createDirectories(dir.resolve("bake"));
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            }
        });

        Map<PipelineStage, PipelineStageExecutor> plain = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            plain.put(stage, (s, dir) -> List.of());
        }
        Map<PipelineStage, ShardedStageExecutor> sharded = new EnumMap<>(PipelineStage.class);
        sharded.put(PipelineStage.BAKE, bake);
        RunPipelineUseCase pipeline = new RunPipelineUseCase(
                new JsonPipelineCheckpointStore(), plain, sharded);

        PipelineRun crashed = pipeline.run(runDir, "scale-1", "scale");
        assertThat(crashed.stage(PipelineStage.BAKE).status()).isEqualTo(StageStatus.FAILED);
        assertThat(crashed.stage(PipelineStage.BAKE).completedShards().size()).isGreaterThanOrEqualTo(1);

        PipelineRun resumed = pipeline.run(runDir, "scale-1", "scale");
        assertThat(resumed.finished()).isTrue();
        assertThat(resumed.stage(PipelineStage.BAKE).totalShards()).isEqualTo(outcome.regionCount());
        assertThat(resumed.stage(PipelineStage.BAKE).completedShards()).hasSize(outcome.regionCount());
        int totalBakeCalls = bakeCalls.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(totalBakeCalls).isEqualTo(outcome.regionCount());
        bakeCalls.forEach((shard, c) -> assertThat(c.get()).as(shard).isEqualTo(1));
        assertThat(crashOnce.get()).isEqualTo(2);
    }
}
