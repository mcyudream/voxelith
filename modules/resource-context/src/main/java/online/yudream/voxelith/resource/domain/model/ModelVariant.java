package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

/**
 * blockstate 指向的一次模型引用，对应 variants/multipart apply 数组中的一项。
 *
 * @param x      绕 x 轴旋转（0/90/180/270）
 * @param y      绕 y 轴旋转（0/90/180/270）
 * @param uvLock 旋转时是否锁定 uv
 * @param weight 随机权重
 */
public record ModelVariant(Identifier model, int x, int y, boolean uvLock, int weight) {
}
