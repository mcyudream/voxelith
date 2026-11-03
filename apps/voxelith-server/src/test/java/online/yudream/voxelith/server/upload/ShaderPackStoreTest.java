package online.yudream.voxelith.server.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 光影包仓库：目录扫描、配置层读取（properties + GLSL #define）、名字安全。 */
class ShaderPackStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void listsZipsInManagedDirectory() throws IOException {
        ShaderPackStore store = new ShaderPackStore(tempDir.resolve("packs"));
        packZip("BSL.zip", zip -> zip.entry("shaders/shaders.properties", "shadowDistance=220\n"));
        packZip("Complementary.zip", zip -> zip.entry("shaders/shaders.properties", "clouds=off\n"));
        Files.write(tempDir.resolve("packs").resolve("readme.txt"), new byte[] {1});

        assertThat(store.list()).extracting(ShaderPackStore.PackInfo::name)
                .containsExactly("BSL.zip", "Complementary.zip");
    }

    @Test
    void readsPropertiesAndScalarDefinesFromGlsl() throws IOException {
        ShaderPackStore store = new ShaderPackStore(tempDir.resolve("packs"));
        packZip("iterationT.zip", zip -> {
            zip.entry("shaders/shaders.properties", "clouds=off\nsliders=BLOOM_AMOUNT\n");
            zip.entry("shaders/gbuffers_terrain.vsh", String.join("\n",
                    "uniform float x;",
                    "//#define shadowDistance 666.0")); // 注释掉的备选档位不算默认值
            zip.entry("shaders/lib/settings.glsl", String.join("\n",
                    "const float sunPathRotation = -40.0;",
                    "#define shadowDistance 220.0 // [64.0 128.0 220.0]",
                    "#define BLOOM_AMOUNT 0.62",
                    "#define BLOOM 1",
                    "#define WAVING_WATER (0.15 + 0.1)")); // 带表达式的宏不是标量
            zip.entry("shaders/composite.fsh", String.join("\n",
                    "#define shadowDistance 999.0",
                    "const float shadowMapResolution = 2048.0; // [1024 2048 4096]"));
        });

        ShaderPackStore.PackConfig config = store.readConfig("iterationT.zip");
        assertThat(config.name()).isEqualTo("iterationT");
        assertThat(config.properties()).contains("clouds=off");
        // 首次出现为准：lib/settings.glsl 排在 composite.fsh 前面
        assertThat(config.defines())
                .containsEntry("shadowDistance", "220.0")
                .containsEntry("sunPathRotation", "-40.0")
                .containsEntry("shadowMapResolution", "2048.0")
                .containsEntry("BLOOM_AMOUNT", "0.62")
                .containsEntry("BLOOM", "1")
                .doesNotContainValue("666.0")
                .doesNotContainKey("WAVING_WATER");
        // 选项表 = 游戏内光影设置的数据源：只有带可选值注释的声明才进（BLOOM_AMOUNT 没注释）
        assertThat(config.options()).extracting(ShaderPackStore.PackOption::name)
                .containsExactly("shadowDistance", "shadowMapResolution");
        assertThat(config.options().get(0).value()).isEqualTo("220.0");
        assertThat(config.options().get(0).values()).containsExactly("64.0", "128.0", "220.0");
        assertThat(config.options().get(1).name()).isEqualTo("shadowMapResolution");
        assertThat(config.options().get(1).values()).containsExactly("1024", "2048", "4096");
    }

    @Test
    void rejectsTraversalAndNonPacks() throws IOException {
        ShaderPackStore store = new ShaderPackStore(tempDir.resolve("packs"));
        assertThatThrownBy(() -> store.readConfig("../secret.zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.readConfig("missing.zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        packZip("not-a-pack.zip", zip -> zip.entry("readme.txt", "hi"));
        assertThatThrownBy(() -> store.readConfig("not-a-pack.zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shaders.properties");
    }

    private void packZip(String name, ZipBody body) throws IOException {
        Path file = tempDir.resolve("packs").resolve(name);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            body.add(new ZipWriter(out));
        }
    }

    @FunctionalInterface
    private interface ZipBody {
        void add(ZipWriter zip) throws IOException;
    }

    private record ZipWriter(ZipOutputStream out) {
        void entry(String path, String text) throws IOException {
            out.putNextEntry(new ZipEntry(path));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
