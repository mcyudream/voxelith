package online.yudream.voxelith.tile.infrastructure.image;

import online.yudream.voxelith.tile.application.ImageCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 基于 ImageIO 的 PNG 编码。
 */
public final class PngImageCodec implements ImageCodec {

    @Override
    public byte[] encodePng(int width, int height, int[] argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("PNG 编码失败", e);
        }
        return out.toByteArray();
    }

    @Override
    public int[] decodePng(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new IllegalArgumentException("不是可识别的 PNG（" + png.length + " 字节）");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = new int[width * height];
            image.getRGB(0, 0, width, height, argb, 0, width);
            return argb;
        } catch (IOException e) {
            throw new UncheckedIOException("PNG 解码失败", e);
        }
    }
}
