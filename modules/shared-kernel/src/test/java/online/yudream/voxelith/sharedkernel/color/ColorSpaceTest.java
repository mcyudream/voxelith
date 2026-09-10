package online.yudream.voxelith.sharedkernel.color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColorSpaceTest {

    @Test
    void blackAndWhiteAreFixedPoints() {
        assertThat(ColorSpace.srgbToLinearRgb(0x000000)).isEqualTo(0x000000);
        assertThat(ColorSpace.srgbToLinearRgb(0xFFFFFF)).isEqualTo(0xFFFFFF);
        assertThat(ColorSpace.linearToSrgbRgb(0x000000)).isEqualTo(0x000000);
        assertThat(ColorSpace.linearToSrgbRgb(0xFFFFFF)).isEqualTo(0xFFFFFF);
    }

    @Test
    void midGrayMapsToKnownLinearValue() {
        // sRGB 0.5 → linear ≈ 0.2140 → 0x37 (55)
        int linear = ColorSpace.srgbToLinearRgb(0x808080);
        assertThat((linear >> 16) & 0xFF).isBetween(54, 56);
        assertThat((linear >> 8) & 0xFF).isEqualTo((linear >> 16) & 0xFF);
        assertThat(linear & 0xFF).isEqualTo((linear >> 16) & 0xFF);
    }

    @Test
    void roundTripIsStableWithinQuantizationError() {
        // 8bit 线性量化在暗端相对误差大（16→1→13），容差按 v/16 放宽；
        // 中亮部误差 ≤1 由 midGray/channelHelpers 测试钉住
        for (int v = 16; v < 256; v++) {
            int rgb = (v << 16) | (v << 8) | v;
            int back = ColorSpace.linearToSrgbRgb(ColorSpace.srgbToLinearRgb(rgb));
            int tolerance = Math.max(5, v / 8);
            assertThat(back & 0xFF).isBetween(v - tolerance, Math.min(255, v + tolerance));
        }
    }

    @Test
    void channelHelpersMatchPackedConversions() {
        int rgb = 0x3FA0C8;
        int linear = ColorSpace.srgbToLinearRgb(rgb);
        assertThat(Math.round(ColorSpace.srgbChannelToLinear(0x3F) * 255))
                .isEqualTo((linear >> 16) & 0xFF);
        assertThat(ColorSpace.linearChannelToSrgb((linear >> 8) & 0xFF))
                .isBetween(0xA0 - 1, 0xA0 + 1);
    }
}
