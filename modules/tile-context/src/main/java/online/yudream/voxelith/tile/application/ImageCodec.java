package online.yudream.voxelith.tile.application;

/**
 * 图像编码端口：argb 像素 → PNG 字节（实现位于 infrastructure）。
 *
 * <p>放在 application 层而非 domain：它与 {@link TextureColorSampler}、
 * {@link VertexColorTileExporter} 一样是提供给其他限界上下文的出站端口，
 * 而跨上下文只允许访问对方的 application 层（ArchUnit 守护，见
 * modules/architecture-tests）。lod-context 需要编码航拍色图，正是经由此端口。</p>
 */
public interface ImageCodec {

    /**
     * @param width  图宽
     * @param height 图高
     * @param argb   像素（行主序 argb）
     */
    byte[] encodePng(int width, int height, int[] argb);

    /**
     * PNG 字节 → argb 像素（行主序）。
     *
     * <p>增量扩图集要读回已发布图集再向上追加新单元格，所以需要解码方向。
     * 默认实现抛异常：只有真正支持解码的实现（{@code PngImageCodec}）才覆写。</p>
     */
    default int[] decodePng(byte[] png) {
        throw new UnsupportedOperationException("该 ImageCodec 实现不支持 PNG 解码");
    }
}
