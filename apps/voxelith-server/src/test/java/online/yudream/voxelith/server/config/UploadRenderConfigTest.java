package online.yudream.voxelith.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * render.heap 的写法（{@code 8g} / {@code 8192m} / 留空自动）与边界。
 */
class UploadRenderConfigTest {

    private static final long MIB = 1024L * 1024;

    @Test
    void parsesGigabytesAndMegabytes() {
        assertThat(UploadRenderConfig.heapBytes("8g")).isEqualTo(8 * 1024 * MIB);
        assertThat(UploadRenderConfig.heapBytes("8G")).isEqualTo(8 * 1024 * MIB);
        assertThat(UploadRenderConfig.heapBytes("8192m")).isEqualTo(8192 * MIB);
        assertThat(UploadRenderConfig.heapBytes("8192")).isEqualTo(8192 * MIB);
        assertThat(UploadRenderConfig.heapBytes(" 12gb ")).isEqualTo(12 * 1024 * MIB);
    }

    @Test
    void blankMeansAutoWithinSaneBounds() {
        // 自动档：物理内存的 6 成，2G 起步、16G 封顶（web 进程与系统要留下余量）
        assertThat(UploadRenderConfig.heapBytes(null)).isBetween(2 * 1024 * MIB, 16 * 1024 * MIB);
        assertThat(UploadRenderConfig.heapBytes("  ")).isBetween(2 * 1024 * MIB, 16 * 1024 * MIB);
    }

    @Test
    void rejectsGarbageInsteadOfSilentlyUsingDefault() {
        assertThatIllegalArgumentException().isThrownBy(() -> UploadRenderConfig.heapBytes("大"));
    }
}
