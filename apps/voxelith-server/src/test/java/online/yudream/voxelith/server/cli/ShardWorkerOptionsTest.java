package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * shardWorker 参数解析与分片键：分片键就是 region 名（{@code r.X.Z}），
 * 与 {@code ScanRegionShardPlanner} / {@code RegionPos} 必须是同一套写法，
 * 否则 worker 抢到活却找不到对应的 region。
 */
class ShardWorkerOptionsTest {

    @Test
    @DisplayName("解析必填与默认值")
    void parsesOptions() {
        ShardWorkerCli.Options options = ShardWorkerCli.Options.parse(new String[]{
                "--world-dir", "A:/maps/world",
                "--map-id", "swust",
                "--packs", "A:/packs/client.jar,A:/packs/mod.jar",
                "--queue-dir", "A:/work/shard-queue",
                "--threads", "4",
                "--no-meshopt",
        });

        assertThat(options.worldDir()).isEqualTo(Path.of("A:/maps/world"));
        assertThat(options.mapId()).isEqualTo("swust");
        assertThat(options.packs()).containsExactly(
                Path.of("A:/packs/client.jar"), Path.of("A:/packs/mod.jar"));
        assertThat(options.queueDir()).isEqualTo(Path.of("A:/work/shard-queue"));
        assertThat(options.publishDir()).isEqualTo(Path.of("./data/maps"));
        assertThat(options.workDir()).isEqualTo(Path.of("./work"));
        assertThat(options.dimension()).isEqualTo("minecraft:overworld");
        assertThat(options.threads()).isEqualTo(4);
        assertThat(options.leaseMinutes()).isEqualTo(30);
        assertThat(options.meshopt()).isFalse();
        assertThat(options.workerId()).contains("#");
    }

    @Test
    @DisplayName("缺必填项报错，线程数为 0 时回落 1")
    void rejectsIncomplete() {
        assertThatThrownBy(() -> ShardWorkerCli.Options.parse(new String[]{"--map-id", "m"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worldDir");
        assertThatThrownBy(() -> ShardWorkerCli.Options.parse(new String[]{
                "--world-dir", "w", "--map-id", "m", "--queue-dir", "q"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packs");

        ShardWorkerCli.Options zero = ShardWorkerCli.Options.parse(new String[]{
                "--world-dir", "w", "--map-id", "m", "--queue-dir", "q",
                "--packs", "p.jar", "--threads", "0"});
        assertThat(zero.threads()).isEqualTo(1);
    }

    @Test
    @DisplayName("分片键 r.X.Z 解析成 RegionPos（含负坐标）")
    void parsesShardKeys() {
        assertThat(ShardWorkerCli.parseShard("r.0.0")).isEqualTo(new RegionPos(0, 0));
        assertThat(ShardWorkerCli.parseShard("r.-3.12")).isEqualTo(new RegionPos(-3, 12));
        assertThatThrownBy(() -> ShardWorkerCli.parseShard("tiles/hires"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法分片键");
    }
}
