package online.yudream.voxelith.tile.application;

/**
 * 跨上下文传递的无纹理纯色四边形（世界坐标，1 方块 = 1）。
 * 供 lod 等下游上下文提交柱状 LOD 几何：无贴图/uv/光照采样，颜色为最终逐面 RGB
 * （方向明暗已由上游烘进颜色），光照属性由 tile 侧合成为全亮天空光。
 *
 * @param positions 4 顶点 × xyz（世界坐标，逆时针，与 FaceProjection 绕序一致）
 * @param normal    面法线
 * @param rgb       最终颜色 0xRRGGBB（已含方向明暗）
 */
public record VertexColorQuadData(float[] positions, float[] normal, int rgb) {
}
