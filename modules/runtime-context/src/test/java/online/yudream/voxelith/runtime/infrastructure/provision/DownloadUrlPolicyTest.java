package online.yudream.voxelith.runtime.infrastructure.provision;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 下载地址策略：只允许 http/https，且拒绝 localhost、环回、私有与保留地址。
 * provision 请求的 URL 来自 piston-meta / Maven 元数据（远端可控），不校验等于开放 SSRF 面。
 */
class DownloadUrlPolicyTest {

    private final DownloadUrlPolicy policy = new DownloadUrlPolicy();

    @Test
    void allowsPublicHttpAndHttps() {
        assertThat(policy.validate("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
                .hasScheme("https");
        assertThat(policy.validate("http://repo1.maven.org/maven2/x.jar")).hasScheme("http");
        assertThat(policy.validate("https://maven.fabricmc.net/net/fabricmc/intermediary/1.20.1/x.jar"))
                .hasHost("maven.fabricmc.net");
    }

    @Test
    void rejectsNonHttpSchemes() {
        String[] urls = {
                "file:///C:/Windows/System32/drivers/etc/hosts",
                "jar:file:///tmp/x.jar!/",
                "data:text/plain;base64,AAAA",
                "ftp://example.com/x.jar",
                "//example.com/x.jar",
        };
        for (String url : urls) {
            assertThatIllegalArgumentException().as(url).isThrownBy(() -> policy.validate(url));
        }
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validate("https://user:pass@example.com/x.jar"))
                .withMessageContaining("用户信息");
    }

    @Test
    void rejectsLoopbackPrivateAndReservedLiterals() {
        String[] urls = {
                "http://localhost/x.jar",
                "http://127.0.0.1/x.jar",
                "http://127.1.2.3/x.jar",
                "http://[::1]/x.jar",
                "http://0.0.0.0/x.jar",
                "http://10.0.0.5/x.jar",
                "http://172.16.9.9/x.jar",
                "http://192.168.1.1/x.jar",
                "http://169.254.169.254/latest/meta-data/", // 云元数据端点
                "http://100.64.0.1/x.jar", // 运营商级 NAT
                "http://192.0.0.1/x.jar",
                "http://198.18.0.1/x.jar",
                "http://[fd00::1]/x.jar", // 唯一本地 IPv6
                "http://224.0.0.1/x.jar", // 组播
        };
        for (String url : urls) {
            assertThatIllegalArgumentException().as(url).isThrownBy(() -> policy.validate(url));
        }
    }

    @Test
    void rejectsLocalHostNames() {
        String[] urls = {
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://router.local/x.jar",
                "http://printer.internal/x.jar",
        };
        for (String url : urls) {
            assertThatIllegalArgumentException().as(url).isThrownBy(() -> policy.validate(url));
        }
    }

    @Test
    void rejectsHostNameThatResolvesToLoopback() {
        // 名称看着正常、解析却指向本机：只查名称不足以防住
        DownloadUrlPolicy guarded = new DownloadUrlPolicy(
                host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guarded.validate("https://evil.example.com/x.jar"))
                .withMessageContaining("本机/私有/保留");
    }

    @Test
    void rejectsHostNameResolvingToPrivateRange() throws Exception {
        DownloadUrlPolicy guarded = new DownloadUrlPolicy(
                host -> new InetAddress[]{InetAddress.getByName("10.1.2.3")});
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guarded.validate("https://intranet.example.com/x.jar"));
    }

    @Test
    void rejectsUnresolvableHost() {
        DownloadUrlPolicy guarded = new DownloadUrlPolicy(host -> {
            throw new UnknownHostException(host);
        });
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guarded.validate("https://nope.invalid/x.jar"))
                .withMessageContaining("无法解析");
    }
}
