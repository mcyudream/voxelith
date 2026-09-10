package online.yudream.voxelith.tile.domain.tile;

/**
 * 瓦片几何 + 内嵌图集 PNG → 自包含 glb 字节。
 */
public interface TileEncoder {

    /**
     * @param geometry 瓦片几何（局部坐标顶点）
     * @param atlasPng 图集 PNG 字节（内嵌进 glb，保证瓦片自包含）；
     *                 null = 无纹理纯色瓦片（省略 TEXCOORD_0 与贴图，颜色全由 COLOR_0 承载）
     * @return glb 字节
     */
    byte[] encode(TileGeometry geometry, byte[] atlasPng);
}
