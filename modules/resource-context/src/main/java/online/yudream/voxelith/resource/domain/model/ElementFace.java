package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Direction;

/**
 * 模型元素的一个面。
 *
 * @param uv        [u1,v1,u2,v2]，null 表示按 from/to 自动推导
 * @param texture   贴图变量引用（"#all" 形式），最终贴图由 ResolvedModel 解析
 * @param cullface  遮挡剔除方向，null 表示不剔除
 * @param rotation  uv 旋转（0/90/180/270）
 * @param tintIndex 染色索引（-1 表示不染色，用于草/树叶等生物群系 tint）
 */
public record ElementFace(float[] uv, String texture, Direction cullface, int rotation, int tintIndex) {
}
