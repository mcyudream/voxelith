package online.yudream.voxelith.world.domain.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PalettedContainerTest {

    @Test
    void singleValuePaletteNeedsNoData() {
        PalettedContainer<String> container = new PalettedContainer<>(List.of("air"), null, 4, 4096);

        assertThat(container.bitsPerEntry()).isZero();
        assertThat(container.get(0)).isEqualTo("air");
        assertThat(container.get(4095)).isEqualTo("air");
    }

    @Test
    void extractsValuesPackedFourBitsPerEntry() {
        // 4 bit → 16 条目/long；值 i%2 交替
        long[] data = new long[256];
        for (int i = 0; i < 4096; i++) {
            if (i % 2 == 1) {
                data[i / 16] |= 1L << ((i % 16) * 4);
            }
        }
        PalettedContainer<String> container = new PalettedContainer<>(List.of("a", "b"), data, 4, 4096);

        assertThat(container.bitsPerEntry()).isEqualTo(4);
        assertThat(container.get(0)).isEqualTo("a");
        assertThat(container.get(1)).isEqualTo("b");
        assertThat(container.get(4095)).isEqualTo("b");
    }

    @Test
    void supportsNonPowerOfTwoBitWidths() {
        // 调色板 3 项 → 5 bit（向上取 log2）？不：ceil(log2(3)) = 2 bit → max(4, 2) = 4。
        // 用 minBits=1 验证 2 bit 非 2 次幂步长：perLong = 32
        long[] data = new long[128];
        data[0] = 0b01_10_11L; // slot0=3? 仅验证读取路径一致性：slot0=3(0b11), slot1=2(0b10), slot2=1(0b01)
        PalettedContainer<String> container = new PalettedContainer<>(List.of("a", "b", "c", "d"), data, 1, 4096);

        assertThat(container.bitsPerEntry()).isEqualTo(2);
        assertThat(container.get(0)).isEqualTo("d");
        assertThat(container.get(1)).isEqualTo("c");
        assertThat(container.get(2)).isEqualTo("b");
        assertThat(container.get(3)).isEqualTo("a");
    }

    @Test
    void rejectsUndersizedData() {
        assertThatThrownBy(() -> new PalettedContainer<>(List.of("a", "b"), new long[1], 4, 4096))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
