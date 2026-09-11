package online.yudream.voxelith.tile.application;

/**
 * 跨上下文传递的 LOD 四边形（世界坐标，1 方块 = 1）。
 * 无 {@code uvs} 时为纯色瓦片（COLOR_0 承载全部颜色）；有 UV 时配合航拍色图，
 * COLOR_0 只承载方向明暗（顶面白、侧面灰度），反照率由贴图提供。
 *
 * @param positions 4 顶点 × xyz（世界坐标，逆时针，与 FaceProjection 绕序一致）
 * @param normal    面法线
 * @param rgb       最终颜色 0xRRGGBB（已含方向明暗；有色图时仅作无纹理回退）
 * @param uvs       4 顶点 × uv；长度为 0 表示无 UV
 */
public record VertexColorQuadData(float[] positions, float[] normal, int rgb, float[] uvs) {

    public VertexColorQuadData(float[] positions, float[] normal, int rgb) {
        this(positions, normal, rgb, new float[0]);
    }
}
