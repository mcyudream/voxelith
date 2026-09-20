package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.BakeCommand;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * renderMap 参数：{@link RenderMapOptions#toArgs()} 必须能被 {@link RenderMapOptions#parse} 还原。
 *
 * <p>服务端网页触发的渲染是把任务交给独立 JVM 子进程跑的（见 RenderProcessLauncher），
 * 参数正是从这个 toArgs 来的；一旦加了字段忘了补这里，任务就会静默丢掉那个设置
 * （最典型：maxChunks 丢了 → 范围上限失效 → 又一次把内存跑爆）。</p>
 */
class RenderMapOptionsTest {

    @Test
    void roundTripsEveryField() {
        RenderMapOptions options = new RenderMapOptions(
                Path.of("A:/maps/world"),
                "minecraft:overworld",
                "school",
                "校园",
                List.of(Path.of("A:/packs/client.jar"), Path.of("A:/packs/mod.jar")),
                Path.of("A:/work/school"),
                Path.of("A:/data/maps"),
                Path.of("A:/work/school/models.json.gz"),
                -3, -1, 0, 2,
                4, 2, false, 75,
                -1137, -345, 131, 1318,
                "1.20.4", "0.16.9", true,
                List.of(Path.of("A:/repo/worker"), Path.of("A:/repo/gson.jar")),
                4000, 2048, false);

        RenderMapOptions parsed = RenderMapOptions.parse(options.toArgs().toArray(String[]::new));

        assertThat(parsed).isEqualTo(options);
    }

    @Test
    void roundTripsDefaultsOfAWebTriggeredJob() {
        // 网页触发时常见形态：只给方块范围、不给 region 窗口、不采集、采样 0
        RenderMapOptions options = new RenderMapOptions(
                Path.of("A:/maps/world"),
                "minecraft:overworld",
                "qingyi",
                "青义",
                List.of(Path.of("A:/packs/client.jar")),
                Path.of("A:/work/qingyi"),
                Path.of("A:/data/maps"),
                null,
                null, null, null, null,
                0, 0, true, BakeCommand.NO_MIN_Y,
                0, 511, -512, -1,
                "1.21.1", "0.16.14", false,
                List.of(),
                2000, 0, true);

        RenderMapOptions parsed = RenderMapOptions.parse(options.toArgs().toArray(String[]::new));

        assertThat(parsed).isEqualTo(options);
        assertThat(parsed.hasWindow()).isFalse();
        assertThat(parsed.maxChunks()).isEqualTo(2000);
    }

    @Test
    void maxChunksDefaultsToUnlimitedAndRejectsNegative() {
        assertThat(RenderMapOptions.parse(new String[]{
                "--world-dir", "w", "--map-id", "m", "--packs", "p.jar"})
                .maxChunks()).isZero();
        assertThat(RenderMapOptions.parse(new String[]{
                "--world-dir", "w", "--map-id", "m", "--packs", "p.jar",
                "--max-chunks", "-5"})
                .maxChunks()).isZero();
    }
}
