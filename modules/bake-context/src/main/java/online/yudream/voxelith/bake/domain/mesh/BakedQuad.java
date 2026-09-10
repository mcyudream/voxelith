package online.yudream.voxelith.bake.domain.mesh;

/**
 * 世界空间中的烘焙面片（单位：方块，1 格 = 1 单位）。
 *
 * @param positions  4 顶点 × xyz（世界绝对坐标）
 * @param uvs        4 顶点 × uv（0~16 贴图坐标，图集归一化在 tile 链路完成）
 * @param normal     单位法向
 * @param texture    最终贴图 id
 * @param tintIndex  染色索引
 * @param shade      是否参与明暗着色
 * @param face       面方向名（报告/调试用）
 * @param tintRgb    染色 RGB（0xRRGGBB），-1 = 不染色（下游按白色处理）
 * @param skyLight   4 顶点天空光 0..15（角点平均，读取存档 NBT）
 * @param blockLight 4 顶点方块光 0..15
 * @param ao         4 顶点 AO 遮挡级数 0..3（原版角点算法，亮度 = 1 - 0.25n）
 * @param translucent 半透明面片（水等），tile 链路拆分为 alphaMode BLEND 的独立 primitive
 */
public record BakedQuad(float[] positions, float[] uvs, float[] normal,
                        String texture, int tintIndex, boolean shade, String face,
                        int tintRgb, byte[] skyLight, byte[] blockLight, byte[] ao,
                        boolean translucent) {
}
