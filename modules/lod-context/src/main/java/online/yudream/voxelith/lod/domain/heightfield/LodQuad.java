package online.yudream.voxelith.lod.domain.heightfield;

/**
 * LOD 四边形（世界坐标）。颜色为最终逐面 RGB（方向明暗已烘入）；
 * {@code uvs} 映射到瓦片航拍色图（8 个分量，可空表示无纹理）。
 *
 * @param positions 4 顶点 × xyz（逆时针绕序，与 bake 侧 FaceProjection 一致）
 * @param normal    面法线
 * @param rgb       0xRRGGBB（已含方向明暗；有色图时仅作无纹理回退）
 * @param uvs       4 顶点 × uv；长度为 0 表示无 UV
 */
public record LodQuad(float[] positions, float[] normal, int rgb, float[] uvs) {

    public LodQuad(float[] positions, float[] normal, int rgb) {
        this(positions, normal, rgb, new float[0]);
    }
}
