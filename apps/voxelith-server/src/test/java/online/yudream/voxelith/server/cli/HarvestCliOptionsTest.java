package online.yudream.voxelith.server.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * harvestModels 参数解析：缺省值、可重复参数、逗号列表与错误提示。
 */
class HarvestCliOptionsTest {

    private static final String[] MINIMAL = {
            "--pack-dir", "C:/mc/client-1.20.1.jar",
            "--worker-classpath", "C:/repo/modules/runtime-worker/build/classes/java/main",
    };

    @Test
    void appliesDefaults() {
        HarvestCliOptions options = HarvestCliOptions.parse(MINIMAL);

        assertThat(options.mcVersion()).isEqualTo(HarvestCliOptions.DEFAULT_MC_VERSION);
        assertThat(options.loaderVersion()).isEqualTo(HarvestCliOptions.DEFAULT_LOADER_VERSION);
        assertThat(options.workDir()).isEqualTo(Path.of(HarvestCliOptions.DEFAULT_WORK_DIR));
        assertThat(options.timeoutMinutes()).isEqualTo(HarvestCliOptions.DEFAULT_TIMEOUT_MINUTES);
        assertThat(options.modJars()).isEmpty();
        assertThat(options.skipProvision()).isFalse();
        assertThat(options.help()).isFalse();
    }

    @Test
    void provisionCacheDefaultsUnderWorkDir() {
        HarvestCliOptions options = HarvestCliOptions.parse(new String[]{
                "--pack-dir", "C:/client.jar",
                "--worker-classpath", "C:/worker",
                "--work-dir", "C:/out/work",
        });
        assertThat(options.provisionCacheDir()).isEqualTo(Path.of("C:/out/work/.provision-cache"));
    }

    @Test
    void collectsRepeatableModsAndWorkerClasspath() {
        HarvestCliOptions options = HarvestCliOptions.parse(new String[]{
                "--pack-dir", "C:/client.jar",
                "--worker-classpath", "C:/worker/classes",
                "--worker-classpath", "C:/worker/gson.jar",
                "--mod", "C:/mods/aaa.jar",
                "--mod", "C:/mods/bbb.jar",
        });

        // mod jar 加载顺序即用户给定顺序：同名资源后加载者覆盖
        assertThat(options.modJars())
                .containsExactly(Path.of("C:/mods/aaa.jar"), Path.of("C:/mods/bbb.jar"));
        assertThat(options.workerClasspath())
                .containsExactly(Path.of("C:/worker/classes"), Path.of("C:/worker/gson.jar"));
    }

    @Test
    void splitsCommaAndSemicolonLists() {
        HarvestCliOptions options = HarvestCliOptions.parse(new String[]{
                "--pack-dir", "C:/client.jar",
                "--worker-classpath", "C:/worker",
                "--mod", "C:/a.jar,C:/b.jar;C:/c.jar",
        });
        assertThat(options.modJars())
                .containsExactly(Path.of("C:/a.jar"), Path.of("C:/b.jar"), Path.of("C:/c.jar"));
    }

    @Test
    void acceptsGradleStyleAliases() {
        HarvestCliOptions options = HarvestCliOptions.parse(new String[]{
                "-packDir", "C:/client.jar",
                "-workerClasspath", "C:/worker",
                "-workDir", "C:/out",
                "-mcVersion", "1.20.4",
                "-timeoutMinutes", "30",
                "-skipProvision",
        });
        assertThat(options.assetJar()).isEqualTo(Path.of("C:/client.jar"));
        assertThat(options.mcVersion()).isEqualTo("1.20.4");
        assertThat(options.timeoutMinutes()).isEqualTo(30);
        assertThat(options.skipProvision()).isTrue();
    }

    @Test
    void helpShortCircuitsWithoutRequiredOptions() {
        HarvestCliOptions options = HarvestCliOptions.parse(new String[]{"--help"});
        assertThat(options.help()).isTrue();
        assertThat(HarvestCliOptions.USAGE).contains("harvestModels").contains("packDir");
    }

    @Test
    void rejectsMissingPackDir() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{
                        "--worker-classpath", "C:/worker"}))
                .withMessageContaining("packDir");
    }

    @Test
    void rejectsMissingWorkerClasspath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{"--pack-dir", "C:/client.jar"}))
                .withMessageContaining("worker-classpath");
    }

    @Test
    void rejectsUnknownOptionAndBadNumbers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{"--nope"}))
                .withMessageContaining("未知参数");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{
                        "--pack-dir", "C:/c.jar", "--worker-classpath", "C:/w",
                        "--timeout-minutes", "abc"}))
                .withMessageContaining("需要整数");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{
                        "--pack-dir", "C:/c.jar", "--worker-classpath", "C:/w",
                        "--timeout-minutes", "0"}))
                .withMessageContaining("必须为正数");
    }

    @Test
    void rejectsOptionWithoutValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarvestCliOptions.parse(new String[]{"--pack-dir"}))
                .withMessageContaining("缺少取值");
    }

    @Test
    void exposesUsageForAllOptions() {
        assertThat(HarvestCliOptions.USAGE)
                .contains("mcVersion", "loaderVersion", "workDir", "packDir", "mods", "skipProvision");
    }

    @Test
    void recordIsImmutableDefensiveCopy() {
        List<Path> mods = new java.util.ArrayList<>(List.of(Path.of("C:/a.jar")));
        HarvestCliOptions options = new HarvestCliOptions(
                "1.20.1", "0.16.14", Path.of("C:/w"), Path.of("C:/client.jar"),
                mods, null, Path.of("C:/cache"), 15, false, false);
        mods.add(Path.of("C:/b.jar"));
        assertThat(options.modJars()).containsExactly(Path.of("C:/a.jar"));
        assertThat(options.workerClasspath()).isEmpty();
    }
}
