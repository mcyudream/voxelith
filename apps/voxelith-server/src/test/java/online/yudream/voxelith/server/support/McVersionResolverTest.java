package online.yudream.voxelith.server.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集版本自动判定：**不写死版本**，按「显式 → 资源包文件名 → 存档版本 → 兜底」取证据。
 *
 * <p>以前默认写死 1.20.1，任何 1.20.5/1.21 的存档都会用 1.20.1 的模型去烘，
 * 结果就是队友遇到的「草变紫」（模型引用的贴图名在新包里已改名）。</p>
 */
class McVersionResolverTest {

    @Test
    @DisplayName("显式指定最高优先：用户说了算")
    void explicitWins() {
        assertThat(McVersionResolver.resolve("1.21.4", "client-1.21.1.jar", "1.20.4", "1.20.1"))
                .isEqualTo("1.21.4");
        assertThat(McVersionResolver.source("1.21.4", "client-1.21.1.jar", "1.20.4"))
                .isEqualTo("显式指定");
    }

    @Test
    @DisplayName("其次看资源包文件名：模型必须与包里贴图同版本")
    void packFileNameSecond() {
        assertThat(McVersionResolver.resolve(null, "client-1.21.1.jar", "1.20.4", "1.20.1"))
                .isEqualTo("1.21.1");
        assertThat(McVersionResolver.source(null, "client-1.21.1.jar", "1.20.4"))
                .isEqualTo("资源包文件名");
        // 家里自己命名的包（含空格的路径）也能抽出主版本
        assertThat(McVersionResolver.resolve(null, "我的整合包 1.20.6 client.jar", null, "1.20.1"))
                .isEqualTo("1.20.6");
    }

    @Test
    @DisplayName("包名没版本时用存档版本；都没有才落到兜底")
    void fallsBackToWorldThenConstant() {
        assertThat(McVersionResolver.resolve(null, "client.jar", "1.21.1", "1.20.1"))
                .isEqualTo("1.21.1");
        assertThat(McVersionResolver.source(null, "client.jar", "1.21.1")).isEqualTo("存档版本");

        assertThat(McVersionResolver.resolve(null, "client.jar", "unknown", "1.20.1"))
                .isEqualTo("1.20.1");
        assertThat(McVersionResolver.source(null, null, null)).isEqualTo("兜底默认");
        assertThat(McVersionResolver.resolve("  ", "  ", "  ", "1.20.1")).isEqualTo("1.20.1");
    }

    @Test
    @DisplayName("版本号抽取：能认 1.20 / 1.21.1，认不出就不猜")
    void extractsVersions() {
        assertThat(McVersionResolver.versionOf("client-1.21.1.jar")).isEqualTo("1.21.1");
        assertThat(McVersionResolver.versionOf("client-1.20.jar")).isEqualTo("1.20");
        assertThat(McVersionResolver.versionOf("client.jar")).isNull();
        assertThat(McVersionResolver.versionOf(null)).isNull();
        assertThat(McVersionResolver.versionOf("")).isNull();
    }
}
