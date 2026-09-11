package online.yudream.voxelith.lod.domain.heightfield;

import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AerialRasterTest {

    @Test
    void splatKeepsHighestSurfaceAndDownsamplesToSrgb() {
        AerialRaster raster = new AerialRaster(0, 0, 4, 4);
        raster.splat(0, 2, 0, 2, 64, 0x00FF00, 4f);
        raster.splat(0, 1, 0, 1, 70, 0xFF0000, 1f);
        int[] argb = raster.downsample(0, 0, 2, 2);
        int high = ColorSpace.linearToSrgbRgb(0xFF0000) | 0xFF000000;
        int low = ColorSpace.linearToSrgbRgb(0x00FF00) | 0xFF000000;
        assertThat(argb[0]).isEqualTo(high);
        assertThat(argb[1]).isEqualTo(low);
        assertThat(argb[2]).isEqualTo(low);
        assertThat(argb[3]).isEqualTo(low);
    }

    @Test
    void emptyCellsStayTransparent() {
        AerialRaster raster = new AerialRaster(0, 0, 2, 2);
        int[] argb = raster.downsample(0, 0, 2, 1);
        assertThat(argb).containsExactly(0);
    }
}
