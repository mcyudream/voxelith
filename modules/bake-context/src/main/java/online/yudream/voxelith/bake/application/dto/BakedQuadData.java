package online.yudream.voxelith.bake.application.dto;

/**
 * 跨上下文传递的烘焙四边形（世界坐标，1 方块 = 1）。与 domain 的 BakedQuad 字段一致，
 * 作为 application 层契约供 tile 等下游上下文消费。
 */
public record BakedQuadData(float[] positions, float[] uvs, float[] normal,
                            String texture, int tintIndex, boolean shade, String face,
                            int tintRgb, byte[] skyLight, byte[] blockLight, byte[] ao,
                            boolean translucent) {
}
