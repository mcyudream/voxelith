package online.yudream.voxelith.runtime.infrastructure.provision;

import online.yudream.voxelith.runtime.domain.LoaderKind;
import online.yudream.voxelith.runtime.domain.ProvisionedRuntime;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实网络集成测试：首次运行下载约 60MB 到 build/runtime-cache，之后走缓存幂等。
 */
class HttpRuntimeProvisionerTest {

    private static final Path CACHE = Paths.get("build/runtime-cache");

    @Test
    void provisionsMc1201WithFabricLoader() {
        RuntimeSpec spec = new RuntimeSpec(
                "1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), Paths.get("build/it-work"));

        ProvisionedRuntime rt = new HttpRuntimeProvisioner().provision(spec, CACHE);

        assertThat(rt.gameJar()).exists();
        assertThat(rt.gameJar().getFileName().toString()).isEqualTo("1.20.1-client.jar");
        assertThat(rt.gameJar()).satisfies(p -> {
            try {
                assertThat(Files.size(p)).isGreaterThan(10_000_000L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(rt.mappingsJar()).exists();
        assertThat(rt.mappingsJar().getFileName().toString()).isEqualTo("intermediary-1.20.1-v2.jar");
        assertThat(rt.loaderJar()).exists();

        List<String> names = rt.libraries().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("asm-9."));
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("sponge-mixin-"));
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("launchwrapper-"));
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("datafixerupper-"));
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("brigadier-"));
        assertThat(names).noneMatch(n -> n.startsWith("lwjgl"));
        rt.libraries().forEach(p -> assertThat(p).exists());

        // 幂等：第二次 provision 走缓存，产物一致
        ProvisionedRuntime again = new HttpRuntimeProvisioner().provision(spec, CACHE);
        assertThat(again).isEqualTo(rt);
    }

    @Test
    void rejectsForgeForNow() {
        RuntimeSpec spec = new RuntimeSpec(
                "1.20.1", LoaderKind.FORGE, "47.4.0", List.of(), Paths.get("build/it-work"));
        assertThatThrownBy(() -> new HttpRuntimeProvisioner().provision(spec, CACHE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FABRIC");
    }

    @Test
    void unknownMcVersionFailsFast() {
        RuntimeSpec spec = new RuntimeSpec(
                "0.0.0-nonexistent", LoaderKind.FABRIC, "0.16.14", List.of(), Paths.get("build/it-work"));
        assertThatThrownBy(() -> new HttpRuntimeProvisioner().provision(spec, CACHE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.0.0-nonexistent");
    }
}
