package online.yudream.voxelith.lod.domain.heightfield;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地表栅格导出：后端全景渲染靠它拿「逐格色 + 高度」，
 * 行序必须与色图/高度场一致（行 0 = 最小 Z），否则全景会上下颠倒。
 */
class AerialRasterGridTest {

    @Test
    void exportsOnePixelPerBlockWithRowZeroAtMinZ() {
        AerialRaster raster = new AerialRaster(-10, 20, 3, 2);
        // 左上角一格（world -10,20）铺纯红，右下角一格（world -8,21）铺纯蓝
        raster.splat(-10, -9, 20, 21, 70, 0xFF0000, 1f);
        raster.splat(-8, -7, 21, 22, 80, 0x0000FF, 1f);

        int[] argb = raster.toArgbGrid();

        assertThat(argb).hasSize(3 * 2);
        assertThat(raster.originX()).isEqualTo(-10);
        assertThat(raster.originZ()).isEqualTo(20);
        assertThat(raster.width()).isEqualTo(3);
        assertThat(raster.depth()).isEqualTo(2);
        // row 0 = 最小 Z：第一格是红，最后一格（z=1,x=2）是蓝
        assertThat((argb[0] >> 16) & 0xFF).isGreaterThan(200);      // 红
        assertThat(argb[0] & 0xFF).isLessThan(60);
        assertThat(argb[5] & 0xFF).isGreaterThan(200);              // 蓝
        assertThat((argb[5] >> 16) & 0xFF).isLessThan(60);
        // 未铺的格子透明
        assertThat(argb[1] >>> 24).isZero();
        // 高度也对得上（无表面为 NaN）
        assertThat(raster.topYAt(0, 0)).isEqualTo(70f);
        assertThat(raster.topYAt(2, 1)).isEqualTo(80f);
        assertThat(raster.topYAt(1, 0)).isNaN();
    }
}
