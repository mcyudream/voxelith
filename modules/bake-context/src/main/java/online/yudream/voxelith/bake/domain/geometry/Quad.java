package online.yudream.voxelith.bake.domain.geometry;

/**
 * 一个烘焙出的面片（模型局部空间，0~16 坐标系）。
 *
 * @param positions 4 顶点 × xyz，外向逆时针（three.js 正面）
 * @param uvs       4 顶点 × uv（0~16 贴图坐标）
 * @param normal    单位法向
 * @param texture   最终贴图 id（"minecraft:block/stone"），null 表示引用未解析
 * @param cullface  遮挡剔除方向名（旋转后的最终方向），null 表示不剔除
 * @param tintIndex 染色索引，-1 = 不染色
 * @param shade     是否参与明暗着色
 * @param face      原始面方向名（调试/报告用）
 */
public record Quad(float[] positions, float[] uvs, float[] normal,
                   String texture, String cullface, int tintIndex, boolean shade, String face) {

    public static final int VERTEX_COUNT = 4;
}
