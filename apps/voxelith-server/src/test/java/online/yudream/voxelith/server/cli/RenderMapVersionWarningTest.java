package online.yudream.voxelith.server.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本体检：存档 / 采集版本 / 资源包版本三者不一致时必须在管线开头就报警。
 *
 * <p>动机是线上真实问题：拿 1.20.1 的 client jar 解析 1.21 的世界，草地整片品红
 * （1.20.3 起 {@code grass} 改名 {@code short_grass}）、新方块缺几何；
 * 而原来的日志只有跑到 tile 阶段的「缺贴图」清单，很容易被忽略。</p>
 */
class RenderMapVersionWarningTest {

    @Test
    @DisplayName("三者一致时不出告警")
    void silentWhenConsistent() {
        assertThat(RenderMapCli.versionWarnings("1.21.1", "1.21.1", List.of("client-1.21.1.jar")))
                .isEmpty();
        // jar 文件名里没有版本号（自定义包名）时也无法比较，不该误报
        assertThat(RenderMapCli.versionWarnings("1.21.1", "1.21.1", List.of("my-pack.jar")))
                .isEmpty();
    }

    @Test
    @DisplayName("采集版本与存档不一致：报出两者并给出处理办法")
    void warnsOnHarvestMismatch() {
        List<String> warnings = RenderMapCli.versionWarnings("1.21.1", "1.20.1",
                List.of("client-1.21.1.jar"));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("采集版本与存档版本不一致")
                .contains("1.21.1")
                .contains("1.20.1")
                .contains("品红")
                .contains("-PmcVersion=1.21.1");
    }

    @Test
    @DisplayName("资源包版本与存档不一致：点名是哪个 jar")
    void warnsOnPackMismatch() {
        List<String> warnings = RenderMapCli.versionWarnings("1.21.1", "1.21.1",
                List.of("client-1.20.1.jar", "mod-x.jar"));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("资源包版本与存档版本不一致")
                .contains("client-1.20.1.jar（1.20.1）")
                .contains("贴图名会随版本改名");
    }

    @Test
    @DisplayName("存档版本读不出来时退化为「资源包 vs 采集版本」比较")
    void fallsBackWhenWorldVersionUnknown() {
        List<String> warnings = RenderMapCli.versionWarnings("unknown", "1.20.1",
                List.of("client-1.21.1.jar"));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("资源包版本与采集版本不一致")
                .contains("client-1.21.1.jar（1.21.1）");

        // 全是未知信息时不产告警（不制造噪音）
        assertThat(RenderMapCli.versionWarnings("unknown", "", List.of("client.jar"))).isEmpty();
    }

    @Test
    @DisplayName("从 jar 文件名抽版本号")
    void extractsVersionFromFileName() {
        assertThat(RenderMapCli.versionOf("client-1.21.1.jar")).isEqualTo("1.21.1");
        assertThat(RenderMapCli.versionOf("client-1.20.jar")).isEqualTo("1.20");
        assertThat(RenderMapCli.versionOf("minecraft-client-1.21.jar")).isEqualTo("1.21");
        assertThat(RenderMapCli.versionOf("pack.jar")).isNull();
        assertThat(RenderMapCli.versionOf("1.21.1-forge-51.0.0.jar")).isEqualTo("1.21.1");
    }
}
