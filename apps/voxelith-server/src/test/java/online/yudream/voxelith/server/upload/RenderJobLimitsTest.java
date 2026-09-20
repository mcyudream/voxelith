package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.server.cli.RenderMapOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渲染规模上限与子进程参数拼装——「整图全选」把服务拖死那条路必须被堵住。
 */
class RenderJobLimitsTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static RenderJobService service(int maxChunks, long heapBytes) {
        return new RenderJobService(null, null, Path.of("."),
                BakeCommand.NO_MIN_Y, 0, true, true, maxChunks, 0, heapBytes);
    }

    /** 分遍渲染开着时的服务（内存只与单批相关，拒绝线改成时间/磁盘保护）。 */
    private static RenderJobService batchedService(int batchChunks, long heapBytes) {
        return new RenderJobService(null, null, Path.of("."),
                BakeCommand.NO_MIN_Y, 0, true, true, 0, batchChunks, heapBytes);
    }

    @Test
    void explicitLimitWins() {
        assertThat(service(1234, 16 * GIB).effectiveMaxChunks()).isEqualTo(1234);
    }

    @Test
    void derivesSuggestedLimitFromRenderHeap() {
        // 按实测标定：几何吃堆的一半、每区块按 1MB 估 → 16G 堆给出 8192
        assertThat(service(0, 16 * GIB).effectiveMaxChunks()).isEqualTo(8192);
        assertThat(service(0, 8 * GIB).effectiveMaxChunks()).isEqualTo(4096);
        assertThat(service(0, 32 * GIB).effectiveMaxChunks()).isEqualTo(16384);
    }

    @Test
    void hardCeilingIsThreeTimesTheSuggestion() {
        // 拒绝线比建议值宽 3 倍：密度能差 3 倍以上，超了也只是这个任务失败（独立进程）
        assertThat(service(0, 16 * GIB).hardMaxChunks()).isEqualTo(8192 * 3);
        // 显式配置时它就是拒绝线本身，不再放大
        assertThat(service(9000, 16 * GIB).hardMaxChunks()).isEqualTo(9000);
    }

    @Test
    void batchingRaisesTheCeilingToATimeAndDiskGuard() {
        // 分遍之后内存不再是瓶颈：拒绝线抬到 12 万（青义整图 9.5 万、燕理 8.8 万都能进）
        assertThat(batchedService(2048, 16 * GIB).hardMaxChunks()).isEqualTo(120_000);
        // 关掉分遍就退回「单遍内存上限」的老规则
        assertThat(batchedService(0, 16 * GIB).hardMaxChunks()).isEqualTo(8192 * 3);
    }

    @Test
    void commandCarriesHeapClasspathAndGuard() {
        RenderMapOptions options = new RenderMapOptions(
                Path.of("A:/maps/world"), "minecraft:overworld", "school", "校园",
                List.of(Path.of("A:/packs/client.jar")),
                Path.of("A:/work/school"), Path.of("A:/data/maps"),
                Path.of("A:/work/school/models.json.gz"),
                null, null, null, null,
                0, 0, true, 75, 0, 511, 0, 511,
                "1.20.1", "0.16.14", false, List.of(), 4321, 0, true);

        List<String> command = RenderProcessLauncher.command(options, 12 * GIB, "A:/cp/a.jar;A:/cp/b.jar");

        assertThat(command).contains("-Xmx12288m");
        assertThat(command).containsSubsequence("-cp", "A:/cp/a.jar;A:/cp/b.jar");
        assertThat(command).contains("online.yudream.voxelith.server.cli.RenderMapCli");
        // 上限必须真的传给孩子进程，否则独立进程跑得比 web 进程还野
        assertThat(command).containsSubsequence("--max-chunks", "4321");
        assertThat(command).containsSubsequence("--map-id", "school");
    }

    @Test
    void commandSkipsHeapWhenNotConfigured() {
        RenderMapOptions options = new RenderMapOptions(
                Path.of("A:/maps/world"), "minecraft:overworld", "m", "m",
                List.of(Path.of("A:/packs/client.jar")),
                Path.of("A:/work"), Path.of("A:/data/maps"), null,
                null, null, null, null,
                0, 0, true, BakeCommand.NO_MIN_Y, null, null, null, null,
                "1.20.1", "0.16.14", false, List.of(), 0, 0, true);

        List<String> command = RenderProcessLauncher.command(options, 0, "cp");

        assertThat(command).noneMatch(arg -> arg.startsWith("-Xmx"));
        assertThat(command).doesNotContain("--max-chunks");
    }
}
