package online.yudream.voxelith.lod.domain.heightfield;

/**
 * LOD 四边形（世界坐标）。颜色为最终逐面 RGB（方向明暗已烘入）。
 *
 * @param positions 4 顶点 × xyz（逆时针绕序，与 bake 侧 FaceProjection 一致）
 * @param normal    面法线
 * @param rgb       0xRRGGBB（已含方向明暗）
 */
public record LodQuad(float[] positions, float[] normal, int rgb) {
}
