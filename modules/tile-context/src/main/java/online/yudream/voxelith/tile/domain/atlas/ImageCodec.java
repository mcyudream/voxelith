package online.yudream.voxelith.tile.domain.atlas;

/**
 * 图像编码端口：argb 像素 → PNG 字节（实现位于 infrastructure）。
 */
public interface ImageCodec {

    /**
     * @param width  图宽
     * @param height 图高
     * @param argb   像素（行主序 argb）
     */
    byte[] encodePng(int width, int height, int[] argb);
}
