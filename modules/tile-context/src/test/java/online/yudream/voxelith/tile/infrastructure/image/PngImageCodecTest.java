package online.yudream.voxelith.tile.infrastructure.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PNG 编码在 LOD 图集页量级下的正确性：此前只编过 128²（逐瓦片色图）与面板图集，
 * 分层图集页会到 4096² 且非正方形，尺寸/行序/像素必须仍然对得上——
 * 行序错了整页色图就会上下颠倒（与 glb 的 flipY=false 约定冲突）。
 */
class PngImageCodecTest {

    private final PngImageCodec codec = new PngImageCodec();

    @Test
    void roundTripsLargeNonSquareAtlasPage() throws Exception {
        // 典型大规模 LOD 图集页：4096×1920（grid 64×30，槽位 64）
        int width = 4096;
        int height = 1920;
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = 0xFF204060;
        }
        // 四个角与中心打标记，验证行序与列序不被翻转
        argb[0] = 0xFFFF0000;                          // 左上
        argb[width - 1] = 0xFF00FF00;                  // 右上
        argb[(height - 1) * width] = 0xFF0000FF;       // 左下
        argb[(height - 1) * width + width - 1] = 0xFFFFFFFF; // 右下

        byte[] png = codec.encodePng(width, height, argb);

        assertThat(png).hasSizeGreaterThan(1000);
        assertThat(png[0] & 0xFF).isEqualTo(0x89); // PNG magic
        assertThat((char) (png[1] & 0xFF)).isEqualTo('P');

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded.getWidth()).isEqualTo(width);
        assertThat(decoded.getHeight()).isEqualTo(height);
        // 行 0 = 数组首行：左上/右上标记必须落在图像第一行
        assertThat(decoded.getRGB(0, 0)).isEqualTo(0xFFFF0000);
        assertThat(decoded.getRGB(width - 1, 0)).isEqualTo(0xFF00FF00);
        assertThat(decoded.getRGB(0, height - 1)).isEqualTo(0xFF0000FF);
        assertThat(decoded.getRGB(width - 1, height - 1)).isEqualTo(0xFFFFFFFF);
        assertThat(decoded.getRGB(width / 2, height / 2)).isEqualTo(0xFF204060);
    }
}
